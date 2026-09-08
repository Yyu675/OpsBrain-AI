package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketActivity;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketActionRepository;
import com.devops.agent.domain.biz.repository.TicketActivityRepository;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import com.devops.agent.domain.biz.repository.TicketReplyRepository;
import com.devops.agent.domain.biz.repository.TicketTagRepository;
import com.devops.agent.domain.notify.Notifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketService#saveTicket} 单元测试（AI 建单主路径）。
 *
 * <h3>为什么补这个</h3>
 * 由 {@code tools/audit/scan_service_write_coverage.py} 扫出，
 * 在 {@code baseline.json} 里被标为「确属缺口」且优先级最高。
 * 它是 <b>AI 自动建单的唯一落库入口</b>（{@code DevOpsAgentServiceImpl}
 * 的 Single Writer 调用点），6 个入参 + 5 处派生逻辑，全部无直测。
 *
 * <h3>这个方法一旦出错，错的是「事后才发现」的那类东西</h3>
 * 它写下的不只是一行工单，还包括三项<b>后续无法重算</b>的派生数据：
 * <ul>
 *   <li><b>SLA 截止时刻</b>（{@code responseDeadline}/{@code resolveDeadline}）——
 *       以建单时刻为基准冻结。若这里没写或算错，超时判定、SLA 看板、
 *       首响告警全部失准，而工单本身看起来完全正常；</li>
 *   <li><b>优先级归一化</b>——模型可能传旧三档 HIGH/MEDIUM/LOW 或 URGENT 别名。
 *       不归一化就把非法值写库，排序权重与 SLA 计时都落到兜底分支，
 *       表现为「P0 工单排在 P2 后面」这类没人能立刻解释的现象；</li>
 *   <li><b>sourceTraceId</b>——AI 会话与工单的唯一关联。丢了就再也无法
 *       从工单回溯到「当时模型是怎么判断的」，这正是 AI 建单最需要的审计能力。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("TicketService.saveTicket（AI 建单主路径）")
class TicketSaveTicketTest {

    private DevOpsTicketRepository ticketRepository;
    private TicketActivityRepository activityRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TicketService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ticketRepository = mock(DevOpsTicketRepository.class);
        activityRepository = mock(TicketActivityRepository.class);
        TicketReplyRepository replyRepository = mock(TicketReplyRepository.class);
        TicketTagRepository tagRepository = mock(TicketTagRepository.class);
        TicketActionRepository actionRepository = mock(TicketActionRepository.class);
        TicketPostmortemRepository postmortemRepository = mock(TicketPostmortemRepository.class);
        Notifier notifier = mock(Notifier.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 默认：序号 7，落库成功
        when(valueOps.increment(anyString())).thenReturn(7L);
        when(ticketRepository.save(any())).thenReturn(1);

        service = new TicketService(ticketRepository, replyRepository,
                activityRepository, tagRepository, actionRepository, postmortemRepository,
                notifier, redisTemplate);
    }

    /** 取出真正交给仓储落库的那个实体——断言派生字段只能看它，不能看入参 */
    private DevOpsTicket saved() {
        ArgumentCaptor<DevOpsTicket> cap = ArgumentCaptor.forClass(DevOpsTicket.class);
        verify(ticketRepository).save(cap.capture());
        return cap.getValue();
    }

    private String callWith(String priority, String module) {
        return service.saveTicket("标题", priority, module, "描述", null, "trace-1");
    }

    // ==================================================================
    // 工单号
    // ==================================================================

    @Nested
    @DisplayName("工单号生成")
    class TicketId {

