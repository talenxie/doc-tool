package com.doctool.service;

import com.doctool.mapper.TaskRecordMapper;
import com.doctool.model.TaskRecord;
import com.doctool.util.DocxUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranslateService {

    private final TaskRecordMapper taskRecordMapper;

    @Value("${translate.api-url:https://translate.googleapis.com/translate_a/single}")
    private String apiUrl;

    @Value("${translate.provider:google}")
    private String provider;

    @Value("${translate.api-key:}")
    private String apiKey;

    @Value("${translate.remove-red-watermark:true}")
    private boolean removeRedWatermark;

    private static final int MAX_TEXT_LENGTH = 4500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Random RANDOM = new Random();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final AtomicInteger failedBatchCount = new AtomicInteger(0);
    private final AtomicInteger totalBatchCount = new AtomicInteger(0);

    private boolean useMicrosoft() {
        return "microsoft".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank();
    }

    /** 翻译并返回结果字节（全内存，不落盘） */
    public byte[] translateAndReturn(byte[] fileBytes) throws Exception {
        XWPFDocument doc;
        try (var is = new ByteArrayInputStream(fileBytes)) {
            doc = DocxUtils.readDocx(is);
        }

        failedBatchCount.set(0);
        totalBatchCount.set(0);

        DocxUtils.translateInPlace(doc, this::translateBatchText);

        int total = totalBatchCount.get();
        int failed = failedBatchCount.get();
        if (total > 0 && failed == total) {
            throw new RuntimeException("翻译API不可用，所有翻译批次均失败，请稍后重试");
        }
        if (failed > 0) {
            log.warn("翻译部分完成：共 {} 个批次，{} 个失败，失败部分保留原文", total, failed);
        }

        if (removeRedWatermark) {
            DocxUtils.removeRedWatermarks(doc);
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.write(baos);
            doc.close();
            return baos.toByteArray();
        }
    }

    public TaskRecord createRecord(String originalFilename) {
        TaskRecord record = new TaskRecord();
        record.setTaskType("TRANSLATE");
        record.setOriginalFilename(originalFilename);
        record.setStatus("SUCCESS");
        record.setCreateTime(LocalDateTime.now());
        record.setFinishTime(LocalDateTime.now());
        taskRecordMapper.insert(record);
        return record;
    }

    private String translateSingleText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        try {
            String translated = callTranslateApi(text);
            if (translated.equals(text)) {
                log.warn("翻译API返回原文，可能未生效: {}", text.length() > 50 ? text.substring(0, 50) + "..." : text);
            }
            return translated;
        } catch (Exception e) {
            log.error("翻译API调用失败: {}", e.getMessage(), e);
            return text;
        }
    }

    /** 批量翻译：将多段文本拼接后一次性翻译，超长则分批 */
    private String translateBatchText(String joinedText) {
        if (joinedText == null || joinedText.trim().isEmpty()) {
            return joinedText;
        }

        // 按 MAX_TEXT_LENGTH 分批，按段落边界切分
        String[] lines = joinedText.split("\n", -1);
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        for (String line : lines) {
            if (currentChunk.length() + line.length() + 1 > MAX_TEXT_LENGTH && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            if (currentChunk.length() > 0) {
                currentChunk.append("\n");
            }
            currentChunk.append(line);
        }
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        // 逐批翻译，间隔 2 秒避免限流
        List<String> translatedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
            totalBatchCount.incrementAndGet();
            try {
                String translated = callTranslateApi(chunks.get(i));
                translatedChunks.add(translated);
                log.info("翻译批次 {}/{} 完成，原文长度: {}, 译文长度: {}",
                        i + 1, chunks.size(), chunks.get(i).length(), translated.length());
            } catch (Exception e) {
                failedBatchCount.incrementAndGet();
                log.error("翻译批次 {}/{} 失败: {}", i + 1, chunks.size(), e.getMessage());
                translatedChunks.add(chunks.get(i)); // 失败则保留原文
            }
        }

        return String.join("\n", translatedChunks);
    }

    private String callTranslateApi(String text) throws Exception {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return useMicrosoft() ? doCallMsTranslateApi(text) : doCallGoogleTranslateApi(text);
            } catch (RuntimeException e) {
                if (e.getMessage().contains("429") && attempt < maxRetries) {
                    long baseWait = attempt * 3000L;
                    long jitter = RANDOM.nextLong(1001); // 0~1000ms 随机抖动
                    long wait = baseWait + jitter;
                    log.warn("翻译API限流，等待 {}ms 后重试 ({}/{})", wait, attempt, maxRetries);
                    try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("翻译API重试耗尽");
    }

    private String doCallGoogleTranslateApi(String text) throws Exception {
        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String url = apiUrl + "?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" + encodedText;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            log.error("Google翻译API返回状态码: {}", response.statusCode());
            throw new RuntimeException("翻译API返回状态码: " + response.statusCode());
        }

        return parseTranslateResponse(response.body(), text);
    }

    private String doCallMsTranslateApi(String text) throws Exception {
        String url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-CN";

        String jsonBody = OBJECT_MAPPER.writeValueAsString(List.of(Map.of("text", text)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            log.error("Microsoft翻译API返回状态码: {}", response.statusCode());
            throw new RuntimeException("翻译API返回状态码: " + response.statusCode());
        }

        return parseMsTranslateResponse(response.body(), text);
    }

    private static String parseMsTranslateResponse(String body, String fallback) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode translations = root.path(0).path("translations");
            if (!translations.isArray() || translations.isEmpty()) {
                return fallback;
            }
            String translated = stripInvisibleChars(translations.path(0).path("text").asText(""));
            return translated.isEmpty() ? fallback : translated;
        } catch (Exception e) {
            log.error("Microsoft翻译响应解析失败: {}", e.getMessage());
            return fallback;
        }
    }

    static String parseTranslateResponse(String body, String fallback) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode segments = root.path(0);
            if (!segments.isArray()) {
                return fallback;
            }
            StringBuilder result = new StringBuilder();
            for (JsonNode segment : segments) {
                JsonNode translatedPart = segment.path(0);
                if (translatedPart.isTextual()) {
                    result.append(translatedPart.asText());
                }
            }
            String translated = stripInvisibleChars(result.toString());
            return translated.isEmpty() ? fallback : translated;
        } catch (Exception e) {
            log.error("翻译响应解析失败: {}", e.getMessage());
            return fallback;
        }
    }

    private static String stripInvisibleChars(String s) {
        return s.replaceAll("[\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF\\u00AD]", "");
    }
}
