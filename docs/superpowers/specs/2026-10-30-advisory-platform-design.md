# 智能咨询平台 — 设计文档

- 日期：2026-10-30
- 状态：待批准
- 规模：项目级（MVP 7.5–9 周，1 后端 + 1 前端）

## 1. 意图与为什么

做一个内部智能咨询平台：管理员维护树状知识库、接入模型提供商、把"知识库范围 + 模型 + 提示词 + 参数"组合成咨询 Agent；普通用户选择 Agent 提问，平台执行固定的多步推理流程（规划 → 检索 → 反思 → 再检索），并把每一步的推理过程实时展示给用户。

核心价值不是"又一个聊天框"，而是**可配置、可追溯、有据可查**：答案必须带引用，推理过程必须可回看，Agent 配置必须可版本化。

## 2. 目标

1. 树状知识库：任意层级节点，文档挂载到节点，Agent 可按节点子树限定检索范围。
2. 模型提供商管理：支持 Ollama 与 OpenAI 兼容接口，区分 chat / embedding 能力，可测连通性。
3. Agent 自定义：绑定多个知识库、模型、系统提示词、检索与轮数参数；配置自动版本化，支持试运行。
4. 深度思考：固定四阶段多步流程，模型自评充分性 + 最大轮数兜底，全过程持久化并 SSE 实时推送。
5. 答案溯源：每条答案附引用（文档、节点、片段、相似度），点击打开片段预览。
6. 会话审计：管理员可查看全部用户的会话与推理轨迹，用于定位知识缺口、调优知识库。
7. 一键部署：`docker compose up -d` 起全栈。

## 3. 非目标

- 不做多租户隔离。表结构预留 `tenant_id`，逻辑上单租户。
- 不做扫描件 OCR、图片理解、音视频解析。
- 不做对外的开放 API、计费、配额。
- 一期不做 rerank 模型、不做知识图谱、不做联网/工具调用。
- 不做引用到 PDF 页码的定位（一期只到片段级）。
- 不支持后端多副本横向扩容（文档任务在进程内，见 §9）。
- 一期不做用户反馈/评分，也不做自动审计 Agent（见 §22）。

## 4. 反目标

- 不是通用聊天机器人：每次问答必须绑定某个 Agent 与其知识库范围，不允许无检索空聊。
- 不允许模型凭参数知识自由作答：终答必须基于召回片段，无依据时明确回复"知识库中没有找到"。
- 不做"黑盒 Agent"：任何一次回答都要能还原出当时用的 Agent 版本、模型、召回片段和每一步耗时。
- **用户之间会话隔离**：普通用户只能看到自己的会话；ADMIN 可只读审计全部会话用于调优，但不能代替用户提问，也不能修改历史消息。

## 5. 约束

- 前端：Vue 3 + Vite + TypeScript + Pinia + Vue Router + Element Plus。
- 后端：Java 21 + Spring Boot 3.x + Spring AI 1.x（以立项时最新 GA 为准）。
- 数据库：PostgreSQL 16 + pgvector + pg_trgm + ltree。
- 对象存储：MinIO（S3 兼容）。
- 部署：Docker Compose，后端单副本。
- Ollama 默认**不**打包进 compose，通过可配置 base URL 接入；提供 compose profile 供本地联调。

## 6. 方案选型（已讨论）

### 6.1 向量检索：PostgreSQL + pgvector（已选）

对比过 Chroma + SentenceTransformer。未选 Chroma 的原因：需要额外服务与双写，文档/节点/片段/向量跨库一致性差；且向量库本身不决定检索质量。pgvector 与业务数据同库，可同事务、可 JOIN 树节点过滤、备份复用 `pg_dump`。

**检索质量不作为一期目标**，一期用"结构感知切分 + 中文 embedding + 向量与别名检索 RRF 融合 + 多查询"打底，评测与 rerank 留到二期。

### 6.2 编排实现：手写状态机（已选）

流程固定为四阶段，不引入 LangGraph4j 等工作流引擎。理由：轮数少、分支少，手写状态机可控、易调试、流式与异常处理直观。

### 6.3 服务形态：模块化单体（已选）

