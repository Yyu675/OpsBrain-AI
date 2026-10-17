package com.devops.agent.domain.biz.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KnowledgeBoostRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void boostBatchMapsTypedCountRowsAndKeepsDynamicChunkIdMap() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        KnowledgeBoostRepository repository = new KnowledgeBoostRepository(jdbc);
        doReturn(List.of(new KnowledgeBoostRepository.BoostCountsRow(12L, 1, 1)))
                .when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));

        assertThat(repository.boostBatch(List.of(12L, 34L)))
                .containsOnlyKeys(12L)
                .containsEntry(12L, KnowledgeBoostRepository.computeBoost(1, 1));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class),
                aryEq(new Object[]{12L, 34L}));
        assertThat(sql.getValue()).contains("SELECT chunk_id, helpful_count, wrong_count",
                "chunk_id IN (?,?)");
    }
}
