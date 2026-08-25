package com.devops.agent.domain.rag;

import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeCategoryRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeDocHistoryRepository;
import com.devops.agent.infrastructure.persistence.repo.KnowledgeDocRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeDocService} <b>写操作</b>单元测试。
 *
 * <h3>为什么这个类是当前最该补的一处</h3>
 * 全项目盘点后它是<b>最大的单点风险</b>：702 行、9 个 {@code @Transactional}
 * 写方法，此前<b>没有任何直接单测</b>——只有 {@code KnowledgeDocVisibilityTest}
 * （只测实体默认值）和 Controller 契约测试（只测 HTTP 层）间接沾边。
 *
 * <p>它管着知识库的<b>发布、废弃、版本回滚、物理删除</b>。这些方法出错的后果
 * 不是报错，而是两类静默事故：</p>
 * <ol>
 *   <li><b>数据丢了</b>——物理删除少清一张表就留下孤儿数据，
 *       多清一张就把不该删的历史一起抹掉；</li>
 *   <li><b>AI 检索不到</b>——发布了但没建向量、或废弃了但没删向量，
 *       文档在列表里看着好好的，问 AI 它却说不知道（或反过来，
 *       已废弃的内容仍被检索出来当依据）。</li>
 * </ol>
 *
 * <h3>测试边界</h3>
 * mock 全部 9 个协作者，只验证<b>编排逻辑</b>：调了什么、顺序如何、
 * 失败时怎么处理。各协作者自身的行为由它们各自的测试覆盖
 * （{@code ContentFingerprintTest} / {@code SimhashCalibrationTest} 等）。
 */
@DisplayName("KnowledgeDocService 写操作")
class KnowledgeDocServiceWriteTest {

    private KnowledgeDocRepository docRepo;
    private KnowledgeDocHistoryRepository historyRepo;
    private KnowledgeDocTagRepository tagRepo;
    private ContentFingerprint fingerprint;
    private DocumentIndexer indexer;
    private SemanticCacheService semanticCache;
    private KnowledgeContentCleaner contentCleaner;
    private KnowledgeCategoryRepository categoryRepo;
    private com.devops.agent.infrastructure.persistence.repo.KnowledgeTagRepository tagCatalog;

    private KnowledgeDocService service;

    @BeforeEach
    void setUp() {
        docRepo = mock(KnowledgeDocRepository.class);
        historyRepo = mock(KnowledgeDocHistoryRepository.class);
        tagRepo = mock(KnowledgeDocTagRepository.class);
        fingerprint = mock(ContentFingerprint.class);
        indexer = mock(DocumentIndexer.class);
        semanticCache = mock(SemanticCacheService.class);
        contentCleaner = mock(KnowledgeContentCleaner.class);
        categoryRepo = mock(KnowledgeCategoryRepository.class);
        tagCatalog = mock(com.devops.agent.infrastructure.persistence.repo.KnowledgeTagRepository.class);

        service = new KnowledgeDocService(docRepo, historyRepo, tagRepo, fingerprint,
                indexer, semanticCache, contentCleaner, categoryRepo, tagCatalog);

        // 默认：内容清洗放行、指纹稳定
        when(contentCleaner.clean(anyString())).thenAnswer(i ->
                new KnowledgeContentCleaner.CleanResult(i.getArgument(0), false, null, null));
        // ⚠️ 必须显式声明为 String。
        // 写成 String.valueOf(i.getArgument(0)) 时，getArgument 的返回类型是泛型 T，
        // 编译器按 String.valueOf(char[]) 这个重载来推断，运行时抛
        // ClassCastException: String cannot be cast to [C —— 而且是在 lambda 里抛，
        // 堆栈指向 setUp 而非用例本身，极难看出是打桩写错了。
        when(fingerprint.sha256(anyString())).thenAnswer(i -> {
            String content = i.getArgument(0);
            return "hash-" + content.hashCode();
        });
        when(fingerprint.simhash(anyString())).thenReturn(1L);
        when(docRepo.findSimhashCandidates(any(), any(), anyInt())).thenReturn(List.of());
        // update 返回受影响行数，未打桩时 Mockito 默认返回 0，
        // 而 service 把「0 行」解读为 CAS 失败并抛 OptimisticLockException——
        // 于是所有走到 update 的用例都会以一个与被测行为无关的异常告终。
        // 默认打成 1（成功），需要测 CAS 失败的用例再单独覆盖。
        when(docRepo.update(any(), any())).thenReturn(1);
    }

