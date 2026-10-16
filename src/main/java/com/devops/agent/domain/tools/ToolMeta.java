package com.devops.agent.domain.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tool 元数据注解
 * <p>
 * 定义工具的治理属性，参考 Agent Methodology §9.1 Tool 元数据规范。
 * 运行时由 {@link ToolRuntimeManager} 读取并执行相应治理逻辑。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolMeta {

    /**
     * 工具名称（唯一标识）
     */
    String name();

    /**
     * 业务描述（给模型看的用途说明）
     */
    String description() default "";

    /**
     * 风险等级
     * <ul>
     *   <li>READ_ONLY: 纯只读查询，可重试、可降级、无副作用</li>
     *   <li>DRAFT: 生成草稿/建议，不直接改状态</li>
     *   <li>CONTROLLED_WRITE: 受控写操作（如创建工单），需幂等、可补偿</li>
     *   <li>HIGH_RISK_EXECUTION: 高风险执行（如生产环境重启、删除资源），必须审批</li>
     * </ul>
     */
    ToolRiskLevel riskLevel() default ToolRiskLevel.READ_ONLY;

    /**
     * 是否幂等
     * 幂等工具可安全重试，非幂等工具重试需谨慎
     */
    boolean idempotent() default false;

    /**
     * 幂等键 SpEL 表达式
     * <p>示例：</p>
     * <ul>
     *   <li>单参数：<code>"#title"</code></li>
     *   <li>多参数组合：<code>"#title + '_' + #priority + '_' + #module"</code></li>
     *   <li>复杂对象：<code>"#request.title + '_' + #request.priority"</code></li>
     * </ul>
     * 运行时解析表达式生成 Redis Key：<code>devops:tool:idempotent:{toolName}:{keyValue}</code>
     */
    String idempotencyKey() default "";

    /**
     * 是否需要审批
     * HIGH_RISK_EXECUTION 默认 true，CONTROLLED_WRITE 可配置
     */
    boolean requiresApproval() default false;

    /**
     * 补偿动作方法名（同类中对应的补偿方法）
     * <p>示例：</p>
     * <ul>
     *   <li>创建工单 -> voidTicket(工单号)</li>
     *   <li>执行脚本 -> rollbackScript(执行ID)</li>
     * </ul>
     * 为空表示无补偿动作
     */
    String compensationAction() default "";

    /**
     * 超时时间（毫秒）
     * 只读工具建议 10-30s，写操作建议 30-60s
     */
    long timeoutMs() default 30000;

    /**
     * 最大重试次数
     * 只读工具可重试 2-3 次，写操作建议 0-1 次（避免重复副作用）
     */
    int maxRetries() default 2;

    /**
     * 允许调用的角色（空=不限制）
     * 示例：{"ADMIN", "OPERATOR"}
     */
    String[] allowedRoles() default {};
}