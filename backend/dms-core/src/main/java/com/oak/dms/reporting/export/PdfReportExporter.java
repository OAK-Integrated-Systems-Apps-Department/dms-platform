package com.oak.dms.reporting.export;

import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.settings.ReportSettingsService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF through PDFBox, one page at a time: page size and orientation from the organisation's settings, the column headings
 * repeated on every page, wrapped cells, a header per group and a total for grouped reports, the organisation's footer and
 * confidentiality text, and "Page n of N" on every page (added once the page count is known).
 * <p>
 * Fonts are chosen per character by {@link PdfFontChain}; a character no installed font has prints as {@code ?}. PDF has its own,
 * much lower row limit (default 5,000) because this is a layout, not a stream.
 */
@Component
public class PdfReportExporter implements ReportExporter {

    private static final float MARGIN = 36f;
    private static final float FONT_SIZE = 8f;
    private static final float TITLE_SIZE = 14f;
    private static final float LINE = 10f;
    private static final float CELL_PAD = 3f;
    private static final int MAX_CELL_LINES = 3;
    private static final float FOOTER_RESERVE = 34f;
    private static final float[] HEADER_FILL = {0.88f};
    private static final float[] GROUP_FILL = {0.95f};

    private final List<Path> fontDirectories;

    @Autowired
    public PdfReportExporter(ReportSettingsService settings) {
        this(settings.pdfFontDirectories());
    }

    PdfReportExporter(List<Path> fontDirectories) {
        this.fontDirectories = fontDirectories;
    }

