package com.devops.agent.domain.alert;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 告警源适配器注册表（L2 跨源接缝的入口）。
 *
 * <h3>为什么需要它：第二个源一来，「注一个适配器」就编译不过了</h3>
 * <p>
 * 只有一个实现时，{@code AlertService} 直接注入 {@code AlertmanagerSourceAdapter} 是
 * 最简单也最清楚的写法。接入 Zabbix 后 Spring 会发现两个
 * {@link AlertSourceAdapter} 候选，注入点立刻变成
 * {@code NoUniqueBeanDefinitionException}——这正是「抽象只有在第二个真实源出现时
 * 才值得」的另一面：那一刻必须把「选谁」这件事显式写下来，而不是靠注入顺序。
 * </p>
 * <p>
 * 本类就是那个显式决定：按 {@link AlertSourceAdapter#supports} 选第一个认得负载的
 * 适配器。选不中时<b>抛异常而不是静默忽略</b>——静默的后果是「告警发出来平台没反应」，
 * 而报错能让接入方在第一次自测就发现参数错（控制器把它翻译成 400）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-10-14
 */
@Component
public class AlertSourceNormalizer {

    private final List<AlertSourceAdapter> adapters;

    public AlertSourceNormalizer(List<AlertSourceAdapter> adapters) {
        // 防御性拷贝：注入的列表由容器持有，注册表不该在运行期被外部改动
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    /**
     * 把任意已注册来源的原始负载归一化为平台信号。
     *
     * @throws IllegalArgumentException 负载为空、或没有适配器认得它
     */
    public List<AlertSignal> normalize(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("告警负载为空");
        }
        return adapters.stream()
                .filter(a -> a.supports(payload))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "没有适配器认识这份负载（" + payload.getClass().getSimpleName()
                                + "）；已注册来源：" + knownSources()))
                .normalize(payload);
    }

    /** 已注册来源清单（错误提示与接入文档共用一份事实，避免两处各写一份） */
    public List<String> knownSources() {
        return adapters.stream().map(AlertSourceAdapter::sourceName).collect(Collectors.toList());
    }

    /** 适配器数量（注册表自检用：Spring 漏装某个适配器时应当立刻可见） */
    public int size() {
        return adapters.size();
    }
}
