# Agent 上下线与删除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Agent 支持上线/下线，并且只有已下线的 Agent 才能被软删；历史会话与版本快照保留。

**Architecture:** 复用 `agents.enabled` 表示在线状态；新增 `agents.deleted_at` 做软删。上下线走专用端点，只改状态不产生版本；删除只写 `deleted_at`，不触碰 `conversations` / `agent_versions`。读取路径分两类：管理操作只认未删除行，历史会话重放仍能读到已删除行。

**Tech Stack:** Java 21 / Spring Boot 3.4 / JdbcTemplate / Flyway / PostgreSQL；Vue 3 + TypeScript + Element Plus + Vitest。

**Spec:** `docs/superpowers/specs/2026-11-05-agent-lifecycle.md`

## Global Constraints

- V1–V3 迁移已发布，**一个字节都不能改**；所有 schema 变更只写 `V4__agent_soft_delete.sql`。
- 响应体统一 `{code,message,data}`；权限保持 ADMIN 写、USER 只读。
- 所有新增注释与用户可见文案用中文。
- 不新增第三方依赖；不修改 `AgentSnapshot` 的字段名。
- 后端测试需要 JDK 21：`$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'`。
- 后端集成测试共享同一个 Testcontainers 数据库，夹具用 `agent10-` 前缀并在 `@AfterEach` 自清理。

## Review Focus

- 已删除 Agent 的历史会话仍能列出、打开、重放锁定版本（`ConversationRepository` / `AuditRepository` 的 `JOIN agents` 不能过滤 `deleted_at`）。
- 在线 Agent 删除必须 409，且不写 `deleted_at`。
- 上下线后 `currentVersion` 与 `agent_versions` 行数不变。
- 软删后同名 Agent 可以重新创建；并发创建由部分唯一索引兜底。
- 已删除 Agent 不能新建会话、不能试运行，但旧会话可以继续。
- 只被软删 Agent 引用的知识库可以删除；模型引用统计仍按真实外键计算。

---

### Task 1: V4 软删字段与仓库层过滤

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__agent_soft_delete.sql`
- Modify: `backend/src/main/java/com/advisory/agent/repo/AgentRepository.java`
- Test: `backend/src/test/java/com/advisory/agent/AgentConfigTest.java`

**Interfaces:**
- Consumes: 现有 `AgentRepository.findById(long) : Optional<Agent>`
- Produces:
  - `AgentRepository.findActiveById(long id) : Optional<Agent>`
  - `AgentRepository.findActiveByIdForUpdate(long id) : Optional<Agent>`
  - `AgentRepository.findPage(int limit, int offset)` 只返回 `deleted_at IS NULL`
  - `AgentRepository.count()`、`existsByName(String)`、`existsByNameExcluding(String, long)` 只统计未删除行

- [ ] **Step 1: 写失败测试**

在 `AgentConfigTest` 注入仓库并新增测试（同时补充 `import com.advisory.agent.repo.AgentRepository;`、`assertFalse`、`assertTrue`）：

```java
@Autowired
private AgentRepository agentRepository;

