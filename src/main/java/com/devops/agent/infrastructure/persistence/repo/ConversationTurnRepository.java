package com.devops.agent.infrastructure.persistence.repo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 对话原文数据访问层（B-2 冷归档补全）。
 *
 * <p>操作 {@code sys_agent_conversation_turn}。这张表补的是三层记忆的一个缺口：
 * 热层（Redis）存原文但 TTL 仅 120 分钟，而冷归档按天执行——
 * 归档时原文早已过期，只能存摘要。合规审计与模型评测取数都需要逐轮原文。</p>
 *
 * <h3>写入语义：旁路、幂等、不阻塞主流程</h3>
 * <ul>
 *   <li><b>旁路</b>：写失败只记日志。用户已经收到回答了，
 *       不能因为「存档」失败而让接口报错；</li>
 *   <li><b>幂等</b>：{@code (session_id, turn_seq)} 唯一 +
 *       {@code ON CONFLICT DO NOTHING}。重放或重试不会写重，
 *       也不会因唯一键冲突抛异常污染日志。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@Slf4j
@Repository
public class ConversationTurnRepository {

    /**
     * 单字段长度上限。
     *
     * <p>运维场景里用户常粘贴整段日志或堆栈，单条可达数十 KB。
     * 不截断会让单行无限膨胀——而这张表的用途是审计与评测取数，
     * 保留前 32K 字符足以还原上下文。
     * 截断时显式追加标记，避免评测时把「被截断的半句」当成模型输出异常。</p>
     */
    private static final int MAX_TEXT_LEN = 32_000;
    private static final String TRUNCATED_MARK = "…（已截断）";

    private final JdbcTemplate jdbcTemplate;

    public ConversationTurnRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一轮对话原文。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而非先查后插：
     * 后者在并发下仍会撞唯一键，且多一次往返。</p>
     *
     * @return 是否实际写入（冲突跳过时返回 false）
     */
    public boolean append(String sessionId, String traceId, int turnSeq,
                          String userQuery, String aiAnswer, String toolResults,
                          int tokens, double costRmb, String finalState) {
        String sql = """
                INSERT INTO sys_agent_conversation_turn
                    (session_id, trace_id, turn_seq, user_query, ai_answer,
                     tool_results, tokens, cost_rmb, final_state, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (session_id, turn_seq) DO NOTHING
                """;
        try {
            int rows = jdbcTemplate.update(sql,
                    sessionId, traceId, turnSeq,
                    truncate(userQuery), truncate(aiAnswer), truncate(toolResults),
                    tokens, costRmb, finalState);
            return rows > 0;
        } catch (Exception e) {
            // 旁路能力：失败不外抛，但必须留痕。
            // 静默丢失会让「归档里少了几轮」在很久之后才被发现，
            // 且无从判断是没写进去还是被清理了
            log.error("🚨 [ConversationTurn] 对话原文写入失败，该轮将不会出现在归档中"
                    + " | sessionId={} | turnSeq={} | {}", sessionId, turnSeq, e.getMessage());
            return false;
        }
    }

    /**
     * 查询下一个轮次序号。
     *
     * <p>从库里推导而非依赖内存计数：Redis 不可用或实例重启后，
     * 内存里的轮次会归零，导致 turn_seq 从 1 重来，
     * 与既有记录撞唯一键——那样后续所有轮次都会被 DO NOTHING 静默丢弃。</p>
     */
    public int nextTurnSeq(String sessionId) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(turn_seq), 0) + 1 FROM sys_agent_conversation_turn "
                            + "WHERE session_id = ?", Integer.class, sessionId);
            return n != null ? n : 1;
        } catch (Exception e) {
            // 兜底为 1 会与既有记录冲突而被 DO NOTHING 跳过——
            // 结果是「这一轮没存上」，比写错数据好，但必须能被发现
            log.warn("⚠️ [ConversationTurn] 取轮次序号失败，兜底为 1"
                    + "（若该会话已有记录，本轮将因唯一键冲突被跳过）| sessionId={} | {}",
                    sessionId, e.getMessage());
            return 1;
        }
    }

    /**
     * 按会话取全部对话原文（轮次正序），供归档与审计回放。
     */
    public List<Map<String, Object>> findBySession(String sessionId) {
        String sql = """
                SELECT turn_seq, trace_id, user_query, ai_answer, tool_results,
                       tokens, cost_rmb, final_state, create_time
                  FROM sys_agent_conversation_turn
                 WHERE session_id = ?
                 ORDER BY turn_seq
                """;
        try {
            return jdbcTemplate.queryForList(sql, sessionId);
        } catch (Exception e) {
            // 与 ToolExecutionRepository.findBySaga 同型：空列表与「这个会话
            // 真的没有原文」无法区分，故必须记 ERROR。
            // 归档时若静默返回空，会产出一份「看起来完整实则缺失」的归档文件
            log.error("🚨 [ConversationTurn] 查询对话原文失败，归档将缺少本会话原文"
                    + "（这与「该会话无原文」无法区分）| sessionId={} | {}",
                    sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除指定会话的原文（归档完成后清理，或按保留期批量清理）。
     *
     * @return 删除行数
     */
    public int deleteBySession(String sessionId) {
        try {
            return jdbcTemplate.update(
                    "DELETE FROM sys_agent_conversation_turn WHERE session_id = ?", sessionId);
        } catch (Exception e) {
            log.warn("⚠️ [ConversationTurn] 删除对话原文失败 | sessionId={} | {}",
                    sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 按保留期清理过期原文。
     *
     * <p>原文体积远大于摘要，不清理会让这张表成为库里最大的表。
     * 清理边界由调用方按合规要求给定。</p>
     *
     * @param retentionDays 保留天数
     * @param batchSize     单批上限，防长事务
     * @return 删除行数
     */
    public int deleteOlderThan(int retentionDays, int batchSize) {
        String sql = """
                DELETE FROM sys_agent_conversation_turn
                 WHERE id IN (
                     SELECT id FROM sys_agent_conversation_turn
                      WHERE create_time < (CURRENT_DATE - CAST(? AS INTEGER))
                      LIMIT ?
                 )
                """;
        try {
            return jdbcTemplate.update(sql, retentionDays, batchSize);
        } catch (Exception e) {
            log.warn("⚠️ [ConversationTurn] 清理过期原文失败 | retentionDays={} | {}",
                    retentionDays, e.getMessage());
            return 0;
        }
    }

    private String truncate(String s) {
        if (s == null || s.length() <= MAX_TEXT_LEN) {
            return s;
        }
        return s.substring(0, MAX_TEXT_LEN) + TRUNCATED_MARK;
    }
}
