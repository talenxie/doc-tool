package com.doctool.model;

import java.time.LocalDateTime;

public class TaskRecord {
    public Long id;
    public String taskType;
    public String originalFilename;
    public String resultFilename;
    public String status;
    public String errorMessage;
    public LocalDateTime createTime;
    public LocalDateTime finishTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getResultFilename() { return resultFilename; }
    public void setResultFilename(String resultFilename) { this.resultFilename = resultFilename; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }
}
