package com.doctool.util;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DocxUtilsWatermarkTest {

    /** 序列化后重新打开，绕过 POI 内部缓存，验证真实写出的内容 */
    private static XWPFDocument roundTrip(XWPFDocument doc) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.write(bos);
        doc.close();
        return new XWPFDocument(new ByteArrayInputStream(bos.toByteArray()));
    }

    private static String allText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            sb.append(p.getText()).append('\n');
        }
        return sb.toString();
    }

    /** 统计"有红色文字"的段落数（空段落不算，红色段落标记不算） */
    private static int countVisibleRedParagraphs(XWPFDocument doc) {
        int count = 0;
        for (XWPFParagraph p : doc.getParagraphs()) {
            boolean redText = false;
            for (XWPFRun r : p.getRuns()) {
                String t = r.text();
                String c = r.getColor();
                if (t != null && !t.trim().isEmpty() && c != null
                        && (c.equalsIgnoreCase("ff0000") || c.equalsIgnoreCase("red"))) {
                    redText = true;
                }
            }
            if (redText) count++;
        }
        return count;
    }

    @Test
    void removesRedAndEvalWarningButKeepsContent() throws Exception {
        XWPFDocument doc = new XWPFDocument();

        // 正常黑字段落 —— 应保留
        XWPFRun r1 = doc.createParagraph().createRun();
        r1.setText("This is normal content.");
        r1.setColor("000000");

        // 红色帧段落（Spire 水印形态）—— 应删除
        XWPFParagraph p2 = doc.createParagraph();
        p2.getCTP().addNewPPr().addNewFramePr();
        XWPFRun r2 = p2.createRun();
        r2.setText("Evaluation Warning: The document was created with Spire.PDF for java.");
        r2.setColor("FF0000");

        // 翻译后的中文评估警告 —— 应删除（关键字命中）
        XWPFRun r3 = doc.createParagraph().createRun();
        r3.setText("评估警告：该文档是使用 Spire.PDF for java 创建的。");
        r3.setColor("FF0000");

        // 普通红字段落 —— 应删除
        XWPFRun r4 = doc.createParagraph().createRun();
        r4.setText("Some red note");
        r4.setColor("ff0000");

        // 紫字段落（文档原有内容色）—— 应保留
        XWPFRun r5 = doc.createParagraph().createRun();
        r5.setText("Purple content should stay.");
        r5.setColor("6f2f9f");

        DocxUtils.removeRedWatermarks(doc);
        XWPFDocument result = roundTrip(doc);

        String text = allText(result);
        assertTrue(text.contains("This is normal content."), "正文内容应保留");
        assertTrue(text.contains("Purple content should stay."), "紫色内容色应保留");
        assertFalse(text.contains("Evaluation Warning"), "英文评估警告应被删除");
        assertFalse(text.contains("评估警告"), "中文评估警告应被删除");
        assertFalse(text.contains("Some red note"), "红字段落应被删除");
        assertEquals(0, countVisibleRedParagraphs(result), "不应再有红色文字段落");
        result.close();
    }

    @Test
    void cleansRealTranslatedFile() throws Exception {
        Path file = Paths.get("outputs", "prefix_lidas_Adventures_Walkthrough_EP2_翻译.docx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(file), "测试输入文件不存在，跳过: " + file.toAbsolutePath());

        XWPFDocument doc;
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            doc = new XWPFDocument(fis);
        }
        DocxUtils.removeRedWatermarks(doc);
        XWPFDocument result = roundTrip(doc);

        String text = allText(result);
        assertFalse(text.contains("Evaluation Warning"), "英文评估警告应被删除");
        assertFalse(text.contains("评估警告"), "中文评估警告应被删除");
        assertEquals(0, countVisibleRedParagraphs(result), "不应再有红色文字段落");
        // 正文中文内容仍然存在
        assertTrue(text.contains("丽达"), "正文中文内容应保留");
        result.close();
    }
}
