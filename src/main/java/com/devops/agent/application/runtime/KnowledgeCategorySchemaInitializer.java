package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeCategoryRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Keeps existing PostgreSQL development volumes compatible with the category tree model. */
@Component
@Order(10)
public class KnowledgeCategorySchemaInitializer implements ApplicationRunner {

    private final KnowledgeCategoryRepository repository;

    public KnowledgeCategorySchemaInitializer(KnowledgeCategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        repository.ensureSchema();
    }
}
