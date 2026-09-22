package com.doctool.model;

public class VideoInfo {
    private String filename;
    private String duration;
    private String resolution;
    private String videoCodec;
    private String audioCodec;
    private String videoBitrate;
    private String audioBitrate;
    private String frameRate;
    private String fileSize;
    private String format;

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public String getVideoBitrate() { return videoBitrate; }
    public void setVideoBitrate(String videoBitrate) { this.videoBitrate = videoBitrate; }
    public String getAudioBitrate() { return audioBitrate; }
    public void setAudioBitrate(String audioBitrate) { this.audioBitrate = audioBitrate; }
    public String getFrameRate() { return frameRate; }
    public void setFrameRate(String frameRate) { this.frameRate = frameRate; }
    public String getFileSize() { return fileSize; }
    public void setFileSize(String fileSize) { this.fileSize = fileSize; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
}