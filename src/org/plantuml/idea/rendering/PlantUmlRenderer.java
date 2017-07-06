package org.plantuml.idea.rendering;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.util.ui.UIUtil;
import net.sourceforge.plantuml.*;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.core.DiagramDescription;
import net.sourceforge.plantuml.cucadiagram.Display;
import net.sourceforge.plantuml.cucadiagram.DisplayPositionned;
import net.sourceforge.plantuml.descdiagram.DescriptionDiagram;
import net.sourceforge.plantuml.sequencediagram.Event;
import net.sourceforge.plantuml.sequencediagram.Newpage;
import net.sourceforge.plantuml.sequencediagram.SequenceDiagram;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.plantuml.idea.plantuml.PlantUml;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.plantuml.idea.lang.annotator.LanguageDescriptor.IDEA_PARTIAL_RENDER;

public class PlantUmlRenderer {
    private static final Logger logger = Logger.getInstance(PlantUmlRenderer.class);

    public static final Pattern NEW_PAGE_PATTERN = Pattern.compile("\\n\\s*@?(?i)(newpage)(\\p{Blank}+[^\\n]+|\\p{Blank}*)(?=\\n)");

    private static final PlantUmlPartialRenderer PARTIAL_RENDERER = new PlantUmlPartialRenderer();
    private static final PlantUmlNormalRenderer NORMAL_RENDERER = new PlantUmlNormalRenderer();

    /**
     * Renders source code and saves diagram images to files according to provided naming scheme
     * and image format.
     *
     * @param source         source code to be rendered
     * @param baseDir        base dir to set for "include" functionality
     * @param format         image format
     * @param fileName       fileName to use with first file
     * @param fileNameFormat file naming scheme for further files
     * @param pageNumber     -1 for all pages   
     * @throws IOException in case of rendering or saving fails
     */
    public static void renderAndSave(String source, @Nullable File baseDir, PlantUml.ImageFormat format, String fileName, String fileNameFormat, int zoom, int pageNumber)
            throws IOException {
        NORMAL_RENDERER.renderAndSave(source, baseDir, format, fileName, fileNameFormat, zoom, pageNumber);
    }

    /**
     * Renders file with support of plantUML include ange paging features, setting base dir and page for plantUML
     * to provided values
     */
    public static RenderResult render(RenderRequest renderRequest, RenderCacheItem cachedItem) {
        try {
            File baseDir = renderRequest.getBaseDir();
            if (baseDir != null) {
                FileSystem.getInstance().setCurrentDir(baseDir);
            }
            long start = System.currentTimeMillis();

            String source = renderRequest.getSource();
            String[] sourceSplit = NEW_PAGE_PATTERN.split(source);
            logger.debug("split done ", System.currentTimeMillis() - start, "ms");

            boolean partialRender = sourceSplit[0].contains(IDEA_PARTIAL_RENDER);
            logger.debug("partialRender ", partialRender);

            RenderResult renderResult;
            if (partialRender) {
                renderResult = PARTIAL_RENDERER.partialRender(renderRequest, cachedItem, start, sourceSplit);
            } else {
                renderResult = NORMAL_RENDERER.doRender(renderRequest, cachedItem, sourceSplit);
            }
            return renderResult;
        } finally {
            FileSystem.getInstance().reset();
        }
    }

    public static Pair<Integer, Titles> getDiagram(SourceStringReader reader, int zoom) {
        logger.debug("getting diagram");
        int totalPages = 0;
        List<BlockUml> blocks = reader.getBlocks();

        for (BlockUml block : blocks) {
            long start = System.currentTimeMillis();
            checkCancel();
            Diagram diagram = block.getDiagram();
            logger.debug("getDiagram done in  ", System.currentTimeMillis() - start, " ms");

            start = System.currentTimeMillis();
            zoomDiagram(diagram, zoom);
            logger.debug("zoom diagram done in  ", System.currentTimeMillis() - start, " ms");

            totalPages = totalPages + diagram.getNbImages();
        }
        Titles titles = getTitles(totalPages, blocks);
        return new Pair<>(totalPages, titles);
    }

