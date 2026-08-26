package com.doctool.controller;

import com.doctool.service.TranslateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/translate")
@RequiredArgsConstructor
public class TranslateController {

    private final TranslateService translateService;

    @PostMapping
    public ResponseEntity<?> translate(@RequestParam("file") MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            byte[] fileBytes = file.getBytes();

            // 记录历史
            translateService.createRecord(originalFilename);

            // 翻译
            byte[] result = translateService.translateAndReturn(fileBytes);

            // 构造下载文件名
            String baseName = originalFilename.contains(".")
                    ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                    : originalFilename;
            String fileName = baseName + "_翻译.docx";
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .body(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", "FAILED", "errorMessage", e.getMessage()));
        }
    }
}
