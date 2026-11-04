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
 * {@code kb_nodes} 上的树操作。路径是形如 {@code n{id}} 的 {@code ltree} 标签：根节点为 {@code n{id}}，
 * 子节点为 {@code parent.path.n{id}}。
 *
 * <p>所有节点读写都经过 {@link KbNodeJdbcRepository}，返回 {@link KbNodeRow} 投影。用 JDBC 写完再用 JPA
 * 的 {@code findById} 会拿到共享一级缓存里的旧实体，因此响应一律基于一次全新的 JDBC 回读构造；
 * JPA 仓储只负责删除实体这一件事。</p>
 *
 * <p>同级重名先做前置检查，再由唯一索引兜底（{@link DataIntegrityViolationException} 被翻译成
 * {@code KB_NODE_NAME_DUPLICATED}）；根节点因为 {@code parent_id IS NULL}，普通唯一索引覆盖不到，
 * 需要一个部分索引。只翻译 PostgreSQL 的唯一冲突（SQLSTATE 23505）：外键、CHECK、ltree 语法错误仍按
 * 服务端错误上报，不能被包装成「名称重复」这种客户端错误。</p>
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

    /** 新建节点并回填 ltree 路径。 */
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

    /** 移动节点，含「不能移动到自身子树」的校验。 */
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

    /** 重命名节点，同级不可重名。 */
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

    /** 删除叶子节点；有子节点或仍有文档时拒绝。 */
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

    /** 列出知识库的全部节点，父节点排在子节点之前。 */
    @Transactional(readOnly = true)
    public List<KbNodeRow> listNodes(Long kbId) {
        kbRepository.findById(kbId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge base not found"));
        return nodeJdbc.findByKbId(kbId);
    }

    /**
     * {@code PATCH /api/nodes/{id}} 的重命名与移动，二合一处理。
     *
     * <p>{@code name} 为 {@code null} 表示不改名；{@code move} 用来区分「显式传了 parentId」
     * （其中 {@code null} 表示移到根）与「根本没传 parentId」——两者的语义完全不同，靠字段本身无法区分。</p>
     *
     * @param id    节点主键
     * @param name  新名称，{@code null} 表示不变
     * @param move  是否调整父节点
     * @param parentId 目标父节点，{@code null} 表示根
     * @return 更新后的节点
     */
    /** 重命名与移动的组合操作。 */
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
     * 只把 PostgreSQL 的唯一冲突翻译成 {@code KB_NODE_NAME_DUPLICATED}，其余完整性错误
     * （外键、CHECK、ltree 语法等）原样抛出。
     *
     * <p>pgjdbc 是 runtime 作用域依赖，因此用标准的 {@link SQLException#getSQLState()} 判断，
     * 而不是引用 PostgreSQL 特有的 {@code PSQLException}/{@code ServerErrorMessage} 类型。</p>
     *
     * @param e 捕获到的数据完整性异常
     * @return 是同级重名返回 {@code true}
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
