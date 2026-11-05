# Agent 上下线与删除（2026-11-05）

## 目标

把 Agent 的「启用/停用」明确为「在线/已下线」，并改变删除规则：

- 管理员可以把 Agent **下线**；下线后普通用户不能再新建会话，已有会话继续按锁定版本可用。
- **只有已下线的 Agent 才能删除**；在线删除返回 409。
- 删除采用**软删**：Agent 从列表与详情消失、名称可复用，但历史会话、消息、推理步骤与版本快照全部保留。
- 上下线是运行状态，不是配置变更，**不生成新版本**。

## 已确认的决策

| 决策点 | 结论 |
|---|---|
| 状态表示 | 复用 `agents.enabled`：`true` = 在线，`false` = 已下线 |
| 删除语义 | 软删 `agents.deleted_at`，保留会话与版本；不硬删、不级联清会话 |
| 版本语义 | 上下线不追加版本、不改 `current_version` |
| 操作入口 | Agent 列表页提供上线/下线；编辑页移除 `enabled` 开关 |
| 删除前置条件 | 必须先下线；否则 409 `CONFLICT` |

## 数据模型（V4）

新增迁移 `V4__agent_soft_delete.sql`，V1–V3 不动：

```sql
ALTER TABLE agents ADD COLUMN deleted_at TIMESTAMPTZ;

-- 只约束未删除的 Agent，名称在软删后可复用
DROP INDEX ux_agents_name;
CREATE UNIQUE INDEX ux_agents_name ON agents (name) WHERE deleted_at IS NULL;
```

软删行保留原名称，历史会话的 `JOIN agents` 仍能显示原名称；审计可通过 `agent_id` 区分新旧同名 Agent。

## 后端

### API

| 方法 | 路径 | 权限 | 行为 |
|---|---|---|---|
| `POST` | `/api/agents/{id}/online` | ADMIN | 置 `enabled=true`，幂等，不产生版本 |
| `POST` | `/api/agents/{id}/offline` | ADMIN | 置 `enabled=false`，幂等，不产生版本 |
| `DELETE` | `/api/agents/{id}` | ADMIN | 在线时 409；已下线时写 `deleted_at` |

`PATCH /api/agents/{id}` 不再接受 `enabled`（字段从 `UpdateAgentRequest` 移除）；更新时沿用当前状态写入，避免「改配置顺手改状态」和重复版本。

### 仓库层

- `AgentRepository.COLUMNS` 增加 `deleted_at`，`Agent` 记录增加对应字段。
- 新增 `findActiveById` / `findActiveByIdForUpdate`：过滤 `deleted_at IS NULL`。
- `findPage`、`count`、`existsByName`、`existsByNameExcluding` 全部只统计未删除行。
- `findById` **保留不过滤**，仅供 `resolveSnapshot` 重放已删 Agent 的历史会话。
- 新增 `updateEnabled(id, enabled)`：只更新 `enabled` 与 `updated_at`。
- 新增 `softDelete(id)`：只写 `deleted_at`。
- 删除 `countConversations` 的删除守卫用途；若已无其他调用点则移除。

### 服务层

- `list/get/listVersions/update/online/offline/delete` 只对未删除 Agent 生效（否则 404）。
- `delete`：未删且 `enabled=true` → 409「请先下线再删除」；否则软删。
- `createConversation`、`AgentTestRunService` 使用 `findActiveById`：已删除 Agent 不可新建会话、不可试运行。
- `resolveSnapshot` 继续用 `findById`：已删除 Agent 的历史会话仍能解析锁定的快照。
- `KnowledgeBaseService.delete` 的「被 Agent 引用」统计增加 `a.deleted_at IS NULL`，避免软删 Agent 永久占用知识库。
- `ProviderService` 的模型引用统计**不排除**软删 Agent：外键仍真实引用模型，统计必须与数据库约束一致。

## 前端

- `src/api/agents.ts`：新增 `onlineAgent` / `offlineAgent`。
- `AgentList.vue`：
  - 状态标签改为「在线 / 已下线」。
  - 操作列新增「下线 / 上线」按钮，调用专用端点后刷新列表。
  - 「删除」仅在下线状态可用；在线时禁用并提示「请先下线」。
  - 删除确认文案说明「历史会话会保留，Agent 不可恢复」。
- `AgentEdit.vue`：删除 `enabled` 表单项、表单字段与保存载荷；新建固定为在线。
- `ChatView.vue` 已按 `enabled` 过滤，无需改动；已下线 Agent 不再出现在选择器。
- `AuditList.vue` 使用的 Agent 列表只包含未删除行，符合预期。

## 测试

后端（`AgentConfigTest`）：

1. 在线 Agent 删除 → 409；下线后删除 → 200，列表与详情不再返回，`agents` 行仍在且 `deleted_at` 非空。
2. 删除后历史会话仍在，`resolveSnapshot` 仍能解析旧版本；已删 Agent 不能新建会话、不能试运行。
3. 上下线不改变 `current_version`，`agent_versions` 数量不变。
4. 下线后新建会话 400；上线后可新建。
5. 软删后可用同名新建 Agent。
6. USER 调用上线/下线 → 403。
7. `KnowledgeBaseService`：仅被软删 Agent 引用的知识库允许删除。

前端（新增 `tests/agent-list.spec.ts`）：

1. 在线行「下线」可点、「删除」禁用。
2. 已下线行「上线」可点、「删除」可点并调用 `deleteAgent`。
3. 点击下线调用 `offlineAgent` 并刷新列表。

## 文档与验收

- `docs/backend.md` §10：端点、删除前置条件、软删语义、版本不变。
- `docs/frontend.md`：Agent 列表增加上下线，编辑页不再含启停。
- `AGENTS.md`：更新 Agent 模块第 4 条与测试用例数。
- `docs/acceptance.md`：补本次上下线与删除验收记录。

## 不在范围

- 回收站 / 恢复已删除 Agent。
- 硬删或清理历史会话。
- 下线时中断正在进行的会话（已开始的会话按设计继续完成）。
- 新增 `status` 枚举或修改 `AgentSnapshot` 结构。
