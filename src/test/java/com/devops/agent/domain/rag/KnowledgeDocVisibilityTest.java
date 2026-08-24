package com.devops.agent.domain.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link KnowledgeDoc} 可见性字段的默认值契约（C1）。
 *
 * <p>这组用例保护一个很容易复发、且后果严重的问题：
 * {@code KnowledgeDoc} 同时充当「实体」与「更新补丁」，
 * 而 {@code KnowledgeDocService.update} 的合并规则是<b>只覆盖非空字段</b>。
 * 一旦 {@code visibility} 带上字段级默认值 {@code "PUBLIC"}，
 * 任何 {@code new KnowledgeDoc()} 构造的补丁都会挟带一个看似显式的 PUBLIC，
 * 把受限文档静默降级为全员可见 —— 这是一次无声的提权。</p>
 */
class KnowledgeDocVisibilityTest {

    @Test
    @DisplayName("新建文档对象的 visibility 必须是 null，而非 PUBLIC")
    void freshDocHasNullVisibility() {
        KnowledgeDoc patch = new KnowledgeDoc();

        assertNull(patch.getVisibility(),
                "字段默认值若为 PUBLIC，版本回滚等场景构造的 patch 会把 RESTRICTED 文档降级为公开");
        assertNull(patch.getOwnerDept());
    }

    @Test
    @DisplayName("模拟 update 的「只覆盖非空」合并：空补丁不得改动既有可见性")
    void emptyPatchPreservesRestrictedVisibility() {
        KnowledgeDoc existing = new KnowledgeDoc();
        existing.setVisibility("RESTRICTED");
        existing.setOwnerDept("财务");

        // 版本回滚就是这样构造补丁的：只设内容/标题/状态，不碰权限
        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setTitle("回滚后的标题");

        applyPatch(existing, patch);

        assertEquals("RESTRICTED", existing.getVisibility(), "回滚不得把受限文档变成公开");
        assertEquals("财务", existing.getOwnerDept());
        assertEquals("回滚后的标题", existing.getTitle());
    }

    @Test
    @DisplayName("显式设置可见性时正常生效")
    void explicitVisibilityIsApplied() {
        KnowledgeDoc existing = new KnowledgeDoc();
        existing.setVisibility("PUBLIC");

        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setVisibility("RESTRICTED");
        patch.setOwnerDept("研发");

        applyPatch(existing, patch);

        assertEquals("RESTRICTED", existing.getVisibility());
        assertEquals("研发", existing.getOwnerDept());
    }

    /** 复刻 KnowledgeDocService.update 的「仅覆盖非空字段」合并语义 */
    private void applyPatch(KnowledgeDoc existing, KnowledgeDoc patch) {
        if (patch.getTitle() != null) {
            existing.setTitle(patch.getTitle());
        }
        if (patch.getVisibility() != null) {
            existing.setVisibility(patch.getVisibility());
        }
        if (patch.getOwnerDept() != null) {
            existing.setOwnerDept(patch.getOwnerDept());
        }
    }
}
