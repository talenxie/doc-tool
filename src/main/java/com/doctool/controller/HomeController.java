package com.doctool.controller;

import com.doctool.mapper.TaskRecordMapper;
import com.doctool.model.TaskRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final TaskRecordMapper taskRecordMapper;

    @GetMapping("/")
    public String index(Model model) {
        List<TaskRecord> records = taskRecordMapper.findAll();
        model.addAttribute("records", records);
        return "index";
    }

    @GetMapping("/api/records")
    @ResponseBody
    public List<TaskRecord> records() {
        return taskRecordMapper.findAll();
    }

    @GetMapping("/translate")
    public String translatePage() {
        return "translate";
    }

    @GetMapping("/pdf-convert")
    public String pdfConvertPage() {
        return "pdf-convert";
    }

    @GetMapping("/ocr")
    public String ocrPage() {
        return "ocr";
    }
}
