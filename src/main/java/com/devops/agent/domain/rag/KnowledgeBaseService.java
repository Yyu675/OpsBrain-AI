package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 知识库服务：CRUD + 切片参数解析。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>切片参数三件套允许只配一部分——{@link #resolveProfile(Long)} 逐字段
 *       回落全局默认，库只覆盖它真正想定制的项。</li>
 *   <li>参数变更只影响之后的索引，不自动重建存量（全库重嵌是付费 API 成本），
 *       存量生效须显式走 {@code KnowledgeDocService.reindexByKnowledgeBase}。</li>
 *   <li>不提供物理删除：删库会让其下文档失去归属。关闭用 DISABLED——
 *       拒收新文档，存量文档不受影响（检索层目前不按库过滤）。</li>
 *   <li>默认库（code=default）是 V2 迁移承接存量文档的回落键：
 *       禁止改 code、禁止停用，否则未指定库的新建文档无处落。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepo;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepo) {
        this.kbRepo = kbRepo;
    }

    // ==================== 创建 ====================

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase create(KnowledgeBase kb) {
        validateForSave(kb);
        if (kb.getStatus() == null) {
            kb.setStatus(KnowledgeBase.STATUS_ACTIVE);
        }
        try {
            Long id = kbRepo.insert(kb);
            kb.setId(id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("知识库编码已存在: " + kb.getCode());
        }
        log.info("📚 [KB] 新建知识库 | id={} | code={} | name={}", kb.getId(), kb.getCode(), kb.getName());
        // 回读落库值：create_time/update_time 由数据库默认值生成，
        // 直接返回内存对象会让调用方拿到 null 时间戳（真机联调实测过）
        KnowledgeBase saved = kbRepo.findById(kb.getId());
        return saved != null ? saved : kb;
    }

    // ==================== 更新 ====================

    /**
     * 读取-合并-写回。patch 字段为 null 表示不修改。
     * <p>
     * 切片参数支持「显式清空回默认」：patch 的 {@code clearChunkParams=true} 时
     * 三参数全部置 NULL（跟随全局默认）；否则 null 字段保持原值。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase update(Long id, KnowledgeBase patch, boolean clearChunkParams) {
        KnowledgeBase existing = kbRepo.findById(id);
        if (existing == null) {
            throw new IllegalStateException("知识库不存在: " + id);
        }

        boolean isDefault = KnowledgeBase.CODE_DEFAULT.equalsIgnoreCase(existing.getCode());

        if (patch.getCode() != null && !patch.getCode().equalsIgnoreCase(existing.getCode())) {
            if (isDefault) {
                throw new IllegalArgumentException(
                        "默认知识库的编码（" + KnowledgeBase.CODE_DEFAULT + "）是系统回落键，不允许修改");
            }
            existing.setCode(patch.getCode().trim());
        }
        if (patch.getName() != null) {
            existing.setName(patch.getName());
        }
        if (patch.getDescription() != null) {
            existing.setDescription(patch.getDescription());
        }
        if (patch.getStatus() != null && !patch.getStatus().equals(existing.getStatus())) {
            if (isDefault && KnowledgeBase.STATUS_DISABLED.equals(patch.getStatus())) {
                throw new IllegalArgumentException("默认知识库不允许停用——未指定库的新建文档将无处落");
            }
            existing.setStatus(patch.getStatus());
        }

        if (clearChunkParams) {
            existing.setParentChunkSize(null);
            existing.setChildChunkSize(null);
            existing.setChunkOverlap(null);
        } else {
            if (patch.getParentChunkSize() != null) existing.setParentChunkSize(patch.getParentChunkSize());
            if (patch.getChildChunkSize() != null) existing.setChildChunkSize(patch.getChildChunkSize());
            if (patch.getChunkOverlap() != null) existing.setChunkOverlap(patch.getChunkOverlap());
        }

        validateForSave(existing);
        try {
            kbRepo.update(existing);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("知识库编码已存在: " + existing.getCode());
        }
        log.info("📚 [KB] 更新知识库 | id={} | code={} | 参数={}/{}/{}",
                id, existing.getCode(),
                existing.getParentChunkSize(), existing.getChildChunkSize(), existing.getChunkOverlap());
        return kbRepo.findById(id);
    }

    // ==================== 查询 ====================

    public KnowledgeBase findById(Long id) {
        return id == null ? null : kbRepo.findById(id);
    }

    public List<KnowledgeBase> findAll() {
        return kbRepo.findAll();
    }

    public long countDocs(Long kbId) {
        return kbRepo.countDocs(kbId);
    }

    /** 各库文档索引健康度（总数/已索引/失败），列表展示用 */
    public Map<Long, KnowledgeBaseRepository.KbDocStats> docStatsByBase() {
        return kbRepo.docStatsByBase();
    }

    // ==================== 切片参数解析 ====================

    /**
     * 解析某库的生效切片参数（逐字段回落全局默认）。
     *
     * <p>kbId 为 null 或库不存在时返回 {@link ChunkProfile#DEFAULT}——
     * 索引链路不允许因为归属信息缺失而中断，宁可按默认粒度切。</p>
     */
    public ChunkProfile resolveProfile(Long kbId) {
        if (kbId == null) {
            return ChunkProfile.DEFAULT;
        }
        KnowledgeBase kb = kbRepo.findById(kbId);
        if (kb == null) {
            log.warn("⚠️ [KB] 文档引用了不存在的知识库 kbId={}，切片参数回落全局默认", kbId);
            return ChunkProfile.DEFAULT;
        }
        return kb.resolveProfile();
    }

    /**
     * 为新文档解析归属库：kbId 为空时落默认库。
     *
     * @throws IllegalArgumentException 库不存在或已停用
     */
    public KnowledgeBase resolveForNewDoc(Long kbId) {
        if (kbId == null) {
            KnowledgeBase def = kbRepo.findByCode(KnowledgeBase.CODE_DEFAULT);
            if (def == null) {
                throw new IllegalArgumentException("默认知识库缺失（V2 迁移未生效）");
            }
            return def;
        }
        return requireActive(kbId);
    }

    /**
     * 要求库存在且处于 ACTIVE。
     *
     * @throws IllegalArgumentException 库不存在或已停用
     */
    public KnowledgeBase requireActive(Long kbId) {
        KnowledgeBase kb = kbRepo.findById(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + kbId);
        }
        if (!KnowledgeBase.STATUS_ACTIVE.equals(kb.getStatus())) {
            throw new IllegalArgumentException("知识库已停用，不接受新文档: " + kb.getName());
        }
        return kb;
    }

    // ==================== 内部 ====================

    /**
     * 保存前校验：名称/编码必填，合并默认值后的切片参数必须合法
     * （{@link ChunkProfile} 构造期拒绝非法组合）。
     */
    private void validateForSave(KnowledgeBase kb) {
        if (kb.getName() == null || kb.getName().isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        if (kb.getCode() == null || kb.getCode().isBlank()) {
            throw new IllegalArgumentException("知识库编码不能为空");
        }
        kb.setName(kb.getName().trim());
        kb.setCode(kb.getCode().trim());
        // 触发合并校验：非法组合（如子片≥父片）在此抛 IllegalArgumentException
        kb.resolveProfile();
    }
}