后端单体按领域分包，前端独立 SPA。文档解析/向量化用应用内线程池异步执行，不引入消息队列。代价是后端只能单副本运行。

## 7. 架构

```
Vue SPA ──HTTP/SSE──> Spring Boot (modular monolith)
                          ├── auth / user
                          ├── knowledge (树 / 文档 / 切分 / 向量化)
                          ├── provider (提供商 / 模型)
                          ├── agent (配置 / 版本 / 试运行)
                          ├── chat (会话 / 编排 / 推理轨迹)
                          └── common (MinIO / 加密 / 异常 / 分页)
                     ├──> PostgreSQL 16 + pgvector
                     ├──> MinIO
                     └──> Ollama / OpenAI 兼容端点
```

后端按领域分包：`auth`, `knowledge`, `provider`, `agent`, `chat`, `common`。领域内分层 `api / service / repository / domain`，禁止跨领域直接访问对方 repository。

## 8. 权限矩阵

| 能力 | ADMIN | USER |
|---|---|---|
| 知识库/节点/文档 读写 | ✅ | 只读 |
| 提供商/模型 管理 | ✅ | ❌ |
| Agent 创建/修改/版本 | ✅ | ❌ |
| Agent 列表与详情 | ✅ | ✅（只读，可选择来对话） |
| 发起会话 | ✅ | ✅ |
| 读取会话 | 全部（只读审计） | 仅自己创建的 |
| 用户管理 | ✅ | ❌ |
| 试运行 Agent | ✅ | ❌ |

- 会话查询在 repository 层按角色过滤：USER 强制 `user_id = currentUserId`；ADMIN 不过滤，但只读，不能代替用户提问或删除他人会话。
- 管理员审计入口独立（`/admin/conversations`），详情可复用对话页的只读组件。
- 容量按单公司、用户数 < 200 估算。
- 无注册接口。初始 ADMIN 在启动时按 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 创建，仅当库中不存在 ADMIN 时执行；默认值为 `admin / admin`。
- 初始 ADMIN 与管理员新建/重置密码的用户 `must_change_password = true`。未改密前，除 `GET /api/auth/me`、`POST /api/auth/change-password`、`POST /api/auth/logout` 外一律返回 403 `PASSWORD_CHANGE_REQUIRED`；改密成功后重新签发 token 并吊销该用户全部 refresh token。
- refresh token 入库可吊销：登出、改密、禁用用户时删除该用户全部 token。

## 9. 数据模型（Flyway 管理，关闭 VectorStore 自动建表）

| 表 | 关键字段 |
|---|---|
| `users` | id, username(unique), password_hash, role(ADMIN/USER), tenant_id, enabled, must_change_password, created_at |
| `refresh_tokens` | id, user_id, token_hash, expires_at, created_at, revoked_at |
| `knowledge_bases` | id, name, description, embedding_model_id, chunk_config jsonb, reindex_status(READY/REINDEXING/FAILED), created_by, created_at |
| `kb_nodes` | id, kb_id, parent_id(nullable), name, path(ltree), sort_order, created_at；唯一 (kb_id, parent_id, name) |
| `documents` | id, kb_id, node_id, filename, object_key, parsed_key, mime, size, checksum, status(PENDING/PARSING/CHUNKING/EMBEDDING/READY/FAILED/DELETED), error_msg, created_by, created_at, updated_at |
| `chunks` | id, document_id, kb_id, node_id, seq, content, token_count, embedding vector(1024), created_at；HNSW(embedding vector_cosine_ops) + GIN(content gin_trgm_ops) |
| `model_providers` | id, name, type(OLLAMA/OPENAI_COMPATIBLE), base_url, api_key_cipher, enabled, created_at |
| `models` | id, provider_id, model_name, capability(CHAT/EMBEDDING), dimension(nullable), params jsonb, enabled |
| `agents` | id, name, description, system_prompt, chat_model_id, embedding_model_id, kb_ids jsonb, node_scope jsonb, max_rounds, top_k, temperature, enabled, current_version |
| `agent_versions` | id, agent_id, version, snapshot jsonb, created_at；唯一 (agent_id, version) |
| `conversations` | id, agent_id, agent_version, user_id, title, created_at, updated_at；索引 (user_id, created_at desc)、(agent_id, created_at desc) 供审计筛选 |
| `messages` | id, conversation_id, role(USER/ASSISTANT), content, citations jsonb, status(PENDING/STREAMING/COMPLETED/FAILED/TRUNCATED), token_usage jsonb, latency_ms, created_at |
| `reasoning_steps` | id, message_id, round, phase(PLAN/RETRIEVE/REFLECT/ANSWER), payload jsonb, token_usage jsonb, latency_ms, created_at |