    private static void zoomDiagram(Diagram diagram, int zoom) {
        double scaleFactor = Math.max(1, Math.min(zoom / 100f, 2));
        if (diagram instanceof UmlDiagram) {
            UmlDiagram umlDiagram = (UmlDiagram) diagram;
            Scale scale = umlDiagram.getScale();
            if (scale == null) {
                umlDiagram.setScale(new ScaleSimple(scaleFactor));
            }
        } else if (diagram instanceof NewpagedDiagram) {
            NewpagedDiagram newpagedDiagram = (NewpagedDiagram) diagram;
            for (Diagram page : newpagedDiagram.getDiagrams()) {
                if (page instanceof DescriptionDiagram) {
                    DescriptionDiagram descriptionDiagram = (DescriptionDiagram) page;
                    Scale scale = descriptionDiagram.getScale();
                    if (scale == null) {
                        descriptionDiagram.setScale(new ScaleSimple(scaleFactor));
                    }
                }
            }
        }
    }

    public static DiagramDescription outputImage(SourceStringReader reader, OutputStream destination, int page, FileFormatOption format, int width) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DiagramDescription description = reader.outputImage(output, page, format);
        output.close();

        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        BufferedImage image = ImageIO.read(input);
        double scale = (double)width / (double)image.getWidth();
        int scaledWidth = (int)(image.getWidth() * scale);
        int scaledHeight = (int)(image.getHeight() * scale);

        Image scaledImage = image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
        BufferedImage bufferedImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D imageGraphics = bufferedImage.createGraphics();
        imageGraphics.drawImage(scaledImage, null, null);
        ImageOutputStream imageOutputStream = new MemoryCacheImageOutputStream(destination);
        ImageIO.write(bufferedImage, format.getFileFormat().name(), imageOutputStream);
        imageOutputStream.close();

        return description;
    }

    @NotNull
    protected static Titles getTitles(int totalPages, List<BlockUml> blocks) {
        List<String> titles = new ArrayList<String>(totalPages);
        for (BlockUml block : blocks) {
            Diagram diagram = block.getDiagram();
            if (diagram instanceof SequenceDiagram) {
                SequenceDiagram sequenceDiagram = (SequenceDiagram) diagram;
                addTitle(titles, sequenceDiagram.getTitle().getDisplay());
                List<Event> events = sequenceDiagram.events();
                for (Event event : events) {
                    if (event instanceof Newpage) {
                        Display title = ((Newpage) event).getTitle();
                        addTitle(titles, title);
                    }
                }
            } else if (diagram instanceof NewpagedDiagram) {
                NewpagedDiagram newpagedDiagram = (NewpagedDiagram) diagram;
                List<Diagram> diagrams = newpagedDiagram.getDiagrams();
                for (Diagram diagram1 : diagrams) {
                    if (diagram1 instanceof UmlDiagram) {
                        DisplayPositionned title = ((UmlDiagram) diagram1).getTitle();
                        addTitle(titles, title.getDisplay());
                    }
                }
            } else if (diagram instanceof UmlDiagram) {
                DisplayPositionned title = ((UmlDiagram) diagram).getTitle();
                addTitle(titles, title.getDisplay());
            } else if (diagram instanceof PSystemError) {
                DisplayPositionned title = ((PSystemError) diagram).getTitle();
                if (title == null) {
                    titles.add(null);
                } else {
                    addTitle(titles, title.getDisplay());
                }
            }
        }
        return new Titles(titles);
    }


    protected static void addTitle(List<String> titles, Display display) {
        if (display.size() > 0) {
            titles.add(display.toString());
        } else {
            titles.add(null);
        }
    }

    private static void checkCancel() {
        if (Thread.currentThread().isInterrupted()) {
            throw new RenderingCancelledException();
        }
    }

}
