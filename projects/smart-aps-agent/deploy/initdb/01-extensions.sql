-- 本地开发库初始化脚本：仅创建扩展，不建业务表
-- 业务 schema / 表结构由后端 Flyway 迁移（db/migration）负责，避免与迁移脚本冲突。
-- 该脚本仅在数据卷首次初始化时执行（pgdata 为空）。

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 便于排查：输出已安装扩展版本
SELECT extname, extversion FROM pg_extension WHERE extname IN ('pgcrypto', 'pg_trgm');