设计要点：

- **向量维度固定 1024**（bge-m3）。保存 embedding 模型时校验 `dimension == 1024`，不匹配直接拒绝；二期再支持多维度并存。
- **`agent_versions` 自动生成**：每次 `PATCH /agents/{id}` 在同一事务内更新配置、写入 `snapshot`、`current_version + 1`。版本列表只读，不提供"手动建版本"。
- **会话锁定版本**：创建会话时把 `current_version` 写入 `conversations.agent_version`，后续改配置不影响历史会话复现。
- **`node_scope` 按库分组**：`{ "kbId": ["nodeId", ...] }`，空数组表示该库全量。检索时拼成 `(kb_id=1 AND path <@ ...) OR (kb_id=2 AND ...)`。
- **跨库约束**：`agents.kb_ids` 可含多个库，但保存时校验这些库的 `embedding_model_id` 全部相同；`agents.embedding_model_id` 由校验结果冗余写入，用于运行期防漂移。
- **关键词检索走 `pg_trgm`**，不使用 `tsv`（PG 默认分词不支持中文，该列会是空的）。相似度用 `similarity(content, query)`，索引 `gin_trgm_ops`。
- **推理步骤是主数据**，SSE 只是实时通道；断线后靠 `GET /messages/{id}` 恢复。

### 删除与完整性规则

- 删除节点：有子节点或挂载文档时返回 `409 NODE_NOT_EMPTY`，要求先移动或清空。
- 删除知识库：被任何 Agent 的 `kb_ids` 引用时返回 `409 KB_IN_USE`。
- 删除提供商/模型：被知识库或 Agent 引用时返回 `409 MODEL_IN_USE`。
- 删除文档：软删（`status=DELETED`），异步清理 chunks 与 MinIO 对象；检索 SQL 必须 `JOIN documents` 过滤 `status != 'DELETED'`。
- `node_scope` 中指向已删除节点的 id：因节点删除被上述规则阻塞，正常路径下不会产生悬空引用；重建索引时仍做一次过滤兜底。

## 10. 文档处理流程

1. 前端向 `POST /documents/presign` 获取 MinIO 预签名 URL，直传对象存储。
2. 前端回调 `POST /documents` 提交元信息，文档状态 `PENDING`。
3. 异步 worker 拉取对象：`PARSING`（Apache Tika）→ `CHUNKING`（结构感知切分）→ `EMBEDDING`（批量调 embedding 模型）→ `READY`。
4. 任一步失败置 `FAILED` 并记录 `error_msg`，支持单文档重试与整库重建。

**重启恢复**：应用启动时把处于 `PARSING/CHUNKING/EMBEDDING` 的文档重置为 `PENDING` 并重新入队。任务幂等：按 `document_id` 先删旧 chunks 再插入，`parsed_key` 覆盖写。

切分策略：

- 优先按文档结构（Markdown 标题层级、Word 样式标题）递归切分。
- 叶子块目标 400–800 字，重叠 10–15%；表格与代码块不被截断。
- 超长块回退到 `TokenTextSplitter`。
- 每个 chunk 记录 `document_id`、`node_id`、序号，供引用定位。

解析后的 Markdown 中间产物存 MinIO（`parsed_key`），便于排查与重新切分，避免重复解析。

**重建索引**：切换 embedding 模型或调整切分配置时触发异步重建，`knowledge_bases.reindex_status = REINDEXING`，期间该库禁止上传与检索（接口返回 `KB_REINDEXING`），完成后原子恢复为 `READY`；失败置 `FAILED` 并保留错误信息。

## 11. 问答编排

