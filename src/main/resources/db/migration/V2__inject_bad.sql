-- S0-1-K2 注入：坏迁移（引用不存在类型，migrate 必须失败、启动被拒、下轮恢复即绿）
CREATE TABLE sys__k2_probe (id nosuchtype PRIMARY KEY);
