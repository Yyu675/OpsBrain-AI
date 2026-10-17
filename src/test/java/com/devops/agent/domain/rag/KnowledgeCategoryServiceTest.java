package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeCategoryServiceTest {

    private KnowledgeCategoryRepository repository;
    private KnowledgeCategoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeCategoryRepository.class);
        service = new KnowledgeCategoryService(repository);
    }

    @Test
    void treeGroupsDocumentsByIdOrCaseInsensitiveNameAndKeepsUncategorizedOrder() {
        KnowledgeCategory first = category(1L, null, "K8s", 0, 2);
        KnowledgeCategory second = category(2L, null, "Databases", 1, 2);
        when(repository.findAll()).thenReturn(List.of(first, second));
        when(repository.findDocuments()).thenReturn(List.of(
                new KnowledgeCategoryTreeData.Document(11L, "关联 ID", "旧名", 2L, 1, "PUBLISHED", null),
                new KnowledgeCategoryTreeData.Document(12L, "匹配名称", "databases", null, 3, "DRAFT", null),
                new KnowledgeCategoryTreeData.Document(13L, "无分类", null, null, 1, "DRAFT", null),
                new KnowledgeCategoryTreeData.Document(14L, "未知名称", "其他", null, 1, "PUBLISHED", null)));

        KnowledgeCategoryTreeData tree = service.tree();

        assertThat(tree.categories()).extracting(KnowledgeCategoryTreeData.Node::id)
                .containsExactly(1L, 2L);
        assertThat(tree.categories().get(1).documents()).extracting(KnowledgeCategoryTreeData.Document::id)
                .containsExactly(11L, 12L);
        assertThat(tree.uncategorized()).extracting(KnowledgeCategoryTreeData.Document::id)
                .containsExactly(13L, 14L);
    }

    @Test
    void renameKeepsDocumentCategoryInSync() {
        KnowledgeCategory before = category(1L, null, "数据库", 0, 2);
        KnowledgeCategory after = category(1L, null, "数据存储", 0, 2);
        when(repository.findById(1L)).thenReturn(before, after);
        when(repository.update(1L, null, "数据存储", 0)).thenReturn(1);

        KnowledgeCategory result = service.update(1L, null, "数据存储", 0);

        assertThat(result.name()).isEqualTo("数据存储");
        verify(repository).renameDocuments("数据库", "数据存储");
    }

    @Test
    void nonEmptyCategoryCannotBeDeleted() {
        when(repository.findById(1L)).thenReturn(category(1L, null, "数据库", 0, 1));
        when(repository.countChildren(1L)).thenReturn(0L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仍包含文档");
        verify(repository, never()).delete(1L);
    }

    @Test
    void categoryCannotMoveBelowItsDescendant() {
        KnowledgeCategory root = category(1L, null, "根目录", 0, 0);
        KnowledgeCategory child = category(2L, 1L, "子目录", 0, 0);
        when(repository.findById(1L)).thenReturn(root);
        when(repository.findById(2L)).thenReturn(child);
        when(repository.findAll()).thenReturn(List.of(root, child));

        assertThatThrownBy(() -> service.update(1L, 2L, "根目录", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自身或其子分类");
        verify(repository, never()).update(1L, 2L, "根目录", 0);
    }

    private KnowledgeCategory category(
            Long id, Long parentId, String name, int sortOrder, long docCount) {
        return new KnowledgeCategory(id, parentId, name, sortOrder, docCount, null, null);
    }
}
