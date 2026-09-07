-- 伪装的旁路建表脚本（S0-1 契约探测 K1：应被 FlywayMigrationContractTest 拦截）
CREATE TABLE IF NOT EXISTS probe_dual_source (id BIGSERIAL PRIMARY KEY);
