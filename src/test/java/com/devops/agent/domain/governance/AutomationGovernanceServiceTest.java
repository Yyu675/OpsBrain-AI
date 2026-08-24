package com.devops.agent.domain.governance;

import com.devops.agent.domain.tools.ToolRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动化治理服务测试。
 *
 * <h3>为什么这组测试比一般 CRUD 测试更值得写</h3>
 * 这里校验的是<b>安全边界</b>。CRUD 出错用户会立刻看到（数据没保存），
 * 但校验漏洞不会有任何表现——直到某天 AI 在生产环境执行了一个
 * 本该被拦下的动作。那时唯一的证据是「配置当时确实是这样的」。
 *
 * <p>因此覆盖重点放在<b>每一种绕过方式</b>，而不是正常路径。</p>
 */
@DisplayName("自动化治理服务")
class AutomationGovernanceServiceTest {

    private RiskPolicyRepository policyRepo;
    private ActionAllowlistRepository allowlistRepo;
    private AutomationGovernanceService service;

    @BeforeEach
    void setUp() {
        policyRepo = mock(RiskPolicyRepository.class);
        allowlistRepo = mock(ActionAllowlistRepository.class);
        service = new AutomationGovernanceService(policyRepo, allowlistRepo);
    }

    // ==================== 夹具 ====================

    private RiskPolicy policy(String level, ApprovalMode mode, String envs,
                              boolean autoExecute, int blastCount) {
        RiskPolicy p = new RiskPolicy();
        p.setRiskLevel(level);
        p.setDisplayName(level);
        p.setApprovalMode(mode);
        p.setApprovalTimeoutMinutes(30);
        p.setAutoExecuteAllowed(autoExecute);
        p.setMaxBlastRadiusPercent(10);
        p.setMaxBlastRadiusCount(blastCount);
        p.setCooldownSeconds(60);
        p.setMaxRetries(1);
        p.setEscalateAfterMinutes(15);
        p.setEscalateTarget(EscalateTarget.TICKET);
        p.setAllowedEnvironments(envs);
        return p;
    }

    private ActionAllowlistEntry entry(String key, String riskLevel, String envs) {
        ActionAllowlistEntry e = new ActionAllowlistEntry();
        e.setActionKey(key);
        e.setDisplayName("测试动作");
        e.setCategory("k8s");
        e.setRiskLevel(riskLevel);
        e.setTargetPattern("ns:staging/*");
        e.setEnvironments(envs);
        e.setEnabled(true);
        return e;
    }

    private void stubPolicy(RiskPolicy p) {
        when(policyRepo.findByRiskLevel(p.getRiskLevel())).thenReturn(Optional.of(p));
        when(policyRepo.findAll()).thenReturn(List.of(p));
    }

    /** 让 insert 返回 ID，并让随后的 getAction(id) 能读回条目 */
    private void stubInsertAndReadBack(ActionAllowlistEntry stored) {
        when(allowlistRepo.insert(any(), anyString())).thenReturn(1L);
        when(allowlistRepo.findById(1L)).thenReturn(Optional.of(stored));
    }

    // ==================================================================

    @Nested
    @DisplayName("跨表校验：条目只能收紧，不能放宽")
    class CrossTableValidation {

