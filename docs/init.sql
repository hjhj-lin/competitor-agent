-- ============================================================
-- 竞品分析多Agent平台 - 数据库初始化脚本
-- 使用方法: mysql -uroot -p < init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS competitor_agent
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE competitor_agent;

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT,
    username    VARCHAR(50) NOT NULL,
    password    VARCHAR(100) NOT NULL,
    email       VARCHAR(100),
    daily_task_limit INT DEFAULT 5,
    deleted     TINYINT DEFAULT 0,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分析任务表
CREATE TABLE IF NOT EXISTS analysis_task (
    id           BIGINT AUTO_INCREMENT,
    user_id      BIGINT NOT NULL,
    company_name VARCHAR(100) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_agent VARCHAR(50),
    ai_call_count INT DEFAULT 0,
    result       TEXT,
    deleted      TINYINT DEFAULT 0,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent执行记录表
CREATE TABLE IF NOT EXISTS agent_execution (
    id            BIGINT AUTO_INCREMENT,
    task_id       BIGINT NOT NULL,
    agent_name    VARCHAR(50) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    input_data    TEXT,
    output_data   TEXT,
    error_message TEXT,
    steps         TEXT,
    duration_ms   INT,
    ai_call_count INT DEFAULT 0,
    created_at    DATETIME,
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 报告表
CREATE TABLE IF NOT EXISTS report (
    id           BIGINT AUTO_INCREMENT,
    task_id      BIGINT NOT NULL,
    user_id      BIGINT NOT NULL,
    company_name VARCHAR(100) NOT NULL,
    content      LONGTEXT,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task_id (task_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每日AI用量表
CREATE TABLE IF NOT EXISTS ai_usage_daily (
    id         BIGINT AUTO_INCREMENT,
    user_id    BIGINT NOT NULL,
    usage_date DATE NOT NULL,
    call_count INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_id, usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 默认管理员: admin / admin123
INSERT INTO sys_user (username, password, email, daily_task_limit)
VALUES ('admin', '$2a$10$U2F1MdSNdHiriKee8nXTBOpQFpTRRMWjwraexMZ5gszLeXEQTs9Xe', 'admin@example.com', 50)
ON DUPLICATE KEY UPDATE username = username;
