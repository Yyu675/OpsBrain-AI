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