```
POST /api/conversations/{id}/messages  (SSE)
  ├─ 锁定 Agent 版本快照（conversations.agent_version）
  ├─ 首帧下发 meta { messageId }
  ├─ round = 1
  ├─ PLAN      生成 1–3 个检索查询
  ├─ RETRIEVE  每个库分别向量 topK + trgm topK → RRF 融合 → 取 topN
  ├─ REFLECT   输出 { sufficient, gaps[], nextQueries[], draftAnswer }
  ├─ sufficient=false 且 round < maxRounds → round++，带 gaps 回到 RETRIEVE
  └─ 终止 → ANSWER 基于片段生成终答（强制引用编号）
```

- 结构化输出用 Spring AI 的 `BeanOutputConverter` / `.entity()` 绑定到 record；解析失败降级为"再检索一轮"，不得中断会话。
- 终止条件：`sufficient == true` 或 `round >= max_rounds`。默认 3 轮，上限 6。
- 单步超时 60s，整体 180s；超时按已有证据直接进 ANSWER，消息标记 `TRUNCATED`。
- 终答提示词要求：只用编号片段作答；每个论断后标注 `[n]`；证据不足时明确说明。
- 编排策略固定，不暴露给用户配置。
- 并发：同一会话同一时间只允许一个进行中的提问，第二次返回 `409 CONVERSATION_BUSY`。

### SSE 事件

| 事件 | 载荷 |
|---|---|
| `meta` | `{ messageId, conversationId }`（首帧，断线重连的依据） |
| `step` | `{ round, phase, status: started/finished, summary }` |
| `delta` | `{ phase, text }` 规划/反思/终答的增量文本 |
| `citations` | `{ items: [{ chunkId, documentId, docName, nodeName, snippet, score }] }` |
| `done` | `{ messageId, status, tokenUsage, latencyMs }` |
| `error` | `{ code, message, recoverable }` |

心跳 15s 一次。中断时已生成内容与已完成步骤照常落库，`messages.status` 置 `STREAMING` 或 `FAILED`；前端重连后调用 `GET /messages/{id}` 取回完整步骤。前端用 `fetch + ReadableStream` 或 `@microsoft/fetch-event-source`（`EventSource` 无法携带 Authorization 头）。

## 12. API 概览

| 域 | 端点 |
|---|---|
| auth | `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`, `POST /auth/change-password` |
| kb | `GET/POST /knowledge-bases`, `PATCH/DELETE /knowledge-bases/{id}`, `POST /knowledge-bases/{id}/reindex` |
| node | `GET /knowledge-bases/{id}/nodes`, `POST/PATCH/DELETE /nodes`, `POST /nodes/{id}/move` |
| doc | `POST /documents/presign`, `POST /documents`, `GET /documents`, `POST /documents/{id}/retry`, `DELETE /documents/{id}`, `GET /documents/{id}/chunks` |
| provider | `GET/POST /providers`, `PATCH/DELETE /providers/{id}`, `POST /providers/{id}/test`, `GET /providers/{id}/models?remote=true`, `POST /providers/{id}/models/import` |
| agent | `GET/POST /agents`, `PATCH/DELETE /agents/{id}`, `GET /agents/{id}/versions`, `POST /agents/{id}/test` |
| chat | `GET/POST /conversations`, `GET /conversations/{id}/messages`, `GET /messages/{id}`（含 steps 与 citations）, `POST /conversations/{id}/messages`（SSE） |
| admin | `GET/POST/PATCH /users`（仅 ADMIN） |
| audit | `GET /admin/conversations?userId=&agentId=&from=&to=`、`GET /admin/conversations/{id}`（仅 ADMIN） |

- 统一响应 `{ code, message, data }`；分页 `?page=&size=`；错误码细分（`KB_NODE_NAME_DUPLICATED`、`CONVERSATION_BUSY`、`KB_REINDEXING` 等）。
- 提供商连通性测试：`test` 可带 `model`；不带时 Ollama 取 `/api/tags` 首个模型、OpenAI 兼容取 `/v1/models` 首个。错误分类为 `NETWORK_UNREACHABLE` / `AUTH_FAILED` / `MODEL_NOT_FOUND` / `TIMEOUT`。
- `GET /providers/{id}/models?remote=true` 返回远端实时列表；`import` 把选中项写入本地 `models` 表并补 `dimension`，后续以本地表为准。
- 试运行 `POST /agents/{id}/test` 复用编排逻辑，轨迹只在响应中返回，**不写** `conversations/messages/reasoning_steps`。

