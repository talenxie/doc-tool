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

@Service
@RequiredArgsConstructor
public class TranslateService {

    private final TaskRecordMapper taskRecordMapper;

    @Value("${translate.api-url:https://translate.googleapis.com/translate_a/single}")
    private String apiUrl;

    @Value("${translate.remove-red-watermark:true}")
    private boolean removeRedWatermark;

    private static final int MAX_TEXT_LENGTH = 4500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 翻译并返回结果字节（全内存，不落盘） */
    public byte[] translateAndReturn(byte[] fileBytes) throws Exception {
        XWPFDocument doc;
        try (var is = new ByteArrayInputStream(fileBytes)) {
            doc = DocxUtils.readDocx(is);
        }

        DocxUtils.translateInPlace(doc, this::translateSingleText);

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
            return callTranslateApi(text);
        } catch (Exception e) {
            return text;
        }
    }

    private String callTranslateApi(String text) throws Exception {
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

        return parseTranslateResponse(response.body(), text);
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
            return fallback;
        }
    }

    private static String stripInvisibleChars(String s) {
        return s.replaceAll("[\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF\\u00AD]", "");
    }
}
