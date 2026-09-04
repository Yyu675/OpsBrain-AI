package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.TicketAiAnalysis;
import com.devops.agent.domain.biz.repository.TicketAiAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
 * {@link TicketAiAnalysisService} 单元测试。
 *
 * <h3>为什么补这个</h3>
 * {@code recordFeedback} 在 {@code baseline.json} 里被标为「确属缺口」。
 * 补测时把同一个类里的 {@code save} 与 {@code accuracyStats} 一并覆盖——
 * 它们和反馈是同一条数据链：<b>save 写入分析 → 用户反馈 → 统计出准确率</b>，
 * 任一环出错，最终呈现的都是「一个看起来合理、实际不可信的准确率」。
 *
 * <h3>这条链的特点：错了没人能立刻发现</h3>
 * 准确率是用来评估模型质量、决定要不要换模型/调提示词的依据。
 * 它不像工单状态那样有人天天核对——<b>数字只要在 0~100% 之间就显得正常</b>。
 * 所以这里重点测三类容易写错且无声的地方：
 * <ul>
 *   <li><b>置信度越界纠偏</b>：模型偶发给出 120 或 -5。不纠偏就写库，
 *       前端进度条会溢出，而更糟的是它会参与后续统计；</li>
 *   <li><b>已评价数为 0 时的除法</b>：一个新部署的系统没有任何反馈，
 *       {@code helpful / rated} 就是 0/0。不处理会得到 NaN，
 *       序列化成 JSON 后前端拿到的是 {@code null} 或崩溃；</li>
 *   <li><b>「分析不存在」与「记录成功」必须可区分</b>：反馈落到一个
 *       不存在的 analysisId 上时返回 false，否则用户点了「有用」
 *       看到成功提示，而这条反馈从未进入统计。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("TicketAiAnalysisService 分析保存 / 反馈 / 准确率")
class TicketAiAnalysisServiceTest {

    private TicketAiAnalysisRepository repository;
    private TicketAiAnalysisService service;

    @BeforeEach
    void setUp() {
        repository = mock(TicketAiAnalysisRepository.class);
        // insert 原样回传，便于断言"交给仓储的那个实体"长什么样
        when(repository.insert(any())).thenAnswer(i -> i.getArgument(0));
        service = new TicketAiAnalysisService(repository);
    }

    /** 取出真正交给仓储的实体——断言纠偏结果只能看它，不能看入参 */
    private TicketAiAnalysis persisted() {
        ArgumentCaptor<TicketAiAnalysis> cap = ArgumentCaptor.forClass(TicketAiAnalysis.class);
        verify(repository).insert(cap.capture());
        return cap.getValue();
    }

    // ==================================================================
    // save
    // ==================================================================

    @Nested
    @DisplayName("save 保存分析")
    class Save {

        @Test
        @DisplayName("正常保存：字段原样落库，工单号两端空白被裁掉")
        void savesFields() {
            service.save("  TK-1  ", "分析正文",
                    List.of("原因A"), List.of("kubectl get pod"), List.of("手册X"),
                    80, new BigDecimal("0.12"));

            TicketAiAnalysis a = persisted();
            assertThat(a.getTicketId()).isEqualTo("TK-1");
            assertThat(a.getContent()).isEqualTo("分析正文");
            assertThat(a.getConfidence()).isEqualTo(80);
            assertThat(a.getCostRmb()).isEqualByComparingTo("0.12");
        }

        @Test
        @DisplayName("置信度 >100 纠偏为 100")
        void confidenceOverHundredClamped() {
            // 模型偶发给出 120。不纠偏就写库：前端进度条溢出是小事，
            // 真正的问题是这个值会参与后续的质量评估统计
            service.save("TK-1", "正文", null, null, null, 120, null);
            assertThat(persisted().getConfidence()).isEqualTo(100);
        }

        @Test
        @DisplayName("置信度为负纠偏为 0")
        void negativeConfidenceClamped() {
            service.save("TK-1", "正文", null, null, null, -5, null);
            assertThat(persisted().getConfidence()).isZero();
        }

