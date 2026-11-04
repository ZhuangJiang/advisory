# 验收记录（spec §17）

- 验收环境：Windows 11 + Docker Desktop（WSL2 后端），单机 `docker compose` 全栈；后端 JDK 21，前端 Node 22。
- 验收日期：2026-11-04。
- 自动化基线：后端 `mvn -f backend/pom.xml test` **197 用例全绿**；前端 `npm --prefix frontend run test` **37 用例全绿**，`npm run build` 通过。
- 端到端：`scripts/smoke.ps1` 通过 nginx（`http://localhost`）跑完整链路，输出
  `SMOKE OK messageId=4 status=COMPLETED steps=4 citations=1 answerChars=169`。
  **限制**：该次冒烟使用的是 `scripts/fake-model-server.mjs`（OpenAI 兼容桩，1024 维确定性向量），
  用于验证链路与协议，不验证模型回答质量。接入真实 Ollama / OpenAI 兼容端点后需重跑同一脚本确认。

> 判定口径：**通过** = 有自动化用例或本次实测支撑；**部分通过** = 逻辑有自动化覆盖，但 spec 中的具体规模/耗时未实测。

| # | 验收项 | 验证方式 | 实际结果 | 结论 |
|---|--------|----------|----------|------|
| 1 | 知识库树任意层级增删改移动，同级重名拒绝，非空节点删除 `NODE_NOT_EMPTY` | `mvn -f backend/pom.xml test -Dtest=NodeTreeTest`（15 用例） | 全绿：移动/重名 40901/非空 40902 均有断言 | 通过 |
| 2 | 上传 10 页 PDF，2 分钟内 `READY`，片段可预览；重启后中间态自动恢复 | `DocumentUploadTest`（12）、`ChunkBrowseTest`、`IngestRecoveryTest`（3）；冒烟上传 txt 实测 | 冒烟文档 ~4s 由 `PENDING` 变 `READY`，`GET /documents/{id}/chunks` 返回 1 条；重启恢复由 `IngestRecoveryTest` 覆盖（PARSING/CHUNKING/EMBEDDING 三态重新入队且不产生重复 chunks） | 部分通过：未用 10 页 PDF 实测耗时 |
| 3 | 连通性测试区分网络不可达 / 鉴权失败 / 模型不存在 | `ProviderProbeClassifyTest`（10 用例） | 三类错误码 `NETWORK_UNREACHABLE` / `AUTH_FAILED` / `MODEL_NOT_FOUND` 分别断言 | 通过 |
| 4 | 两库不同节点子树，检索不越界 | `RetrievalTest`（18 用例） | 范围外 chunk 不出现在结果中（SQL + 接口双层断言）；悬空 nodeId 被忽略 | 通过 |
| 5 | 两库 embedding 模型不一致时保存 Agent 报错 | `AgentConfigTest#rejectsMixedEmbeddingModelsAcrossKbs` | 明确错误信息，含冲突知识库 | 通过 |
| 6 | 一次提问 ≥2 条 `reasoning_steps`，前端按阶段实时展示 | 冒烟 `steps=4`（PLAN/RETRIEVE/REFLECT/ANSWER，`maxRounds=2` 且首轮即 sufficient）；`ChatAccessTest#completedAnswerAndStepsSurviveDisconnect`；前端 `chat-view.spec.ts` 按 round 分组 | SSE 帧序 `meta → step×4 → delta → citations → done`，页面按「第 1 轮 / 第 2 轮」分组渲染 | 通过 |
| 7 | `maxRounds=1` 只跑一轮；`maxRounds=3` 证据不足跑满并标记 | `ChatOrchestratorTest#stopsWhenReflectSaysSufficient`、`#runsToMaxRoundsWhenNeverSufficient` | 轮次与 `TRUNCATED` 标记均断言；非法 JSON 降级不抛 500 | 通过 |
| 8 | 答案至少 1 条引用，点击打开片段抽屉显示来源文档与节点路径 | 冒烟 `citations=1` 且正文含 `[1]`；`MessageBubble` 单测校验 `[n]` 上标与点击事件；`CitationDrawer` 展示文档名/节点/相关度/全文 | 通过（点击打开抽屉为组件级验证） | 通过 |
| 9 | 改 Agent 自动产生新版本，历史会话仍用旧快照 | `AgentConfigTest#everyUpdateCreatesNewVersion`、`#versionSnapshotIsImmutable`；`ChatStreamTest` | 版本号自增、快照不可变、旧会话按 `agent_version` 复现 | 通过 |
| 10 | USER 读他人会话 404、写配置 403；ADMIN 可按用户/Agent 筛选并查看完整消息与推理步骤 | `ChatAccessTest`（5）、`PermissionMatrixTest`、`AuditAccessTest`（8） | 越权 404 不泄露存在性；ADMIN 全量列表 + 详情含 steps/citations；本次新增「管理员不得替用户提问」（403，且不落 USER 消息行） | 通过 |
| 11 | 初始 admin 首次登录强制改密，改密前业务接口 403 `PASSWORD_CHANGE_REQUIRED`，改密后恢复 | `MustChangePasswordTest`；冒烟首次运行实测 | 冒烟首跑打印 `forced password change on first login` 并成功换发 token；改密前仅放行 `me`/`change-password`/`logout` | 通过 |
| 12 | 切换 embedding → `REINDEXING`，期间上传与检索被拒，完成后恢复 | `ReindexTest`（9 用例） | 202 受理、重复触发 40905、期间上传/PATCH 拒绝、失败置 `FAILED`、重启后状态收敛 | 通过 |
| 13 | `docker compose up -d` 后全栈可用，SSE 不被 nginx 缓冲 | `docker compose ps`；`curl http://localhost/`；冒烟经 nginx 读 SSE | postgres / minio / backend / frontend 四服务 `healthy`；SPA 200；SSE 帧经 nginx 逐帧到达（`proxy_buffering off`，`proxy_read_timeout 300s`） | 通过 |

