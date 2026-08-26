package com.doctool.service;

import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.Point;
import com.benjaminwan.ocrlibrary.TextBlock;
import com.doctool.mapper.TaskRecordMapper;
import com.doctool.model.OcrLine;
import com.doctool.model.TaskRecord;
import com.doctool.util.DocxUtils;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    private final TaskRecordMapper taskRecordMapper;

    @Value("${tesseract.datapath:/usr/share/tessdata}")
    private String tesseractDataPath;

    /** 内存缓存：任务 ID → 结果文件字节 */
    private final ConcurrentHashMap<Long, byte[]> resultCache = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    public void warmUp() {
        new Thread(() -> {
            try {
                log.info("预加载 RapidOCR 模型...");
                long t = System.currentTimeMillis();
                InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
                log.info("RapidOCR 模型预加载完成，耗时 {}ms", System.currentTimeMillis() - t);
            } catch (Exception e) {
                log.warn("RapidOCR 模型预加载失败，首次请求时再加载：{}", e.getMessage());
            }
        }, "ocr-warmup").start();
    }

    /** 异步提交：文件保存到临时目录，后台处理，结果存内存 */
    public TaskRecord submit(MultipartFile file) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String savedName = UUID.randomUUID() + "_" + originalFilename;
        Path tempPath = Path.of(System.getProperty("java.io.tmpdir"), savedName);
        file.transferTo(tempPath.toFile());

        TaskRecord record = new TaskRecord();
        record.setTaskType("OCR");
        record.setOriginalFilename(originalFilename);
        record.setStatus("PROCESSING");
        record.setCreateTime(LocalDateTime.now());
        taskRecordMapper.insert(record);

        long taskId = record.getId();
        new Thread(() -> processOcr(taskId, tempPath, originalFilename), "ocr-" + taskId).start();
        return record;
    }

    public TaskRecord getTask(Long id) {
        return taskRecordMapper.findById(id);
    }

    /** 获取结果字节，取完即删（一次性） */
    public byte[] getResultBytes(Long id) {
        return resultCache.remove(id);
    }

    private void processOcr(long taskId, Path tempPath, String originalFilename) {
        TaskRecord record = taskRecordMapper.findById(taskId);
        try {
            BufferedImage image = ImageIO.read(tempPath.toFile());
            if (image == null) {
                throw new IOException("无法读取该图片（不支持的格式）：" + originalFilename);
            }

            long t0 = System.currentTimeMillis();
            List<OcrLine> lines = recognize(tempPath.toFile(), image);
            log.info("OCR 识别完成，耗时 {}ms", System.currentTimeMillis() - t0);

            String baseName = originalFilename.contains(".")
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : originalFilename;
            String resultName = baseName + "_OCR.docx";

            XWPFDocument docxDoc = DocxUtils.createDocxFromLines(lines);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                docxDoc.write(baos);
                docxDoc.close();
                resultCache.put(taskId, baos.toByteArray());
            }

            record.setStatus("SUCCESS");
            record.setResultFilename(resultName);
            record.setFinishTime(LocalDateTime.now());
            taskRecordMapper.update(record);
        } catch (Exception e) {
            log.error("OCR 处理失败：{}", e.getMessage(), e);
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage());
            record.setFinishTime(LocalDateTime.now());
            taskRecordMapper.update(record);
        } finally {
            // 清理临时文件
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
        }
    }

    private List<OcrLine> recognize(File imageFile, BufferedImage image) throws TesseractException {
        try {
            return doOcrRapid(imageFile, image);
        } catch (Throwable t) {
            log.warn("RapidOCR 识别失败，回退 Tesseract：{}", t.toString());
        }
        return doOcrTesseract(imageFile, image);
    }

    static List<OcrLine> doOcrRapid(File imageFile, BufferedImage image) throws IOException {
        InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
        OcrResult result;
        synchronized (InferenceEngine.class) {
            result = engine.runOcr(imageFile.getAbsolutePath());
        }

        if (image == null) {
            image = ImageIO.read(imageFile);
        }
        BufferedImage inkImage = scaleForInk(image);
        List<OcrLine> lines = new ArrayList<>();
        for (TextBlock block : result.getTextBlocks()) {
            String text = cleanOcrText(block.getText());
            if (text.isEmpty()) {
                continue;
            }
            int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = 0, bottom = 0;
            for (Point p : block.getBoxPoint()) {
                left = Math.min(left, p.getX());
                top = Math.min(top, p.getY());
                right = Math.max(right, p.getX());
                bottom = Math.max(bottom, p.getY());
            }
            int inkHeight = 0;
            double inkDensity = 0;
            if (inkImage != null) {
                double sx = (double) inkImage.getWidth() / image.getWidth();
                double sy = (double) inkImage.getHeight() / image.getHeight();
                int sl = (int)(left * sx), st = (int)(top * sy);
                int sw = (int)((right - left) * sx), sh = (int)((bottom - top) * sy);
                int[] ink = measureInk(inkImage, sl, st, sw, sh);
                inkHeight = (int)(ink[0] / sy);
                int bandArea = (right - left) * Math.max(1, inkHeight);
                inkDensity = bandArea > 0 ? ink[1] / (double) bandArea : 0;
            }
            lines.add(new OcrLine(text, left, top, right - left, bottom - top, inkHeight, inkDensity));
        }
        lines.sort(Comparator.comparingInt(OcrLine::top).thenComparingInt(OcrLine::left));
        return mergeSameVisualLine(lines);
    }

    private static BufferedImage scaleForInk(BufferedImage src) {
        int maxDim = Math.max(src.getWidth(), src.getHeight());
        if (maxDim <= 1500) return src;
        double scale = 1500.0 / maxDim;
        int w = (int)(src.getWidth() * scale);
        int h = (int)(src.getHeight() * scale);
        BufferedImage dest = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dest;
    }

    private static int[] measureInk(BufferedImage img, int left, int top, int w, int h) {
        int x0 = Math.max(0, left);
        int y0 = Math.max(0, top);
        int x1 = Math.min(img.getWidth(), left + w);
        int y1 = Math.min(img.getHeight(), top + h);
        int dark = 0;
        int firstRow = -1;
        int lastRow = -1;
        for (int y = y0; y < y1; y++) {
            int rowDark = 0;
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                double lum = 0.299 * ((rgb >> 16) & 0xFF)
                        + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
                if (lum < 150) {
                    dark++;
                    rowDark++;
                }
            }
            if (rowDark > 2) {
                if (firstRow < 0) {
                    firstRow = y;
                }
                lastRow = y;
            }
        }
        int inkHeight = firstRow >= 0 ? lastRow - firstRow + 1 : 0;
        return new int[]{inkHeight, dark};
    }

    static List<OcrLine> mergeSameVisualLine(List<OcrLine> sortedLines) {
        List<OcrLine> merged = new ArrayList<>();
        for (OcrLine line : sortedLines) {
            if (merged.isEmpty()) {
                merged.add(line);
                continue;
            }
            OcrLine last = merged.get(merged.size() - 1);
            double tol = Math.min(last.height(), line.height()) * 0.5;
            double lastCenterY = last.top() + last.height() / 2.0;
            double centerY = line.top() + line.height() / 2.0;
            int hGap = line.left() - (last.left() + last.width());
            boolean sameLine = Math.abs(centerY - lastCenterY) <= tol
                    && hGap < Math.max(last.height(), line.height()) * 2;
            if (!sameLine) {
                merged.add(line);
                continue;
            }
            OcrLine left = line.left() < last.left() ? line : last;
            OcrLine right = left == line ? last : line;
            String sep = needsSpaceBetween(left.text(), right.text()) ? " " : "";
            int newLeft = Math.min(left.left(), right.left());
            int newTop = Math.min(left.top(), right.top());
            int newRight = Math.max(left.left() + left.width(), right.left() + right.width());
            int newBottom = Math.max(left.top() + left.height(), right.top() + right.height());
            merged.set(merged.size() - 1, new OcrLine(left.text() + sep + right.text(),
                    newLeft, newTop, newRight - newLeft, newBottom - newTop,
                    Math.max(left.inkHeight(), right.inkHeight()),
                    Math.max(left.inkDensity(), right.inkDensity())));
        }
        return merged;
    }

    private static boolean needsSpaceBetween(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return isLatinLetterOrDigit(a.charAt(a.length() - 1)) && isLatinLetterOrDigit(b.charAt(0));
    }

    private static boolean isLatinLetterOrDigit(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private List<OcrLine> doOcrTesseract(File imageFile, BufferedImage image) throws TesseractException {
        File tessDataDir = new File(tesseractDataPath);
        if (!tessDataDir.exists()) {
            throw new TesseractException("RapidOCR 不可用，且 Tesseract 未安装。");
        }

        File chiSimData = new File(tesseractDataPath, "chi_sim.traineddata");
        File engData = new File(tesseractDataPath, "eng.traineddata");

        String language;
        if (chiSimData.exists() && engData.exists()) {
            language = "chi_sim+eng";
        } else if (chiSimData.exists()) {
            language = "chi_sim";
        } else if (engData.exists()) {
            language = "eng";
        } else {
            throw new TesseractException("Tesseract 语言包缺失。");
        }

        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath(tesseractDataPath);
        tesseract.setLanguage(language);
        String text = cleanOcrText(tesseract.doOCR(preprocess(image)));

        List<OcrLine> lines = new ArrayList<>();
        int top = 0;
        for (String line : text.split("\n")) {
            lines.add(new OcrLine(line, 0, top, 0, 0, 0, 0));
            top += 20;
        }
        return lines;
    }

    static BufferedImage preprocess(BufferedImage src) {
        int maxDim = Math.max(src.getWidth(), src.getHeight());
        double scale = Math.min(3.0, Math.max(1.0, 2800.0 / maxDim));
        int w = (int) Math.round(src.getWidth() * scale);
        int h = (int) Math.round(src.getHeight() * scale);

        BufferedImage dest = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dest;
    }

    static String cleanOcrText(String text) {
        if (text == null) return "";
        text = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        text = text.replaceAll("[\\uE000-\\uF8FF\\uFFFD]", "");
        text = text.replaceAll("(?<=[\\u4e00-\\u9fff]) +(?=[\\u4e00-\\u9fff])", "");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }
}