## 13. 前端页面

1. **登录** + **强制改密**（`mustChangePassword=true` 时只能停留在改密页）
2. **知识库列表**：卡片/表格，显示文档数、状态、embedding 模型。USER 只读。
3. **知识库详情**：左侧节点树（可拖拽排序/移动），右侧文档表格（上传、状态、重试、删除），点开文档看片段预览。
4. **模型提供商**：提供商 CRUD、密钥只写不读、连通性测试、远端模型导入并补维度。
5. **Agent 配置**：基础信息、chat 模型选择、多知识库选择 + 每库节点多选、系统提示词、maxRounds/topK/temperature；版本列表（只读，可对比）；试运行抽屉展示完整四阶段轨迹。
6. **对话页**：左会话列表（仅本人），中消息流（Markdown + 引用角标），右/折叠"思考过程"面板（按轮次分组的阶段卡片、耗时与 token）。终答流式输出；点引用打开片段抽屉（片段全文 + 文档名 + 节点路径）。
7. **会话记录**（ADMIN）：按用户 / Agent / 时间筛选全部会话，查看消息、推理步骤与引用，用于定位知识缺口。
8. **用户管理**（ADMIN）。

## 14. 安全与运维

- JWT access 30min；refresh token 随机串哈希入库，有效期 7 天，可吊销。
- 密码 BCrypt；新建/重置用户与初始 admin 必须首次改密；Spring Security 方法级鉴权，矩阵见 §8。
- 提供商 API Key 用 AES-GCM 加密存库，主密钥来自环境变量 `APP_ENCRYPTION_KEY`，接口只返回掩码。
- MinIO 预签名 URL 有效期 10 分钟；bucket 私有。
- 所有写操作记 `created_by`/`updated_at`；文档软删。
- Actuator 暴露 health/metrics；日志结构化，编排日志带 `conversationId`/`messageId`。
- 对话页明确提示"会话记录可能被管理员查看，用于知识库调优"，避免隐私预期落差。
- 管理员查看会话的操作写入结构化日志（操作人、时间、会话 id），不额外建表。
- 备份：`pg_dump` + MinIO 数据卷；compose 使用命名卷。

## 15. 部署

| 服务 | 说明 |
|---|---|
| `postgres` | `pgvector/pgvector:pg16`；V1 迁移创建 `vector`、`ltree`、`pg_trgm` 扩展 |
| `minio` | 控制台 + API，启动时自动建 bucket |
| `backend` | 多阶段构建，JDK 21，单副本，健康检查 `/actuator/health` |
| `frontend` | nginx 托管 dist，反代 `/api`，SSE 关闭 `proxy_buffering` 并延长超时 |
| `ollama` | 可选 profile，仅本地联调 |

关键环境变量：`DB_*`、`MINIO_*`、`JWT_SECRET`、`APP_ENCRYPTION_KEY`、`ADMIN_USERNAME`、`ADMIN_PASSWORD`、`EMBEDDING_DIMENSION`。初始管理员默认 `admin / admin`，首次部署后应立即修改。

Flyway：`V1__init.sql` 建扩展、全表与索引，后续版本递增。

## 16. 测试策略

- 单元：编排状态机用 mock `ChatModel` 覆盖分支（充分/不充分/达上限/输出不合法/超时）。
- 集成：Testcontainers 起 PG + MinIO，覆盖上传→解析→切分→向量化→跨库检索全链路（用假 embedding 模型产出固定向量）。
- 权限：USER 访问他人会话、写配置接口必须 403；ADMIN 读他人会话也必须 404/403。
- 前端：Vitest 覆盖 SSE 解析、引用渲染、树操作；关键流程可用 Playwright（非硬性）。
- 检索质量评测（recall@k、引用命中率）留二期，需 50–100 条真实问题作为评测集。

## 17. 验收标准

