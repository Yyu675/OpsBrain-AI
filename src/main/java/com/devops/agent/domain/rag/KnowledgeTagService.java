package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeTagRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识标签字典服务
 * <p>
 * 职责：封装 {@code KnowledgeTagRepository} 的标签 CRUD 操作，
 * 为 Controller 提供 domain 层接口，使 Controller 不直接依赖 infrastructure 层。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
@Service
public class KnowledgeTagService {

    private final KnowledgeTagRepository repository;

    public KnowledgeTagService(KnowledgeTagRepository repository) {
        this.repository = repository;
    }

    public List<KnowledgeTag> findAll() {
        return repository.findAll();
    }

    public KnowledgeTag create(String name, String description, String color) {
        return repository.create(name, description, color);
    }

    public KnowledgeTag rename(long id, String name, String description, String color) {
        return repository.rename(id, name, description, color);
    }

    public KnowledgeTag merge(long sourceId, long targetId) {
        return repository.merge(sourceId, targetId);
    }

    public void delete(long id, Long replacementId) {
        repository.delete(id, replacementId);
    }
}
