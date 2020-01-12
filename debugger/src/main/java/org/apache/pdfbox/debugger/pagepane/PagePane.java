/*
 * Copyright 2015 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pdfbox.debugger.pagepane;

import java.awt.Graphics2D;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.debugger.PDFDebugger;
import org.apache.pdfbox.debugger.ui.ImageUtil;
import org.apache.pdfbox.debugger.ui.RotationMenu;
import org.apache.pdfbox.debugger.ui.ViewMenu;
import org.apache.pdfbox.debugger.ui.ZoomMenu;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.debugger.ui.HighResolutionImageIcon;
import org.apache.pdfbox.debugger.ui.ImageTypeMenu;
import org.apache.pdfbox.debugger.ui.RenderDestinationMenu;
import org.apache.pdfbox.debugger.ui.TextDialog;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.RenderDestination;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Display the page number and a page rendering.
 * 
 * @author Tilman Hausherr
 * @author John Hewson
 */
public class PagePane implements ActionListener, AncestorListener, MouseMotionListener, MouseListener
{
    private static final Log LOG = LogFactory.getLog(PagePane.class);
    private final PDDocument document;
    private final JLabel statuslabel;
    private final PDPage page;
    private JPanel panel;
    private int pageIndex = -1;
    private JLabel label;
    private ZoomMenu zoomMenu;
    private RotationMenu rotationMenu;
    private ImageTypeMenu imageTypeMenu;
    private RenderDestinationMenu renderDestinationMenu;
    private ViewMenu viewMenu;
    private String labelText = "";
    private final Map<PDRectangle,String> rectMap = new HashMap<>();
    private final AffineTransform defaultTransform = GraphicsEnvironment.getLocalGraphicsEnvironment().
                        getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform();
    // more ideas:
    // https://stackoverflow.com/questions/16440159/dragging-of-shapes-on-jpanel

    public PagePane(PDDocument document, COSDictionary pageDict, JLabel statuslabel)
    {
        page = new PDPage(pageDict);
        pageIndex = document.getPages().indexOf(page);
        this.document = document;
        this.statuslabel = statuslabel;
        initUI();
        initRectMap();
    }

    private void initRectMap()
    {
        try
        {
            collectFieldLocations();
            collectLinkLocations();
        }
        catch (IOException ex)
        {
            LOG.error(ex.getMessage(), ex);
        }
    }

    private void collectLinkLocations() throws IOException
    {
        for (PDAnnotation annotation : page.getAnnotations())
        {
            if (annotation instanceof PDAnnotationLink)
            {
                collectLinkLocation((PDAnnotationLink) annotation);
            }
        }
    }

    private void collectLinkLocation(PDAnnotationLink linkAnnotation) throws IOException
    {
        PDAction action = linkAnnotation.getAction();
        if (action instanceof PDActionURI)
        {
            PDActionURI uriAction = (PDActionURI) action;
            rectMap.put(linkAnnotation.getRectangle(), "URI: " + uriAction.getURI());
            return;
        }
        PDDestination destination;
        if (action instanceof PDActionGoTo)
        {
            PDActionGoTo goToAction = (PDActionGoTo) action;
            destination = goToAction.getDestination();
        }
        else
        {
            destination = linkAnnotation.getDestination();
        }
        if (destination instanceof PDNamedDestination)
        {
            destination = document.getDocumentCatalog().
                    findNamedDestinationPage((PDNamedDestination) destination);
        }
        if (destination instanceof PDPageDestination)
        {
            PDPageDestination pageDestination = (PDPageDestination) destination;
            int pageNum = pageDestination.retrievePageNumber();
            if (pageNum != -1)
            {
                rectMap.put(linkAnnotation.getRectangle(), "Page destination: " + (pageNum + 1));
            }
        }
    }

