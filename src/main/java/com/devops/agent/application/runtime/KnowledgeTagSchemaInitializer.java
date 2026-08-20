package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeTagRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(11)
public class KnowledgeTagSchemaInitializer implements ApplicationRunner {
    private final KnowledgeTagRepository repository;

    public KnowledgeTagSchemaInitializer(KnowledgeTagRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        repository.ensureSchema();
    }
}