@Test
void softDeletedAgentsAreHiddenFromReadsAndReleaseTheirName() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    String name = "agent10-a-" + UUID.randomUUID();
    long agentId = createAgent(name, List.of(kbId), Map.of(), "prompt");

    assertTrue(agentRepository.findActiveById(agentId).isPresent());
    jdbc.update("UPDATE agents SET deleted_at = now() WHERE id = ?", agentId);

    assertTrue(agentRepository.findActiveById(agentId).isEmpty());
    assertTrue(agentRepository.findById(agentId).isPresent(),
            "历史会话重放仍需要读到已删除的行");

    String listed = mockMvc.perform(get("/api/agents")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    List<Integer> ids = JsonPath.read(listed, "$.data.content[*].id");
    assertFalse(ids.contains((int) agentId));

    long reused = createAgent(name, List.of(kbId), Map.of(), "prompt again");
    assertTrue(reused > 0, "软删后名称必须可以复用");
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'; mvn -f backend/pom.xml test "-Dtest=AgentConfigTest#softDeletedAgentsAreHiddenFromReadsAndReleaseTheirName"`
Expected: 编译失败或找不到 `findActiveById` / `deleted_at` 列。

- [ ] **Step 3: 创建 V4 迁移**

`V4__agent_soft_delete.sql`：

```sql
-- Agent 软删：历史会话与版本快照必须保留，因此不物理删除 agents 行。
ALTER TABLE agents ADD COLUMN deleted_at TIMESTAMPTZ;

-- 唯一性只约束未删除的 Agent，名称在软删后可复用。
DROP INDEX ux_agents_name;
CREATE UNIQUE INDEX ux_agents_name ON agents (name) WHERE deleted_at IS NULL;
```

- [ ] **Step 4: 实现仓库层过滤**

在 `AgentRepository` 中：

```java
/** 读取未删除的 Agent；管理操作与新建会话都走这里。 */
public Optional<Agent> findActiveById(long id) {
    return jdbc.query("SELECT " + COLUMNS + " FROM agents WHERE id = ? AND deleted_at IS NULL",
                    rowMapper, id)
            .stream()
            .findFirst();
}

/** 在事务期间锁住未删除的行，供状态切换与删除使用。 */
public Optional<Agent> findActiveByIdForUpdate(long id) {
    return jdbc.query("SELECT " + COLUMNS
                    + " FROM agents WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                    rowMapper, id)
            .stream()
            .findFirst();
}
```

`findPage` 改为 `WHERE deleted_at IS NULL ORDER BY id`；`count`、`existsByName`、`existsByNameExcluding` 的 `WHERE` 都追加 `deleted_at IS NULL`。`findById` 保持不过滤，并补注释「仅供历史会话重放」。

- [ ] **Step 5: 运行测试，确认通过**

Run: `$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'; mvn -f backend/pom.xml test "-Dtest=AgentConfigTest"`
Expected: BUILD SUCCESS，所有 `AgentConfigTest` 用例通过。

- [ ] **Step 6: 提交**

```powershell
git add backend/src/main/resources/db/migration/V4__agent_soft_delete.sql backend/src/main/java/com/advisory/agent/repo/AgentRepository.java backend/src/test/java/com/advisory/agent/AgentConfigTest.java
git commit -m "feat(agent): add soft-delete column and active-row repository reads"
```

---

### Task 2: 上线/下线端点与版本不变

**Files:**
- Modify: `backend/src/main/java/com/advisory/agent/repo/AgentRepository.java`
- Modify: `backend/src/main/java/com/advisory/agent/service/AgentService.java`
- Modify: `backend/src/main/java/com/advisory/agent/api/AgentController.java`
- Modify: `backend/src/main/java/com/advisory/agent/api/dto/UpdateAgentRequest.java`
- Test: `backend/src/test/java/com/advisory/agent/AgentConfigTest.java`

**Interfaces:**
- Consumes: Task 1 的 `findActiveById` / `findActiveByIdForUpdate`
- Produces:
  - `AgentRepository.updateEnabled(long id, boolean enabled) : void`
  - `AgentService.setEnabled(Long id, boolean enabled) : AgentResponse`
  - `AgentService.online(Long id) : AgentResponse`
  - `AgentService.offline(Long id) : AgentResponse`
  - `POST /api/agents/{id}/online`、`POST /api/agents/{id}/offline` → `ApiResponse<AgentResponse>`

- [ ] **Step 1: 写失败测试**

在 `AgentConfigTest` 新增：

```java
@Test
void lifecycleChangesDoNotCreateVersions() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");

    mockMvc.perform(post("/api/agents/" + agentId + "/offline")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.currentVersion").value(1));
    assertEquals(List.of(1), versions(agentId));

    mockMvc.perform(post("/api/agents/" + agentId + "/online")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.currentVersion").value(1));
    assertEquals(List.of(1), versions(agentId));
}

@Test
void offlineAgentCannotStartNewConversationUntilOnline() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");

    mockMvc.perform(post("/api/agents/" + agentId + "/offline")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk());

    mockMvc.perform(post("/api/conversations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentId\":" + agentId + ",\"title\":\"offline\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40000));

    mockMvc.perform(post("/api/agents/" + agentId + "/online")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk());

    mockMvc.perform(post("/api/conversations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentId\":" + agentId + ",\"title\":\"online\"}"))
            .andExpect(status().isCreated());
}

@Test
void patchCannotChangeEnabledAndStillCreatesExactlyOneVersion() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");

    patchAgent(agentId, patchBody(Map.of("enabled", false, "topK", 30)));

    mockMvc.perform(get("/api/agents/" + agentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.topK").value(30))
            .andExpect(jsonPath("$.data.currentVersion").value(2));
}

@Test
void userCannotToggleAgentLifecycle() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");

    mockMvc.perform(post("/api/agents/" + agentId + "/offline")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(40300));
    mockMvc.perform(post("/api/agents/" + agentId + "/online")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(40300));
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'; mvn -f backend/pom.xml test "-Dtest=AgentConfigTest#lifecycleChangesDoNotCreateVersions"`
Expected: 404/405，端点不存在。

- [ ] **Step 3: 实现仓库与 DTO**

`AgentRepository` 新增：

```java
/** 只切换在线状态并刷新时间，不触碰版本号。 */
public void updateEnabled(long id, boolean enabled) {
    jdbc.update("UPDATE agents SET enabled = ?, updated_at = now()"
            + " WHERE id = ? AND deleted_at IS NULL", enabled, id);
}
```

`UpdateAgentRequest` 删除 `Boolean enabled` 字段（保留其余字段与注释中的 `enabled` 提及改为「状态由专用端点维护」）。

- [ ] **Step 4: 实现服务层**

`AgentService`：

```java
/** 上线/下线：幂等，只改状态，不追加版本。 */
@Transactional
public AgentResponse setEnabled(Long id, boolean enabled) {
    Agent current = agents.findActiveByIdForUpdate(id)
            .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 不存在"));
    if (current.enabled() != enabled) {
        agents.updateEnabled(id, enabled);
    }
    return AgentResponse.from(agents.findActiveById(id)
            .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 不存在")));
}

public AgentResponse online(Long id) { return setEnabled(id, true); }
public AgentResponse offline(Long id) { return setEnabled(id, false); }
```

同时：

- `get`、`listVersions`、`update` 的 Agent 读取改为 `findActiveById` / `findActiveByIdForUpdate`；`requireAgent` 改为 `findActiveById`。
- `update` 中 `boolean enabled = current.enabled();`，删除 `body.enabled()` 分支。
- `create` 保持不变（`CreateAgentRequest.enabled` 默认 true；新建即在线）。

- [ ] **Step 5: 实现控制器端点**

`AgentController` 新增：

```java
/** 上线：只改状态，不生成新版本。 */
@PostMapping("/api/agents/{id}/online")
@PreAuthorize("hasRole('ADMIN')")
public ApiResponse<AgentResponse> online(@PathVariable Long id) {
    return ApiResponse.ok(service.online(id));
}

/** 下线：普通用户不能再新建会话，已有会话继续可用。 */
@PostMapping("/api/agents/{id}/offline")
@PreAuthorize("hasRole('ADMIN')")
public ApiResponse<AgentResponse> offline(@PathVariable Long id) {
    return ApiResponse.ok(service.offline(id));
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'; mvn -f backend/pom.xml test "-Dtest=AgentConfigTest"`
Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```powershell
git add backend/src/main/java/com/advisory/agent backend/src/test/java/com/advisory/agent/AgentConfigTest.java
git commit -m "feat(agent): add online/offline endpoints without version bumps"
```

---

### Task 3: 下线后软删与历史会话保留

**Files:**
- Modify: `backend/src/main/java/com/advisory/agent/repo/AgentRepository.java`
- Modify: `backend/src/main/java/com/advisory/agent/service/AgentService.java`
- Modify: `backend/src/main/java/com/advisory/chat/service/ChatService.java`
- Modify: `backend/src/main/java/com/advisory/agent/service/AgentTestRunService.java`
- Modify: `backend/src/main/java/com/advisory/knowledge/service/KnowledgeBaseService.java`
- Test: `backend/src/test/java/com/advisory/agent/AgentConfigTest.java`

**Interfaces:**
- Consumes: Task 1 的 `findActiveById` / `findActiveByIdForUpdate`；Task 2 的 `offline`
- Produces:
  - `AgentRepository.softDelete(long id) : void`
  - `AgentService.delete(Long id)`：未删除且在线 → 409；已下线 → 写 `deleted_at`
  - `AgentService.resolveSnapshot(Long, int)` 仍可解析已删除 Agent 的版本

- [ ] **Step 1: 写失败测试**

删除旧的 `deleteIsBlockedWhileConversationsExist`，替换为：

```java
@Test
void deleteRequiresOfflineAndKeepsConversationHistory() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");
    long userId = jdbc.queryForObject(
            "SELECT id FROM users WHERE username LIKE 'agent10-user-%' LIMIT 1", Long.class);
    long conversationId = jdbc.queryForObject("""
            INSERT INTO conversations (agent_id, agent_version, user_id, title)
            VALUES (?, 1, ?, 'history') RETURNING id
            """, Long.class, agentId, userId);

    mockMvc.perform(delete("/api/agents/" + agentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(40900));

    mockMvc.perform(post("/api/agents/" + agentId + "/offline")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk());

    mockMvc.perform(delete("/api/agents/" + agentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

    mockMvc.perform(get("/api/agents/" + agentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40400));

    String agentList = mockMvc.perform(get("/api/agents")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    List<Integer> agentIds = JsonPath.read(agentList, "$.data.content[*].id");
    assertFalse(agentIds.contains((int) agentId), "软删后列表不再返回");

    String conversationList = mockMvc.perform(get("/api/conversations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    List<Integer> conversationIds = JsonPath.read(conversationList, "$.data.content[*].id");
    assertTrue(conversationIds.contains((int) conversationId),
            "历史会话仍出现在会话列表，JOIN agents 不能过滤 deleted_at");

    assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM agents WHERE id = ?", Integer.class, agentId),
            "软删保留 agents 行");
    assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM agents WHERE id = ? AND deleted_at IS NOT NULL",
            Integer.class, agentId));
    assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM conversations WHERE id = ?", Integer.class, conversationId),
            "历史会话不能被级联删除");
    assertEquals("prompt", agentService.resolveSnapshot(agentId, 1).systemPrompt(),
            "已删除 Agent 的历史会话仍能重放");
}

@Test
void deletedAgentCannotStartConversationOrTestRun() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");
    mockMvc.perform(post("/api/agents/" + agentId + "/offline")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk());
    mockMvc.perform(delete("/api/agents/" + agentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk());

    mockMvc.perform(post("/api/conversations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentId\":" + agentId + ",\"title\":\"deleted\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40400));

    mockMvc.perform(post("/api/agents/" + agentId + "/test")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"还在吗\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40400));
}

@Test
void knowledgeBaseReferencedOnlyByDeletedAgentCanBeDeleted() throws Exception {
    long kbId = createKb(firstEmbeddingModelId);
    long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");

    mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(40903));

    mockMvc.perform(post("/api/agents/" + agentId + "/offline")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk());
    mockMvc.perform(delete("/api/agents/" + agentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk());

    mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'; mvn -f backend/pom.xml test "-Dtest=AgentConfigTest#deleteRequiresOfflineAndKeepsConversationHistory"`
Expected: 删除后 `GET` 仍返回 200（尚未软删过滤）或删除顺序断言失败。

- [ ] **Step 3: 实现软删与守卫**

`AgentRepository` 新增：

```java
/** 软删：只写 deleted_at，保留会话、消息、版本与历史配置。 */
public void softDelete(long id) {
    jdbc.update("UPDATE agents SET deleted_at = now()"
            + " WHERE id = ? AND deleted_at IS NULL", id);
}
```

移除不再使用的 `countConversations` 与 `findByIdForUpdate`（若没有其他调用点）。

`AgentService.delete`：

```java
/** 只有已下线的 Agent 才能删除；删除是软删，历史会话保留。 */
@Transactional
public void delete(Long id) {
    Agent current = agents.findActiveByIdForUpdate(id)
            .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 不存在"));
    if (current.enabled()) {
        throw new AppException(ErrorCode.CONFLICT, "请先下线再删除");
    }
    agents.softDelete(id);
}
```

`resolveSnapshot` 改用 `agents.findById`（不过滤软删），保留版本缺失时 404。

- [ ] **Step 4: 下游读取路径改为 active**

- `ChatService.createConversation`：`agents.findById` → `agents.findActiveById`，不存在的文案保持「Agent 不存在」。
- `AgentTestRunService.run`：同样改为 `findActiveById`。
- `KnowledgeBaseService.delete` 的引用统计在 `WHERE` 前加 `AND a.deleted_at IS NULL`：

```sql
FROM agents a
CROSS JOIN LATERAL jsonb_array_elements_text(a.kb_ids) AS e(elem)
WHERE a.deleted_at IS NULL
  AND e.elem ~ '^-?[0-9]+$' AND e.elem::bigint = ?
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'; mvn -f backend/pom.xml test "-Dtest=AgentConfigTest"`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```powershell
git add backend/src/main/java backend/src/test/java/com/advisory/agent/AgentConfigTest.java
git commit -m "feat(agent): require offline before soft delete and keep history"
```

---

### Task 4: 前端上下线与删除可用性

**Files:**
- Modify: `frontend/src/api/agents.ts`
- Modify: `frontend/src/pages/agents/AgentList.vue`
- Modify: `frontend/src/pages/agents/AgentEdit.vue`
- Create: `frontend/tests/agent-list.spec.ts`

**Interfaces:**
- Consumes: Task 2 的 `POST /api/agents/{id}/online|offline`；Task 3 的删除语义
- Produces:
  - `onlineAgent(id: number): Promise<Agent>`
  - `offlineAgent(id: number): Promise<Agent>`
  - `AgentInput` 不再包含 `enabled`

- [ ] **Step 1: 写失败测试**

新建 `frontend/tests/agent-list.spec.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push: vi.fn() }) }
})

const agents = [
  { id: 1, name: '在线助手', description: null, systemPrompt: 'p', chatModelId: 9, embeddingModelId: 8, kbIds: [1], nodeScope: {}, maxRounds: 3, topK: 20, temperature: 0.7, enabled: true, currentVersion: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { id: 2, name: '已下线助手', description: null, systemPrompt: 'p', chatModelId: 9, embeddingModelId: 8, kbIds: [1], nodeScope: {}, maxRounds: 3, topK: 20, temperature: 0.7, enabled: false, currentVersion: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }
]

vi.mock('../src/api/agents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/agents')>()
  return {
    ...actual,
    listAgents: vi.fn(() => Promise.resolve({ content: agents, page: 0, size: 10, total: 2, totalPages: 1 })),
    offlineAgent: vi.fn(() => Promise.resolve(agents[0])),
    onlineAgent: vi.fn(() => Promise.resolve(agents[1])),
    deleteAgent: vi.fn(() => Promise.resolve())
  }
})

async function mountList() {
  const module = await import('../src/pages/agents/AgentList.vue')
  const wrapper = mount(module.default, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('agent lifecycle actions', () => {
  beforeEach(() => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  })

  it('disables delete for an online agent and enables it once offline', async () => {
    const wrapper = await mountList()
    const rows = wrapper.findAll('.el-table__body tr')

    expect(rows[0].find('[data-test="agent-delete"]').attributes('disabled')).toBeDefined()
    expect(rows[1].find('[data-test="agent-delete"]').attributes('disabled')).toBeUndefined()
    expect(rows[0].find('[data-test="agent-toggle"]').text()).toBe('下线')
    expect(rows[1].find('[data-test="agent-toggle"]').text()).toBe('上线')
  })

  it('calls offlineAgent when taking an online agent down', async () => {
    const api = await import('../src/api/agents')
    const wrapper = await mountList()

    await wrapper.findAll('.el-table__body tr')[0].find('[data-test="agent-toggle"]').trigger('click')
    await flushPromises()

    expect(api.offlineAgent).toHaveBeenCalledWith(1)
  })

  it('deletes an offline agent after confirmation', async () => {
    const api = await import('../src/api/agents')
    const wrapper = await mountList()

    await wrapper.findAll('.el-table__body tr')[1].find('[data-test="agent-delete"]').trigger('click')
    await flushPromises()

    expect(api.deleteAgent).toHaveBeenCalledWith(2)
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `npm --prefix frontend run test -- tests/agent-list.spec.ts`
Expected: FAIL，`offlineAgent` / `onlineAgent` 未导出，按钮不存在。

- [ ] **Step 3: 实现 API 封装**

`frontend/src/api/agents.ts` 新增：

```ts
/** ADMIN-only：上线/下线只改状态，不产生新版本。 */
export function onlineAgent(id: number): Promise<Agent> {
  return http.post(`/api/agents/${id}/online`)
}

export function offlineAgent(id: number): Promise<Agent> {
  return http.post(`/api/agents/${id}/offline`)
}
```

从 `AgentInput` 删除 `enabled: boolean`。

- [ ] **Step 4: 实现列表页**

`AgentList.vue`：

- 导入 `offlineAgent`、`onlineAgent`。
- 新增 `toggle(row)`：确认后调用对应端点，`ElMessage.success(row.enabled ? '已下线' : '已上线')`，再 `await load()`。
- 删除确认文案改为 `确认删除 Agent「${row.name}」？删除后不可恢复，历史会话会保留。`
- 状态标签文案改为「在线 / 已下线」。
- 操作列宽改为 230，加入上线/下线按钮；删除按钮 `:disabled="row.enabled"` 并带 `:title="row.enabled ? '请先下线后再删除' : ''"`。

- [ ] **Step 5: 编辑页移除启停开关**

`AgentEdit.vue`：

- 删除 `form.enabled` 字段、`applyAgent` 中的 `form.enabled = agent.enabled`、`save` 载荷中的 `enabled`。
- 删除模板中的「启用」`el-form-item`（约 214–217 行）。
- 新建页不再传 `enabled`，后端默认 true。

- [ ] **Step 6: 运行前端测试与类型检查**

Run: `npm --prefix frontend run test -- tests/agent-list.spec.ts`
Expected: 3 个用例通过。

Run: `npm --prefix frontend run build`
Expected: exit 0（`vue-tsc --noEmit` 通过）。

- [ ] **Step 7: 提交**

```powershell
git add frontend/src/api/agents.ts frontend/src/pages/agents/AgentList.vue frontend/src/pages/agents/AgentEdit.vue frontend/tests/agent-list.spec.ts
git commit -m "feat(agent-ui): add online/offline actions and gate delete on offline"
```

---

### Task 5: 文档更新与全量验证

**Files:**
- Modify: `docs/backend.md`
- Modify: `docs/frontend.md`
- Modify: `AGENTS.md`
- Modify: `docs/acceptance.md`

**Interfaces:**
- Consumes: Task 1–4 的最终行为
- Produces: 与实现一致的文档与验收记录

- [ ] **Step 1: 更新后端文档**

`docs/backend.md` §10：

- 新增 `POST /api/agents/{id}/online`、`POST /api/agents/{id}/offline` 说明（ADMIN、幂等、不产生版本）。
- 把「删除仍有关联会话的 Agent 也是 409（先归档会话）」改为「在线 Agent 删除 409（先下线）；已下线 Agent 软删，`deleted_at` 置位，历史会话与版本快照保留」。
- 说明 `PATCH` 不再接受 `enabled`。
- 说明 `KnowledgeBaseService` 的引用统计排除软删 Agent，而模型引用统计仍按真实外键计算。

- [ ] **Step 2: 更新前端文档**

`docs/frontend.md` 组件表：`agents/AgentList` 改为「Agent 列表、上下线、删除」；`agents/AgentEdit` 去掉「启停」相关表述。

- [ ] **Step 3: 更新 AGENTS.md**

- Agent 模块要点第 4 条改为：在线不可删，需先下线；删除是软删，会话与版本保留。
- 命令注释中的后端/前端用例数改为本次实际输出值。
- 如「容易写错的地方」涉及 `enabled`，补一句「上下线不产生版本」。

- [ ] **Step 4: 追加验收记录**

在 `docs/acceptance.md` 末尾追加一节，记录：

- 在线删除 409、下线后可删。
- 删除后 Agent 从列表消失、历史会话仍可读。
- 上下线不产生新版本。
- 软删后名称可复用。
- 后端/前端测试与构建通过。

- [ ] **Step 5: 全量验证**

Run: `$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'; mvn -f backend/pom.xml test`
Expected: BUILD SUCCESS，全部用例通过。

Run: `npm --prefix frontend run test`
Expected: 全部用例通过。

Run: `npm --prefix frontend run build`
Expected: exit 0。

- [ ] **Step 6: 提交**

```powershell
git add docs/backend.md docs/frontend.md AGENTS.md docs/acceptance.md docs/superpowers/specs/2026-11-05-agent-lifecycle.md docs/superpowers/plans/2026-11-05-agent-lifecycle.md
git commit -m "docs(agent): document lifecycle and soft-delete behavior"
```
