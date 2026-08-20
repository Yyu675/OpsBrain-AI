package com.devops.agent.common.guard;

import com.devops.agent.common.exception.SecurityGuardException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 输入安全门卫 - 拦截 Prompt 注入攻击
 * <p>
 * 职责: 在 Agent 编排前对用户输入进行安全检查,拦截可能的 Prompt 注入、越权指令等攻击
 * <p>
 * MVP-6 增强：集成 PromptInjectionGuard 多层检测引擎
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Slf4j
@Component
public class SecurityInputGuard {

    @Autowired
    private PromptInjectionGuard promptInjectionGuard;

    /**
     * 危险操作关键词(防止诱导执行危险命令)
     */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            ".*(删除数据库|drop.*database|rm.*-rf|格式化磁盘|shutdown|重启服务器).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * 检查用户输入是否安全
     *
     * @param userInput 用户输入文本
     * @throws SecurityGuardException 检测到攻击时抛出,code=40301
     */
    public void check(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            throw new SecurityGuardException(40001, "输入不能为空");
        }

        // 长度校验(防止超长输入导致 Token 溢出)
        if (userInput.length() > 1500) {
            throw new SecurityGuardException(40001, "输入超过最大长度限制(1500字符)");
        }

        // MVP-6: Prompt 注入多层检测（高危直接阻断，中危清洗，低危监控）
        promptInjectionGuard.checkAndBlock(userInput, PromptInjectionGuard.InjectionSource.USER_INPUT);

        // 危险操作检测（保留原有规则作为兜底）
        if (DANGEROUS_PATTERN.matcher(userInput).matches()) {
            log.warn("⚠️ [SecurityGuard] 拦截危险操作请求: {}", userInput.substring(0, Math.min(50, userInput.length())));
            throw new SecurityGuardException("该操作存在安全风险,已被拦截。");
        }

        log.debug("✅ [SecurityGuard] 输入安全检查通过");
    }

    /**
     * 检查外部来源文本（知识库、工单、工具结果等）
     * 不抛异常，仅返回检测结果供上层决策
     */
    public PromptInjectionGuard.DetectionResult checkExternal(String input, PromptInjectionGuard.InjectionSource source) {
        if (input == null || input.isEmpty()) {
            return PromptInjectionGuard.DetectionResult.clean();
        }
        return promptInjectionGuard.detect(input, source);
    }

    /**
     * 清洗外部文本（移除控制字符、标准化分隔符）
     */
    public String sanitizeExternal(String input) {
        if (input == null) return "";
        return promptInjectionGuard.sanitize(input);
    }
}