        @Test
        @DisplayName("置信度边界 0 与 100 原样保留——纠偏不能把合法值改掉")
        void boundaryConfidenceUntouched() {
            // 若纠偏写成 >=100 / <=0 的排他比较，合法边界会被误改，
            // 而这种偏差小到没人会发现
            service.save("TK-1", "正文", null, null, null, 100, null);
            assertThat(persisted().getConfidence()).isEqualTo(100);
        }

        @Test
        @DisplayName("置信度为 null 时保持 null，不要擅自补 0")
        void nullConfidencePreserved() {
            // null 表示「模型没给置信度」，0 表示「模型认为完全不可信」。
            // 混为一谈会让统计把大量「未知」算成「极低置信度」
            service.save("TK-1", "正文", null, null, null, null, null);
            assertThat(persisted().getConfidence()).isNull();
        }

        @Test
        @DisplayName("超长内容截断到 20000 字，且不报错")
        void oversizedContentTruncated() {
            service.save("TK-1", "x".repeat(25000), null, null, null, 50, null);
            assertThat(persisted().getContent()).hasSize(20000);
        }

        @Test
        @DisplayName("正好 20000 字原样保留——用尾字符分辨，只比长度是分辨不出来的")
        void contentAtLimitNotTruncated() {
            // 只断言 hasSize(20000) 无效：若边界写成 >= 20000 而误截，
            // 结果长度仍是 20000，两种实现给出相同答案。
            // 这里让首尾字符不同，截断会丢掉末尾那个 'E'
            String content = "S" + "x".repeat(19998) + "E";
            service.save("TK-1", content, null, null, null, 50, null);

            assertThat(persisted().getContent())
                    .hasSize(20000)
                    .endsWith("E");
        }