## 验收过程中发现并修复的缺陷

1. **预签名 URL 主机不可达**（阻断上传）：后端用内部地址 `http://minio:9000` 签名，浏览器无法解析。新增
   `app.minio.public-endpoint` / `MINIO_PUBLIC_ENDPOINT` 与独立的签名客户端，并固定 region 以避免向公网
   地址发起 location 查询。回归用例 `StoragePresignTest`。
2. **SSE 完成时的 ASYNC 派发被安全链拒绝**（阻断问答）：`SseEmitter` 完成触发 ASYNC dispatch，JWT 过滤器
   不再执行，`AuthorizationFilter` 二次鉴权判为匿名 → 响应已提交无法写错误 → 连接被 500 关闭。
   `SecurityConfig` 显式放行 `DispatcherType.ASYNC` / `ERROR`。**MockMvc 测试无法覆盖，只在真实容器中暴露。**
3. **容器健康检查误判**：健康检查用 `localhost`，容器内先解析到 `::1` 而服务只监听 IPv4，healthy 变
   `connection refused`；改为 `127.0.0.1`。
4. **`minio-init` 依赖 `minio/mc` 镜像**：目标环境镜像仓库不可拉取。改用 `minio/minio` 自带客户端
   `/opt/bin/mc`，并兼容 `/opt/bin` 不在 PATH 的情况。
5. **挂载脚本 CRLF**：`deploy/init-bucket.sh` 以 CRLF 检出导致 `set -eu` 解析失败。新增 `.gitattributes`
   固定 `*.sh` / `Dockerfile` / `*.conf` 为 LF。

## 未覆盖 / 待办

- 真实模型端点下的完整冒烟（本文档的端到端数据来自本地桩服务）。
- 10 页 PDF 的 2 分钟时延未实测；解析正确性由 Tika 集成用例覆盖。
- 单副本约束：文档任务与 SSE 编排在进程内，暂不支持多副本（spec §19 已列为已知风险）。
