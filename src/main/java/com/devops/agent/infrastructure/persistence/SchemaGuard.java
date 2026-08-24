package com.devops.agent.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动期数据库 Schema 自检。
 *
 * <h3>解决什么问题</h3>
 * 本项目的迁移脚本靠人工按顺序执行（{@code sql/migration_vNN_*.sql}），
 * 没有 Flyway 之类的版本表来记录"执行到哪了"。于是存在一类很难查的故障：
 * <b>部署了新版 JAR 但漏执行了迁移</b>。
 *
 * <p>后果不对称，取决于漏的是哪一个：
 * <ul>
 *   <li>漏 v24（知识可见性）→ 检索 SQL 引用不存在的 {@code visibility} 列，
 *       <b>整个知识检索链路报错</b>。而 {@code DevOpsTools} 会把检索异常
 *       呈现为「知识库暂不可用」，排查者会去查向量库、查模型，
 *       很难想到是少跑了一个 ALTER TABLE；</li>
 *   <li>漏 v25（审计表）→ 审计写入失败但已被 catch，业务不受影响，
 *       只是<b>悄悄没有审计记录</b>——这在 L3/L4 阶段是合规问题。</li>
 * </ul>
 *
 * <h3>为什么在 ApplicationReadyEvent 而不是启动时直接失败</h3>
 * 用 {@code @EventListener(ApplicationReadyEvent)} 而非 {@code @PostConstruct}：
 * 此时数据源已完全就绪，且即使检查本身出错也不会阻断启动。
 * <p>
 * 默认<b>只告警不阻断</b>（{@code fail-fast=false}）：开发环境常常只跑了
 * 部分迁移，直接拒绝启动会让人无法调试。生产建议开
 * {@code devops.schema.fail-fast=true}，让漏迁移在部署时就暴露，
 * 而不是等用户点了检索才发现。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Component
public class SchemaGuard {

    /**
     * 关键列清单：表名 → 该表必须存在的列。
     * <p>只列「缺了会导致功能静默损坏」的列，不做全量 schema 比对——
     * 全量比对维护成本高且容易因无关变更误报。</p>
     */
    private static final Map<String, List<String>> REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        // v24：知识可见性。缺失会让检索 SQL 直接报错
        REQUIRED_COLUMNS.put("sys_knowledge_doc", List.of("visibility", "owner_dept"));
        REQUIRED_COLUMNS.put("sys_knowledge_chunk", List.of("visibility", "owner_dept"));
        REQUIRED_COLUMNS.put("sys_user", List.of("dept"));
    }

    /**
     * 需要整表存在的迁移产物。
     *
     * <ul>
     *   <li>v25 {@code sys_operation_audit}：缺失只影响审计，不影响业务；</li>
     *   <li>v26 {@code sys_risk_policy} / {@code sys_action_allowlist}：
     *       缺失会让 L3 治理配置页整页报错。更要紧的是<b>缺表时不能默认放行</b>——
     *       {@code AutomationGovernanceService.evaluate} 在读不到策略时返回拒绝，
     *       所以漏迁移的表现是「所有自动化动作都被拒」，
     *       这个方向是安全的，但用户会完全摸不着头脑，故必须在启动期就报出来。</li>
     * </ul>
     */
    private static final List<String> REQUIRED_TABLES = List.of(
            "sys_operation_audit", "sys_risk_policy", "sys_action_allowlist");

    @Value("${devops.schema.fail-fast:false}")
    private boolean failFast;

    private final JdbcTemplate jdbcTemplate;

    public SchemaGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifySchema() {
        List<String> problems = new ArrayList<>();

        try {
            REQUIRED_COLUMNS.forEach((table, columns) -> {
                for (String column : columns) {
                    if (!columnExists(table, column)) {
                        problems.add(String.format("缺列 %s.%s", table, column));
                    }
                }
            });
            for (String table : REQUIRED_TABLES) {
                if (!tableExists(table)) {
                    problems.add("缺表 " + table);
                }
            }
        } catch (Exception e) {
            // 自检本身失败（如权限不足读不到 information_schema）不应影响启动
            log.warn("⚠️ [SchemaGuard] Schema 自检未能完成（不阻断启动）: {}", e.getMessage());
            return;
        }

        if (problems.isEmpty()) {
            log.info("✅ [SchemaGuard] 数据库 Schema 自检通过");
            return;
        }

        String detail = String.join("、", problems);
        String hint = "请按序执行 sql/migration_v*.sql（缺 visibility 列会导致知识检索整体报错）";

        if (failFast) {
            throw new IllegalStateException(
                    "数据库 Schema 不完整：" + detail + "。" + hint);
        }
        log.error("""
                ❌ [SchemaGuard] 数据库 Schema 不完整，部分功能将不可用！
                   缺失项：{}
                   处理：{}
                   生产环境建议设置 devops.schema.fail-fast=true，让此类问题在部署时即暴露""",
                detail, hint);
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return n != null && n > 0;
    }

    private boolean tableExists(String table) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }
}
