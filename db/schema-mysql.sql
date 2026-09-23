-- 核心表（MySQL 8）。H2 模式下无需执行，JPA 会自动建表。
-- 切到 mysql profile 时可以手动执行本文件，或依赖 ddl-auto=update 自动生成。

CREATE TABLE IF NOT EXISTS interview_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company VARCHAR(100),
  `position` VARCHAR(100),
  interview_date DATE,
  jd TEXT,
  result VARCHAR(50),
  notes TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interview_question (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  record_id BIGINT,
  question TEXT,
  my_answer TEXT,
  feedback TEXT,
  score INT,
  tags VARCHAR(255),
  weak_points TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_question_record FOREIGN KEY (record_id) REFERENCES interview_record (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_question_record ON interview_question (record_id);

-- 分析报告快照：每次分析新增一条，保留历史
CREATE TABLE IF NOT EXISTS interview_analysis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  record_id BIGINT,
  model VARCHAR(50),
  summary TEXT,
  report_json TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_analysis_record FOREIGN KEY (record_id) REFERENCES interview_record (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_analysis_record ON interview_analysis (record_id);

-- 模拟面试
CREATE TABLE IF NOT EXISTS mock_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_jd TEXT,
  focus VARCHAR(100),
  status VARCHAR(20),
  total_score INT,
  summary TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mock_turn (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  mock_session_id BIGINT,
  turn_index INT,
  role VARCHAR(20),
  content TEXT,
  score INT,
  feedback TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_turn_session FOREIGN KEY (mock_session_id) REFERENCES mock_session (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_turn_session ON mock_turn (mock_session_id);

-- 以下为 RAG 相关表预留，默认不需要执行
-- knowledge_doc / knowledge_chunk 放 PgVector，见 README 的 pgvector 建表语句
