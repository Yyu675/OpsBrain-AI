package com.devops.agent.domain.tools;

import com.devops.agent.domain.biz.entity.TicketEnums;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * L3 Schema 校验自愈重试
 * <p>
 * 职责: 工具参数强校验, 不合格抛 IllegalArgumentException
 * LangChain4j 自动捕获异常并回喂模型重试(最多 3 次)
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Slf4j
@Component
public class ToolParameterValidator {

    /**
     * 工单优先级合法值（单源引用 {@link TicketEnums.Priority}）
     */
    private static final Set<String> VALID_PRIORITIES = TicketEnums.Priority.ALL;

    /**
     * 工单模块合法值（单源引用 {@link TicketEnums.Module}）
     */
    private static final Set<String> VALID_MODULES = TicketEnums.Module.ALL;

    /**
     * 校验工单创建参数
     *
     * @param title       工单标题
     * @param priority    优先级
     * @param module      故障模块
     * @param description 故障描述
     * @throws IllegalArgumentException 参数不合法时抛出,触发模型自愈重试
     */
    public void validateTicketParams(String title, String priority, String module, String description) {
        // 1. 标题校验
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "❌ 工单标题不能为空。请重新生成一个有意义的标题,例如:'K8s Pod 启动失败排查'");
        }
        if (title.length() < 5) {
            throw new IllegalArgumentException(
                    "❌ 工单标题过短(少于5字符)。请提供更详细的标题,例如:'生产环境 Redis 连接池耗尽问题'");
        }
        if (title.length() > 255) {
            throw new IllegalArgumentException(
                    "❌ 工单标题过长(超过255字符)。请精简标题,保留核心问题描述即可");
        }

        // 2. 优先级校验
        if (priority == null || priority.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "❌ 优先级不能为空。请从 [P0, P1, P2, P3] 中选择一个"
                            + "（P0=生产宕机, P1=影响业务有临时方案, P2=需处理不紧急, P3=优化建议）");
        }
        String normalizedPriority = priority.trim().toUpperCase();
        // 同时接受旧三档 HIGH/MEDIUM/LOW：模型可能沿用历史提示词，
        // 直接拒绝会触发无谓的自愈重试。接受但记 WARN 以便发现残留调用点
        boolean legacyPriority = TicketEnums.Priority.isLegacyValue(normalizedPriority);
        if (!VALID_PRIORITIES.contains(normalizedPriority) && !legacyPriority) {
            throw new IllegalArgumentException(
                    String.format("❌ 优先级 '%s' 无效。必须是 [P0, P1, P2, P3] 之一,请重新选择", priority));
        }
        if (legacyPriority) {
            log.warn("⚠️ [Validator] 模型传入旧三档优先级，已兼容接受 | 入参={}", priority);
        }

        // 3. 模块校验
        if (module == null || module.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "❌ 故障模块不能为空。请从 [K8S, ALIYUN_SLB, MYSQL, NETWORK, OTHER] 中选择一个");
        }
        String normalizedModule = module.trim().toUpperCase();
        if (!VALID_MODULES.contains(normalizedModule)) {
            throw new IllegalArgumentException(
                    String.format("❌ 故障模块 '%s' 无效。必须是 [K8S, ALIYUN_SLB, MYSQL, NETWORK, OTHER] 之一,请重新选择", module));
        }

        // 4. 描述校验
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "❌ 故障描述不能为空。请提供详细的问题描述,包括错误信息、堆栈信息等");
        }
        if (description.length() < 10) {
            throw new IllegalArgumentException(
                    "❌ 故障描述过短(少于10字符)。请提供更详细的问题描述,帮助运维人员快速定位问题");
        }
        if (description.length() > 2000) {
            throw new IllegalArgumentException(
                    "❌ 故障描述过长(超过2000字符)。请精简描述,只保留关键错误信息和堆栈片段");
        }
    }

    /**
     * 校验检索关键词参数
     *
     * @param keyword 检索关键词
     * @throws IllegalArgumentException 参数不合法时抛出
     */
    public void validateSearchKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "❌ 检索关键词不能为空。请从用户问题中提取核心技术关键词,例如:'K8s Pod'、'SLB 健康检查'");
        }
        if (keyword.length() < 2) {
            throw new IllegalArgumentException(
                    "❌ 检索关键词过短(少于2字符)。请提供更具体的关键词以提高检索精度");
        }
        if (keyword.length() > 100) {
            throw new IllegalArgumentException(
                    "❌ 检索关键词过长(超过100字符)。请精炼关键词,只保留核心概念");
        }
    }
}
