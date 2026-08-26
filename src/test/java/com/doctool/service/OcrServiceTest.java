package com.doctool.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.doctool.model.OcrLine;

import static org.junit.jupiter.api.Assertions.*;

class OcrServiceTest {

    @Test
    void clean_keepsChinesePunctuation() {
        // 中文引号、书名号、破折号、省略号都是合法内容，不能被清洗掉
        String in = "选择“打印”对话框，参考《手册》——详见第3页……完成。";
        assertEquals(in, OcrService.cleanOcrText(in));
    }

    @Test
    void clean_removesSpacesBetweenCjkChars() {
        // chi_sim 识别会在汉字间插入空格，应去掉；中英之间的空格保留
        assertEquals("将 Word2003 文档转换成 JPG 图片",
                OcrService.cleanOcrText("将 Word2003 文 档 转 换 成 JPG 图 片"));
    }

    @Test
    void clean_removesControlAndPrivateUseChars() {
        // 控制字符 BEL/US、私用区字形、替换符应被剔除（用 (char) 构造避免源码中出现转义序列）
        String withControl = "正常" + (char) 7 + "文字" + (char) 31 + "ABC";
        assertEquals("正常文字ABC", OcrService.cleanOcrText(withControl));
        String withPrivateUse = "内容" + (char) 0xE000 + (char) 0xFFFD;
        assertEquals("内容", OcrService.cleanOcrText(withPrivateUse));
    }

    @Test
    void clean_compressesBlankLines() {
        assertEquals("第一段\n\n第二段", OcrService.cleanOcrText("第一段\n\n\n\n\n第二段"));
    }

    @Test
    void clean_handlesNullAndEmpty() {
        assertEquals("", OcrService.cleanOcrText(null));
        assertEquals("", OcrService.cleanOcrText("   "));
    }

    @Test
    void merge_joinsBlocksOnSameVisualLine() {
        // 同一视觉行被拆成两块（如被 ==== 分隔线打断），应合并且按左右顺序拼接
        List<OcrLine> lines = new ArrayList<>(List.of(
                new OcrLine("右侧块", 420, 102, 100, 20, 20, 0.05),   // top 差 2px，排序后可能在后
                new OcrLine("左侧块", 100, 100, 300, 20, 20, 0.05),
                new OcrLine("下一行", 100, 200, 300, 20, 20, 0.05)));
        lines.sort(Comparator.comparingInt(OcrLine::top).thenComparingInt(OcrLine::left));
        List<OcrLine> merged = OcrService.mergeSameVisualLine(lines);
        assertEquals(2, merged.size());
        assertEquals("左侧块右侧块", merged.get(0).text());
        assertEquals(100, merged.get(0).left());
        assertEquals("下一行", merged.get(1).text());
    }

    @Test
    void merge_keepsDistantBlocksSeparate() {
        // 水平间距很大（如分栏）的同行块不应合并
        List<OcrLine> lines = new ArrayList<>(List.of(
                new OcrLine("左栏文字", 100, 100, 200, 20, 20, 0.05),
                new OcrLine("右栏文字", 700, 100, 200, 20, 20, 0.05)));
        List<OcrLine> merged = OcrService.mergeSameVisualLine(lines);
        assertEquals(2, merged.size());
    }

    @Test
    void merge_addsSpaceBetweenLatinBlocks() {
        List<OcrLine> lines = new ArrayList<>(List.of(
                new OcrLine("Hello", 100, 100, 50, 20, 20, 0.05),
                new OcrLine("World", 160, 100, 50, 20, 20, 0.05)));
        List<OcrLine> merged = OcrService.mergeSameVisualLine(lines);
        assertEquals(1, merged.size());
        assertEquals("Hello World", merged.get(0).text());
    }

    @Test
    void preprocess_upscalesSmallImageAndGrayscales() {
        BufferedImage src = new BufferedImage(662, 940, BufferedImage.TYPE_INT_RGB);
        BufferedImage out = OcrService.preprocess(src);
        // 小图按比例放大到长边约 2800px，且转为灰度
        assertEquals(2800, out.getHeight());
        assertTrue(out.getWidth() > 662 * 2, "宽度应按比例放大约3倍");
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, out.getType());
    }

    @Test
    void preprocess_keepsLargeImageSize() {
        // 大图不再放大，避免内存膨胀
        BufferedImage src = new BufferedImage(3000, 4000, BufferedImage.TYPE_INT_RGB);
        BufferedImage out = OcrService.preprocess(src);
        assertEquals(3000, out.getWidth());
        assertEquals(4000, out.getHeight());
    }
}
