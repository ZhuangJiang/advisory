package com.advisory.knowledge.service;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.domain.KbNodeRow;
import com.advisory.knowledge.repo.KbNodeJdbcRepository;
import com.advisory.knowledge.repo.KbNodeRepository;
import com.advisory.knowledge.repo.KnowledgeBaseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

/**
 * Tree operations over {@code kb_nodes}. Paths are {@code ltree} labels of the form {@code n{id}};
 * a root is {@code n{id}}, a child is {@code parent.path.n{id}}.
 *
 * <p>Every node read and write goes through {@link KbNodeJdbcRepository}, which returns
 * {@link KbNodeRow} projections of the stored row. A JPA {@code findById} after a JDBC write would
 * return the stale entity cached in the shared persistence context, so responses are always built
 * from a fresh JDBC read-back. The JPA repository is only used to delete the entity.</p>
 *
 * <p>Sibling-name uniqueness is checked up front and again by the unique indexes (translated from
 * {@link DataIntegrityViolationException} to {@code KB_NODE_NAME_DUPLICATED}); the partial root index
 * covers the {@code parent_id IS NULL} case that the plain index cannot. Only PostgreSQL unique
 * violations (SQLSTATE 23505) are translated: FK, CHECK and ltree syntax errors stay server errors
 * instead of being reported as client duplicate-name conflicts.</p>
 */
@Service
public class NodeService {

    private static final int MAX_NAME_LENGTH = 255;

    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

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
    public KbNodeRow createNode(Long kbId, Long parentId, String name) {
        name = validateName(name);
        kbRepository.findById(kbId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge base not found"));

        String parentPath = null;
        if (parentId != null) {
            KbNodeRow parent = find(parentId);
            if (!parent.kbId().equals(kbId)) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Parent node not found in this knowledge base");
            }
            parentPath = parent.path();
        }

        if (nodeJdbc.existsSibling(kbId, parentId, name, null)) {
            throw duplicateName();
        }

        try {
            long id = nodeJdbc.insert(kbId, parentId, parentPath, name);
            return find(id);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicateName(e);
        }
    }

    @Transactional
    public KbNodeRow moveNode(Long nodeId, Long newParentId) {
        KbNodeRow node = find(nodeId);
        String oldPath = node.path();

        String newNodePath;
        if (newParentId == null) {
            newNodePath = "n" + nodeId;
        } else {
            KbNodeRow parent = find(newParentId);
            if (!parent.kbId().equals(node.kbId())) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Parent node not found in this knowledge base");
            }
            if (nodeJdbc.isDescendantOrSelf(parent.path(), oldPath)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "Cannot move a node into its own subtree");
            }
            newNodePath = parent.path() + ".n" + nodeId;
        }

        if (oldPath.equals(newNodePath)) {
            return node;
        }

        if (nodeJdbc.existsSibling(node.kbId(), newParentId, node.name(), nodeId)) {
            throw duplicateName();
        }

        try {
            nodeJdbc.moveSubtree(nodeId, oldPath, newNodePath, newParentId);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicateName(e);
        }
        return find(nodeId);
    }

    @Transactional
    public KbNodeRow renameNode(Long nodeId, String name) {
        name = validateName(name);
        KbNodeRow node = find(nodeId);
        if (nodeJdbc.existsSibling(node.kbId(), node.parentId(), name, nodeId)) {
            throw duplicateName();
        }
        try {
            nodeJdbc.updateName(nodeId, name);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicateName(e);
        }
        return find(nodeId);
    }

    @Transactional
    public void deleteNode(Long nodeId) {
        find(nodeId);
        if (nodeJdbc.countByParentId(nodeId) > 0) {
            throw new AppException(ErrorCode.NODE_NOT_EMPTY, "Node has children");
        }
        if (nodeJdbc.countDocuments(nodeId) > 0) {
            throw new AppException(ErrorCode.NODE_NOT_EMPTY, "Node has documents");
        }
        nodeRepository.deleteById(nodeId);
    }

    @Transactional(readOnly = true)
    public List<KbNodeRow> listNodes(Long kbId) {
        kbRepository.findById(kbId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge base not found"));
        return nodeJdbc.findByKbId(kbId);
    }

    /**
     * Combined rename and/or move for {@code PATCH /api/nodes/{id}}. {@code name} is null for
     * "no rename"; {@code move} distinguishes "parentId present" (including {@code null} = root)
     * from "parentId absent".
     */
    @Transactional
    public KbNodeRow updateNode(Long nodeId, String name, Long newParentId, boolean move) {
        if (name != null) {
            renameNode(nodeId, name);
        }
        if (move) {
            moveNode(nodeId, newParentId);
        }
        return find(nodeId);
    }

    private KbNodeRow find(Long id) {
        return nodeJdbc.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Node not found"));
    }

    private static AppException duplicateName() {
        return new AppException(ErrorCode.KB_NODE_NAME_DUPLICATED, "A node with this name already exists");
    }

    /**
     * Translates only PostgreSQL unique violations to {@code KB_NODE_NAME_DUPLICATED}; any other
     * integrity violation (FK, CHECK, ltree syntax, ...) is rethrown unchanged.
     *
     * <p>The pgjdbc driver is runtime-scoped, so the exception is inspected through the standard
     * {@link SQLException#getSQLState()} rather than the PostgreSQL-specific
     * {@code PSQLException}/{@code ServerErrorMessage} types.</p>
     */
    private static RuntimeException translateDuplicateName(DataIntegrityViolationException e) {
        if (hasUniqueViolation(e)) {
            return duplicateName();
        }
        return e;
    }

    private static boolean hasUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && SQLSTATE_UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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