    private void collectFieldLocations() throws IOException
    {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm == null)
        {
            return;
        }
        Set<COSDictionary> dictionarySet = new HashSet<>();
        for (PDAnnotation annotation : page.getAnnotations())
        {
            dictionarySet.add(annotation.getCOSObject());
        }
        for (PDField field : acroForm.getFieldTree())
        {
            for (PDAnnotationWidget widget : field.getWidgets())
            {
                // check if the annotation widget is on this page
                // (checking widget.getPage() also works, but it is sometimes null)
                if (dictionarySet.contains(widget.getCOSObject()))
                {
                    rectMap.put(widget.getRectangle(), "Field name: " + field.getFullyQualifiedName());
                }
            }
        }
    }

    private void initUI()
    {
        panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        String pageLabelText = pageIndex < 0 ? "Page number not found" : "Page " + (pageIndex + 1);

        // append PDF page label, if available
        String lbl = PDFDebugger.getPageLabel(document, pageIndex);
        if (lbl != null)
        {
            pageLabelText += " - " + lbl;
        }

        JLabel pageLabel = new JLabel(pageLabelText);
        pageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        pageLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20));
        pageLabel.setBorder(BorderFactory.createEmptyBorder(5, 0, 10, 0));
        panel.add(pageLabel);
        
        label = new JLabel();
        label.addMouseMotionListener(this);
        label.addMouseListener(this);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(label);
        panel.addAncestorListener(this);

        zoomMenu = ZoomMenu.getInstance();
        zoomMenu.changeZoomSelection(zoomMenu.getPageZoomScale());
        startRendering();
    }

    /**
     * Returns the main panel that hold all the UI elements.
     *
     * @return JPanel instance
     */
    public Component getPanel()
    {
        return panel;
    }

    @Override
    public void actionPerformed(ActionEvent actionEvent)
    {
        String actionCommand = actionEvent.getActionCommand();
        if (ZoomMenu.isZoomMenu(actionCommand) ||
            RotationMenu.isRotationMenu(actionCommand) ||
            ImageTypeMenu.isImageTypeMenu(actionCommand) ||
            RenderDestinationMenu.isRenderDestinationMenu(actionCommand) ||
            ViewMenu.isRenderingOptions(actionCommand))
        {
            startRendering();
        }
        else if (ViewMenu.isExtractText(actionEvent))
        {
            startExtracting();
        }
    }

    private void startExtracting()
    {
        TextDialog textDialog = TextDialog.instance();
        textDialog.setSize(800, 400);
        textDialog.setVisible(true);
        textDialog.setLocation(getPanel().getLocationOnScreen().x + getPanel().getWidth() / 2,
                               getPanel().getLocationOnScreen().y + getPanel().getHeight() / 2);
        try
        {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            textDialog.setText(stripper.getText(document));
        }
        catch (IOException ex)
        {
            LOG.error(ex.getMessage(), ex);
        }
    }

    private void startRendering()
    {
        // render in a background thread: rendering is read-only, so this should be ok, despite
        // the fact that PDDocument is not officially thread safe
        new RenderWorker(ZoomMenu.getZoomScale(),
                RotationMenu.getRotationDegrees(),
                ViewMenu.isShowTextStripper(),
                ViewMenu.isShowTextStripperBeads(),
                ViewMenu.isShowFontBBox(),
                ViewMenu.isShowGlyphBounds(),
                ViewMenu.isAllowSubsampling(),
                ImageTypeMenu.getImageType(),
                RenderDestinationMenu.getRenderDestination()
        ).execute();
        zoomMenu.setPageZoomScale(ZoomMenu.getZoomScale());
    }

    @Override
    public void ancestorAdded(AncestorEvent ancestorEvent)
    {
        zoomMenu.addMenuListeners(this);
        zoomMenu.setEnableMenu(true);
        
        rotationMenu = RotationMenu.getInstance();
        rotationMenu.addMenuListeners(this);
        rotationMenu.setEnableMenu(true);

        imageTypeMenu = ImageTypeMenu.getInstance();
        imageTypeMenu.addMenuListeners(this);
        imageTypeMenu.setEnableMenu(true);

        renderDestinationMenu = RenderDestinationMenu.getInstance();
        renderDestinationMenu.addMenuListeners(this);
        renderDestinationMenu.setEnableMenu(true);

        viewMenu = ViewMenu.getInstance(null);

        JMenu menuInstance = viewMenu.getMenu();
        int itemCount = menuInstance.getItemCount();
        
        for (int i = 0; i< itemCount; i++)
        {
            JMenuItem item = menuInstance.getItem(i);
            if (item != null)
            {
                item.setEnabled(true);
                item.addActionListener(this);
            }
        }
    }

    @Override
    public void ancestorRemoved(AncestorEvent ancestorEvent)
    {
        boolean isFirstEntrySkipped = false;
        zoomMenu.setEnableMenu(false);
        rotationMenu.setEnableMenu(false);
        imageTypeMenu.setEnableMenu(false);
        renderDestinationMenu.setEnableMenu(false);

        JMenu menuInstance = viewMenu.getMenu();
        int itemCount = menuInstance.getItemCount();
        
        for (int i = 0; i< itemCount; i++)
        {
            JMenuItem item = menuInstance.getItem(i);
            // skip the first JMenuItem as this shall always be shown
            if (item != null)
            {
                if (!isFirstEntrySkipped)
                {
                    isFirstEntrySkipped = true;
                }
                else
                {
                    item.setEnabled(false);
                    item.removeActionListener(this);
                }
            }
        }
    }

    @Override
    public void ancestorMoved(AncestorEvent ancestorEvent)
    {
        // do nothing
    }

    @Override
    public void mouseDragged(MouseEvent e)
    {
        // do nothing
    }

    /**
     * Catch mouse event to display cursor position in PDF coordinates in the status bar.
     *
     * @param e mouse event with position
     */
    @Override
    public void mouseMoved(MouseEvent e)
    {
        float height = page.getCropBox().getHeight();
        float width  = page.getCropBox().getWidth();
        float offsetX = page.getCropBox().getLowerLeftX();
        float offsetY = page.getCropBox().getLowerLeftY();
        float zoomScale = zoomMenu.getPageZoomScale();
        float x = e.getX() / zoomScale * (float) defaultTransform.getScaleX();
        float y = e.getY() / zoomScale * (float) defaultTransform.getScaleY();
        int x1;
        int y1;
        switch ((RotationMenu.getRotationDegrees() + page.getRotation()) % 360)
        {
            case 90:
                x1 = (int) (y + offsetX);
                y1 = (int) (x + offsetY);
                break;
            case 180:
                x1 = (int) (width - x + offsetX);
                y1 = (int) (y - offsetY);
                break;
            case 270:
                x1 = (int) (width - y + offsetX);
                y1 = (int) (height - x + offsetY);
                break;
            case 0:
            default:
                x1 = (int) (x + offsetX);
                y1 = (int) (height - y + offsetY);
                break;
        }
        String text = "x: " + x1 + ", y: " + y1;
        
        // are we in a field widget?
        for (Entry<PDRectangle,String> entry : rectMap.entrySet())
        {
            if (entry.getKey().contains(x1, y1))
            {
                text += ", " + rectMap.get(entry.getKey());
                break;
            }
        }

        statuslabel.setText(text);
    }

    @Override
    public void mouseClicked(MouseEvent e)
    {
        // do nothing
    }

    @Override
    public void mousePressed(MouseEvent e)
    {
        // do nothing
    }

    @Override
    public void mouseReleased(MouseEvent e)
    {
        // do nothing
    }

    @Override
    public void mouseEntered(MouseEvent e)
    {
        // do nothing
    }

    @Override
    public void mouseExited(MouseEvent e)
    {
        statuslabel.setText(labelText);
    }

    /**
     * Note that PDDocument is not officially thread safe, caution advised.
     */
    private final class RenderWorker extends SwingWorker<BufferedImage, Integer>
    {
        private final float scale;
        private final int rotation;
        private final boolean showTextStripper;
        private final boolean showTextStripperBeads;
        private final boolean showFontBBox;
        private final boolean showGlyphBounds;
        private final boolean allowSubsampling;
        private final ImageType imageType;
        private final RenderDestination renderDestination;

        private RenderWorker(float scale, int rotation, boolean showTextStripper,
                             boolean showTextStripperBeads, boolean showFontBBox,
                             boolean showGlyphBounds, boolean allowSubsampling,
                             ImageType imageType, RenderDestination renderDestination)
        {
            this.scale = scale;
            this.rotation = rotation;
            this.showTextStripper = showTextStripper;
            this.showTextStripperBeads = showTextStripperBeads;
            this.showFontBBox = showFontBBox;
            this.showGlyphBounds = showGlyphBounds;
            this.allowSubsampling = allowSubsampling;
            this.imageType = imageType;
            this.renderDestination = renderDestination;
        }

        @Override
        protected BufferedImage doInBackground() throws IOException
        {
            label.setIcon(null);
            labelText = "Rendering...";
            label.setText(labelText);
            statuslabel.setText(labelText);
            
            PDFRenderer renderer = new DebugPDFRenderer(document, this.showGlyphBounds);
            renderer.setSubsamplingAllowed(allowSubsampling);

            long t0 = System.nanoTime();
            BufferedImage image = renderer.renderImage(pageIndex, scale, imageType, renderDestination);
            long t1 = System.nanoTime();

            long ms = TimeUnit.MILLISECONDS.convert(t1 - t0, TimeUnit.NANOSECONDS);
            labelText = "Rendered in " + ms + " ms";
            statuslabel.setText(labelText);

            // debug overlays
            DebugTextOverlay debugText = new DebugTextOverlay(document, pageIndex, scale, 
                                                              showTextStripper, showTextStripperBeads,
                                                              showFontBBox);
            Graphics2D g = image.createGraphics();
            debugText.renderTo(g);
            g.dispose();
            
            return ImageUtil.getRotatedImage(image, rotation);
        }

        @Override
        protected void done()
        {
            try
            {
                BufferedImage image = get();

                // We cannot use "label.setIcon(new ImageIcon(get()))" here 
                // because of blurry upscaling in JDK9. Instead, the label is now created with 
                // a smaller size than the image to compensate that the
                // image is scaled up with some screen configurations (e.g. 125% on windows).
                // See PDFBOX-3665 for more sample code and discussion.
                label.setSize((int) Math.ceil(image.getWidth() / defaultTransform.getScaleX()), 
                              (int) Math.ceil(image.getHeight() / defaultTransform.getScaleY()));
                label.setIcon(new HighResolutionImageIcon(image, label.getWidth(), label.getHeight()));
                label.setText(null);
            }
            catch (InterruptedException | ExecutionException e)
            {
                label.setText(e.getMessage());
                throw new RuntimeException(e);
            }
        }
    }
}
