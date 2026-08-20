package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeDoc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KnowledgeDocRepositorySortTest {

    @Autowired
    private KnowledgeDocRepository repository;

    @Test
    void supportedSortModesExecuteAndTitleSortIsStable() {
        List<KnowledgeDoc> titleSorted = repository.findPage(
                1, 200, null, null, null, null, "TITLE_ASC");

        assertThat(titleSorted)
                .extracting(KnowledgeDoc::getTitle)
                .isSortedAccordingTo(Comparator.comparing(String::toLowerCase));

        assertThat(repository.findPage(
                1, 20, null, null, "K8s", null, "RELEVANCE")).isNotNull();
        assertThat(repository.findPage(
                1, 20, null, null, "%_", null, "RELEVANCE")).isNotNull();
        assertThat(repository.findPage(
                1, 20, null, null, null, null, "CREATED_DESC")).isNotNull();
        assertThat(repository.findPage(
                1, 20, null, null, null, null, "UNTRUSTED_SQL")).isNotNull();
    }
}