    @Override
    public String format() {
        return "pdf";
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public String extension() {
        return "pdf";
    }

    @Override
    public void write(ReportFileSpec spec, ReportFileSpec.RowCursor rows, Path target) throws IOException {
        try (PDDocument document = new PDDocument(); PdfFontChain fonts = new PdfFontChain(document, fontDirectories)) {
            new Layout(document, fonts, spec).run(rows);
            document.save(target.toFile());
        }
    }

    /** One report being laid out: the current page, the cursor on it, and the widths of the columns. */
    private static final class Layout {
        private final PDDocument document;
        private final PdfFontChain fonts;
        private final ReportFileSpec spec;
        private final PDRectangle pageSize;
        private final float[] columnX;
        private final float[] columnWidth;
        private PDPageContentStream stream;
        private float y;
        private int pages = 0;

        Layout(PDDocument document, PdfFontChain fonts, ReportFileSpec spec) {
            this.document = document;
            this.fonts = fonts;
            this.spec = spec;
            PDRectangle base = "Letter".equalsIgnoreCase(spec.settings().pdfPageSize()) ? PDRectangle.LETTER : PDRectangle.A4;
            this.pageSize = "portrait".equalsIgnoreCase(spec.settings().pdfOrientation())
                    ? base : new PDRectangle(base.getHeight(), base.getWidth());
            int n = Math.max(1, spec.columns().size());
            float usable = pageSize.getWidth() - 2 * MARGIN;
            this.columnWidth = new float[n];
            this.columnX = new float[n];
            for (int i = 0; i < n; i++) {
                columnWidth[i] = usable / n;
                columnX[i] = MARGIN + i * columnWidth[i];
            }
        }

        void run(ReportFileSpec.RowCursor rows) throws IOException {
            try {
                newPage();
                titleBlock();
                columnHeadings();
                String currentGroup = null;
                ReportRow row;
                while ((row = rows.next()) != null) {
                    if (spec.groupColumn() != null) {
                        String group = spec.groupOf(row);
                        if (!group.equals(currentGroup)) {
                            ensureRoom(LINE + 2 * CELL_PAD + LINE); // keep a group header with at least one row
                            groupHeader(spec.groupHeader(group));
                            currentGroup = group;
                        }
                    }
                    dataRow(row);
                }
                if (spec.groupColumn() != null) {
                    ensureRoom(LINE + 2 * CELL_PAD);
                    groupHeader(spec.totalLine());
                }
            } finally {
                if (stream != null) {
                    stream.close();
                    stream = null;
                }
            }
            footers();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            pages++;
            stream = new PDPageContentStream(document, page);
            y = pageSize.getHeight() - MARGIN;
        }

        private void ensureRoom(float needed) throws IOException {
            if (y - needed < MARGIN + FOOTER_RESERVE) {
                newPage();
                columnHeadings();
            }
        }

        private void titleBlock() throws IOException {
            boolean first = true;
            for (ReportFileSpec.Info info : spec.info()) {
                String line = CellSanitizer.sanitize(info.line());
                float size = first ? TITLE_SIZE : FONT_SIZE;
                for (String part : wrap(line, size, pageSize.getWidth() - 2 * MARGIN, 4)) {
                    text(part, MARGIN, y - size, size);
                    y -= size + 2;
                }
                first = false;
            }
            y -= 6;
        }

        private void columnHeadings() throws IOException {
            float height = LINE + 2 * CELL_PAD;
            fill(MARGIN, y - height, pageSize.getWidth() - 2 * MARGIN, height, HEADER_FILL);
            for (int c = 0; c < spec.columns().size(); c++) {
                String label = fit(spec.columns().get(c).label(), columnWidth[c] - 2 * CELL_PAD);
                text(label, columnX[c] + CELL_PAD, y - CELL_PAD - FONT_SIZE, FONT_SIZE);
            }
            y -= height;
        }

        private void groupHeader(String line) throws IOException {
            float height = LINE + 2 * CELL_PAD;
            fill(MARGIN, y - height, pageSize.getWidth() - 2 * MARGIN, height, GROUP_FILL);
            text(fit(CellSanitizer.sanitize(line), pageSize.getWidth() - 2 * MARGIN - 2 * CELL_PAD), MARGIN + CELL_PAD,
                    y - CELL_PAD - FONT_SIZE, FONT_SIZE);
            y -= height;
        }

        private void dataRow(ReportRow row) throws IOException {
            List<List<String>> cells = new ArrayList<>();
            int lines = 1;
            for (int c = 0; c < spec.columns().size(); c++) {
                Column column = spec.columns().get(c);
                Object value = row.cells().get(c);
                String shown = spec.formatter().display(column, value);
                if (value instanceof String) {
                    shown = CellSanitizer.sanitize(shown);
                }
                List<String> wrapped = wrap(shown, FONT_SIZE, columnWidth[c] - 2 * CELL_PAD, MAX_CELL_LINES);
                cells.add(wrapped);
                lines = Math.max(lines, wrapped.size());
            }
            float height = lines * LINE + 2 * CELL_PAD;
            ensureRoom(height);
            for (int c = 0; c < cells.size(); c++) {
                float lineY = y - CELL_PAD - FONT_SIZE;
                for (String line : cells.get(c)) {
                    text(line, columnX[c] + CELL_PAD, lineY, FONT_SIZE);
                    lineY -= LINE;
                }
            }
            y -= height;
        }

        /** "Page n of N" and the organisation's footer and confidentiality text, on every page. */
        private void footers() throws IOException {
            for (int i = 0; i < pages; i++) {
                PDPage page = document.getPage(i);
                try (PDPageContentStream footer = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String number = "Page " + (i + 1) + " of " + pages;
                    float numberWidth = fonts.width(number, FONT_SIZE);
                    draw(footer, number, pageSize.getWidth() - MARGIN - numberWidth, MARGIN, FONT_SIZE);
                    float left = pageSize.getWidth() - 2 * MARGIN - numberWidth - 12;
                    float lineY = MARGIN + LINE;
                    for (String part : List.of(nullToEmpty(spec.settings().footerText()),
                            nullToEmpty(spec.settings().confidentialityNotice()))) {
                        if (!part.isBlank()) {
                            draw(footer, fit(CellSanitizer.sanitize(part), left), MARGIN, lineY, FONT_SIZE);
                            lineY -= LINE;
                        }
                    }
                }
            }
        }

        // ---- drawing

        private void fill(float x, float bottom, float width, float height, float[] gray) throws IOException {
            stream.setNonStrokingColor(gray[0]);
            stream.addRect(x, bottom, width, height);
            stream.fill();
            stream.setNonStrokingColor(0f);
        }

        private void text(String text, float x, float baseline, float size) throws IOException {
            draw(stream, text, x, baseline, size);
        }

        private void draw(PDPageContentStream out, String text, float x, float baseline, float size) throws IOException {
            if (text.isEmpty()) {
                return;
            }
            out.beginText();
            out.newLineAtOffset(x, baseline);
            for (PdfFontChain.Run run : fonts.runs(text)) {
                out.setFont(run.font(), size);
                out.showText(run.text());
            }
            out.endText();
        }

        /** The text cut to one line that fits, ending in an ellipsis if something was dropped. */
        private String fit(String text, float width) {
            List<String> lines = wrap(text, FONT_SIZE, width, 1);
            return lines.isEmpty() ? "" : lines.get(0);
        }

        /** Greedy wrap by measured width (breaking at a space when there is one); the last allowed line ends in an ellipsis. */
        private List<String> wrap(String text, float size, float width, int maxLines) {
            String flat = text == null ? "" : text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            int i = 0;
            while (i < flat.length()) {
                int cp = flat.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                if (fonts.width(line + ch, size) > width && line.length() > 0) {
                    int space = line.lastIndexOf(" ");
                    String head;
                    String rest = "";
                    if (space > 0 && cp != ' ') {
                        head = line.substring(0, space);
                        rest = line.substring(space + 1);
                    } else {
                        head = line.toString();
                    }
                    lines.add(head);
                    line = new StringBuilder(rest);
                    if (lines.size() == maxLines) {
                        return ellipsis(lines, size, width);
                    }
                    if (cp == ' ' && rest.isEmpty()) {
                        i += Character.charCount(cp);
                        continue;
                    }
                }
                line.append(ch);
                i += Character.charCount(cp);
            }
            if (line.length() > 0 || lines.isEmpty()) {
                lines.add(line.toString());
            }
            if (lines.size() > maxLines) {
                return ellipsis(lines.subList(0, maxLines), size, width);
            }
            return lines;
        }

        /** The last line of a cut-off cell, shortened until "…" fits after it. */
        private List<String> ellipsis(List<String> lines, float size, float width) {
            List<String> out = new ArrayList<>(lines);
            String last = out.get(out.size() - 1);
            while (!last.isEmpty() && fonts.width(last + "…", size) > width) {
                last = last.substring(0, last.length() - 1);
            }
            out.set(out.size() - 1, last + "…");
            return out;
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }
    }
}
