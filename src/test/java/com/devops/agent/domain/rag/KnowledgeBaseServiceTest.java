package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link KnowledgeBaseService} 编排逻辑测试。
 *
 * <p>覆盖重点：</p>
 * <ul>
 *   <li>切片参数校验在保存前完成（非法组合落库 = 索引期产出垃圾切片）</li>
 *   <li>默认库保护：code 不可改、不可停用（它是新建文档的回落键）</li>
 *   <li>{@code resolveProfile} 三级回落：库配置 → 全局默认 → 库不存在也默认</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@DisplayName("KnowledgeBaseService")
class KnowledgeBaseServiceTest {

    private KnowledgeBaseRepository kbRepo;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        kbRepo = mock(KnowledgeBaseRepository.class);
        service = new KnowledgeBaseService(kbRepo);
    }

    private KnowledgeBase kb(Long id, String code, String status) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName("运维手册库");
        kb.setCode(code);
        kb.setStatus(status);
        return kb;
    }

    // ==================== 创建 ====================

    @Test
    @DisplayName("创建：缺省状态 ACTIVE，参数留空合法（跟随全局默认）")
    void createWithDefaults() {
        KnowledgeBase kb = kb(null, "sop-manual", null);
        when(kbRepo.insert(any())).thenReturn(7L);

        KnowledgeBase saved = service.create(kb);

        assertEquals(7L, saved.getId());
        assertEquals(KnowledgeBase.STATUS_ACTIVE, saved.getStatus());
        verify(kbRepo).insert(any());
    }

    @Test
    @DisplayName("创建：非法切片参数（子≥父）被拒绝，不落库")
    void createRejectsInvalidChunkParams() {
        KnowledgeBase kb = kb(null, "bad-params", null);
        kb.setParentChunkSize(100);
        kb.setChildChunkSize(600); // 子 > 父

        assertThrows(IllegalArgumentException.class, () -> service.create(kb));
        verify(kbRepo, never()).insert(any());
    }

    @Test
    @DisplayName("创建：名称/编码为空被拒绝")
    void createRequiresNameAndCode() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(kb(null, null, null)));

        KnowledgeBase noName = kb(null, "x", null);
        noName.setName("  ");
        assertThrows(IllegalArgumentException.class, () -> service.create(noName));
    }

    // ==================== 更新 ====================

    @Test
    @DisplayName("更新：默认库禁止改 code（它是新建文档的回落键）")
    void defaultKbCodeIsImmutable() {
        when(kbRepo.findById(1L))
                .thenReturn(kb(1L, KnowledgeBase.CODE_DEFAULT, KnowledgeBase.STATUS_ACTIVE));

        KnowledgeBase patch = new KnowledgeBase();
        patch.setCode("renamed");

        assertThrows(IllegalArgumentException.class, () -> service.update(1L, patch, false));
    }

    @Test
    @DisplayName("更新：默认库禁止停用（停用后未指定库的新建文档无处落）")
    void defaultKbCannotBeDisabled() {
        when(kbRepo.findById(1L))
                .thenReturn(kb(1L, KnowledgeBase.CODE_DEFAULT, KnowledgeBase.STATUS_ACTIVE));

        KnowledgeBase patch = new KnowledgeBase();
        patch.setStatus(KnowledgeBase.STATUS_DISABLED);

        assertThrows(IllegalArgumentException.class, () -> service.update(1L, patch, false));
    }

    @Test
    @DisplayName("更新：clearChunkParams 清空三参数回默认")
    void clearChunkParamsResetsToDefault() {
        KnowledgeBase existing = kb(2L, "faq", KnowledgeBase.STATUS_ACTIVE);
        existing.setParentChunkSize(4000);
        existing.setChildChunkSize(800);
        existing.setChunkOverlap(200);
        when(kbRepo.findById(2L)).thenReturn(existing);

        service.update(2L, new KnowledgeBase(), true);

        assertNull(existing.getParentChunkSize());
        assertNull(existing.getChildChunkSize());
        assertNull(existing.getChunkOverlap());
        verify(kbRepo).update(existing);
    }

    // ==================== resolveProfile ====================

    @Test
    @DisplayName("resolveProfile：kbId 为 null → 全局默认")
    void resolveProfileNullKbId() {
        assertSame(ChunkProfile.DEFAULT, service.resolveProfile(null));
    }

    @Test
    @DisplayName("resolveProfile：库不存在 → 全局默认（索引链不中断）")
    void resolveProfileMissingKb() {
        when(kbRepo.findById(99L)).thenReturn(null);
        assertSame(ChunkProfile.DEFAULT, service.resolveProfile(99L));
    }

    @Test
    @DisplayName("resolveProfile：库部分配置 → 逐字段回落合并")
    void resolveProfilePartialOverride() {
        KnowledgeBase kb = kb(3L, "faq", KnowledgeBase.STATUS_ACTIVE);
        kb.setChildChunkSize(300); // 只覆盖子段落
        when(kbRepo.findById(3L)).thenReturn(kb);

        ChunkProfile p = service.resolveProfile(3L);
        assertEquals(ChunkProfile.DEFAULT_PARENT_CHUNK_SIZE, p.parentChunkSize());
        assertEquals(300, p.childChunkSize());
        assertEquals(ChunkProfile.DEFAULT_OVERLAP_SIZE, p.overlap());
    }

    // ==================== resolveForNewDoc ====================

    @Test
    @DisplayName("resolveForNewDoc：未指定库 → 默认库；指定库停用 → 拒绝")
    void resolveForNewDocFallbackAndDisabled() {
        KnowledgeBase def = kb(1L, KnowledgeBase.CODE_DEFAULT, KnowledgeBase.STATUS_ACTIVE);
        when(kbRepo.findByCode(KnowledgeBase.CODE_DEFAULT)).thenReturn(def);
        assertEquals(1L, service.resolveForNewDoc(null).getId());

        KnowledgeBase disabled = kb(4L, "legacy", KnowledgeBase.STATUS_DISABLED);
        when(kbRepo.findById(4L)).thenReturn(disabled);
        assertThrows(IllegalArgumentException.class, () -> service.resolveForNewDoc(4L));
    }
}
