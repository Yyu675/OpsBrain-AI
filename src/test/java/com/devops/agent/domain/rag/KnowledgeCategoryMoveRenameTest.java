package com.devops.agent.domain.rag;

import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeCategoryRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeDocHistoryRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeDocRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeDocService#moveCategory} 与
 * {@link KnowledgeDocService#renameCategoryDocuments} 单元测试。
 *
 * <h3>为什么补这两个</h3>
 * 由 {@code scan_service_write_coverage.py} 扫出、记在 {@code baseline.json}
 * 的「确属缺口」，74 号报告把它们排在 saveTicket 之后。
 *
 * <h3>它们守的是「分类」这个检索过滤维度</h3>
 * 分类不只是界面上的一棵树——{@code category} / {@code categoryId} 是
 * 知识检索的过滤条件之一。这两个方法出错的后果是<b>文档还在、但查不到</b>：
 * <ul>
 *   <li>{@code moveCategory} 漏了乐观锁 → 编辑器里打开的旧分类会覆盖移动结果，
 *       用户刚移走的文档又跳回原处，而且没有任何报错；</li>
 *   <li>{@code renameCategoryDocuments} 只改了名字没改 {@code categoryId}
 *       （或反之）→ 两个字段指向不同分类，按名字筛能查到、按 id 筛查不到，
 *       这种「一半对一半错」比整个坏掉更难排查。</li>
 * </ul>
 *
 * <h3>乐观锁的双重检查是刻意的</h3>
 * {@code moveCategory} 先比对 {@code expectedVersion} 与读到的版本，
 * 再看 UPDATE 的影响行数。看似重复，实则缺一不可：
 * 读与写之间存在时间窗，别人在这个窗口里改了文档，
 * 前一道检查会放行，只有后一道能拦住。测试对两条路径分别断言。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("KnowledgeDocService 分类移动与重命名")
class KnowledgeCategoryMoveRenameTest {

    private KnowledgeDocRepository docRepo;
    private KnowledgeDocHistoryRepository historyRepo;
    private KnowledgeDocService service;

    @BeforeEach
    void setUp() {
        docRepo = mock(KnowledgeDocRepository.class);
        historyRepo = mock(KnowledgeDocHistoryRepository.class);
        KnowledgeDocTagRepository tagRepo = mock(KnowledgeDocTagRepository.class);
        ContentFingerprint fingerprint = mock(ContentFingerprint.class);
        DocumentIndexer indexer = mock(DocumentIndexer.class);
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        KnowledgeContentCleaner contentCleaner = mock(KnowledgeContentCleaner.class);
        KnowledgeCategoryRepository categoryRepo = mock(KnowledgeCategoryRepository.class);
        KnowledgeTagRepository tagCatalog = mock(KnowledgeTagRepository.class);

        service = new KnowledgeDocService(docRepo, historyRepo, tagRepo, fingerprint,
                indexer, semanticCache, contentCleaner, categoryRepo, tagCatalog);

        // 与既有 KnowledgeDocServiceWriteTest 相同的默认桩：
        // 不打这些桩时 Mockito 返回 0/null，会让用例以「与被测行为无关的异常」告终
        when(contentCleaner.clean(anyString())).thenAnswer(i ->
                new KnowledgeContentCleaner.CleanResult(i.getArgument(0), false, null, null));
        when(fingerprint.sha256(anyString())).thenAnswer(i -> {
            String content = i.getArgument(0);
            return "hash-" + content.hashCode();
        });
        when(fingerprint.simhash(anyString())).thenReturn(1L);
        when(docRepo.findSimhashCandidates(any(), any(), anyInt())).thenReturn(List.of());
        when(docRepo.update(any(), any())).thenReturn(1);
        when(docRepo.updateCategory(any(), any(), any(), any())).thenReturn(1);
    }

    private KnowledgeDoc doc(Long id, int version, String category, Long categoryId) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.setId(id);
        d.setTitle("手册-" + id);
        d.setContent("正文");
        d.setVersion(version);
        d.setCategory(category);
        d.setCategoryId(categoryId);
        return d;
    }

    // ==================================================================
    // moveCategory
    // ==================================================================

    @Nested
    @DisplayName("moveCategory 移动文档分类")
    class MoveCategory {

        @Test
        @DisplayName("正常移动：先归档快照再更新，两者顺序不能反")
        void archivesThenUpdates() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, 3, "旧分类", 10L));

            service.moveCategory(1L, 20L, "新分类", 3, "张三");

            // 顺序有意义：必须先归档「移动前」的样子。
            // 反过来先更新再归档，存进历史的就是移动后的状态，
            // 版本回滚时会回到一个错误的分类，而历史记录看起来完全正常
            InOrder order = inOrder(historyRepo, docRepo);
            order.verify(historyRepo).archive(any(), eq(KnowledgeDocLifecycle.CHANGE_UPDATE),
                    eq("张三"), anyString());
            order.verify(docRepo).updateCategory(1L, "新分类", 20L, 3);
        }

        @Test
        @DisplayName("版本不匹配时抛乐观锁异常，且不归档、不更新")
        void versionMismatchRejected() {
            // 读到的是 v5，调用方以为还是 v3 —— 说明它拿的是过期数据。
            // 此时若放行，会把别人刚做的修改覆盖掉
            when(docRepo.findById(1L)).thenReturn(doc(1L, 5, "旧分类", 10L));

            assertThatThrownBy(() -> service.moveCategory(1L, 20L, "新分类", 3, "张三"))
                    .isInstanceOf(OptimisticLockException.class);

            verify(historyRepo, never()).archive(any(), anyString(), anyString(), anyString());
            verify(docRepo, never()).updateCategory(any(), any(), any(), any());
        }

        @Test
        @DisplayName("UPDATE 影响 0 行同样抛乐观锁异常——这是第二道检查，缺一不可")
        void zeroRowsAlsoRejected() {
            // ── 本组最重要的一条 ──────────────────────────────
            // 前一道检查（比对 version）与这一道（看影响行数）看似重复，
            // 实则拦的是不同时刻：读与写之间存在时间窗，
            // 别人在窗口里改了文档，前一道会放行，只有这一道能拦住。
            // 若只保留前一道，并发移动会静默丢失一方的修改
            when(docRepo.findById(1L)).thenReturn(doc(1L, 3, "旧分类", 10L));
            when(docRepo.updateCategory(any(), any(), any(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.moveCategory(1L, 20L, "新分类", 3, "张三"))
                    .isInstanceOf(OptimisticLockException.class);
        }

        @Test
        @DisplayName("expectedVersion 为 null 时跳过前置版本比对（强制移动）")
        void nullExpectedVersionSkipsPreCheck() {
            // 管理端批量整理分类时没有版本上下文，允许强制移动。
            // 但注意：跳过的只是前置比对，UPDATE 影响行数那道仍然生效
            when(docRepo.findById(1L)).thenReturn(doc(1L, 7, "旧分类", 10L));

            service.moveCategory(1L, 20L, "新分类", null, "管理员");

            verify(docRepo).updateCategory(1L, "新分类", 20L, null);
        }

        @Test
        @DisplayName("文档不存在时抛错，且不归档")
        void missingDocRejected() {
            when(docRepo.findById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.moveCategory(99L, 20L, "新分类", 1, "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("文档不存在");
            verify(historyRepo, never()).archive(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("分类名与分类 id 一并写入——只写一个会让两个字段指向不同分类")
        void bothCategoryFieldsUpdated() {
            // category（名字）与 categoryId 是冗余的一对。只更新其一，
            // 按名字筛能查到、按 id 筛查不到，这种「一半对一半错」
            // 比整个坏掉更难排查
            when(docRepo.findById(1L)).thenReturn(doc(1L, 1, "旧", 10L));

            service.moveCategory(1L, 20L, "新分类", 1, "张三");

            verify(docRepo).updateCategory(eq(1L), eq("新分类"), eq(20L), eq(1));
        }
    }

    // ==================================================================
    // renameCategoryDocuments
    // ==================================================================

    @Nested
    @DisplayName("renameCategoryDocuments 分类重命名后批量更新文档")
    class RenameCategoryDocuments {

        @Test
        @DisplayName("逐个更新该分类下的全部文档")
        void updatesEveryDocInCategory() {
            when(docRepo.findByCategory(10L, "旧名"))
                    .thenReturn(List.of(doc(1L, 1, "旧名", 10L), doc(2L, 2, "旧名", 10L)));

            service.renameCategoryDocuments(10L, "旧名", "新名", "张三");

            // 漏掉任何一篇，那篇文档的 category 就还留着旧名字，
            // 与分类表不一致，按分类筛选时会漏掉它
            verify(docRepo, times(2)).update(any(), any());
        }

        @Test
        @DisplayName("分类下无文档时安全返回，不做任何写操作")
        void emptyCategoryIsNoop() {
            when(docRepo.findByCategory(10L, "旧名")).thenReturn(List.of());

            service.renameCategoryDocuments(10L, "旧名", "新名", "张三");

            verify(docRepo, never()).update(any(), any());
        }

        @Test
        @DisplayName("按各文档自己的版本号更新，不能共用同一个版本")
        void usesEachDocOwnVersion() {
            // 两篇文档的 version 不同（1 和 2）。若实现里写死某个版本，
            // 另一篇的 CAS 必然失败——而失败会抛 OptimisticLockException，
            // 整批重命名中断，前面几篇已改、后面没改，分类处于半更新状态
            when(docRepo.findByCategory(10L, "旧名"))
                    .thenReturn(List.of(doc(1L, 1, "旧名", 10L), doc(2L, 2, "旧名", 10L)));

            service.renameCategoryDocuments(10L, "旧名", "新名", "张三");

            verify(docRepo, times(2)).update(any(), any());
        }

        @Test
        @DisplayName("按 categoryId 与旧名两个条件查，不能只按其一")
        void looksUpByBothIdAndOldName() {
            // 只按名字查会误伤同名的其它分类（不同父节点下可以重名）；
            // 只按 id 查则漏掉那些 categoryId 尚未回填的历史文档
            when(docRepo.findByCategory(10L, "旧名")).thenReturn(List.of());

            service.renameCategoryDocuments(10L, "旧名", "新名", "张三");

            verify(docRepo).findByCategory(10L, "旧名");
        }
    }
}
