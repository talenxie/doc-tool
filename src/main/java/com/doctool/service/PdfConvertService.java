package com.doctool.service;

import com.doctool.mapper.TaskRecordMapper;
import com.doctool.model.TaskRecord;
import com.doctool.util.DocxUtils;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PdfConvertService {

    private final TaskRecordMapper taskRecordMapper;

    /** PDF 转 DOCX 并返回结果字节（全内存，不落盘） */
    public byte[] convertAndReturn(byte[] fileBytes) throws IOException {
        PDDocument pdfDoc = Loader.loadPDF(fileBytes);
        PDFTextStripper stripper = new PDFTextStripper();
        String pdfText = stripper.getText(pdfDoc);
        pdfDoc.close();

        XWPFDocument docxDoc = DocxUtils.createDocxWithText(pdfText);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            docxDoc.write(baos);
            docxDoc.close();
            return baos.toByteArray();
        }
    }

    public TaskRecord createRecord(String originalFilename) {
        TaskRecord record = new TaskRecord();
        record.setTaskType("PDF_CONVERT");
        record.setOriginalFilename(originalFilename);
        record.setStatus("SUCCESS");
        record.setCreateTime(LocalDateTime.now());
        record.setFinishTime(LocalDateTime.now());
        taskRecordMapper.insert(record);
        return record;
    }
}
