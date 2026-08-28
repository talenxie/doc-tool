package com.doctool.util;

import com.doctool.model.OcrLine;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

public class DocxUtils {

    /** 评估警告水印关键字（覆盖英文原文和翻译后的中文） */
    private static final Pattern EVAL_WARNING =
            Pattern.compile("Evaluation Warning|评估警告", Pattern.CASE_INSENSITIVE);

    /** 页眉页脚中图形水印的标志（Word 内置水印 / VML WordArt 艺术字） */
    private static final Pattern WATERMARK_MARKER =
            Pattern.compile("(?i)watermark|#_x0000_t136");

    /** 中文标准字号档位（磅）：小五/五号/小四/四号/小三/三号/小二/二号/小一/一号 */
    private static final double[] FONT_SIZE_STOPS = {9, 10.5, 12, 14, 15, 16, 18, 22, 24, 26, 28};

    public static XWPFDocument readDocx(String filePath) throws IOException {
        return new XWPFDocument(new FileInputStream(filePath));
    }

    public static XWPFDocument readDocx(InputStream inputStream) throws IOException {
        return new XWPFDocument(inputStream);
    }

    public static String extractText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph para : doc.getParagraphs()) {
            sb.append(para.getText()).append("\n");
        }
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    sb.append(cell.getText()).append("\t");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public static XWPFDocument createDocxWithText(String text) {
        XWPFDocument doc = new XWPFDocument();
        String[] lines = text.split("\n");
        for (String line : lines) {
            XWPFParagraph para = doc.createParagraph();
            XWPFRun run = para.createRun();
            run.setText(line);
            run.setFontSize(12);
        }
        return doc;
    }

    /**
     * 根据 OCR 行级信息生成 DOCX，流式排版、贴近原图样式。
     *
     * 做法要点（均经过实拍图校准）：
     * 1. 先把识别行合并回逻辑段落（充满栏宽的行文字未完，与下一行同段），让 Word 自然重排；
     * 2. 字号由"墨迹高度"决定（OCR 检测框含行距留白，区分不出 9px 正文与 10px 引言），
     *    正文字号按 96dpi 校准并吸附到中文标准字号档位，正文最大不超过五号（10.5pt）；
     * 3. 加粗由"墨迹带密度"决定（宋体正文 ≈0.10，加粗宋体 ≈0.46，黑体标题 ≈0.36~0.40）；
     * 4. 大标题/节标题用黑体（厚重无衬线），小字加粗和正文用宋体；
     * 5. 标题居中、正文两端对齐 + 首行缩进 2 字符、1.5 倍行距、段落间隔至少 12 磅。
     * 行无坐标信息（Tesseract 回退路径）时退化为纯文本逐行段落。
     */
    public static XWPFDocument createDocxFromLines(List<OcrLine> lines) {
        XWPFDocument doc = new XWPFDocument();
        if (lines == null || lines.isEmpty()) {
            return doc;
        }
        setupA4Page(doc);

        // 基准量：检测框行高（定段落结构）、墨迹高度（定字号）
        double median = medianHeight(lines);
        double medianInk = medianInkHeight(lines);

        // 正文字号校准：96dpi 下 字号(磅) ≈ 墨迹高度(px)；落在合理范围才采用，否则默认五号
        double bodyPt = 10.5;
        if (medianInk > 0) {
            double est = medianInk * 0.96;
            if (est >= 7 && est <= 14) {
                bodyPt = snapFontSize(est);
            }
        }

        // 无坐标信息（Tesseract 回退路径）→ 纯文本逐行段落
        if (median <= 0) {
            for (OcrLine line : lines) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(line.text());
                run.setFontSize(12);
                applyCjkFont(run, "Times New Roman", "宋体");
            }
            return doc;
        }

        // 文本栏范围：所有行的最小 left 到最大 right。
        // 注意：整栏文字通常在页面上居中排版，居中判定必须以栏为参照系，不能用页面中心。
        int columnLeft = Integer.MAX_VALUE;
        int columnRight = 0;
        for (OcrLine l : lines) {
            columnLeft = Math.min(columnLeft, l.left());
            if (l.width() > 0) {
                columnRight = Math.max(columnRight, l.left() + l.width());
            }
        }
        double columnWidth = Math.max(1, columnRight - columnLeft);
        double columnCenter = columnLeft + columnWidth / 2;

        // 合并逻辑段落：行到达栏右缘 → 文字未完，与下一行同段；短行 → 段落结束。
        // 超大字号的标题行前后都断开，独立成段。
        List<List<OcrLine>> paragraphs = new ArrayList<>();
        List<OcrLine> current = new ArrayList<>();
        for (OcrLine line : lines) {
            boolean oversized = line.height() > median * 1.3;
            if (oversized && !current.isEmpty()) {
                paragraphs.add(current);
                current = new ArrayList<>();
            }
            current.add(line);
            boolean reachesRightEdge = line.width() > 0
                    && line.left() + line.width() >= columnRight - columnWidth * 0.05;
            if (oversized || !reachesRightEdge) {
                paragraphs.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(current);
        }

        int prevBottom = -1;
        for (List<OcrLine> paraLines : paragraphs) {
            OcrLine first = paraLines.get(0);

            // 拼接段落文字（拉丁字母边界补空格，中文之间无缝衔接）
            StringBuilder text = new StringBuilder();
            for (OcrLine l : paraLines) {
                if (text.length() > 0 && needsSpaceBetween(text.toString(), l.text())) {
                    text.append(' ');
                }
                text.append(l.text());
            }

            // 与上一段的纵向间隔
            int gapTwips = 0;
            if (prevBottom >= 0) {
                int gap = first.top() - prevBottom;
                if (gap > median * 1.2) {
                    gapTwips = (int) Math.round(Math.min((gap / median - 0.9) * 11, 24) * 20);
                }
            }

            // 字号：段落墨迹高度相对正文的比例，吸附到标准字号档位
            double ink = medianInkHeight(paraLines);
            double density = medianInkDensity(paraLines);
            double ratio = (ink > 0 && medianInk > 0) ? ink / medianInk : 1.0;
            double pt = snapFontSize(bodyPt * ratio);

            // 加粗判定：墨迹带密度 >= 0.30（粗体笔画占像素约为常规体的 2~4 倍）
            boolean bold = density >= 0.30;

            // 小标题兜底：独立的短行、不以句读结尾、字号接近正文、墨迹不粗
            boolean sectionHeading = !bold && ratio >= 0.85 && ratio < 1.3
                    && first.width() > 0 && first.width() < columnWidth * 0.65
                    && !endsWithSentencePunct(text.toString());
            if (sectionHeading) {
                bold = true;
                if (pt < 12) {
                    pt = 12;
                }
            }

            // 正文统一为正文字号，且不超过五号：原图各区块的字号抖动多是采集噪声；
            // 原图正文是小号细体，五号最接近观感
            if (!bold && ratio >= 0.7 && ratio <= 1.3) {
                pt = Math.min(bodyPt, 10.5);
            }

            XWPFParagraph para = doc.createParagraph();
            XWPFRun run = para.createRun();
            run.setText(text.toString());
            setFontSizePt(run, pt);
            run.setBold(bold);
            // 大字号加粗标题用黑体（厚重醒目），小字加粗和正文用宋体——还原中文文档字体层次
            boolean heavy = bold && pt >= 15;
            applyCjkFont(run, heavy ? "Arial" : "Times New Roman", heavy ? "黑体" : "宋体");
            para.setSpacingAfter(0);
            // 1.5 倍行距，贴近原扫描件的行距，不拥挤
            para.setSpacingBetween(1.5, LineSpacingRule.AUTO);

            // 居中判定（参照文本栏）：明显短于栏宽、中心对齐栏中心、左侧内缩
            int lineCenter = first.left() + first.width() / 2;
            if (first.width() > 0
                    && first.width() < columnWidth * 0.85
                    && Math.abs(lineCenter - columnCenter) < columnWidth * 0.08
                    && first.left() > columnLeft + columnWidth * 0.05) {
                para.setAlignment(ParagraphAlignment.CENTER);
                // 大标题段后留白 18 磅，与正文拉开层次
                if (pt >= 15) {
                    para.setSpacingAfter(360);
                }
            } else {
                // 正文两端对齐（OOXML 的 justify 枚举名为 BOTH），还原扫描件的齐边排版
                para.setAlignment(ParagraphAlignment.BOTH);
                // 中文首行缩进 2 字符（标题不缩进；单位缇 = 1/20 磅）
                if (!bold && first.left() > columnLeft + h(paraLines) * 0.8) {
                    para.setFirstLineIndent((int) Math.round(pt * 2 * 20));
                }
            }

            // 段落间隔：至少 12 磅，让分段清晰可见
            if (gapTwips > 0) {
                para.setSpacingBefore(Math.max(gapTwips, 240));
            }
            OcrLine last = paraLines.get(paraLines.size() - 1);
            prevBottom = last.top() + last.height();
        }
        return doc;
    }

    /** 吸附到最近的中文标准字号档位 */
    private static double snapFontSize(double pt) {
        double best = FONT_SIZE_STOPS[0];
        for (double stop : FONT_SIZE_STOPS) {
            if (Math.abs(stop - pt) < Math.abs(best - pt)) {
                best = stop;
            }
        }
        return best;
    }

    /** 设置字号（支持半磅，如五号 10.5）：同时写 w:sz 和 w:szCs */
    private static void setFontSizePt(XWPFRun run, double pt) {
        BigInteger half = BigInteger.valueOf(Math.round(pt * 2));
        CTRPr rPr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        if (rPr.sizeOfSzArray() > 0) {
            rPr.getSzArray(0).setVal(half);
        } else {
            rPr.addNewSz().setVal(half);
        }
        if (rPr.sizeOfSzCsArray() > 0) {
            rPr.getSzCsArray(0).setVal(half);
        } else {
            rPr.addNewSzCs().setVal(half);
        }
    }

    /** 设置中英文字体 */
    private static void applyCjkFont(XWPFRun run, String latinFont, String eastAsiaFont) {
        run.setFontFamily(latinFont);
        if (run.getCTR().isSetRPr()) {
            CTRPr rPr = run.getCTR().getRPr();
            CTFonts fonts = rPr.sizeOfRFontsArray() > 0 ? rPr.getRFontsArray(0) : rPr.addNewRFonts();
            fonts.setEastAsia(eastAsiaFont);
        }
    }

    /** 页面设置为 A4、上下边距1英寸、左右边距3.17cm（还原典型中文文档版心） */
    private static void setupA4Page(XWPFDocument doc) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr sectPr =
                doc.getDocument().getBody().isSetSectPr()
                        ? doc.getDocument().getBody().getSectPr()
                        : doc.getDocument().getBody().addNewSectPr();
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz pgSz =
                sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pgSz.setW(BigInteger.valueOf(11906));
        pgSz.setH(BigInteger.valueOf(16838));
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar pgMar =
                sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        pgMar.setTop(BigInteger.valueOf(1440));
        pgMar.setBottom(BigInteger.valueOf(1440));
        pgMar.setLeft(BigInteger.valueOf(1800));
        pgMar.setRight(BigInteger.valueOf(1800));
    }

    /** 是否以句读/收尾标点结尾（小节标题通常没有） */
    private static boolean endsWithSentencePunct(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return "。，；、！？：…,.;!?）)】」』”'\"".indexOf(last) >= 0;
    }

    /** 检测框行高中位数（忽略 height<=0 的行）；全部未知时返回 0 */
    private static double medianHeight(List<OcrLine> lines) {
        List<Integer> heights = new ArrayList<>();
        for (OcrLine l : lines) {
            if (l.height() > 0) {
                heights.add(l.height());
            }
        }
        if (heights.isEmpty()) {
            return 0;
        }
        heights.sort(Integer::compareTo);
        return heights.get(heights.size() / 2);
    }

    /** 段落代表检测框行高（首行） */
    private static double h(List<OcrLine> paraLines) {
        return paraLines.get(0).height() > 0 ? paraLines.get(0).height() : medianHeight(paraLines);
    }

    /** 墨迹高度中位数（忽略 inkHeight<=0 的行）；全部未知时返回 0 */
    private static double medianInkHeight(List<OcrLine> lines) {
        List<Integer> values = new ArrayList<>();
        for (OcrLine l : lines) {
            if (l.inkHeight() > 0) {
                values.add(l.inkHeight());
            }
        }
        if (values.isEmpty()) {
            return 0;
        }
        values.sort(Integer::compareTo);
        return values.get(values.size() / 2);
    }

    /** 墨迹密度中位数（忽略 density<=0 的行）；全部未知时返回 0 */
    private static double medianInkDensity(List<OcrLine> lines) {
        List<Double> values = new ArrayList<>();
        for (OcrLine l : lines) {
            if (l.inkDensity() > 0) {
                values.add(l.inkDensity());
            }
        }
        if (values.isEmpty()) {
            return 0;
        }
        values.sort(Double::compareTo);
        return values.get(values.size() / 2);
    }

    /** 拼接处两端都是拉丁字母/数字时需要补空格（如 Microso ft 断行） */
    private static boolean needsSpaceBetween(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return isLatinLetterOrDigit(a.charAt(a.length() - 1)) && isLatinLetterOrDigit(b.charAt(0));
    }

    private static boolean isLatinLetterOrDigit(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * 在原文档上直接翻译文字，保留所有格式、图片和样式
     * 只翻译正文段落和表格，不处理页眉页脚（避免水印和重复内容问题）
     */
    public static void translateInPlace(XWPFDocument doc, Function<String, String> translator) {
        List<XWPFParagraph> paragraphs = doc.getParagraphs();
        List<XWPFTable> tables = doc.getTables();

        // 收集需要翻译的段落及其原文
        List<XWPFParagraph> translatableParas = new ArrayList<>();
        List<String> originalTexts = new ArrayList<>();
        for (XWPFParagraph para : paragraphs) {
            String text = getParagraphText(para);
            if (text != null) {
                translatableParas.add(para);
                originalTexts.add(text);
            }
        }

        if (originalTexts.isEmpty()) {
            return;
        }

        // 批量翻译：用换行符拼接，一次 API 调用
        String joined = String.join("\n", originalTexts);
        String translatedJoined = translator.apply(joined);
        String[] translatedParts = translatedJoined.split("\n", -1);

        // 数量匹配则逐段写回，不匹配则跳过（避免错位）
        if (translatedParts.length == translatableParas.size()) {
            for (int i = 0; i < translatableParas.size(); i++) {
                String translated = translatedParts[i].trim();
                if (!translated.isEmpty() && !translated.equals(originalTexts.get(i))) {
                    writeTranslatedText(translatableParas.get(i), translated);
                }
            }
        }

        // 表格逐单元格翻译（量少，单次调用）
        for (XWPFTable table : tables) {
            translateTable(table, translator);
        }
    }

    private static String getParagraphText(XWPFParagraph para) {
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) {
            String text = run.text();
            if (text != null) {
                sb.append(text);
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty() || text.length() <= 2) {
            return null;
        }
        return sb.toString();
    }

    private static void writeTranslatedText(XWPFParagraph para, String translated) {
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) {
            return;
        }
        boolean written = false;
        for (XWPFRun run : runs) {
            String runText = run.text();
            if (runText == null || runText.isEmpty()) {
                continue;
            }
            clearRunText(run);
            if (!written) {
                run.setText(translated, 0);
                written = true;
            }
        }
    }

    /** 清空 run 的全部 <w:t> 文本节点，不影响图片等 drawing 元素 */
    private static void clearRunText(XWPFRun run) {
        for (int i = run.getCTR().sizeOfTArray() - 1; i >= 0; i--) {
            run.getCTR().removeT(i);
        }
    }

    private static void translateTable(XWPFTable table, Function<String, String> translator) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph para : cell.getParagraphs()) {
                    String text = getParagraphText(para);
                    if (text != null) {
                        String translated = translator.apply(text);
                        if (translated != null && !translated.equals(text)) {
                            writeTranslatedText(para, translated);
                        }
                    }
                }
            }
        }
    }

    /**
     * 去除文档中的红字水印（如原文档被 Spire 免费版转换时添加的
     * "Evaluation Warning / 评估警告" 红色评估警告，通常是浮动文本帧段落）。
     * 清理范围：正文段落、表格单元格、页眉页脚中的——
     * 1. 含评估警告关键字的段落
     * 2. 全部文字均为红色系的段落
     * 3. 页眉页脚中含图形水印标志（watermark 形状 / WordArt）的段落
     *
     * 注意：本方法直接从 XML 中移除段落，POI 内部的段落缓存列表不会同步更新，
     * 因此应在所有遍历操作（如翻译）完成之后、写出文档之前调用。
     */
    public static void removeRedWatermarks(XWPFDocument doc) {
        // 正文段落
        for (XWPFParagraph para : new ArrayList<>(doc.getParagraphs())) {
            if (isWatermarkParagraph(para)) {
                removeParagraph(para);
            }
        }
        // 正文表格
        for (XWPFTable table : doc.getTables()) {
            removeWatermarksFromTable(table);
        }
        // 页眉页脚（文字水印 + 图形水印）
        for (XWPFHeader header : doc.getHeaderList()) {
            removeWatermarksFromHeaderFooter(header);
        }
        for (XWPFFooter footer : doc.getFooterList()) {
            removeWatermarksFromHeaderFooter(footer);
        }
    }

    private static void removeWatermarksFromTable(XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph para : new ArrayList<>(cell.getParagraphs())) {
                    if (isWatermarkParagraph(para)) {
                        if (cell.getParagraphs().size() > 1) {
                            removeParagraph(para);
                        } else {
                            // 单元格至少保留一个段落，只清空内容
                            clearParagraphContent(para);
                        }
                    }
                }
            }
        }
    }

    private static void removeWatermarksFromHeaderFooter(XWPFHeaderFooter hf) {
        for (XWPFParagraph para : new ArrayList<>(hf.getParagraphs())) {
            if (isWatermarkParagraph(para) || isWatermarkShapeParagraph(para)) {
                if (hf.getParagraphs().size() > 1) {
                    removeParagraph(para);
                } else {
                    clearParagraphContent(para);
                }
            }
        }
        for (XWPFTable table : hf.getTables()) {
            removeWatermarksFromTable(table);
        }
    }

    /** 判断是否为水印段落：含评估警告关键字，或全部文字为红色系 */
    private static boolean isWatermarkParagraph(XWPFParagraph para) {
        String text = para.getText();
        if (text != null && EVAL_WARNING.matcher(text).find()) {
            return true;
        }
        return isAllTextRed(para);
    }

    /** 页眉页脚中的图形水印段落（VML pict 或 DrawingML，且含 watermark/WordArt 标志） */
    private static boolean isWatermarkShapeParagraph(XWPFParagraph para) {
        String xml = para.getCTP().xmlText();
        if (!xml.contains("<w:pict") && !xml.contains("<w:drawing") && !xml.contains("AlternateContent")) {
            return false;
        }
        return WATERMARK_MARKER.matcher(xml).find();
    }

    private static boolean isAllTextRed(XWPFParagraph para) {
        boolean hasText = false;
        for (XWPFRun run : para.getRuns()) {
            String t = run.text();
            if (t == null || t.trim().isEmpty()) {
                continue;
            }
            hasText = true;
            if (!isReddish(run.getColor())) {
                return false;
            }
        }
        return hasText;
    }

    /** 红色系判定：R 高且 G、B 低（覆盖 FF0000、C00000 等），或命名色 red/darkred */
    private static boolean isReddish(String color) {
        if (color == null) {
            return false;
        }
        if ("red".equalsIgnoreCase(color) || "darkred".equalsIgnoreCase(color)) {
            return true;
        }
        if (color.length() != 6) {
            return false;
        }
        try {
            int r = Integer.parseInt(color.substring(0, 2), 16);
            int g = Integer.parseInt(color.substring(2, 4), 16);
            int b = Integer.parseInt(color.substring(4, 6), 16);
            return r >= 150 && g <= 100 && b <= 100;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 从 XML 中移除整个段落 */
    private static void removeParagraph(XWPFParagraph para) {
        XmlCursor cursor = para.getCTP().newCursor();
        cursor.removeXml();
        cursor.dispose();
    }

    /** 清空段落的所有 run（含图片/形状），保留段落本身 */
    private static void clearParagraphContent(XWPFParagraph para) {
        for (XWPFRun run : new ArrayList<>(para.getRuns())) {
            XmlCursor cursor = run.getCTR().newCursor();
            cursor.removeXml();
            cursor.dispose();
        }
    }
}
