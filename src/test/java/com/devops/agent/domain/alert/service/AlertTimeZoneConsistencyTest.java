package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.notify.Notifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.TimeZone;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警时间列的时区口径一致性契约。
 *
 * <h3>要防住的缺陷</h3>
 * <p>
 * {@code AlertService.toLocalDateTime} 曾把 Alertmanager 下发的 RFC3339 时间
 * 转成 <b>UTC 墙钟</b>再存进 {@code first_occurred_at}。而同一张 {@code sys_alert}
 * 表里，{@code last_occurred_at} / {@code create_time} 是
 * {@code LocalDateTime.now()} 与数据库 {@code CURRENT_TIMESTAMP} 写的<b>本地时间</b>，
 * 容器 TZ 固定 {@code Asia/Shanghai}。
 * </p>
 * <p>
 * 列类型都是无时区的 {@code TIMESTAMP}，<b>没有任何字段记录这个 8 小时的差异</b>，
 * 下游只能一律按本地时间解释。用户看到的是：刚触发的告警在详情页显示
 * 「已持续 8 小时」，处置时间线上「首次发生」排在「已恢复」之后。
 * </p>
 *
 * <h3>为什么必须自己设默认时区（关键）</h3>
 * <p>
 * GitHub Actions runner 的系统时区是 <b>UTC</b>。在 UTC 环境下
 * {@code atZoneSameInstant(ZoneOffset.UTC)} 与
 * {@code atZoneSameInstant(ZoneId.systemDefault())} 给出<b>完全相同的答案</b>——
 * 照常写测试，正确实现与错误实现都会通过，这是典型的第 5 类假测试
 * （用例构造的场景与要防的缺陷不是同一件事）。
 * </p>
 * <p>
 * 故本类在每个用例里显式把 JVM 默认时区切到一个<b>非 UTC</b> 的时区，
 * 让两种实现必然分叉，跑完再还原（{@link #restoreTimeZone}）——
 * 不还原会污染同一 JVM 里后续所有测试的时间断言。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("告警时间列时区口径一致性")
class AlertTimeZoneConsistencyTest {

    /** 生产部署时区（Dockerfile / docker-compose 均固定 Asia/Shanghai，UTC+8 且无夏令时） */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private TimeZone originalTimeZone;

    private AlertRepository alertRepository;
    private TicketService ticketService;
    private AlertWebSocketNotifier notifier;
    private Notifier dingTalk;
    private AlertService service;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();

        alertRepository = mock(AlertRepository.class);
        ticketService = mock(TicketService.class);
        notifier = mock(AlertWebSocketNotifier.class);
        dingTalk = mock(Notifier.class);
        service = new AlertService(alertRepository, ticketService, notifier, dingTalk);

        // @Value 字段在非 Spring 环境不会注入，不设则全是 false/0，
        // alertEnabled=false 会让 handle() 直接返回，用例以无关原因"通过"
        ReflectionTestUtils.setField(service, "alertEnabled", true);
        ReflectionTestUtils.setField(service, "autoTicketEnabled", false);
        ReflectionTestUtils.setField(service, "alertCreator", "alert-bot");
        ReflectionTestUtils.setField(service, "aggregateEnabled", false);
        ReflectionTestUtils.setField(service, "aggregateWindowMinutes", 5);

        when(alertRepository.findActiveByDedupKey(anyString())).thenReturn(Optional.empty());
        when(alertRepository.findActiveGroupTicket(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> {
            Alert a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    DevOpsTicket t = new DevOpsTicket();
                    t.setId("TK-2026-0001");
                    return t;
                });
    }

    @AfterEach
    void restoreTimeZone() {
        // 必须还原：JVM 默认时区是进程级全局状态，
        // 不还原会让同一 JVM 里后续所有测试的时间断言在莫名其妙的时区下运行
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    @DisplayName("非 UTC 时区下，firstOccurredAt 与同行 createTime 必须是同一时区口径")
    void firstOccurredAtSharesWallClockWithCreateTime() {
        TimeZone.setDefault(TimeZone.getTimeZone(SHANGHAI));

        service.processWebhook(webhook(incoming("firing",
                labels("alertname", "PodCrashLoopBackOff", "severity", "critical",
                        "service", "order-svc"))));

        Alert saved = savedAlert();
        assertThat(saved.getFirstOccurredAt()).isNotNull();
        assertThat(saved.getCreateTime()).isNotNull();

        // 断言落在「两列的差值」上，而非各自的绝对值——
        // 绝对值断言需要冻结时钟，而差值本身就是这条缺陷的本质：
        // 错误实现下 firstOccurredAt 比 createTime 早 8 小时（Asia/Shanghai）
        long skewSeconds = Math.abs(Duration.between(
                saved.getFirstOccurredAt(), saved.getCreateTime()).getSeconds());

        // 用秒而非分钟做单位：Duration.toMinutes() 会把 8 小时以下的偏差
        // 也表达得很粗，且取整截断是第 1 类假测试的经典来源。
        // 阈值 300 秒足够宽松地容纳执行耗时，又远小于任何时区偏移
        // （现存最小的时区差是 15 分钟 = 900 秒）
        assertThat(skewSeconds)
                .as("firstOccurredAt 与 createTime 必须同为本地时间；"
                        + "转 UTC 存会让两列在 Asia/Shanghai 下相差 28800 秒，"
                        + "而列类型是无时区 TIMESTAMP，下游无从分辨")
                .isLessThan(300L);
    }

    @Test
    @DisplayName("非 UTC 时区下，firstOccurredAt 不得早于本地当下 8 小时（UTC 转换的特征）")
    void firstOccurredAtIsNotShiftedIntoThePast() {
        TimeZone.setDefault(TimeZone.getTimeZone(SHANGHAI));

        LocalDateTime before = LocalDateTime.now();
        service.processWebhook(webhook(incoming("firing",
                labels("alertname", "DiskPressure", "severity", "warning",
                        "service", "node-01"))));
        LocalDateTime after = LocalDateTime.now();

        Alert saved = savedAlert();

        // 正确实现：firstOccurredAt 落在 [before, after] 这个极窄的窗口里。
        // 错误实现（转 UTC）：落在 8 小时之前，远在 before 之前。
        // 这条断言在 CI 的 UTC runner 上也能区分两种实现——
        // 因为用例自己把默认时区切成了 Asia/Shanghai
        assertThat(saved.getFirstOccurredAt())
                .as("转 UTC 存会让 firstOccurredAt 落在 8 小时前，"
                        + "详情页的「持续时长」据此算出「刚触发的告警已持续 8 小时」")
                .isBetween(before.minusSeconds(300), after.plusSeconds(300));
    }

    @Test
    @DisplayName("startsAt 为 null 时兜底为本地当下，同样不得是 UTC 墙钟")
    void nullStartsAtFallsBackToLocalNow() {
        TimeZone.setDefault(TimeZone.getTimeZone(SHANGHAI));

        LocalDateTime before = LocalDateTime.now();
        AlertmanagerWebhook.Alert a = incoming("firing",
                labels("alertname", "NoStartsAt", "severity", "info", "service", "svc"));
        a.setStartsAt(null);
        service.processWebhook(webhook(a));
        LocalDateTime after = LocalDateTime.now();

        Alert saved = savedAlert();
        assertThat(saved.getFirstOccurredAt())
                .as("null 分支的兜底也必须与其它列同口径")
                .isBetween(before.minusSeconds(300), after.plusSeconds(300));
    }

    @Test
    @DisplayName("换一个负偏移时区（America/New_York）结论同样成立——不是给 +08:00 写死的")
    void holdsInNegativeOffsetZoneToo() {
        // 若有人把修复写成硬编码 ZoneOffset.ofHours(8)，在 Asia/Shanghai 下
        // 与 systemDefault 表现一致，上面几条用例抓不到。
        // 换成负偏移时区就能分叉：硬编码 +08:00 会让时间早 13 小时
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/New_York")));

        LocalDateTime before = LocalDateTime.now();
        service.processWebhook(webhook(incoming("firing",
                labels("alertname", "HighLatency", "severity", "critical",
                        "service", "gateway"))));
        LocalDateTime after = LocalDateTime.now();

        Alert saved = savedAlert();
        assertThat(saved.getFirstOccurredAt())
                .as("必须跟随系统默认时区，而非硬编码某个偏移——"
                        + "硬编码会在部署到其它时区时重新制造同一个偏差，且更隐蔽")
                .isBetween(before.minusSeconds(300), after.plusSeconds(300));
    }

    // ==================== 夹具 ====================

    private Alert savedAlert() {
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(captor.capture());
        return captor.getValue();
    }

    private static AlertmanagerWebhook.Alert incoming(String status, Map<String, String> labels) {
        AlertmanagerWebhook.Alert a = new AlertmanagerWebhook.Alert();
        a.setStatus(status);
        a.setLabels(labels);
        // 用 now() 而非固定时刻：真实告警的 startsAt 与入库时刻只差秒级，
        // 固定历史时刻会让「差值应接近 0」这类断言失去意义
        a.setStartsAt(OffsetDateTime.now());
        a.setFingerprint("fp-" + labels.hashCode());
        return a;
    }

    private static Map<String, String> labels(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static AlertmanagerWebhook webhook(AlertmanagerWebhook.Alert... alerts) {
        AlertmanagerWebhook w = new AlertmanagerWebhook();
        w.setAlerts(List.of(alerts));
        return w;
    }
}
