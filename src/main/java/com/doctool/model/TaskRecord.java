package com.doctool.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TaskRecord {
    private Long id;
    private String taskType;
    private String originalFilename;
    private String resultFilename;
    private String status;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
