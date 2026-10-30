package com.advisory.knowledge.service;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.domain.KbNode;
import com.advisory.knowledge.repo.KbNodeJdbcRepository;
import com.advisory.knowledge.repo.KbNodeRepository;
import com.advisory.knowledge.repo.KnowledgeBaseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tree operations over {@code kb_nodes}. Paths are {@code ltree} labels of the form {@code n{id}};
 * a root is {@code n{id}}, a child is {@code parent.path.n{id}}. The path is only ever written
 * through {@link KbNodeJdbcRepository} because it requires the generated id and explicit ltree casts.
 *
 * <p>Sibling-name uniqueness is checked up front and again by the unique indexes (translated from
 * {@link DataIntegrityViolationException} to {@code KB_NODE_NAME_DUPLICATED}); the partial root index
 * covers the {@code parent_id IS NULL} case that the plain index cannot.</p>
 */
@Service
public class NodeService {

    private static final int MAX_NAME_LENGTH = 255;

    private final KbNodeRepository nodeRepository;
    private final KbNodeJdbcRepository nodeJdbc;
    private final KnowledgeBaseRepository kbRepository;

    public NodeService(KbNodeRepository nodeRepository,
                       KbNodeJdbcRepository nodeJdbc,
                       KnowledgeBaseRepository kbRepository) {
        this.nodeRepository = nodeRepository;
        this.nodeJdbc = nodeJdbc;
        this.kbRepository = kbRepository;
    }

    @Transactional
    public KbNode createNode(Long kbId, Long parentId, String name) {
        name = validateName(name);
        kbRepository.findById(kbId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge base not found"));

        String parentPath = null;
        if (parentId != null) {
            KbNode parent = find(parentId);
            if (!parent.getKbId().equals(kbId)) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Parent node not found in this knowledge base");
            }
            parentPath = parent.getPath();
        }

        if (nodeJdbc.existsSibling(kbId, parentId, name, null)) {
            throw new AppException(ErrorCode.KB_NODE_NAME_DUPLICATED, "A node with this name already exists");
        }

        try {
            long id = nodeJdbc.insert(kbId, parentId, parentPath, name);
            return find(id);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.KB_NODE_NAME_DUPLICATED, "A node with this name already exists");
        }
    }

    @Transactional
    public KbNode moveNode(Long nodeId, Long newParentId) {
        KbNode node = find(nodeId);
        String oldPath = node.getPath();

        String newNodePath;
        if (newParentId == null) {
            newNodePath = "n" + nodeId;
        } else {
            KbNode parent = find(newParentId);
            if (!parent.getKbId().equals(node.getKbId())) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Parent node not found in this knowledge base");
            }
            if (nodeJdbc.isDescendantOrSelf(parent.getPath(), oldPath)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "Cannot move a node into its own subtree");
            }
            newNodePath = parent.getPath() + ".n" + nodeId;
        }

        if (!oldPath.equals(newNodePath)) {
            nodeJdbc.rewriteSubtreePath(newNodePath, oldPath);
        }
        return find(nodeId);
    }

    @Transactional
    public KbNode renameNode(Long nodeId, String name) {
        name = validateName(name);
        KbNode node = find(nodeId);
        if (nodeJdbc.existsSibling(node.getKbId(), node.getParentId(), name, nodeId)) {
            throw new AppException(ErrorCode.KB_NODE_NAME_DUPLICATED, "A node with this name already exists");
        }
        try {
            nodeJdbc.updateName(nodeId, name);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.KB_NODE_NAME_DUPLICATED, "A node with this name already exists");
        }
        return find(nodeId);
    }

    @Transactional
    public void deleteNode(Long nodeId) {
        find(nodeId);
        if (nodeRepository.countByParentId(nodeId) > 0) {
            throw new AppException(ErrorCode.NODE_NOT_EMPTY, "Node has children");
        }
        if (nodeJdbc.countDocuments(nodeId) > 0) {
            throw new AppException(ErrorCode.NODE_NOT_EMPTY, "Node has documents");
        }
        nodeRepository.deleteById(nodeId);
    }

    @Transactional(readOnly = true)
    public List<KbNode> listNodes(Long kbId) {
        kbRepository.findById(kbId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge base not found"));
        return nodeRepository.findByKbIdOrderByPath(kbId);
    }

    /**
     * Combined rename and/or move for {@code PATCH /api/nodes/{id}}. {@code name} is null for
     * "no rename"; {@code move} distinguishes "parentId present" (including {@code null} = root)
     * from "parentId absent".
     */
    @Transactional
    public KbNode updateNode(Long nodeId, String name, Long newParentId, boolean move) {
        if (name != null) {
            renameNode(nodeId, name);
        }
        if (move) {
            moveNode(nodeId, newParentId);
        }
        return find(nodeId);
    }

    private KbNode find(Long id) {
        return nodeRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Node not found"));
    }

    private static String validateName(String raw) {
        if (raw == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not be blank");
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not contain control characters");
            }
        }
        return name;
    }
}
