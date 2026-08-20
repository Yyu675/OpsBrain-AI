package com.devops.agent.domain.notify;

/**
 * 通知消息模型（钉钉 markdown 卡片）
 *
 * <p>渠道无关的消息载体：title + markdown 正文 + 可选紧急标记。
 * 当前只有钉钉一个实现，但模型不绑定钉钉——L2 后续扩展企微/飞书/短信时复用。</p>
 *
 * <p>紧急标记 {@code urgent} 用于高危告警：钉钉端会 @所有人 强提醒（蓝图 §二
 * P0/P1「一键弹窗强提醒值班 SRE」）。</p>
 *
 * @param title    卡片标题（钉钉通知列表显示）
 * @param markdown 卡片正文（钉钉 markdown 语法）
 * @param urgent   是否紧急（true → @所有人）
 * @author OpsBrain AI
 * @since 2026-08-20
 */
public record NotifyMessage(String title, String markdown, boolean urgent) {

    /** 普通通知（不 @人） */
    public static NotifyMessage normal(String title, String markdown) {
        return new NotifyMessage(title, markdown, false);
    }

    /** 紧急通知（@所有人强提醒） */
    public static NotifyMessage urgent(String title, String markdown) {
        return new NotifyMessage(title, markdown, true);
    }
}