1. 知识库树支持任意层级增删改与移动，同级重名被拒绝，非空节点删除返回 `NODE_NOT_EMPTY`。
2. 上传 10 页 PDF，2 分钟内状态变 `READY`，片段可在前端预览；后端重启后中间态文档能自动恢复。
3. 提供商连通性测试能区分网络不可达、鉴权失败、模型不存在三类错误。
4. Agent 绑定两个知识库的不同节点子树，检索结果不出现范围外文档（SQL 断言 + 接口验证）。
5. 绑定的两个知识库 embedding 模型不一致时，保存 Agent 返回明确错误。
6. 一次提问产生 ≥2 条 `reasoning_steps`，前端按阶段实时展示。
7. `maxRounds=1` 时只执行一轮；`maxRounds=3` 且证据不足时跑满 3 轮并标记。
8. 答案至少含 1 条引用，点击打开片段抽屉并显示来源文档与节点路径。
9. 修改 Agent 配置自动产生新版本，历史会话仍用旧版本快照复现。
10. USER 无法读取他人会话（404），无法调用任何写配置接口（403）；ADMIN 能按用户和 Agent 筛选并查看任意会话的完整消息与推理步骤。
11. 初始 admin 首次登录后被强制跳转改密，改密前访问业务接口返回 403 `PASSWORD_CHANGE_REQUIRED`；改密后恢复正常并可访问。
12. 切换 embedding 模型后该库进入 `REINDEXING`，期间上传与检索被明确拒绝，完成后恢复。
13. `docker compose up -d` 后全栈可用，SSE 不被 nginx 缓冲。

## 18. 假设（评审时可推翻）

- 单租户、单公司内部部署，用户数 < 200；`tenant_id` 预留不启用。
- 会话与推理步骤长期保留，暂不做归档，数据量按 200 人规模估算。
- 文档类型 PDF / Word / Markdown / TXT，单文件上限 50MB。
- Ollama 部署在 compose 之外，通过 base URL 接入。
- 终答一期即逐字流式输出。
- 嵌入维度固定 1024。

## 19. 风险

| 风险 | 应对 |
|---|---|
| PDF 表格/多栏解析质量差 | 保留 parsed Markdown 便于人工核查；二期引入专项解析 |
| 中文关键词检索弱 | 一期 `pg_trgm`，二期评估 zhparser / OpenSearch |
| 跨库检索分数不可比 | 各库 embedding 模型强制一致，RRF 只依赖名次不依赖原始分数 |
| 多轮推理耗时长、成本高 | maxRounds 硬上限 + 进度提示 + 超时降级 |
| 跨机 Ollama 延迟/不可用 | 单步超时 + 明确错误码 + 连通性测试前置 |
| SSE 断线 | 首帧下发 messageId，步骤已落库，重连拉消息详情 |
| 后端重启丢任务 | 启动扫描中间态文档并重新入队，任务幂等 |
| 单副本无法扩容 | 一期明确约束；扩容前需把文档任务迁出进程 |

## 20. 工作量

MVP 7.5–9 周（1 后端 + 1 前端）：

| 模块 | 估时 |
|---|---|
| 知识库树 + 文档解析/切分/向量化 + 重建 | 2.5 周 |
| 模型提供商 + 远端导入 + 连通性测试 | 4 天 |
| Agent 配置（跨库/版本/试运行） | 1 周 |
| 问答编排 + SSE + 步骤落库 + 引用 | 2 周 |
| 前端八个页面（含会话审计） | 3 周 |
| 权限、部署、联调 | 1.5 周 |

## 21. 留给构建者的决定

- UI 视觉风格与组件细节。
- 具体提示词措辞与 few-shot。
- HNSW 参数、RRF 常数、topK 默认值的调参。
- 线程池大小、批量 embedding 的批大小。
- 是否需要 i18n（一期按中文单语）。

## 22. 后续迭代方向（不在本期）

- **自动审计 Agent**：异步读取会话的消息、四阶段步骤与引用，识别答非所问、引用不匹配、知识缺口，输出审计结论与知识库改进建议。预计需要 `audit_runs` / `audit_findings` 两张表，以及手动/定时两种触发方式。本期只保证会话数据（消息、`reasoning_steps`、`citations`、token 与耗时）完整落库、可被消费，不建表不做 UI。
- 检索质量评测与 rerank（见 §6.1）。
- 用户反馈（赞/踩 + 原因标签），届时可作为自动审计的辅助信号。
