package com.devops.agent.domain.governance;

import com.devops.agent.domain.tools.ToolRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理枚举的解析回退方向测试。
 *
 * <p>这组断言看似琐碎，但守的是一条关键不变式：
 * <b>解析失败时必须倒向更安全的一侧</b>。
 * 若哪天有人图省事把回退值改成 NONE，编译能过、功能"正常"，
 * 但一次数据脏值就会让高危动作变成免审批直接执行。</p>
 */
@DisplayName("治理枚举")
class GovernanceEnumTest {

    @Test
    @DisplayName("审批模式解析失败回退到最严格的 DUAL，而非 NONE")
    void approvalModeFallsBackToStrictest() {
        assertEquals(ApprovalMode.DUAL, ApprovalMode.parseOrStrictest(null));
        assertEquals(ApprovalMode.DUAL, ApprovalMode.parseOrStrictest(""));
        assertEquals(ApprovalMode.DUAL, ApprovalMode.parseOrStrictest("   "));
        assertEquals(ApprovalMode.DUAL, ApprovalMode.parseOrStrictest("NOT_A_MODE"));
        // 历史脏值：曾经用过小写或带空格的写法
        assertEquals(ApprovalMode.SINGLE, ApprovalMode.parseOrStrictest(" single "));
        assertEquals(ApprovalMode.NONE, ApprovalMode.parseOrStrictest("none"));
    }

    @Test
    @DisplayName("审批人数与 requiresHuman 一致")
    void approverCountMatchesRequiresHuman() {
        assertEquals(0, ApprovalMode.NONE.getRequiredApprovers());
        assertFalse(ApprovalMode.NONE.requiresHuman());

        assertEquals(1, ApprovalMode.SINGLE.getRequiredApprovers());
        assertTrue(ApprovalMode.SINGLE.requiresHuman());

        assertEquals(2, ApprovalMode.DUAL.getRequiredApprovers());
        assertTrue(ApprovalMode.DUAL.requiresHuman());
    }

    @Test
    @DisplayName("升级目标解析失败回退到 TICKET，而非 NONE——漏报不可接受")
    void escalateTargetFallsBackToTicket() {
        assertEquals(EscalateTarget.TICKET, EscalateTarget.parseOrDefault(null));
        assertEquals(EscalateTarget.TICKET, EscalateTarget.parseOrDefault("garbage"));
        assertEquals(EscalateTarget.ONCALL, EscalateTarget.parseOrDefault("oncall"));
        assertEquals(EscalateTarget.NONE, EscalateTarget.parseOrDefault("NONE"));
    }

    /**
     * 迁移脚本 v26 的种子数据用枚举名做主键。
     * 若有人改了 {@link ToolRiskLevel} 的枚举名，本测试会失败——
     * 提醒他同步改迁移脚本，否则策略表会出现一行永远匹配不上的孤儿记录，
     * 而对应等级的动作会因「策略缺失」被全部拒绝。
     */
    @Test
    @DisplayName("ToolRiskLevel 枚举名与 v26 种子数据一致")
    void riskLevelNamesMatchMigrationSeed() {
        assertEquals(4, ToolRiskLevel.values().length,
                "新增风险等级时必须同步 migration_v26 的种子数据，否则该级策略缺失");
        assertEquals("READ_ONLY", ToolRiskLevel.READ_ONLY.name());
        assertEquals("DRAFT", ToolRiskLevel.DRAFT.name());
        assertEquals("CONTROLLED_WRITE", ToolRiskLevel.CONTROLLED_WRITE.name());
        assertEquals("HIGH_RISK_EXECUTION", ToolRiskLevel.HIGH_RISK_EXECUTION.name());
    }
}