        @Test
        @DisplayName("格式为 TKT-yyyyMMdd-4位序号，且返回值与落库 id 一致")
        void formatAndConsistency() {
            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            String id = callWith("P1", "K8S");

            assertThat(id).isEqualTo("TKT-" + today + "-0007");
            // 返回值与库里那条必须是同一个号。不一致的话，调用方拿着一个
            // 查不到的工单号告诉用户「已创建」，用户点进去是 404
            assertThat(saved().getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("序号补齐到 4 位——否则工单号按字符串排序会乱")
        void sequenceIsZeroPadded() {
            when(valueOps.increment(anyString())).thenReturn(42L);
            assertThat(callWith("P1", "K8S")).endsWith("-0042");
        }

        @Test
        @DisplayName("序号为 1 时设置 48 小时过期——否则 Redis 键按天无限堆积")
        void firstSequenceSetsExpiry() {
            when(valueOps.increment(anyString())).thenReturn(1L);

            callWith("P1", "K8S");

            verify(redisTemplate).expire(anyString(), org.mockito.ArgumentMatchers.eq(48L),
                    org.mockito.ArgumentMatchers.eq(TimeUnit.HOURS));
        }

        @Test
        @DisplayName("序号非 1 时不重设过期——重设会让当天键的 TTL 被反复延长")
        void laterSequenceDoesNotResetExpiry() {
            when(valueOps.increment(anyString())).thenReturn(2L);

            callWith("P1", "K8S");

            verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("Redis 返回 null 时降级为序号 1，而不是抛 NPE")
        void nullIncrementFallsBackToOne() {
            // Redis 抖动时 increment 可能返回 null。此时宁可产生一个
            // 可能重复的号，也好过让整条 AI 建单链路失败——
            // 重号可由唯一约束兜住，而建单失败是用户直接可见的
            when(valueOps.increment(anyString())).thenReturn(null);

            assertThat(callWith("P1", "K8S")).endsWith("-0001");
        }

        @Test
        @DisplayName("Redis 抛异常时包装为「流水号生成失败」，且不落库")
        void redisFailureIsWrapped() {
            when(valueOps.increment(anyString()))
                    .thenThrow(new RuntimeException("connection refused"));

            assertThatThrownBy(() -> callWith("P1", "K8S"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("流水号生成失败");
            // 号都没生成就不该有工单落库
            verify(ticketRepository, never()).save(any());
        }
    }

    // ==================================================================
    // 优先级归一化与 SLA
    // ==================================================================

    @Nested
    @DisplayName("优先级归一化与 SLA 派生")
    class PriorityAndSla {

        @Test
        @DisplayName("旧三档 HIGH 归一化为 P1——不是 P0")
        void legacyHighMapsToP1() {
            // migration_v16 的存量迁移口径：HIGH→P1。
            // 旧 HIGH 混装了「紧急」与「高」两种语义，无法区分，统一降为 P1 更保守——
            // 误把普通高优当成 P0，会让 15 分钟首响时限失去可信度
            callWith("HIGH", "K8S");
            assertThat(saved().getPriority()).isEqualTo("P1");
        }

        @Test
        @DisplayName("URGENT 别名归一化为 P0")
        void urgentMapsToP0() {
            callWith("URGENT", "K8S");
            assertThat(saved().getPriority()).isEqualTo("P0");
        }

        @Test
        @DisplayName("非法优先级兜底为 P2，而不是原样写库")
        void invalidPriorityFallsBackToP2() {
            // 模型偶尔会编出 "CRITICAL" 这类值。原样入库的后果是
            // 排序权重与 SLA 计时都落到兜底分支，表现为「P0 排在 P2 后面」
            callWith("CRITICAL", "K8S");
            assertThat(saved().getPriority()).isEqualTo("P2");
        }

        @Test
        @DisplayName("null 优先级兜底为 P2")
        void nullPriorityFallsBackToP2() {
            callWith(null, "K8S");
            assertThat(saved().getPriority()).isEqualTo("P2");
        }

        @Test
        @DisplayName("大小写与空白不敏感：' p0 ' 同样归一化为 P0")
        void priorityIsTrimmedAndUpperCased() {
            callWith(" p0 ", "K8S");
            assertThat(saved().getPriority()).isEqualTo("P0");
        }

        @Test
        @DisplayName("SLA 截止时刻按归一化后的优先级派生，且以建单时刻为基准")
        void slaDeadlinesDerivedFromNormalizedPriority() {
            // ── 本类最重要的一条 ──────────────────────────────
            // 这两个时刻一旦写错或没写，超时判定、SLA 看板、首响告警全部失准，
            // 而工单本身看起来完全正常——属于「事后才发现」的那类错误。
            // 用 HIGH 而非 P1 入参：同时验证「派生用的是归一化后的值」
            callWith("HIGH", "K8S");
            DevOpsTicket t = saved();

            assertThat(t.getResponseDeadline()).isNotNull();
            assertThat(t.getResolveDeadline()).isNotNull();
            // P1：30 分钟首响 / 8 小时解决。
            //
            // 必须断言「精确等于 createTime + 时限」，不能用 toMinutes() 比较差值——
            // 那样会把亚分钟的偏差截断掉。实测教训：把基准误写成
            // LocalDateTime.now() 时，它与 createTime 只差几十微秒，
            // toMinutes() 后两者都是 30，测试照样通过（注入验证时 CI 全绿）。
            // 而这个「基准取哪个时刻」恰恰是本方法最该守住的东西：
            // 用当前时刻重算，等于把已消耗的 SLA 时间一笔勾销。
            assertThat(t.getResponseDeadline())
                    .isEqualTo(t.getCreateTime().plusMinutes(30));
            assertThat(t.getResolveDeadline())
                    .isEqualTo(t.getCreateTime().plusHours(8));
        }

        @Test
        @DisplayName("P0 的首响时限是 15 分钟——四档各自不同，不能共用一套")
        void p0HasTightestDeadline() {
            callWith("P0", "K8S");
            DevOpsTicket t = saved();

            assertThat(t.getResponseDeadline())
                    .isEqualTo(t.getCreateTime().plusMinutes(15));
            assertThat(t.getResolveDeadline())
                    .isEqualTo(t.getCreateTime().plusHours(4));
        }

        @Test
        @DisplayName("SLA 展示串由时限数派生，与截止时刻口径一致")
        void slaTextMatchesDeadlines() {
            // 展示串若与实际计时不同源，会出现「界面写 4h 解决、
            // 系统按 24h 判超时」这种没人会怀疑的偏差
            callWith("P0", "K8S");
            assertThat(saved().getSla()).isEqualTo("15m 响应 / 4h 解决");
        }
    }

    // ==================================================================
    // 模块与分类
    // ==================================================================

    @Nested
    @DisplayName("模块映射分类")
    class ModuleMapping {

        @Test
        @DisplayName("K8S → 容器/K8s，MYSQL → 数据库")
        void knownModulesMapped() {
            callWith("P1", "K8S");
            assertThat(saved().getCategory()).isEqualTo("容器/K8s");
        }

        @Test
        @DisplayName("ALIYUN_SLB 与 NETWORK 都映射为「网络」")
        void slbAndNetworkShareCategory() {
            callWith("P1", "ALIYUN_SLB");
            assertThat(saved().getCategory()).isEqualTo("网络");
        }

        @Test
        @DisplayName("小写模块名同样能映射——大小写不敏感")
        void moduleIsCaseInsensitive() {
            // 若这里区分大小写，"k8s" 会落到 default 分支变成「其他」，
            // 分类筛选就查不到这张工单了，而工单本身正常存在
            callWith("P1", "k8s");
            assertThat(saved().getCategory()).isEqualTo("容器/K8s");
        }

        @Test
        @DisplayName("未知模块映射为「其他」而非留空")
        void unknownModuleMapsToOther() {
            callWith("P1", "REDIS");
            assertThat(saved().getCategory()).isEqualTo("其他");
        }
    }

    // ==================================================================
    // 初始字段与落库
    // ==================================================================

    @Nested
    @DisplayName("初始字段与落库")
    class InitialStateAndPersist {

        @Test
        @DisplayName("初始状态 PENDING、待分配、版本号 0")
        void initialFields() {
            callWith("P1", "K8S");
            DevOpsTicket t = saved();

            assertThat(t.getStatus()).isEqualTo("PENDING");
            assertThat(t.getAssignee()).isEqualTo("待分配");
            // 版本号必须从 0 起：乐观锁 UPDATE ... WHERE version=? 依赖它，
            // 若初始为 null，第一次更新就会因条件不匹配而影响 0 行
            assertThat(t.getVersion()).isEqualTo(0);
            assertThat(t.getCreateTime()).isNotNull();
            assertThat(t.getUpdateTime()).isNotNull();
        }

        @Test
        @DisplayName("sourceTraceId 原样写入——它是工单回溯 AI 会话的唯一线索")
        void sourceTraceIdPersisted() {
            service.saveTicket("标题", "P1", "K8S", "描述", null, "trace-abc");
            assertThat(saved().getSourceTraceId()).isEqualTo("trace-abc");
        }

        @Test
        @DisplayName("入库影响 0 行时抛异常——不能返回一个库里不存在的工单号")
        void zeroAffectedRowsRejected() {
            // 若这里放行，调用方会拿着工单号告诉用户「已创建」，
            // 而库里根本没有这条记录。Saga 也会把这一步记为成功，
            // 补偿时找不到对象，脏数据判定全乱
            when(ticketRepository.save(any())).thenReturn(0);

            assertThatThrownBy(() -> callWith("P1", "K8S"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("工单入库失败");
        }

        @Test
        @DisplayName("仓储抛异常时包装为「工单入库失败」并保留根因")
        void repositoryExceptionIsWrapped() {
            when(ticketRepository.save(any()))
                    .thenThrow(new RuntimeException("duplicate key"));

            assertThatThrownBy(() -> callWith("P1", "K8S"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("工单入库失败")
                    // 根因必须保留：只说「入库失败」而丢掉 duplicate key，
                    // 排查者无从判断是主键冲突还是连接问题
                    .hasMessageContaining("duplicate key");
        }

        @Test
        @DisplayName("建单成功写一条「AI 自动创建工单」活动流，且标注来源")
        void writesAiCreationActivity() {
            callWith("P0", "K8S");

            ArgumentCaptor<TicketActivity> cap = ArgumentCaptor.forClass(TicketActivity.class);
            verify(activityRepository).insert(cap.capture());
            TicketActivity a = cap.getValue();

            // AI 建单必须与人工建单可区分，否则事后无法统计
            // 「AI 建了多少单、其中多少是误报」
            assertThat(a.getText()).contains("AI");
            assertThat(a.getDetail()).contains("容器/K8s").contains("紧急");
        }

        @Test
        @DisplayName("默认「待分配」时不写负责人分配活动流——避免无意义噪音")
        void noAssigneeActivityWhenUnassigned() {
            callWith("P1", "K8S");
            // 只有一条建单活动流，不该多出一条「负责人分配：待分配」
            verify(activityRepository, org.mockito.Mockito.times(1)).insert(any());
        }

        @Test
        @DisplayName("落库发生在活动流之前——号都没落库就留痕会产生孤儿活动")
        void persistBeforeActivity() {
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                    ticketRepository, activityRepository);
            callWith("P1", "K8S");
            order.verify(ticketRepository).save(any());
            order.verify(activityRepository).insert(any());
        }

        @Test
        @DisplayName("入库失败时不写活动流——否则留下指向不存在工单的活动记录")
        void noActivityWhenPersistFails() {
            when(ticketRepository.save(any())).thenReturn(0);

            assertThatThrownBy(() -> callWith("P1", "K8S"))
                    .isInstanceOf(RuntimeException.class);
            verify(activityRepository, never()).insert(any());
        }
    }
}
