package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketAction;
import com.devops.agent.domain.biz.entity.TicketActionItem;
import com.devops.agent.domain.biz.entity.TicketPostmortem;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketPostmortemService} 单元测试。
 *
 * <h3>为什么补这个类</h3>
 * Service 层盘点后它是剩余无测试项里<b>最值得补</b>的一个：183 行、
 * 6 个公开方法，且是少数几个用<b>构造器注入</b>的服务
 * （`KnowledgeIngestionService` / `HybridRetrieverService` 都是字段注入 +
 * 读 classpath 文件，脱离 Spring 容器测不出有意义的东西，
 * 后者本就有 6 例集成测试兜底）。
 *
 * <h3>复盘为什么值得测</h3>
 * 复盘是故障闭环的最后一环，也是唯一会被<b>事后翻出来看</b>的产物。
 * 它出错的方式很安静：
 * <ul>
 *   <li>改进项状态放行了非法值 → 看板按状态分组时多出一列谁也不认识的值；</li>
 *   <li>保存时覆盖了作者 → 「谁写的复盘」这条问责线索被抹掉；</li>
 *   <li>时间线草稿漏掉失败尝试 → 复盘只剩「怎么修好的」，
 *       而「试过什么没用」恰恰是最有价值的部分。</li>
 * </ul>
 *
 * <h3>写测试时查出的一处无效代码（已修）</h3>
 * {@code getPostmortem} 里查出 actionItems 后既不返回也不使用
 * （实体根本没有该字段），外加一句 {@code pm.setTimeline(pm.getTimeline())} 自赋值。
 * 每次打开复盘详情都白跑一次改进项查询，而前端本就走独立端点取它们。
 * 不报错、功能也不缺，只是安静地多打一次库——已在同批提交移除。
 */
@DisplayName("TicketPostmortemService 复盘服务")
class TicketPostmortemServiceTest {

    private TicketPostmortemRepository pmRepository;
    private TicketService ticketService;
    private TicketPostmortemService service;

    @BeforeEach
    void setUp() {
        pmRepository = mock(TicketPostmortemRepository.class);
        ticketService = mock(TicketService.class);
        service = new TicketPostmortemService(pmRepository, ticketService);
    }

    private TicketPostmortem pm(String ticketId, String author) {
        TicketPostmortem p = new TicketPostmortem();
        p.setId(7L);
        p.setTicketId(ticketId);
        p.setAuthor(author);
        p.setTimeline("原始时间线");
        return p;
    }

    private TicketActionItem item(String content, Long postmortemId) {
        TicketActionItem it = new TicketActionItem();
        it.setContent(content);
        it.setPostmortemId(postmortemId);
        return it;
    }

    // ==================== 查询 ====================

    @Nested
    @DisplayName("getPostmortem 查询")
    class Get {

        @Test
        @DisplayName("不存在时返回 null，而不是空对象")
        void missingReturnsNull() {
            when(pmRepository.findByTicketId("TK-1")).thenReturn(null);

            // 返回空对象会让前端以为「已有复盘但内容为空」，
            // 从而显示编辑态而非「创建复盘」入口
            assertThat(service.getPostmortem("TK-1")).isNull();
        }

        @Test
        @DisplayName("不再白查一次改进项——实体没有该字段，结果只会被丢弃")
        void doesNotQueryActionItems() {
            when(pmRepository.findByTicketId("TK-1")).thenReturn(pm("TK-1", "张三"));

            service.getPostmortem("TK-1");

            // 这是本轮查出并修掉的无效代码。前端走独立端点
            // /postmortem/action-items 取改进项，这里查了也没地方放
            verify(pmRepository, never()).findActionItemsByPostmortemId(anyLong());
        }
    }

    // ==================== 保存 ====================

    @Nested
    @DisplayName("savePostmortem 保存")
    class Save {

        @Test
        @DisplayName("首次保存走 insert，并把生成的 id 回填")
        void insertsWhenAbsent() {
            when(pmRepository.findByTicketId("TK-1")).thenReturn(null);
            when(pmRepository.insert(any())).thenReturn(99L);

            TicketPostmortem input = pm("TK-1", null);
            input.setId(null);
            TicketPostmortem saved = service.savePostmortem(input, "张三");

            verify(pmRepository).insert(any());
            verify(pmRepository, never()).update(any());
            // 不回填 id 的话，前端拿不到主键，紧接着添加改进项就没有
            // postmortemId 可挂——表现为「保存成功但加不了改进项」
            assertThat(saved.getId()).isEqualTo(99L);
            assertThat(saved.getAuthor()).isEqualTo("张三");
        }

