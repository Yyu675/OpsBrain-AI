package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识标签字典服务
 * <p>
 * 职责：封装 {@code KnowledgeTagRepository} 的标签 CRUD 操作，
 * 为 Controller 提供 domain 层接口，使 Controller 不直接依赖 infrastructure 层。
 * </p>
 * <p>
 * <b>事务边界（C3 修复）</b>：写操作的 {@code @Transactional} 原先标在
 * {@code KnowledgeTagController} 的方法上，有两个问题：
 * <ol>
 *   <li>违反项目分层规范——Controller 只做 HTTP 适配，不承担事务职责；</li>
 *   <li>事务范围过大——参数校验、DTO 转换、异常转 ApiResponse 全被圈进事务，
 *       数据库连接被无谓占用，高并发下连接池更早耗尽。</li>
 * </ol>
 * 现下沉到本层，事务只覆盖真正的数据操作。
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

    @Transactional(readOnly = true)
    public List<KnowledgeTag> findAll() {
        return repository.findAll();
    }

    @Transactional
    public KnowledgeTag create(String name, String description, String color) {
        return repository.create(name, description, color);
    }

    @Transactional
    public KnowledgeTag rename(long id, String name, String description, String color) {
        return repository.rename(id, name, description, color);
    }

    @Transactional
    public KnowledgeTag merge(long sourceId, long targetId) {
        return repository.merge(sourceId, targetId);
    }

    @Transactional
    public void delete(long id, Long replacementId) {
        repository.delete(id, replacementId);
    }
}
