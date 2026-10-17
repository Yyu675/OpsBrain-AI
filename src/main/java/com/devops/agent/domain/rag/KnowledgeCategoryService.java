package com.devops.agent.domain.rag;

import com.devops.agent.infrastructure.persistence.repo.KnowledgeCategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KnowledgeCategoryService {

    private final KnowledgeCategoryRepository repository;
    private final KnowledgeDocService docService;

    public KnowledgeCategoryService(KnowledgeCategoryRepository repository) {
        this(repository, null);
    }

    @Autowired
    public KnowledgeCategoryService(KnowledgeCategoryRepository repository, KnowledgeDocService docService) {
        this.repository = repository;
        this.docService = docService;
    }

    public List<KnowledgeCategory> findAll() {
        return repository.findAll();
    }

    public KnowledgeCategoryTreeData tree() {
        List<KnowledgeCategory> categories = repository.findAll();
        List<KnowledgeCategoryTreeData.Document> docs = repository.findDocuments();
        Map<Long, List<KnowledgeCategoryTreeData.Document>> docsByCategory = new LinkedHashMap<>();
        Map<String, Long> categoryIdsByName = new LinkedHashMap<>();
        for (KnowledgeCategory category : categories) {
            categoryIdsByName.put(category.name().toLowerCase(Locale.ROOT), category.id());
        }
        List<KnowledgeCategoryTreeData.Document> uncategorized = new ArrayList<>();
        for (KnowledgeCategoryTreeData.Document doc : docs) {
            Long categoryId = doc.categoryId();
            if (categoryId == null) {
                String category = doc.category();
                categoryId = category == null ? null : categoryIdsByName.get(category.toLowerCase(Locale.ROOT));
            }
            if (categoryId == null) uncategorized.add(doc);
            else docsByCategory.computeIfAbsent(categoryId, key -> new ArrayList<>()).add(doc);
        }

        List<KnowledgeCategoryTreeData.Node> nodes = new ArrayList<>();
        for (KnowledgeCategory category : categories) {
            nodes.add(new KnowledgeCategoryTreeData.Node(
                    category.id(), category.parentId(), category.name(), category.sortOrder(),
                    category.docCount(), docsByCategory.getOrDefault(category.id(), List.of())));
        }
        return new KnowledgeCategoryTreeData(nodes, uncategorized);
    }

    @Transactional
    public KnowledgeCategory create(Long parentId, String rawName, Integer sortOrder) {
        String name = validateName(rawName);
        requireParent(parentId);
        try {
            Long id = repository.insert(parentId, name, sortOrder == null ? 0 : sortOrder);
            return repository.findById(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("分类名称已存在");
        }
    }

    @Transactional
    public KnowledgeCategory update(Long id, Long parentId, String rawName, Integer sortOrder) {
        KnowledgeCategory existing = requireCategory(id);
        String name = validateName(rawName);
        requireParent(parentId);
        ensureNoCycle(id, parentId);
        try {
            int changed = repository.update(id, parentId, name,
                    sortOrder == null ? existing.sortOrder() : sortOrder);
            if (changed == 0) throw new IllegalStateException("分类不存在");
            if (!existing.name().equals(name)) {
                if (docService != null) {
                    docService.renameCategoryDocuments(id, existing.name(), name, "SYSTEM");
                } else {
                    repository.renameDocuments(existing.name(), name);
                }
            }
            return repository.findById(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("分类名称已存在");
        }
    }

    @Transactional
    public void delete(Long id) {
        KnowledgeCategory category = requireCategory(id);
        if (repository.countChildren(id) > 0) {
            throw new IllegalStateException("该分类仍包含子分类，请先移动或删除子分类");
        }
        if (category.docCount() > 0) {
            throw new IllegalStateException("该分类仍包含文档，请先移动文档");
        }
        repository.delete(id);
    }

    @Transactional
    public void moveDocument(Long docId, Long categoryId, Integer version) {
        String categoryName = categoryId == null ? null : requireCategory(categoryId).name();
        if (docService != null) {
            docService.moveCategory(docId, categoryId, categoryName, version, "SYSTEM");
        } else if (repository.moveDocument(docId, categoryId, categoryName) == 0) {
            throw new IllegalStateException("文档不存在");
        }
    }

    private KnowledgeCategory requireCategory(Long id) {
        KnowledgeCategory category = id == null ? null : repository.findById(id);
        if (category == null) throw new IllegalStateException("分类不存在");
        return category;
    }

    private void requireParent(Long parentId) {
        if (parentId != null) requireCategory(parentId);
    }

    private void ensureNoCycle(Long id, Long parentId) {
        Long cursor = parentId;
        List<KnowledgeCategory> categories = repository.findAll();
        while (cursor != null) {
            if (cursor.equals(id)) throw new IllegalArgumentException("分类不能移动到自身或其子分类下");
            Long current = cursor;
            cursor = categories.stream().filter(item -> item.id().equals(current))
                    .map(KnowledgeCategory::parentId).findFirst().orElse(null);
        }
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("分类名称不能为空");
        if (name.length() > 64) throw new IllegalArgumentException("分类名称不能超过 64 个字符");
        return name;
    }
}