        @Test
        @DisplayName("成本为 null 时落库为 0，而不是 null")
        void nullCostBecomesZero() {
            // 成本字段会被累加做用量统计，null 参与求和会 NPE 或被跳过，
            // 表现为「总成本偏低」——一个不会报错但一直错的数
            service.save("TK-1", "正文", null, null, null, 50, null);
            assertThat(persisted().getCostRmb()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("空工单号 / 空内容被拒绝，且不落库")
        void blankInputsRejected() {
            assertThatThrownBy(() -> service.save("  ", "正文", null, null, null, 50, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.save("TK-1", "  ", null, null, null, 50, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.save(null, "正文", null, null, null, 50, null))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).insert(any());
        }
    }

    // ==================================================================
    // recordFeedback
    // ==================================================================

    @Nested
    @DisplayName("recordFeedback 记录反馈")
    class RecordFeedback {

        @Test
        @DisplayName("helpful=true 写入 HELPFUL，false 写入 UNHELPFUL")
        void mapsBooleanToConstant() {
            // 断言落到实际写入的枚举串上。只验证「返回 true」是分辨不出
            // 两个分支写反的——那种情况下有用/没用会完全对调，
            // 而准确率看起来仍是个正常的百分比
            when(repository.updateFeedback(anyLong(), anyString())).thenReturn(1);

            service.recordFeedback(1L, true);
            verify(repository).updateFeedback(1L, TicketAiAnalysis.FEEDBACK_HELPFUL);

            service.recordFeedback(2L, false);
            verify(repository).updateFeedback(2L, TicketAiAnalysis.FEEDBACK_UNHELPFUL);
        }

        @Test
        @DisplayName("更新成功返回 true")
        void successReturnsTrue() {
            when(repository.updateFeedback(anyLong(), anyString())).thenReturn(1);
            assertThat(service.recordFeedback(1L, true)).isTrue();
        }

        @Test
        @DisplayName("影响 0 行返回 false——分析不存在必须与成功可区分")
        void zeroRowsReturnsFalse() {
            // 若这里返回 true，用户点了「有用」看到成功提示，
            // 而这条反馈从未进入统计。反馈量本就稀少，
            // 静默丢失会让准确率长期偏离真实值
            when(repository.updateFeedback(anyLong(), anyString())).thenReturn(0);
            assertThat(service.recordFeedback(999L, true)).isFalse();
        }
    }

    // ==================================================================
    // accuracyStats
    // ==================================================================

    @Nested
    @DisplayName("accuracyStats 准确率统计")
    class AccuracyStats {

        @Test
        @DisplayName("helpfulRate = helpful / rated")
        void computesRate() {
            when(repository.feedbackStats()).thenReturn(
                    Map.of("total", 10L, "rated", 4L, "helpful", 3L, "unhelpful", 1L));

            assertThat(service.accuracyStats()).containsEntry("helpfulRate", 0.75);
        }

        @Test
        @DisplayName("已评价数为 0 时 helpfulRate 为 0，而不是 NaN")
        void zeroRatedYieldsZeroNotNaN() {
            // ── 本组最重要的一条，也是踩过坑的一条 ────────────────
            // 新部署的系统没有任何反馈，helpful/rated 就是 0/0，
            // Java 里 double 的 0.0/0.0 是 NaN。
            //
            // 但光断言「结果等于 0.0」抓不到缺陷：末尾那步
            // Math.round(rate * 1000) / 1000.0 会把 NaN 悄悄变成 0.0
            // （Math.round(NaN) 返回 0，已实测确认）。也就是说去掉
            // rated > 0 这道保护后，返回值依然是 0.0，测试照样通过——
            // 注入验证时 CI 就是全绿的。
            //
            // 真正能分辨的是「有 helpful 但 rated 为 0」这种不一致数据：
            // 保护在时结果是 0.0；保护不在时 3/0 = Infinity，
            // Math.round(Infinity * 1000) 得到 Long.MAX_VALUE，
            // 除以 1000.0 是一个巨大的数，与 0.0 截然不同。
            //
            // rated=0 而 helpful>0 看似矛盾，却真实存在：feedbackStats
            // 是多个聚合子查询拼出来的，统计口径不一致或并发写入时就会出现。
            when(repository.feedbackStats()).thenReturn(
                    Map.of("total", 5L, "rated", 0L, "helpful", 3L, "unhelpful", 0L));

            Object rate = service.accuracyStats().get("helpfulRate");
            assertThat(rate)
                    .as("rated=0 时必须短路为 0，而不是让除法产生 Infinity/NaN")
                    .isEqualTo(0.0);

            // 再补一条纯 0/0 的常规场景，确保正常路径也是 0.0
            when(repository.feedbackStats()).thenReturn(
                    Map.of("total", 5L, "rated", 0L, "helpful", 0L, "unhelpful", 0L));
            Object plainRate = service.accuracyStats().get("helpfulRate");
            assertThat(plainRate).isEqualTo(0.0);
            assertThat(Double.isNaN((Double) plainRate)).isFalse();
        }

        @Test
        @DisplayName("仓储缺键时按 0 兜底，不抛 NPE")
        void missingKeysDefaultToZero() {
            // feedbackStats 是 SQL 聚合的结果，某些分支下可能不返回全部键
            when(repository.feedbackStats()).thenReturn(Map.of());

            Map<String, Object> s = service.accuracyStats();
            assertThat(s).containsEntry("total", 0L)
                    .containsEntry("rated", 0L)
                    .containsEntry("helpful", 0L)
                    .containsEntry("unhelpful", 0L)
                    .containsEntry("helpfulRate", 0.0);
        }

        @Test
        @DisplayName("比率保留 3 位小数——1/3 得 0.333 而非完整精度")
        void rateRoundedToThreeDecimals() {
            // 取整到 3 位不会掩盖缺陷：这里要防的是「比率算错」，
            // 量级远大于千分位。若哪天精度要求变严（如按万分位对账），
            // 这条断言需要一并收紧
            when(repository.feedbackStats()).thenReturn(
                    Map.of("total", 3L, "rated", 3L, "helpful", 1L, "unhelpful", 2L));

            assertThat(service.accuracyStats()).containsEntry("helpfulRate", 0.333);
        }

        @Test
        @DisplayName("全部有用时比率为 1.0，不是 100")
        void allHelpfulIsOne() {
            // 比率与百分比的口径混淆会让前端显示 10000%。
            // 契约是「0~1 的比率，由前端乘 100」
            when(repository.feedbackStats()).thenReturn(
                    Map.of("total", 2L, "rated", 2L, "helpful", 2L, "unhelpful", 0L));

            assertThat(service.accuracyStats()).containsEntry("helpfulRate", 1.0);
        }
    }
}