        @Test
        @DisplayName("拒绝把需审批等级的动作显式配成免审批")
        void rejectsApprovalDowngrade() {
            stubPolicy(policy("HIGH_RISK_EXECUTION", ApprovalMode.DUAL, "dev", false, 1));

            ActionAllowlistEntry e = entry("k8s.rollout.undo", "HIGH_RISK_EXECUTION", "dev");
            e.setRequiresApproval(Boolean.FALSE);   // ← 试图绕过审批

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAction(e, "admin"));
            assertTrue(ex.getMessage().contains("不能配置为免审批"), ex.getMessage());
            verify(allowlistRepo, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("允许把免审批等级的动作收紧为需审批")
        void allowsApprovalTightening() {
            stubPolicy(policy("READ_ONLY", ApprovalMode.NONE, "prod,staging,dev", true, 9999));

            ActionAllowlistEntry e = entry("k8s.pod.describe", "READ_ONLY", "prod");
            e.setTargetPattern(null);               // 只读动作允许留空目标
            e.setRequiresApproval(Boolean.TRUE);    // ← 收紧，应放行
            stubInsertAndReadBack(e);

            assertDoesNotThrow(() -> service.createAction(e, "admin"));
            verify(allowlistRepo).insert(any(), eq("admin"));
        }

        @Test
        @DisplayName("拒绝条目环境超出策略允许范围")
        void rejectsEnvironmentEscalation() {
            // 策略只开了 staging / dev
            stubPolicy(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "staging,dev", false, 5));

            ActionAllowlistEntry e = entry("k8s.pod.restart", "CONTROLLED_WRITE", "prod");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAction(e, "admin"));
            assertTrue(ex.getMessage().contains("prod"), ex.getMessage());
            assertTrue(ex.getMessage().contains("超出"), ex.getMessage());
        }

        @Test
        @DisplayName("拒绝条目爆炸半径超过策略上限")
        void rejectsBlastRadiusEscalation() {
            stubPolicy(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "staging", false, 5));