        @Test
        @DisplayName("已存在时走 update，并保留原记录的主键")
        void updatesWhenPresent() {
            when(pmRepository.findByTicketId("TK-1")).thenReturn(pm("TK-1", "李四"));

            TicketPostmortem input = pm("TK-1", null);
            input.setId(null);
            TicketPostmortem saved = service.savePostmortem(input, "张三");

            verify(pmRepository).update(any());
            verify(pmRepository, never()).insert(any());
            // 用传入对象的 id（null）覆盖会让后续更新找不到行
            assertThat(saved.getId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("操作人为空时沿用原作者——不能把问责线索抹成 null")
        void nullOperatorKeepsOriginalAuthor() {
            when(pmRepository.findByTicketId("TK-1")).thenReturn(pm("TK-1", "李四"));

            TicketPostmortem saved = service.savePostmortem(pm("TK-1", null), null);

            // 复盘是事后要翻出来看的问责依据。作者被冲成 null，
            // 「这份复盘是谁写的」就再也查不到了
            assertThat(saved.getAuthor()).isEqualTo("李四");
        }

        @Test
        @DisplayName("活动流留痕区分「创建」与「更新」")
        void recordsActivityWithCorrectVerb() {
            when(pmRepository.findByTicketId("TK-1")).thenReturn(null);
            when(pmRepository.insert(any())).thenReturn(1L);

            service.savePostmortem(pm("TK-1", null), "张三");

            ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
            verify(ticketService).recordActivity(eq("TK-1"), anyString(), eq("复盘归档"),
                    detail.capture(), eq("张三"), eq(false));
            assertThat(detail.getValue()).contains("创建");
        }
    }

    // ==================== 时间线草稿 ====================

    @Nested
    @DisplayName("generateTimelineDraft 时间线草稿")
    class TimelineDraft {

        private TicketAction action(String type, String summary, Boolean effective) {
            TicketAction a = new TicketAction();
            a.setActionType(type);
            a.setSummary(summary);
            a.setOperator("张三");
            a.setEffective(effective);
            return a;
        }

        @Test
        @DisplayName("无处置动作时给出占位提示，而不是返回空串")
        void emptyActionsGivesPlaceholder() {
            when(ticketService.listActions("TK-1")).thenReturn(List.of());
            when(ticketService.getTicketWithTags("TK-1")).thenReturn(null);

            String draft = service.generateTimelineDraft("TK-1");

            // 返回空串会让编辑框一片空白，用户不知道是没数据还是接口挂了
            assertThat(draft).contains("暂无处置动作记录");
        }

        @Test
        @DisplayName("失败的尝试同样写进草稿——「试过什么没用」是复盘最有价值的部分")
        void includesIneffectiveAttempts() {
            when(ticketService.listActions("TK-1")).thenReturn(List.of(
                    action("RESTART", "重启 Pod", false),
                    action("ROLLBACK", "回滚版本", true)));
            when(ticketService.getTicketWithTags("TK-1")).thenReturn(null);

            String draft = service.generateTimelineDraft("TK-1");

            // 只留有效动作的话，复盘就只剩「怎么修好的」，
            // 下次遇到同样故障的人还会把无效路径再走一遍
            assertThat(draft).contains("重启 Pod").contains("（无效）");
            assertThat(draft).contains("回滚版本").contains("（有效）");
        }

        @Test
        @DisplayName("effective 为 null 时不标注有效性——未判定不等于无效")
        void nullEffectiveHasNoLabel() {
            when(ticketService.listActions("TK-1")).thenReturn(List.of(
                    action("CHECK", "查看日志", null)));
            when(ticketService.getTicketWithTags("TK-1")).thenReturn(null);

            String draft = service.generateTimelineDraft("TK-1");

            // 把「尚未判定」显示成「无效」会误导复盘结论
            assertThat(draft).contains("查看日志");
            assertThat(draft).doesNotContain("查看日志（无效）");
        }

        @Test
        @DisplayName("带上根因与验证信息")
        void includesRootCauseAndVerification() {
            when(ticketService.listActions("TK-1")).thenReturn(List.of());
            DevOpsTicket t = new DevOpsTicket();
            t.setRootCause("连接池耗尽");
            t.setRootCauseCategory("CAPACITY");
            t.setVerifiedAt(java.time.LocalDateTime.now());
            t.setVerifyMethod("压测复现");
            t.setVerifyConclusion("已恢复");
            when(ticketService.getTicketWithTags("TK-1")).thenReturn(t);

            String draft = service.generateTimelineDraft("TK-1");

            assertThat(draft).contains("连接池耗尽").contains("压测复现").contains("已恢复");
        }

        @Test
        @DisplayName("跳过验证时明确标出理由——不能让它看起来像正常验证过")
        void marksSkippedVerification() {
            when(ticketService.listActions("TK-1")).thenReturn(List.of());
            DevOpsTicket t = new DevOpsTicket();
            t.setVerifiedAt(java.time.LocalDateTime.now());
            t.setVerifyMethod("无");
            t.setVerifySkipped(true);
            t.setVerifySkipReason("非生产环境");
            when(ticketService.getTicketWithTags("TK-1")).thenReturn(t);

            String draft = service.generateTimelineDraft("TK-1");

            // 跳过验证与验证通过是两回事。混为一谈会让复盘评审
            // 误以为这个修复已被证实有效
            assertThat(draft).contains("已跳过验证").contains("非生产环境");
        }

        @Test
        @DisplayName("工单查不到时不抛异常，草稿仍可用")
        void missingTicketDoesNotThrow() {
            when(ticketService.listActions("TK-1")).thenReturn(List.of());
            when(ticketService.getTicketWithTags("TK-1")).thenReturn(null);

            assertThat(service.generateTimelineDraft("TK-1")).isNotBlank();
        }
    }

    // ==================== 改进项 ====================

    @Nested
    @DisplayName("addActionItem 新建改进项")
    class AddItem {

        @Test
        @DisplayName("内容为空或空白时拒绝")
        void rejectsBlankContent() {
            assertThatThrownBy(() -> service.addActionItem(item(null, 1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能为空");

            assertThatThrownBy(() -> service.addActionItem(item("   ", 1L)))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(pmRepository, never()).insertActionItem(any());
        }

        @Test
        @DisplayName("超过 500 字时拒绝——与库字段上限对齐，避免落库时被截断")
        void rejectsOverlongContent() {
            String tooLong = "x".repeat(501);

            // 前端比后端松会让用户白填一遍再收到看不懂的数据库错误；
            // 不校验则内容被静默截断，改进项读起来缺一截
            assertThatThrownBy(() -> service.addActionItem(item(tooLong, 1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("500");
        }

        @Test
        @DisplayName("恰好 500 字放行——边界含等号")
        void allowsExactly500() {
            when(pmRepository.insertActionItem(any())).thenReturn(1L);

            service.addActionItem(item("x".repeat(500), 1L));

            verify(pmRepository).insertActionItem(any());
        }

        @Test
        @DisplayName("未关联复盘 ID 时拒绝——孤儿改进项永远不会出现在任何复盘里")
        void rejectsMissingPostmortemId() {
            assertThatThrownBy(() -> service.addActionItem(item("加监控告警", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("复盘 ID");
        }

        @Test
        @DisplayName("新建的改进项一律 OPEN，并回填自增 id")
        void forcesOpenStatusAndBackfillsId() {
            when(pmRepository.insertActionItem(any())).thenReturn(42L);
            TicketActionItem input = item("加监控告警", 1L);
            input.setStatus("DONE");   // 客户端乱传

            TicketActionItem saved = service.addActionItem(input);

            // 允许客户端指定 DONE 就能凭空造出一条「已完成」的改进项，
            // 复盘完成率立刻失真
            assertThat(saved.getStatus()).isEqualTo("OPEN");
            assertThat(saved.getId()).isEqualTo(42L);
        }
    }

    @Nested
    @DisplayName("updateActionItemStatus 更新状态")
    class UpdateItemStatus {

        @Test
        @DisplayName("四个合法状态均放行，且大小写与空格被规整")
        void acceptsValidStatuses() {
            when(pmRepository.updateActionItemStatus(anyLong(), anyString())).thenReturn(1);

            for (String s : List.of("open", " DOING ", "Done", "dropped")) {
                service.updateActionItemStatus(1L, s);
            }

            // 不规整的话，「done」与「DONE」会在看板上分成两列
            verify(pmRepository).updateActionItemStatus(1L, "OPEN");
            verify(pmRepository).updateActionItemStatus(1L, "DOING");
            verify(pmRepository).updateActionItemStatus(1L, "DONE");
            verify(pmRepository).updateActionItemStatus(1L, "DROPPED");
        }

        @Test
        @DisplayName("非法状态被拒，且提示里列出合法值")
        void rejectsInvalidStatus() {
            assertThatThrownBy(() -> service.updateActionItemStatus(1L, "FINISHED"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FINISHED");

            verify(pmRepository, never()).updateActionItemStatus(anyLong(), anyString());
        }

        @Test
        @DisplayName("null 状态被拒，不会当成空串写库")
        void rejectsNullStatus() {
            assertThatThrownBy(() -> service.updateActionItemStatus(1L, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("影响 0 行时抛出——不能谎报更新成功")
        void rejectsWhenNoRowUpdated() {
            when(pmRepository.updateActionItemStatus(anyLong(), anyString())).thenReturn(0);

            // 记录已被并发删除。返回成功会让前端把它标成已完成，
            // 刷新后又变回来，用户以为是页面出错
            assertThatThrownBy(() -> service.updateActionItemStatus(9L, "DONE"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不存在");
        }
    }

    @Nested
    @DisplayName("findActionItems 查询改进项")
    class FindItems {

        @Test
        @DisplayName("筛选参数原样透传给仓储")
        void passesFiltersThrough() {
            when(pmRepository.findActionItems(any(), any(), anyBoolean())).thenReturn(List.of());

            service.findActionItems("OPEN", "张三", true);

            verify(pmRepository).findActionItems("OPEN", "张三", true);
        }
    }
}
