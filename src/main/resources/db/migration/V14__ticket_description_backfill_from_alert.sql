-- V14：历史告警工单描述回填（一次性；方案 A 的存量补课）
--
-- 背景：2026-09-30 起新建单由 AlertService.buildTicketDescription 写入
-- 「核心描述 → 告警元信息 → 原始标签 → 注解 → 结构化 JSON 块」；此前的
-- 历史单只有一两行 annotations 文本（实测最短 4 字符），值班人拿到旧单
-- 无从定位问题。sys_alert 自 V11 起 labels_json/annotations_json 全量落库，
-- 数据都在——本迁移按同一模板从告警本体重建描述（工单描述是创建时快照，
-- 回填是对快照的一次性修正，不改变「快照」语义）。
--
-- 幂等：以「尚无结构化 JSON 块」为重跑谓词，重复执行不会覆盖新格式工单。
-- 与 Java 侧的格式差异（语义等价，机读不受影响）：
--   * JSON 冒号风格 jsonb_pretty("key": v) vs Jackson("key" : v)；
--   * labels/annotations 段按键名字母序（Java 按 webhook 到达序）；
--   * 时间戳格式化到秒（Java toString 带可变小数位）。
-- 同一工单关联多条告警（聚合组）时取任意一条命中的告警重建——组单描述
-- 本就来自首告警，回填取哪条都是完整上下文。
--
-- 与工单侧行无关的行（手工建单、无 alert.ticket_id 关联）不动。

UPDATE sys_devops_ticket t
SET description = (
    SELECT COALESCE(NULLIF(a.description, ''), t.title)
        || E'\n\n---\n### 告警元信息\n'
        || '- 级别: '    || COALESCE(a.level, '—')           || E'\n'
        || '- 服务: '    || COALESCE(a.service, '—')         || E'\n'
        || '- 模块: '    || COALESCE(a.module, '—')          || E'\n'
        || '- 发生次数: ' || COALESCE(a.occurrence_count::text, '1') || E'\n'
        || '- 去重键: '  || COALESCE(a.dedup_key, '—')        || E'\n'
        || CASE WHEN a.labels_json IS DISTINCT FROM '{}'::jsonb
            THEN E'\n### 原始标签（labels）\n'
              || (SELECT string_agg('- ' || e.key || ': ' || e.value, E'\n' ORDER BY e.key)
                  FROM jsonb_each_text(a.labels_json) e)
            ELSE '' END
        || E'\n'
        || CASE WHEN a.annotations_json IS DISTINCT FROM '{}'::jsonb
            THEN E'\n### 告警注解（annotations）\n'
              || (SELECT string_agg('- ' || e.key || ': ' || e.value, E'\n' ORDER BY e.key)
                  FROM jsonb_each_text(a.annotations_json) e)
            ELSE '' END
        || E'\n### 结构化上下文（JSON）\n```json\n'
        || jsonb_pretty(jsonb_build_object(
               'alertName',      a.alert_name,
               'title',          a.title,
               'level',          a.level,
               'service',        a.service,
               'module',         a.module,
               'status',         a.status,
               'source',         a.source,
               'system',         a.system,
               'occurrenceCount', a.occurrence_count,
               'firstOccurredAt', to_char(a.first_occurred_at, 'YYYY-MM-DD"T"HH24:MI:SS'),
               'lastOccurredAt',  to_char(a.last_occurred_at,  'YYYY-MM-DD"T"HH24:MI:SS'),
               'dedupKey',       a.dedup_key,
               'labels',         COALESCE(a.labels_json, '{}'::jsonb),
               'annotations',    COALESCE(a.annotations_json, '{}'::jsonb)))
        || E'\n```\n'
    FROM sys_alert a
    WHERE a.ticket_id = t.id
    -- 聚合组工单可挂多条告警：标量子查询限一行，取最早告警（即建单那条）
    ORDER BY a.id
    LIMIT 1
)
WHERE t.description NOT LIKE '%### 结构化上下文（JSON）%'
  AND EXISTS (SELECT 1 FROM sys_alert a2 WHERE a2.ticket_id = t.id);
