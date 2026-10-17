package com.devops.agent.infrastructure.persistence.repo;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KnowledgeBaseRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void docStatsAggregatesTypedRowsAndSkipsUnassignedDocuments() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        KnowledgeBaseRepository repository = new KnowledgeBaseRepository(jdbc);
        doReturn(List.of(
                new KnowledgeBaseRepository.KbDocIndexStatusRow(7L, "INDEXED", 3),
                new KnowledgeBaseRepository.KbDocIndexStatusRow(7L, "FAILED", 1),
                new KnowledgeBaseRepository.KbDocIndexStatusRow(7L, "PENDING", 2),
                new KnowledgeBaseRepository.KbDocIndexStatusRow(null, "INDEXED", 20),
                new KnowledgeBaseRepository.KbDocIndexStatusRow(9L, "INDEXED", 1)))
                .when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class));

        Map<Long, KnowledgeBaseRepository.KbDocStats> result = repository.docStatsByBase();

        assertThat(result).containsOnlyKeys(7L, 9L);
        assertThat(result.get(7L)).isEqualTo(new KnowledgeBaseRepository.KbDocStats(6, 3, 1));
        assertThat(result.get(9L)).isEqualTo(new KnowledgeBaseRepository.KbDocStats(1, 1, 0));
        verify(jdbc).query(contains("GROUP BY kb_id, index_status"),
                any(org.springframework.jdbc.core.RowMapper.class));
    }
}
