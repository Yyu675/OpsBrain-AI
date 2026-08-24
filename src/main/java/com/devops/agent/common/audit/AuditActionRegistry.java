package com.devops.agent.common.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「HTTP 方法 + 路由」→ 语言无关操作标识 的映射表（C5）。
 *
 * <h3>为什么要有标识符，而不是直接存中文描述</h3>
 * 中文描述会随产品文案调整而变，无法用于统计、告警规则与权限审计；
 * 而 {@code knowledge.doc.delete} 这样的标识符是稳定契约。
 * 展示文案交给前端映射，将来接 i18n 也不必改历史数据。
 *
 * <h3>匹配策略</h3>
 * 路径里含 ID 等可变段（{@code /api/v1/knowledge/docs/42}），
 * 不能直接用字符串相等。这里把数字段与 UUID 段规范化为 {@code *} 再查表，
 * 例如 {@code DELETE /api/v1/knowledge/docs/42} → {@code DELETE /api/v1/knowledge/docs/*}。
 *
 * <p>未命中的写操作回退为 {@code generic}，仍会留痕（只是 action 粒度粗），
 * <b>不会因为没登记就丢审计</b>——审计的第一要求是不漏，其次才是好看。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
public final class AuditActionRegistry {

    /** 未登记路由的兜底标识 */
    public static final String GENERIC = "generic";

    private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

    static {
        // ── 知识库 ────────────────────────────────────────────────
        put("POST",   "/api/v1/knowledge/docs",              "knowledge.doc.create");
        put("PUT",    "/api/v1/knowledge/docs/*",            "knowledge.doc.update");
        put("DELETE", "/api/v1/knowledge/docs/*",            "knowledge.doc.delete");
        put("POST",   "/api/v1/knowledge/docs/*/publish",    "knowledge.doc.publish");
        put("POST",   "/api/v1/knowledge/docs/*/deprecate",  "knowledge.doc.deprecate");
        put("POST",   "/api/v1/knowledge/docs/*/rollback",   "knowledge.doc.rollback");
        put("POST",   "/api/v1/knowledge/categories",        "knowledge.category.create");
        put("PUT",    "/api/v1/knowledge/categories/*",      "knowledge.category.update");
        put("DELETE", "/api/v1/knowledge/categories/*",      "knowledge.category.delete");
        put("POST",   "/api/v1/knowledge/tags",              "knowledge.tag.create");
        put("PUT",    "/api/v1/knowledge/tags/*",            "knowledge.tag.rename");
        put("POST",   "/api/v1/knowledge/tags/*/merge",      "knowledge.tag.merge");
        put("DELETE", "/api/v1/knowledge/tags/*",            "knowledge.tag.delete");

        // ── 工单 ──────────────────────────────────────────────────
        put("POST",   "/api/v1/tickets",                     "ticket.create");
        put("PUT",    "/api/v1/tickets/*",                   "ticket.update");
        put("PATCH",  "/api/v1/tickets/*/status",            "ticket.status.change");
        put("PATCH",  "/api/v1/tickets/*/assignee",          "ticket.assign");
        put("DELETE", "/api/v1/tickets/*",                   "ticket.delete");
        put("POST",   "/api/v1/tickets/*/replies",           "ticket.reply");

        // ── 审批（L3/L4 的关键留痕）────────────────────────────────
        put("POST",   "/api/v1/approvals/*/approve",         "approval.approve");
        put("POST",   "/api/v1/approvals/*/reject",          "approval.reject");

        // ── 告警 ──────────────────────────────────────────────────
        put("POST",   "/api/v1/alerts/*/ack",                "alert.acknowledge");
        put("POST",   "/api/v1/alerts/*/resolve",            "alert.resolve");

        // ── 账号 ──────────────────────────────────────────────────
        put("POST",   "/api/v1/auth/login",                  "auth.login");
        put("POST",   "/api/v1/auth/logout",                 "auth.logout");
    }

    private AuditActionRegistry() {
    }

    private static void put(String method, String pattern, String action) {
        ACTIONS.put(method + " " + pattern, action);
    }

    /**
     * 解析操作标识。
     *
     * @param method HTTP 方法
     * @param path   请求路径（<b>不含</b> context-path）
     * @return 登记的标识，或 {@link #GENERIC}
     */
    public static String resolve(String method, String path) {
        if (method == null || path == null) {
            return GENERIC;
        }
        String key = method.toUpperCase() + " " + normalize(path);
        return ACTIONS.getOrDefault(key, GENERIC);
    }

    /**
     * 把路径中的可变段规范化为 {@code *}。
     * <p>可变段判定：纯数字（自增 ID）、UUID、或形如 {@code TKT-20260808-0001} 的业务编号。</p>
     */
    static String normalize(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            sb.append('/').append(isVariable(p) ? "*" : p);
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    private static boolean isVariable(String seg) {
        if (seg.isEmpty()) {
            return false;
        }
        // 纯数字：自增主键
        boolean allDigits = true;
        for (int i = 0; i < seg.length(); i++) {
            if (!Character.isDigit(seg.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            return true;
        }
        // UUID（含无分隔形式）
        if (seg.length() == 32 || seg.length() == 36) {
            boolean hexOnly = true;
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (c != '-' && Character.digit(c, 16) < 0) {
                    hexOnly = false;
                    break;
                }
            }
            if (hexOnly) {
                return true;
            }
        }
        // 业务编号：前缀-日期-序号，如 TKT-20260808-0001
        return seg.matches("[A-Z]{2,6}-\\d{6,8}-\\d{2,6}");
    }
}
