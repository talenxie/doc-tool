CREATE TABLE IF NOT EXISTS task_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL COMMENT '任务类型: TRANSLATE/PDF_CONVERT/OCR',
    original_filename VARCHAR(255) NOT NULL COMMENT '原始文件名',
    result_filename VARCHAR(255) COMMENT '结果文件名',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/SUCCESS/FAILED',
    error_message VARCHAR(1024) COMMENT '错误信息',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finish_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS analysis_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL COMMENT '文件指纹: hash(fileSize+关键信息)',
    analysis_type VARCHAR(32) NOT NULL COMMENT '分析类型: VIDEO_RECOGNIZE/VIDEO_SUMMARY/IMAGE_RECOGNIZE',
    result_data CLOB COMMENT '分析结果 JSON',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_analysis_cache_fp ON analysis_cache(fingerprint, analysis_type);

CREATE TABLE IF NOT EXISTS user_preference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    pref_key VARCHAR(64) NOT NULL,
    pref_value VARCHAR(512),
    UNIQUE(username, pref_key)
);
