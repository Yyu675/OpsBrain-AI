package com.devops.agent.domain.rag;

/**
 * 切片参数配置（父子切片三件套）。
 *
 * <p>为什么存在：切片参数原来硬编码在 {@code ParentChildDocumentSplitter}，
 * 全局一套。SOP 长文手册与故障 FAQ 的最优切片粒度不同，全局参数必然
 * 对其中一类欠优——参数随知识库配置后，每个库可以有自己的粒度。</p>
 *
 * <p>参数语义（按 3 字符 ≈ 1 token 估算）：</p>
 * <ul>
 *   <li>{@code parentChunkSize} 父段落目标大小（字符数），默认 2400（~800 token）</li>
 *   <li>{@code childChunkSize} 子段落目标大小（字符数），默认 600（~200 token）</li>
 *   <li>{@code overlap} 相邻切片重叠（字符数），默认 100</li>
 * </ul>
 *
 * <p>不变量：{@code parent > child > overlap >= 0}。违反会让切片器行为
 * 不可预测（窗口无法前进、父子层级倒挂），构造期直接拒绝。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
public record ChunkProfile(int parentChunkSize, int childChunkSize, int overlap) {

    /** 全局默认：父段落目标大小（字符） */
    public static final int DEFAULT_PARENT_CHUNK_SIZE = 2400;
    /** 全局默认：子段落目标大小（字符） */
    public static final int DEFAULT_CHILD_CHUNK_SIZE = 600;
    /** 全局默认：段落重叠（字符） */
    public static final int DEFAULT_OVERLAP_SIZE = 100;

    /** 全局默认配置——未挂知识库 / 库未配参数时的回落值 */
    public static final ChunkProfile DEFAULT = new ChunkProfile(
            DEFAULT_PARENT_CHUNK_SIZE, DEFAULT_CHILD_CHUNK_SIZE, DEFAULT_OVERLAP_SIZE);

    public ChunkProfile {
        if (parentChunkSize <= 0 || childChunkSize <= 0) {
            throw new IllegalArgumentException("切片大小必须为正数");
        }
        if (childChunkSize >= parentChunkSize) {
            throw new IllegalArgumentException(
                    "子段落大小（" + childChunkSize + "）必须小于父段落大小（" + parentChunkSize + "）");
        }
        if (overlap < 0 || overlap >= childChunkSize) {
            throw new IllegalArgumentException(
                    "重叠大小（" + overlap + "）必须在 [0, 子段落大小) 区间内");
        }
    }

    /**
     * 逐字段回落合并：任一字段为 null 时用全局默认代入。
     *
     * <p>知识库允许只覆盖一个参数（例如只把 FAQ 库的 child 调小），
     * 其余字段继续跟随全局默认——默认值改动时未覆盖的字段自动跟随。</p>
     */
    public static ChunkProfile ofNullable(Integer parentChunkSize, Integer childChunkSize, Integer overlap) {
        if (parentChunkSize == null && childChunkSize == null && overlap == null) {
            return DEFAULT;
        }
        return new ChunkProfile(
                parentChunkSize != null ? parentChunkSize : DEFAULT_PARENT_CHUNK_SIZE,
                childChunkSize != null ? childChunkSize : DEFAULT_CHILD_CHUNK_SIZE,
                overlap != null ? overlap : DEFAULT_OVERLAP_SIZE);
    }
}
