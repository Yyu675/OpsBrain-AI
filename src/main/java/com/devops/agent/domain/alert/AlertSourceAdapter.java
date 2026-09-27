package com.devops.agent.domain.alert;

import java.util.List;

/**
 * 告警源适配器（L2 接入层跨源接缝）。
 *
 * <p>
 * 现有实现只有 {@link AlertmanagerSourceAdapter} 一个——「只为一个实现抽
 * 接口」看似过度设计，但这里的抽象对象不是「现在有几个」，而是「接入面
 * 的形状不能长在核心链里」：L2 的「多信号源」（PRD §7.3）是既定方向，
 * 到第二个源接入时若告警解析还散在 {@code AlertService} 里，主链路就要被
 * 第二个源的字段污染。
 * </p>
 *
 * <p>接入新源仅需：实现本接口（{@code supports} + {@code normalize}），
 * 注册为 Spring Bean，核心链零改动。</p>
 */
public interface AlertSourceAdapter {

    /** 该适配器能否识别这份原始负载。 */
    boolean supports(Object rawPayload);

    /**
     * 把某源的原始负载归一化为平台统一告警信号。
     * <p>不支持的类型由实现决定抛异常还是返回空——契约上调用方只传
     * {@link #supports} 判定为 true 的负载。</p>
     */
    List<AlertSignal> normalize(Object rawPayload);
}