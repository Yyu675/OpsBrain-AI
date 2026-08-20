package com.devops.agent.common.exception;

/**
 * 乐观锁版本冲突异常（P1-4）
 * <p>
 * 当更新时数据库中的 version 与客户端持有的不一致，说明记录已被他人修改。
 * 此时<b>不能</b>静默覆盖——那会让先提交者的修改凭空消失。
 * </p>
 * <p>
 * 与「记录不存在」必须区分：前者应提示用户刷新后重试（数据仍在），
 * 后者应提示记录已被删除（数据已消失）。两者混为一谈会误导用户。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
public class OptimisticLockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码：版本冲突 */
    public static final int CODE = 40009;

    /** 冲突记录的业务标识 */
    private final String resourceId;

    /** 客户端持有的版本号 */
    private final Integer expectedVersion;

    /** 数据库中的当前版本号，查询失败时为 null */
    private final Integer actualVersion;

    public OptimisticLockException(String resourceId, Integer expectedVersion, Integer actualVersion) {
        super(buildMessage(resourceId, expectedVersion, actualVersion));
        this.resourceId = resourceId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /**
     * 构造用户可理解的冲突提示
     * <p>避免暴露"version"等实现细节，直接给出可执行的下一步动作。</p>
     */
    private static String buildMessage(String resourceId, Integer expected, Integer actual) {
        if (actual != null) {
            return String.format(
                    "该记录已被他人修改（你基于第 %d 版编辑，当前已是第 %d 版），"
                            + "请刷新查看最新内容后重新提交：%s",
                    expected, actual, resourceId);
        }
        return String.format("该记录已被他人修改，请刷新后重试：%s", resourceId);
    }

    public String getResourceId() {
        return resourceId;
    }

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public Integer getActualVersion() {
        return actualVersion;
    }
}