            ActionAllowlistEntry e = entry("k8s.pod.restart", "CONTROLLED_WRITE", "staging");
            e.setMaxBlastRadiusCount(100);          // ← 策略上限是 5

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAction(e, "admin"));
            assertTrue(ex.getMessage().contains("超过风险等级上限"), ex.getMessage());
        }

        @Test
        @DisplayName("写操作必须指定目标模式——留空等于对所有资源生效")
        void requiresTargetPatternForWrites() {
            stubPolicy(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "staging", false, 5));

            ActionAllowlistEntry e = entry("host.docker.prune", "CONTROLLED_WRITE", "staging");
            e.setTargetPattern("   ");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAction(e, "admin"));
            assertTrue(ex.getMessage().contains("目标匹配模式"), ex.getMessage());
        }

        @Test
        @DisplayName("只读动作可以不指定目标模式")
        void allowsBlankTargetForReadOnly() {
            stubPolicy(policy("READ_ONLY", ApprovalMode.NONE, "prod,staging,dev", true, 9999));

            ActionAllowlistEntry e = entry("k8s.logs.tail", "READ_ONLY", "prod");
            e.setTargetPattern("");
            stubInsertAndReadBack(e);

            assertDoesNotThrow(() -> service.createAction(e, "admin"));
        }
    }

    @Nested
    @DisplayName("重新启用时必须复查——防「停用→策略收紧→启用」绕过")
    class ReEnableRevalidation {

        @Test
        @DisplayName("策略收紧后，旧条目无法被直接重新启用")
        void rejectsReEnableAfterPolicyTightened() {
            // 条目当初是在「允许 prod」时配的
            ActionAllowlistEntry stale = entry("k8s.pod.restart", "CONTROLLED_WRITE", "prod");
            stale.setEnabled(false);
            when(allowlistRepo.findById(7L)).thenReturn(Optional.of(stale));

            // 但策略后来被收紧到只允许 dev
            stubPolicy(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "dev", false, 5));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.toggleAction(7L, true, 0, "admin"));
            assertTrue(ex.getMessage().contains("超出"), ex.getMessage());
            verify(allowlistRepo, never()).toggleEnabled(anyLong(), anyBoolean(), anyInt(), anyString());
        }

        @Test
        @DisplayName("停用不需要复查——收紧方向的操作永远允许")
        void alwaysAllowsDisable() {
            ActionAllowlistEntry stale = entry("k8s.pod.restart", "CONTROLLED_WRITE", "prod");
            when(allowlistRepo.findById(7L)).thenReturn(Optional.of(stale));
            // 刻意不 stub 策略：若停用路径也去查策略校验，这里会因策略缺失而失败

            service.toggleAction(7L, false, 0, "admin");
            verify(allowlistRepo).toggleEnabled(7L, false, 0, "admin");
        }
    }

    @Nested
    @DisplayName("风险策略更新")
    class PolicyUpdate {

        @Test
        @DisplayName("拒绝把高风险执行配成免审批")
        void rejectsNoApprovalForHighRisk() {
            stubPolicy(policy("HIGH_RISK_EXECUTION", ApprovalMode.DUAL, "dev", false, 1));

            RiskPolicy submitted = policy("HIGH_RISK_EXECUTION", ApprovalMode.NONE, "dev", false, 1);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.updatePolicy("HIGH_RISK_EXECUTION", submitted, 0, "admin"));
            assertTrue(ex.getMessage().contains("免审批"), ex.getMessage());
            verify(policyRepo, never()).update(any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("高风险执行开自动执行时，爆炸半径不得超过 25%")
        void capsBlastRadiusForAutonomousHighRisk() {
            stubPolicy(policy("HIGH_RISK_EXECUTION", ApprovalMode.DUAL, "dev", false, 1));

            RiskPolicy submitted = policy("HIGH_RISK_EXECUTION", ApprovalMode.DUAL, "dev", true, 1);
            submitted.setMaxBlastRadiusPercent(50);

            assertThrows(IllegalArgumentException.class,
                    () -> service.updatePolicy("HIGH_RISK_EXECUTION", submitted, 0, "admin"));
        }

        @Test
        @DisplayName("不支持新增等级——未知等级直接拒绝，不插入死配置")
        void rejectsUnknownLevel() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.updatePolicy("SUPER_DANGEROUS", new RiskPolicy(), 0, "admin"));
        }

        @Test
        @DisplayName("越界数值被拒绝：审批时限 0 会让审批单一创建就过期")
        void rejectsOutOfRangeNumbers() {
            stubPolicy(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "dev", false, 5));

            RiskPolicy submitted = policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "dev", false, 5);
            submitted.setApprovalTimeoutMinutes(0);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.updatePolicy("CONTROLLED_WRITE", submitted, 0, "admin"));
            assertTrue(ex.getMessage().contains("审批时限"), ex.getMessage());
        }

        @Test
        @DisplayName("未知环境名被拒绝——防止「production」这类拼写变体静默失效")
        void rejectsUnknownEnvironment() {
            stubPolicy(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "dev", false, 5));

            RiskPolicy submitted = policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "production", false, 5);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.updatePolicy("CONTROLLED_WRITE", submitted, 0, "admin"));
            assertTrue(ex.getMessage().contains("未知环境"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("执行判定 evaluate")
    class Evaluate {

        @Test
        @DisplayName("未登记的动作一律拒绝——白名单是允许清单")
        void deniesUnregisteredAction() {
            when(allowlistRepo.findByActionKey("rm.rf.slash")).thenReturn(Optional.empty());

            Map<String, Object> r = service.evaluate("rm.rf.slash", "prod");
            assertEquals(false, r.get("allowed"));
            assertTrue(String.valueOf(r.get("reason")).contains("未登记"));
        }

        @Test
        @DisplayName("策略缺失时拒绝而非放行——读不到约束等于没有约束")
        void deniesWhenPolicyMissing() {
            ActionAllowlistEntry e = entry("k8s.pod.restart", "CONTROLLED_WRITE", "prod");
            when(allowlistRepo.findByActionKey("k8s.pod.restart")).thenReturn(Optional.of(e));
            when(policyRepo.findByRiskLevel("CONTROLLED_WRITE")).thenReturn(Optional.empty());

            Map<String, Object> r = service.evaluate("k8s.pod.restart", "prod");
            assertEquals(false, r.get("allowed"));
            assertTrue(String.valueOf(r.get("reason")).contains("策略缺失"));
        }

        @Test
        @DisplayName("停用的动作被拒绝，且原因与「未登记」区分开")
        void deniesDisabledActionWithDistinctReason() {
            ActionAllowlistEntry e = entry("k8s.pod.restart", "CONTROLLED_WRITE", "staging");
            e.setEnabled(false);
            when(allowlistRepo.findByActionKey("k8s.pod.restart")).thenReturn(Optional.of(e));

            Map<String, Object> r = service.evaluate("k8s.pod.restart", "staging");
            assertEquals(false, r.get("allowed"));
            assertTrue(String.valueOf(r.get("reason")).contains("停用"));
        }

        @Test
        @DisplayName("策略未开自动执行时拒绝，即便条目本身允许该环境")
        void deniesWhenAutoExecuteOff() {
            ActionAllowlistEntry e = entry("k8s.pod.restart", "CONTROLLED_WRITE", "staging");
            when(allowlistRepo.findByActionKey("k8s.pod.restart")).thenReturn(Optional.of(e));
            when(policyRepo.findByRiskLevel("CONTROLLED_WRITE")).thenReturn(
                    Optional.of(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "staging", false, 5)));

            Map<String, Object> r = service.evaluate("k8s.pod.restart", "staging");
            assertEquals(false, r.get("allowed"));
            assertTrue(String.valueOf(r.get("reason")).contains("未开启自动执行"));
        }

        @Test
        @DisplayName("全部通过时返回生效后的审批要求与爆炸半径")
        void allowsAndReportsEffectiveConstraints() {
            ActionAllowlistEntry e = entry("host.log.rotate", "CONTROLLED_WRITE", "staging");
            e.setMaxBlastRadiusCount(2);
            when(allowlistRepo.findByActionKey("host.log.rotate")).thenReturn(Optional.of(e));
            when(policyRepo.findByRiskLevel("CONTROLLED_WRITE")).thenReturn(
                    Optional.of(policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "staging", true, 5)));

            Map<String, Object> r = service.evaluate("host.log.rotate", "staging");
            assertEquals(true, r.get("allowed"));
            // 条目没设 requiresApproval → 跟随策略的 SINGLE → true
            assertEquals(Boolean.TRUE, r.get("requiresApproval"));
            // 条目 2 与策略 5 取小 → 2
            assertEquals(2, r.get("blastRadiusCount"));
        }
    }

    @Nested
    @DisplayName("生效值合并")
    class EffectiveValues {

        @Test
        @DisplayName("条目未覆盖时跟随策略")
        void fallsBackToPolicy() {
            RiskPolicy p = policy("CONTROLLED_WRITE", ApprovalMode.SINGLE, "staging", true, 5);
            stubPolicy(p);

            ActionAllowlistEntry e = entry("host.log.rotate", "CONTROLLED_WRITE", "staging");
            when(allowlistRepo.findById(3L)).thenReturn(Optional.of(e));

            ActionAllowlistEntry loaded = service.getAction(3L);
            assertEquals(Boolean.TRUE, loaded.getEffectiveRequiresApproval());
            assertEquals(5, loaded.getEffectiveBlastRadiusCount());
        }

        @Test
        @DisplayName("策略读不到时倒向最严格，而不是显示「无需审批」")
        void strictestWhenPolicyMissing() {
            ActionAllowlistEntry e = entry("x.y.z", "CONTROLLED_WRITE", "staging");
            when(allowlistRepo.findById(4L)).thenReturn(Optional.of(e));
            when(policyRepo.findByRiskLevel("CONTROLLED_WRITE")).thenReturn(Optional.empty());

            ActionAllowlistEntry loaded = service.getAction(4L);
            assertEquals(Boolean.TRUE, loaded.getEffectiveRequiresApproval());
            assertEquals(1, loaded.getEffectiveBlastRadiusCount());
        }

        @Test
        @DisplayName("列表查询会为每一行填充生效值，前端不必自己合并")
        void appliesToListItems() {
            RiskPolicy p = policy("READ_ONLY", ApprovalMode.NONE, "prod", true, 9999);
            stubPolicy(p);

            ActionAllowlistEntry e = entry("k8s.pod.describe", "READ_ONLY", "prod");
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("items", List.of(e));
            page.put("total", 1L);
            when(allowlistRepo.query(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(page);

            service.listActions(null, null, null, null, 1, 20);
            assertEquals(Boolean.FALSE, e.getEffectiveRequiresApproval());
            assertEquals(9999, e.getEffectiveBlastRadiusCount());
        }
    }

    @Nested
    @DisplayName("归一化与词表")
    class Normalization {

        @Test
        @DisplayName("动作标识统一转小写并去空格")
        void normalizesActionKey() {
            stubPolicy(policy("READ_ONLY", ApprovalMode.NONE, "dev", true, 9999));

            ActionAllowlistEntry e = entry("  K8s.Pod.Describe  ", "READ_ONLY", "dev");
            e.setTargetPattern(null);
            stubInsertAndReadBack(e);

            service.createAction(e, "admin");
            assertEquals("k8s.pod.describe", e.getActionKey());
        }

        @Test
        @DisplayName("环境列表去重且保序")
        void deduplicatesEnvironments() {
            stubPolicy(policy("READ_ONLY", ApprovalMode.NONE, "prod,staging,dev", true, 9999));

            ActionAllowlistEntry e = entry("k8s.pod.describe", "READ_ONLY", "dev, prod ,dev");
            e.setTargetPattern(null);
            stubInsertAndReadBack(e);

            service.createAction(e, "admin");
            assertEquals("dev,prod", e.getEnvironments());
        }

        @Test
        @DisplayName("重复的 action_key 给出可读提示而非直接抛 DB 约束异常")
        void reportsDuplicateKeyReadably() {
            stubPolicy(policy("READ_ONLY", ApprovalMode.NONE, "dev", true, 9999));

            ActionAllowlistEntry existing = entry("k8s.pod.describe", "READ_ONLY", "dev");
            when(allowlistRepo.findByActionKey("k8s.pod.describe"))
                    .thenReturn(Optional.of(existing));

            ActionAllowlistEntry e = entry("k8s.pod.describe", "READ_ONLY", "dev");
            e.setTargetPattern(null);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.createAction(e, "admin"));
            assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
        }

        @Test
        @DisplayName("筛选选项的风险等级来自枚举，即便库里当前没有该等级的动作")
        void riskLevelOptionsComeFromEnum() {
            when(allowlistRepo.filterOptions()).thenReturn(Map.of("categories", List.of("k8s")));

            Map<String, Object> options = service.actionFilterOptions();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> levels = (List<Map<String, String>>) options.get("riskLevels");

            assertNotNull(levels);
            assertEquals(ToolRiskLevel.values().length, levels.size());
            assertTrue(levels.stream()
                    .anyMatch(m -> "HIGH_RISK_EXECUTION".equals(m.get("value"))));
        }

        @Test
        @DisplayName("非法类别被拒绝")
        void rejectsUnknownCategory() {
            stubPolicy(policy("READ_ONLY", ApprovalMode.NONE, "dev", true, 9999));

            ActionAllowlistEntry e = entry("k8s.pod.describe", "READ_ONLY", "dev");
            e.setCategory("magic");
            e.setTargetPattern(null);

            assertThrows(IllegalArgumentException.class, () -> service.createAction(e, "admin"));
        }
    }

    @Nested
    @DisplayName("更新时 action_key 不可变")
    class ImmutableActionKey {

        @Test
        @DisplayName("前端改了 actionKey 也沿用旧值——它是审计记录的关联键")
        void keepsOriginalKeyOnUpdate() {
            RiskPolicy p = policy("READ_ONLY", ApprovalMode.NONE, "dev", true, 9999);
            stubPolicy(p);

            ActionAllowlistEntry existing = entry("k8s.pod.describe", "READ_ONLY", "dev");
            existing.setId(9L);
            when(allowlistRepo.findById(9L)).thenReturn(Optional.of(existing));

            ActionAllowlistEntry submitted = entry("totally.different.key", "READ_ONLY", "dev");
            submitted.setTargetPattern(null);

            service.updateAction(9L, submitted, 0, "admin");

            assertEquals("k8s.pod.describe", submitted.getActionKey(),
                    "action_key 被改掉会让历史审计记录变成孤儿");
            assertFalse("totally.different.key".equals(submitted.getActionKey()));
        }
    }
}