    private KnowledgeDoc doc(Long id, String status, String content) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.setId(id);
        d.setTitle("Redis 主从延迟处置 SOP");
        d.setContent(content);
        d.setStatus(status);
        d.setVersion(3);
        d.setContentHash("hash-" + content.hashCode());
        d.setCategory("中间件");
        return d;
    }

    // ==================== 废弃 ====================

    @Nested
    @DisplayName("deprecate 废弃")
    class Deprecate {

        @Test
        @DisplayName("文档不存在时抛出，不产生任何副作用")
        void rejectsMissingDoc() {
            when(docRepo.findById(9L)).thenReturn(null);

            assertThatThrownBy(() -> service.deprecate(9L, "张三", "内容过期"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("文档不存在");

            verify(indexer, never()).removeVectors(anyLong());
            verify(historyRepo, never()).archive(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("先归档历史再改状态——顺序反了会把「废弃后」的状态存进历史")
        void archivesBeforeStatusChange() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));

            service.deprecate(1L, "张三", "内容过期");

            // 历史快照的意义在于记下「改之前长什么样」。
            // 先改状态再归档，历史里存的就是已废弃态，回滚时会滚到一个废弃版本
            InOrder order = inOrder(historyRepo, docRepo);
            order.verify(historyRepo).archive(any(), eq(KnowledgeDocLifecycle.CHANGE_DEPRECATE),
                    eq("张三"), anyString());
            order.verify(docRepo).updateStatus(eq(1L),
                    eq(KnowledgeDocLifecycle.STATUS_DEPRECATED), anyString());
        }

        @Test
        @DisplayName("必须删除向量——否则已废弃内容仍会被 AI 检索出来当依据")
        void removesVectors() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));

            service.deprecate(1L, "张三", "内容过期");

            // 这是废弃与「仅改状态」的本质区别：不删向量的话，
            // 文档从列表消失了，AI 却还在拿它回答问题——最难发现的一类错误
            verify(indexer).removeVectors(1L);
            verify(docRepo).updateIndexStatus(eq(1L),
                    eq(KnowledgeDocLifecycle.INDEX_SKIPPED), any(), eq(0));
        }

        @Test
        @DisplayName("清空语义缓存——否则旧答案仍会命中已废弃内容")
        void clearsSemanticCache() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));

            service.deprecate(1L, "张三", null);

            verify(semanticCache).clearAllCache();
        }

        @Test
        @DisplayName("未填原因时给默认值，不写 null 进审计")
        void nullReasonFallsBack() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));

            service.deprecate(1L, "张三", null);

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(historyRepo).archive(any(), anyString(), anyString(), reason.capture());
            assertThat(reason.getValue()).isNotBlank();
        }
    }

    // ==================== 物理删除 ====================

    @Nested
    @DisplayName("purge 物理删除（不可逆）")
    class Purge {

        @Test
        @DisplayName("缺合规理由时拒绝——审计举证不能是空的")
        void requiresComplianceReason() {
            // 合规场景下「谁、为什么删的」是核心证据。
            // 允许空理由等于让这条不可逆操作没有任何交代
            assertThatThrownBy(() -> service.purge(1L, "张三", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("合规理由");

            assertThatThrownBy(() -> service.purge(1L, "张三", "   "))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(docRepo, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("校验发生在查库之前——理由不合法就不该有任何 IO")
        void validatesBeforeAnyIo() {
            assertThatThrownBy(() -> service.purge(1L, "张三", ""))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(docRepo, never()).findById(anyLong());
        }

        @Test
        @DisplayName("文档不存在时抛出，不删任何东西")
        void rejectsMissingDoc() {
            when(docRepo.findById(9L)).thenReturn(null);

            assertThatThrownBy(() -> service.purge(9L, "张三", "内容违规"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("文档不存在");

            verify(docRepo, never()).deleteById(anyLong());
            verify(tagRepo, never()).deleteByDocId(anyLong());
        }

        @Test
        @DisplayName("四类关联数据全部清理——少清一张表就留下孤儿数据")
        void purgesAllAssociatedData() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_DEPRECATED, "正文"));

            service.purge(1L, "张三", "内容违规");

            // 向量、标签关联、历史版本、主表——四者缺一，
            // 要么留下查不到主文档的孤儿行，要么向量库里还留着已删内容
            verify(indexer).removeVectors(1L);
            verify(tagRepo).deleteByDocId(1L);
            verify(historyRepo).deleteByDocId(1L);
            verify(docRepo).deleteById(1L);
            verify(semanticCache).clearAllCache();
        }

        @Test
        @DisplayName("先删向量再删主表——反序则主表已没、向量成为无主残留")
        void removesVectorsBeforeMainRow() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_DEPRECATED, "正文"));

            service.purge(1L, "张三", "内容违规");

            // 向量删除不在数据库事务里（向量库是外部存储）。
            // 主表先删、向量删失败的话，就再也没有 docId 能定位到那些向量了
            InOrder order = inOrder(indexer, docRepo);
            order.verify(indexer).removeVectors(1L);
            order.verify(docRepo).deleteById(1L);
        }
    }

    // ==================== 版本回滚 ====================

    @Nested
    @DisplayName("restore 版本回滚")
    class Restore {

        @Test
        @DisplayName("历史版本不存在时抛出，并指明是哪个版本")
        void rejectsMissingVersion() {
            when(historyRepo.findVersion(1L, 2)).thenReturn(null);

            assertThatThrownBy(() -> service.restore(1L, 2, "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("历史版本不存在");
        }

        @Test
        @DisplayName("目标内容已被其他活跃文档占用时，给出可操作的提示而非 500")
        void rejectsWhenContentOccupiedByActiveDoc() {
            KnowledgeDoc historical = doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "历史正文");
            when(historyRepo.findVersion(1L, 2)).thenReturn(historical);
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "当前正文"));

            KnowledgeDoc occupier = doc(77L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "历史正文");
            occupier.setTitle("另一篇 SOP");
            when(docRepo.findByContentHash(historical.getContentHash())).thenReturn(occupier);

            // 内容哈希上有部分唯一索引。直接回滚会撞库约束，用户看到一个 500，
            // 完全不知道发生了什么、也不知道下一步该做什么
            assertThatThrownBy(() -> service.restore(1L, 2, "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("另一篇 SOP")
                    .hasMessageContaining("77");
        }

        @Test
        @DisplayName("占用者是自己时放行——回滚到自己的历史版本是正常操作")
        void allowsWhenOccupierIsSelf() {
            KnowledgeDoc historical = doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "历史正文");
            when(historyRepo.findVersion(1L, 2)).thenReturn(historical);
            KnowledgeDoc current = doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "当前正文");
            when(docRepo.findById(1L)).thenReturn(current);
            // findByContentHash 返回文档自己
            when(docRepo.findByContentHash(anyString())).thenReturn(current);

            service.restore(1L, 2, "张三");

            verify(docRepo).update(any(), any());
        }

        @Test
        @DisplayName("占用者已废弃时放行——它不占用活跃索引")
        void allowsWhenOccupierIsDeprecated() {
            KnowledgeDoc historical = doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "历史正文");
            when(historyRepo.findVersion(1L, 2)).thenReturn(historical);
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "当前正文"));

            KnowledgeDoc deprecatedOccupier = doc(77L, KnowledgeDocLifecycle.STATUS_DEPRECATED, "历史正文");
            when(docRepo.findByContentHash(anyString())).thenReturn(deprecatedOccupier);

            // 部分唯一索引只约束活跃文档，已废弃的不参与——
            // 若这里也拦，用户会被一个其实不存在的冲突挡住
            service.restore(1L, 2, "张三");

            verify(docRepo).update(any(), any());
        }

        @Test
        @DisplayName("回滚即重新发布——不显式设 PUBLISHED 的话，已废弃文档回滚后仍检索不到")
        void restoreForcesPublishedStatus() {
            KnowledgeDoc historical = doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "历史正文");
            when(historyRepo.findVersion(1L, 2)).thenReturn(historical);
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_DEPRECATED, "当前正文"));
            when(docRepo.findByContentHash(anyString())).thenReturn(null);

            service.restore(1L, 2, "张三");

            // update 的合并逻辑只覆盖非空字段。不设 status 的话，
            // 已废弃文档回滚后 status 仍是 DEPRECATED——
            // 用户以为回滚成功，实际 AI 依然检索不到
            ArgumentCaptor<KnowledgeDoc> saved = ArgumentCaptor.forClass(KnowledgeDoc.class);
            verify(docRepo).update(saved.capture(), any());
            assertThat(saved.getValue().getStatus())
                    .isEqualTo(KnowledgeDocLifecycle.STATUS_PUBLISHED);
        }

        @Test
        @DisplayName("回滚写回历史内容与标题，而不是只改状态")
        void restoresContentAndTitle() {
            KnowledgeDoc historical = doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "历史正文");
            historical.setTitle("旧标题");
            when(historyRepo.findVersion(1L, 2)).thenReturn(historical);
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "当前正文"));
            when(docRepo.findByContentHash(anyString())).thenReturn(null);

            service.restore(1L, 2, "张三");

            ArgumentCaptor<KnowledgeDoc> saved = ArgumentCaptor.forClass(KnowledgeDoc.class);
            verify(docRepo).update(saved.capture(), any());
            assertThat(saved.getValue().getContent()).isEqualTo("历史正文");
            assertThat(saved.getValue().getTitle()).isEqualTo("旧标题");
        }

        @Test
        @DisplayName("回滚记入历史，理由带上目标版本号——事后要能看出「曾回滚过」")
        void recordsRestoreReasonWithVersion() {
            KnowledgeDoc historical = doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "历史正文");
            when(historyRepo.findVersion(1L, 2)).thenReturn(historical);
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "当前正文"));
            when(docRepo.findByContentHash(anyString())).thenReturn(null);

            service.restore(1L, 2, "张三");

            // 回滚实现为「把历史内容作为一次新提交」而非倒退版本号。
            // 若理由里不带版本，历史上就只剩一条普通更新，看不出这是回滚
            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(historyRepo).archive(any(), anyString(), eq("张三"), reason.capture());
            assertThat(reason.getValue()).contains("2");
        }
    }

    // ==================== 乐观锁与编辑门禁 ====================

    @Nested
    @DisplayName("update 并发与门禁")
    class UpdateGuards {

        @Test
        @DisplayName("版本不匹配时抛乐观锁异常，携带期望值与实际值")
        void detectsConcurrentModification() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));

            KnowledgeDoc patch = new KnowledgeDoc();
            patch.setContent("新正文");

            // 实体 version=3，客户端拿的是 2 —— 说明中间有人改过
            assertThatThrownBy(() -> service.update(1L, patch, null, 2, "张三", null))
                    .isInstanceOf(OptimisticLockException.class);

            verify(docRepo, never()).update(any(), any());
        }

        @Test
        @DisplayName("不传期望版本时跳过 CAS——供系统内部调用")
        void skipsCasWhenVersionOmitted() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));

            KnowledgeDoc patch = new KnowledgeDoc();
            patch.setContent("新正文");

            service.update(1L, patch, null, null, "SYSTEM", null);

            verify(docRepo).update(any(), any());
        }

        @Test
        @DisplayName("已废弃文档不能直接编辑正文——必须先恢复")
        void rejectsEditingDeprecatedDoc() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_DEPRECATED, "正文"));

            KnowledgeDoc patch = new KnowledgeDoc();
            patch.setContent("偷偷改内容");

            // 放行的话，一篇「已下架」的文档会被悄悄改出新内容，
            // 而它在列表里仍显示为废弃，没人会去复核
            assertThatThrownBy(() -> service.update(1L, patch, null, null, "张三", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("先恢复");
        }

        @Test
        @DisplayName("已废弃文档允许只改元信息（如分类归置）")
        void allowsMetadataOnlyEditOnDeprecated() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_DEPRECATED, "正文"));

            KnowledgeDoc patch = new KnowledgeDoc();
            patch.setCategory("归档区");

            // 整理分类不改变内容，不该被门禁挡住——
            // 否则废弃文档就再也无法被归置到正确目录
            service.update(1L, patch, null, null, "张三", null);

            verify(docRepo).update(any(), any());
        }

        @Test
        @DisplayName("内容清洗未通过时拒绝写入——脏数据不进向量库")
        void rejectsUncleanContent() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));
            when(contentCleaner.clean(anyString()))
                    .thenReturn(KnowledgeContentCleaner.CleanResult.rejected("正文过短"));

            KnowledgeDoc patch = new KnowledgeDoc();
            patch.setContent("x");

            assertThatThrownBy(() -> service.update(1L, patch, null, null, "张三", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("清洗未通过");

            verify(docRepo, never()).update(any(), any());
        }

        @Test
        @DisplayName("只改元信息时不做内容清洗——沿用旧内容，不该被重新校验")
        void skipsCleaningForMetadataOnlyUpdate() {
            when(docRepo.findById(1L)).thenReturn(doc(1L, KnowledgeDocLifecycle.STATUS_PUBLISHED, "正文"));

            KnowledgeDoc patch = new KnowledgeDoc();
            patch.setCategory("新分类");

            service.update(1L, patch, null, null, "张三", null);

            // 旧内容当初已经通过清洗。若这里重新校验，
            // 清洗规则一收紧，所有老文档就连改个分类都做不到了
            verify(contentCleaner, never()).clean(anyString());
        }

        @Test
        @DisplayName("文档不存在时抛出")
        void rejectsMissingDoc() {
            when(docRepo.findById(9L)).thenReturn(null);

            assertThatThrownBy(() ->
                    service.update(9L, new KnowledgeDoc(), null, null, "张三", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("文档不存在");
        }
    }
}
