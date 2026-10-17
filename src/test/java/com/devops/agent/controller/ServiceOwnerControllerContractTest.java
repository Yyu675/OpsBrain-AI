package com.devops.agent.controller;

import com.devops.agent.domain.biz.repository.ServiceOwnerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceOwnerControllerContractTest {

    @Mock
    private ServiceOwnerRepository repository;

    @Test
    void listReturnsTypedRowsWithOriginalValues() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 17, 12, 0);
        LocalDateTime updated = created.plusMinutes(5);
        when(repository.listAll()).thenReturn(List.of(
                new ServiceOwnerRepository.ServiceOwnerRow(41L, "payments", "on-call", true, created, updated)));

        var result = new ServiceOwnerController(repository).list().getData();

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).containsExactly(new com.devops.agent.controller.dto.ServiceOwnerDto.ServiceOwnerItem(
                41L, "payments", "on-call", true, created, updated));
    }
}
