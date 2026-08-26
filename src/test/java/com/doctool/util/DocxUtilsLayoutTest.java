package com.doctool.util;

import com.doctool.model.OcrLine;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocxUtilsLayoutTest {

    /** 段落的字号（半磅值，如 18 = 9pt、21 = 10.5pt） */
    private static int szHalf(XWPFParagraph p) {
        return ((java.math.BigInteger) p.getRuns().get(0).getCTR().getRPr()
                .getSzArray(0).getVal()).intValue();
    }

    private static String eastAsiaFont(XWPFParagraph p) {
        return p.getRuns().get(0).getCTR().getRPr().getRFontsArray(0).getEastAsia();
    }

    @Test
    void layout_inkBasedSizesAndWeights() throws Exception {
        // 文本栏 [100, 950]；正文墨迹高 10px、墨迹带密度 0.10；引言粗体密度 0.4+；标题墨迹 27px
        List<OcrLine> lines = List.of(
                // 大标题：大字号、加粗、居中
                new OcrLine("这是大标题", 300, 50, 400, 44, 27, 0.36),
                // 正文段1：三行合并（拉丁词边界补空格）
                new OcrLine("正文第一段 MicroSoft", 120, 150, 830, 20, 10, 0.10),
                new OcrLine("Office 继续", 100, 180, 850, 20, 10, 0.10),
                new OcrLine("第一段完。", 100, 210, 400, 20, 10, 0.10),
                // 正文段2
                new OcrLine("第二段开头", 100, 300, 850, 20, 10, 0.10),
                new OcrLine("第二段完。", 100, 330, 300, 20, 10, 0.10),
                // 加粗引言段（密度高 → 加粗；宋体加粗而非黑体）
                new OcrLine("加粗引言第一行", 136, 420, 790, 16, 11, 0.46),
                new OcrLine("加粗引言第二行。", 101, 442, 395, 16, 11, 0.44),
                // 小节标题（墨迹高、密度高）
                new OcrLine("栏内小标题", 100, 550, 300, 21, 17, 0.40),
                // 远处新段落（有句读、密度正常 → 不误判加粗）
                new OcrLine("远处新的一段。", 100, 650, 500, 20, 10, 0.10));
        XWPFDocument doc = DocxUtils.createDocxFromLines(lines);

        List<XWPFParagraph> paras = doc.getParagraphs();
        assertEquals(6, paras.size());

        // 标题：墨迹比例 27/10=2.7 → 9×2.7=24.3 → 吸附 24pt、加粗、黑体、居中、段后留白
        XWPFRun titleRun = paras.get(0).getRuns().get(0);
        assertEquals("这是大标题", titleRun.text());
        assertEquals(48, szHalf(paras.get(0)), "标题应为 24pt");
        assertTrue(titleRun.isBold());
        assertEquals("黑体", eastAsiaFont(paras.get(0)));
        assertEquals(ParagraphAlignment.CENTER, paras.get(0).getAlignment());
        assertEquals(360, paras.get(0).getSpacingAfter(), "标题段后应留白 18pt");

        // 正文段1：合并成段、9pt（小五）、宋体、不加粗、两端对齐、首行缩进
        XWPFRun bodyRun = paras.get(1).getRuns().get(0);
        assertEquals("正文第一段 MicroSoft Office 继续第一段完。", bodyRun.text());
        assertEquals(18, szHalf(paras.get(1)), "正文应为 9pt（小五）");
        assertFalse(bodyRun.isBold());
        assertEquals("宋体", eastAsiaFont(paras.get(1)));
        assertEquals(ParagraphAlignment.BOTH, paras.get(1).getAlignment());
        assertTrue(paras.get(1).getFirstLineIndent() > 0);

        // 正文段2：首行在栏左边距 → 无缩进
        assertEquals("第二段开头第二段完。", paras.get(2).getRuns().get(0).text());
        assertTrue(paras.get(2).getFirstLineIndent() <= 0);

        // 加粗引言段：墨迹密度高 → 加粗；比例 1.1 → 10.5pt（五号）；宋体加粗、不缩进
        XWPFRun quoteRun = paras.get(3).getRuns().get(0);
        assertEquals("加粗引言第一行加粗引言第二行。", quoteRun.text());
        assertEquals(21, szHalf(paras.get(3)), "引言应为 10.5pt（五号）");
        assertTrue(quoteRun.isBold());
        assertEquals("宋体", eastAsiaFont(paras.get(3)));
        assertTrue(paras.get(3).getFirstLineIndent() <= 0);

        // 小节标题：比例 1.7 → 15pt（小三）、加粗、黑体
        XWPFRun headRun = paras.get(4).getRuns().get(0);
        assertEquals("栏内小标题", headRun.text());
        assertEquals(30, szHalf(paras.get(4)), "小标题应为 15pt（小三）");
        assertTrue(headRun.isBold());
        assertEquals("黑体", eastAsiaFont(paras.get(4)));

        // 远处段落：有段前距、不加粗
        assertTrue(paras.get(5).getSpacingBefore() > 0);
        assertFalse(paras.get(5).getRuns().get(0).isBold());
        doc.close();
    }

    @Test
    void layout_normalizesBodySizeJitter() throws Exception {
        // 同一篇文档里两段正文墨迹高分别为 10px 和 12px（采集噪声）→ 字号应统一
        List<OcrLine> lines = List.of(
                new OcrLine("甲段第一行文字内容充满整栏", 100, 100, 850, 20, 10, 0.10),
                new OcrLine("甲段结束。", 100, 130, 400, 20, 10, 0.10),
                new OcrLine("乙段第一行文字内容充满整栏", 100, 220, 850, 20, 12, 0.10),
                new OcrLine("乙段结束。", 100, 250, 400, 20, 12, 0.10));
        XWPFDocument doc = DocxUtils.createDocxFromLines(lines);

        List<XWPFParagraph> paras = doc.getParagraphs();
        assertEquals(2, paras.size());
        assertEquals(szHalf(paras.get(0)), szHalf(paras.get(1)), "两段正文字号应统一");
        assertFalse(paras.get(0).getRuns().get(0).isBold());
        doc.close();
    }

    @Test
    void layout_fallbackPlainWhenNoGeometry() throws Exception {
        // 无坐标信息（Tesseract 回退路径）→ 纯文本逐行段落
        List<OcrLine> lines = List.of(
                new OcrLine("纯文本第一行", 0, 0, 0, 0, 0, 0),
                new OcrLine("纯文本第二行", 0, 20, 0, 0, 0, 0));
        XWPFDocument doc = DocxUtils.createDocxFromLines(lines);
        assertEquals(2, doc.getParagraphs().size());
        assertEquals(12, doc.getParagraphs().get(0).getRuns().get(0).getFontSize());
        doc.close();
    }
}
