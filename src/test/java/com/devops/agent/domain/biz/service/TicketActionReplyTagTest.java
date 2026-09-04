package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketAction;
import com.devops.agent.domain.biz.entity.TicketActivity;
import com.devops.agent.domain.biz.entity.TicketReply;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketService#addAction} / {@link TicketService#addReply}
 * / {@link TicketService#replaceTags} 单元测试。
 *
 * <h3>为什么补这三个</h3>
 * 由 {@code tools/audit/scan_service_write_coverage.py} 扫出且已记入
 * {@code baseline.json}，其中 addAction / addReply 两条的理由栏明确写着
 * 「已列入下一轮」。本轮清偿。
 *
 * <p>这三个方法的共同点是<b>出错时不会报错</b>——它们都在主流程旁边写数据，
 * 写坏了主流程照样返回成功：</p>
 * <ul>
 *   <li>{@code addAction}：B2 现场处置记录。写入内容决定闭环进度条判定，
 *       而 {@code effective=false}（失败的尝试）是<b>刻意允许</b>的——
 *       PRD §2.1 说排查占 40% 且严重依赖经验，「试过重启，没用」恰恰最有价值。
 *       若哪天有人把它当成脏数据过滤掉，大部分经验就被丢弃了，
 *       而这件事不会有任何报错；</li>
 *   <li>{@code addReply}：人工回复要计首响、AI 回复<b>不计</b>。
 *       这条区分若失效，每张工单建单即「已首响」（AI 分析在建单时自动触发），
 *       首响 SLA 这个指标会全线变绿——比没有指标更糟，因为它看起来是好的；</li>
 *   <li>{@code replaceTags}：先删后插。中途失败会让标签<b>整体丢失</b>，
 *       而工单主体完好，界面上只是"标签没了"，很难联想到是替换逻辑的问题。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("TicketService 处置动作 / 回复 / 标签")
class TicketActionReplyTagTest {

    private DevOpsTicketRepository ticketRepository;
    private TicketReplyRepository replyRepository;
    private TicketActivityRepository activityRepository;
    private TicketTagRepository tagRepository;
    private TicketActionRepository actionRepository;
    private TicketService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ticketRepository = mock(DevOpsTicketRepository.class);
        replyRepository = mock(TicketReplyRepository.class);
        activityRepository = mock(TicketActivityRepository.class);
        tagRepository = mock(TicketTagRepository.class);
        actionRepository = mock(TicketActionRepository.class);
        TicketPostmortemRepository postmortemRepository = mock(TicketPostmortemRepository.class);
        Notifier notifier = mock(Notifier.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new TicketService(ticketRepository, replyRepository,
                activityRepository, tagRepository, actionRepository, postmortemRepository,
                notifier, redisTemplate);
    }

    /** 一张处于 PROCESSING 的普通工单 */
    private DevOpsTicket ticket(String id, String status) {
        DevOpsTicket t = new DevOpsTicket();
        t.setId(id);
        t.setStatus(status);
        t.setAssignee("张三");
        return t;
    }

    private TicketActivity capturedActivity() {
        ArgumentCaptor<TicketActivity> cap = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).insert(cap.capture());
        return cap.getValue();
    }

    // ==================================================================
    // addAction
    // ==================================================================

    @Nested
    @DisplayName("addAction 记录处置动作")
    class AddAction {

        @Test
        @DisplayName("正常记录：落库、回填 id、并写一条活动流")
        void recordsActionAndActivity() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(actionRepository.insert(any())).thenReturn(99L);

            TicketAction a = service.addAction("TK-1", "fix", "重启了 Pod",
                    "kubectl delete pod x", "李四", true);

            assertThat(a.getId()).isEqualTo(99L);
            assertThat(a.getTicketId()).isEqualTo("TK-1");
            assertThat(a.getStartedAt()).as("必须记录开始时刻，否则时间线无法排序").isNotNull();
            verify(activityRepository).insert(any(TicketActivity.class));
        }

        @Test
        @DisplayName("动作类型统一大写——'fix' 与 'FIX' 必须落库为同一个值")
        void actionTypeIsUpperCased() {
            // 不归一化的后果：闭环进度条按 actionType 匹配阶段，
            // 大小写不一致会让同一种动作被算作两类，进度判定出错
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThat(service.addAction("TK-1", " fix ", "s", null, "李四", true)
                    .getActionType()).isEqualTo("FIX");
        }

        @Test
        @DisplayName("动作类型为 null 时降级为 INVESTIGATE，而不是写入 null")
        void nullActionTypeFallsBackToInvestigate() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThat(service.addAction("TK-1", null, "s", null, "李四", null)
                    .getActionType()).isEqualTo(TicketService.ACTION_INVESTIGATE);
        }

        @Test
        @DisplayName("effective=false 必须被正常记录——失败的尝试是最有价值的经验")
        void ineffectiveAttemptIsRecorded() {
            // ── 本组最重要的一条 ──────────────────────────────
            // PRD §2.1：排查占 40% 且严重依赖经验。「试过重启，没用」
            // 能避免后人重走弯路。若哪天有人把 effective=false 当脏数据
            // 拒掉或跳过，大部分经验就被丢弃了，而这件事不会有任何报错
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(actionRepository.insert(any())).thenReturn(1L);

            TicketAction a = service.addAction("TK-1", "MITIGATE", "试过重启，没用",
                    null, "李四", false);

            assertThat(a.getEffective()).isFalse();
            verify(actionRepository).insert(any());
            assertThat(capturedActivity().getDetail())
                    .as("活动流要让人一眼看出这次尝试无效")
                    .contains("无效");
        }

        @Test
        @DisplayName("effective=true 的活动流标注「有效」，null 时不加标注")
        void effectiveLabelReflectsOutcome() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            service.addAction("TK-1", "FIX", "改了配置", null, "李四", true);
            assertThat(capturedActivity().getDetail()).contains("有效");
        }

        @Test
        @DisplayName("操作人为空时记为「未知」——留空会让事后追责无从查起")
        void blankOperatorBecomesUnknown() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThat(service.addAction("TK-1", "FIX", "s", null, "   ", true)
                    .getOperator()).isEqualTo("未知");
        }

        @Test
        @DisplayName("工单不存在时抛错且不落库")
        void missingTicketRejected() {
            when(ticketRepository.findById("NOPE")).thenReturn(null);

            assertThatThrownBy(() -> service.addAction("NOPE", "FIX", "s", null, "李四", true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("工单不存在");
            verify(actionRepository, never()).insert(any());
        }

        @Test
        @DisplayName("已作废工单不得再记处置动作")
        void voidTicketRejected() {
            // VOID 是唯一的不可变状态。RESOLVED 反而必须放行——
            // 「当时忙着救火、事后补录」是真实且常见的路径
            when(ticketRepository.findById("TK-V")).thenReturn(ticket("TK-V", "VOID"));

            assertThatThrownBy(() -> service.addAction("TK-V", "FIX", "s", null, "李四", true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已作废");
            verify(actionRepository, never()).insert(any());
        }

        @Test
        @DisplayName("RESOLVED 工单允许补记处置动作")
        void resolvedTicketStillAcceptsAction() {
            when(ticketRepository.findById("TK-R")).thenReturn(ticket("TK-R", "RESOLVED"));
            when(actionRepository.insert(any())).thenReturn(7L);

            assertThat(service.addAction("TK-R", "VERIFY", "补录验证过程", null, "李四", true)
                    .getId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("摘要为空或超 255 字被拒绝，且不落库")
        void summaryValidated() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThatThrownBy(() -> service.addAction("TK-1", "FIX", "  ", null, "李四", true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.addAction("TK-1", "FIX", "x".repeat(256),
                    null, "李四", true))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(actionRepository, never()).insert(any());
        }

        @Test
        @DisplayName("摘要正好 255 字放行——边界不能少一位")
        void summaryAtLimitAccepted() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThat(service.addAction("TK-1", "FIX", "x".repeat(255), null, "李四", true))
                    .isNotNull();
        }
    }

    // ==================================================================
    // addReply
    // ==================================================================

    @Nested
    @DisplayName("addReply 追加回复")
    class AddReply {

        @Test
        @DisplayName("正常回复：落库、刷新工单更新时间、写活动流")
        void savesReplyAndTouchesTicket() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(replyRepository.insert(any())).thenReturn(5L);

            TicketReply r = service.addReply("TK-1", "creator", "王五", "#fff", "  请尽快处理  ");

            assertThat(r.getId()).isEqualTo(5L);
            assertThat(r.getContent()).as("正文两端空白应被裁掉").isEqualTo("请尽快处理");
            // 不刷新更新时间的话，列表按更新时间排序时这张工单不会浮上来，
            // 用户会以为回复没提交成功
            verify(ticketRepository).touchUpdateTime("TK-1");
            verify(activityRepository).insert(any(TicketActivity.class));
        }

        @Test
        @DisplayName("人工回复计入首响")
        void humanReplyMarksFirstResponse() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            service.addReply("TK-1", "agent", "王五", null, "已接手");

            verify(ticketRepository).markFirstResponse(eq("TK-1"), eq("王五"), any());
        }

        @Test
        @DisplayName("AI 回复不计首响——否则首响 SLA 会全线失真")
        void aiReplyDoesNotMarkFirstResponse() {
            // ── 本组最重要的一条 ──────────────────────────────
            // AI 分析在建单时自动触发（6.39）。若 AI 回复也算首响，
            // 每张工单建单即「已首响」，MTTA 恒接近 0、超时告警永不触发。
            // 这比没有这个指标更糟：看板全绿，而实际没人响应
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            service.addReply("TK-1", "ai", "AI 助手", null, "初步分析如下");

            verify(ticketRepository, never()).markFirstResponse(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("AI 角色大小写不敏感——'AI' 同样不计首响")
        void aiRoleIsCaseInsensitive() {
            // 若判断写成区分大小写的 equals，传 "AI" 就会被当成人工回复，
            // 首响被误标记，而没有任何报错
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            service.addReply("TK-1", "AI", "AI 助手", null, "分析");

            verify(ticketRepository, never()).markFirstResponse(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("未知角色降级为 agent，且按人工回复计首响")
        void unknownRoleFallsBackToAgent() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            TicketReply r = service.addReply("TK-1", "robot", "王五", null, "内容");

            assertThat(r.getRole()).isEqualTo("agent");
            // 降级为 agent 意味着它是人工，必须计首响——
            // 若降级后仍跳过首响，未知角色会成为绕过 SLA 的后门
            verify(ticketRepository).markFirstResponse(eq("TK-1"), eq("王五"), any());
        }

        @Test
        @DisplayName("角色为 null 降级为 agent")
        void nullRoleFallsBackToAgent() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThat(service.addReply("TK-1", null, "王五", null, "内容").getRole())
                    .isEqualTo("agent");
        }

        @Test
        @DisplayName("回复人为空时记为「未知用户」")
        void blankAuthorBecomesUnknown() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThat(service.addReply("TK-1", "agent", "  ", null, "内容").getAuthor())
                    .isEqualTo("未知用户");
        }

        @Test
        @DisplayName("空内容 / 超 5000 字被拒绝，且不落库")
        void contentValidated() {
            assertThatThrownBy(() -> service.addReply("TK-1", "agent", "王五", null, "   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.addReply("TK-1", "agent", "王五", null,
                    "x".repeat(5001)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(replyRepository, never()).insert(any());
            // 校验必须在查库之前——否则一次明显非法的请求也要打一次数据库
            verify(ticketRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("内容正好 5000 字放行")
        void contentAtLimitAccepted() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));

            assertThat(service.addReply("TK-1", "agent", "王五", null, "x".repeat(5000)))
                    .isNotNull();
        }

        @Test
        @DisplayName("工单不存在时抛错且不落库")
        void missingTicketRejected() {
            when(ticketRepository.findById("NOPE")).thenReturn(null);

            assertThatThrownBy(() -> service.addReply("NOPE", "agent", "王五", null, "内容"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("工单不存在");
            verify(replyRepository, never()).insert(any());
        }

        @Test
        @DisplayName("已关闭工单不得回复——避免绕过流程")
        void closedTicketRejected() {
            when(ticketRepository.findById("TK-C")).thenReturn(ticket("TK-C", "CLOSED"));

            assertThatThrownBy(() -> service.addReply("TK-C", "agent", "王五", null, "内容"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已关闭");
            verify(replyRepository, never()).insert(any());
        }

        @Test
        @DisplayName("RESOLVED 工单仍可回复——已解决不等于已关闭")
        void resolvedTicketStillAcceptsReply() {
            // 只拦 CLOSED 不拦 RESOLVED 是有意的：用户对处理结果追问、
            // 补充信息都发生在 RESOLVED 之后。若一并拦掉，这些内容会流失到
            // 群聊里，工单上下文就不完整了
            when(ticketRepository.findById("TK-R")).thenReturn(ticket("TK-R", "RESOLVED"));
            when(replyRepository.insert(any())).thenReturn(8L);

            assertThat(service.addReply("TK-R", "creator", "王五", null, "还有个问题")
                    .getId()).isEqualTo(8L);
        }
    }

    // ==================================================================
    // replaceTags
    // ==================================================================

    @Nested
    @DisplayName("replaceTags 替换标签")
    class ReplaceTags {

        @Test
        @DisplayName("返回库中实际结果，而不是调用方提交的原始值")
        void returnsPersistedTags() {
            // 仓储会做归一化（去重/裁空白/大小写）。若直接回显入参，
            // 前端显示的与库里存的不一致，用户下次编辑会把错的又存回去
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(tagRepository.findByTicketId("TK-1"))
                    .thenReturn(List.of())
                    .thenReturn(List.of("k8s", "线上"));

            assertThat(service.replaceTags("TK-1", List.of("K8s", " 线上 ", "k8s")))
                    .containsExactly("k8s", "线上");
        }

        @Test
        @DisplayName("标签有变化时写一条活动流，记录前后对照")
        void writesActivityOnChange() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(tagRepository.findByTicketId("TK-1"))
                    .thenReturn(List.of("旧"))
                    .thenReturn(List.of("新"));

            service.replaceTags("TK-1", List.of("新"));

            assertThat(capturedActivity().getDetail()).contains("旧").contains("新");
        }

        @Test
        @DisplayName("标签无变化时不写活动流——避免刷屏淹没真正的变更")
        void noActivityWhenUnchanged() {
            // 编辑页保存时会无条件调用本方法，多数情况下标签根本没动。
            // 每次都留痕会让活动流被无意义条目填满，真正的处置记录被挤走
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(tagRepository.findByTicketId("TK-1")).thenReturn(List.of("k8s"));

            service.replaceTags("TK-1", List.of("k8s"));

            verify(activityRepository, never()).insert(any());
        }

        @Test
        @DisplayName("清空标签是合法操作，且会留痕")
        void clearingTagsIsAllowed() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(tagRepository.findByTicketId("TK-1"))
                    .thenReturn(List.of("k8s"))
                    .thenReturn(List.of());

            assertThat(service.replaceTags("TK-1", List.of())).isEmpty();
            assertThat(capturedActivity().getDetail()).contains("无");
        }

        @Test
        @DisplayName("提交了标签却一个都没存上时，必须留下 error 日志而非静默返回")
        void reportsSilentDataLoss() {
            // 这是数据丢失：用户以为标签保存成功（接口 200），实际库里为空。
            // 方法本身仍返回空列表（不抛错，避免影响主流程），
            // 但必须在日志里留下痕迹，否则这类丢失永远查不出来
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(tagRepository.findByTicketId("TK-1")).thenReturn(List.of());

            assertThat(service.replaceTags("TK-1", List.of("k8s", "线上"))).isEmpty();
            // 仍然调用了仓储替换——说明不是被前置校验挡掉的
            verify(tagRepository).replaceTags(eq("TK-1"), any());
        }

        @Test
        @DisplayName("工单不存在时抛错，且不触碰标签表")
        void missingTicketRejected() {
            when(ticketRepository.findById("NOPE")).thenReturn(null);

            assertThatThrownBy(() -> service.replaceTags("NOPE", List.of("k8s")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("工单不存在");
            // 先删后插：若不先校验工单存在就动手，会把一张不存在工单的
            // 标签行删掉（脏数据场景），且没有任何补救途径
            verify(tagRepository, never()).replaceTags(anyString(), any());
        }

        @Test
        @DisplayName("tags 传 null 等同清空，不抛 NPE")
        void nullTagsTreatedAsClear() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(tagRepository.findByTicketId("TK-1")).thenReturn(List.of());

            assertThat(service.replaceTags("TK-1", null)).isEmpty();
            verify(tagRepository).replaceTags(eq("TK-1"), eq(null));
        }

        @Test
        @DisplayName("替换顺序是「读旧 → 替换 → 读新」，两次读取结果不同才算变化")
        void readsBeforeAndAfter() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket("TK-1", "PROCESSING"));
            when(tagRepository.findByTicketId("TK-1"))
                    .thenReturn(List.of("a"))
                    .thenReturn(List.of("b"));

            service.replaceTags("TK-1", List.of("b"));

            // 读两次是刻意的：只读一次就无法判断"有没有变"，
            // 也就无法实现上面那条"无变化不留痕"
            verify(tagRepository, times(2)).findByTicketId("TK-1");
        }
    }
}
