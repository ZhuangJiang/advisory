# 前端说明

Vue 3 + Vite + TypeScript 的 SPA，生产由 nginx 托管并反代 `/api`。后端部分见 [`backend.md`](backend.md)。

- Vue 3.5（`<script setup>` + Composition API）、Vue Router 4、Pinia 2
- Element Plus 2（中文 locale 在 `main.ts` 统一注入）
- `@microsoft/fetch-event-source` 消费 SSE
- 测试：Vitest + jsdom + `@vue/test-utils`，**47 用例**

---

## 1. 目录职责

```
frontend/
├── src/
│   ├── main.ts              装 Pinia / Router / Element Plus，注入错误处理器
│   ├── App.vue              只有 <router-view>
│   ├── api/                 唯一 HTTP 出口
│   │   ├── client.ts        apiFetch：信封拆解、401 刷新一次、ApiError
│   │   ├── session.ts       token 的 localStorage 读写 + tokenRevision
│   │   ├── sse.ts           consumeSse（纯解析）/ postSse（传输）
│   │   └── chat|knowledge|providers|agents|audit|users.ts   按域封装的函数
│   ├── stores/
│   │   ├── auth.ts          会话状态（派生自 storage）、loadMe、登出
│   │   └── chat.ts          一个会话的消息 + 正在读的答案的推理轨迹
│   ├── router/index.ts      路由表 + 全局守卫（页面级权限的唯一来源）
│   ├── layouts/AppLayout.vue 侧边栏 + 顶栏 + 退出
│   ├── pages/               路由页面，按域分目录
│   └── components/          复用组件
└── tests/                   *.spec.ts（只收集这一层）
```

一条硬规则：**除 `src/api/client.ts` 外，任何地方不得直接 `fetch`**。token、信封、错误类型、刷新逻辑
全都只在那一个文件里定义，绕过去就会绕开 401 重试与统一报错。

---

## 2. 启动与构建

```bash
npm --prefix frontend install
npm --prefix frontend run dev      # 5173，/api 代理到 http://localhost:8080
npm --prefix frontend run build    # vue-tsc --noEmit + vite build
npm --prefix frontend run test     # vitest run
```

开发服务器代理 `/api` 到 8080（见 `vite.config.ts`），所以浏览器只面对一个源，
**SSE 不需要任何 CORS 配置**。生产由 nginx 做同样的事，并额外关掉 `proxy_buffering`。

`build` 会先跑 `vue-tsc --noEmit`，类型错误直接中断构建——提交前跑一次比在 CI 里发现更省事。

---

## 3. 数据流

```
页面 (pages/*)
   │  读 store、调 api 函数
   ├──→ stores/*           跨组件状态（会话、消息、token 派生）
   └──→ api/*.ts           按域封装：URL + 参数 + 类型
           │
           └──→ api/client.ts   apiFetch
                   ├─ 加 Authorization
                   ├─ 401 → 刷新一次 → 重试
                   ├─ 拆 {code,message,data}
                   └─ code!=0 → throw ApiError
```

页面不直接碰 `localStorage`，也不直接读 `tokenRevision`；token 一律经 `useAuthStore()`。

---

## 4. HTTP 层

### `client.ts`

```ts
apiFetch<T>(path, init?, retry = true): Promise<T>
```

- 自动补 `Content-Type: application/json`（有 body 且未显式设置时）与 `Authorization: Bearer`；
- **401 只重试一次**：刷新成功后重放原请求，失败则清会话并跳登录；
- **并发去重**：`refreshInFlight` 保证同一时刻只有一次刷新。一个页面并发发三个请求时，
  不会把三枚 refresh token 全烧掉（refresh token 是一次性的）；
- 业务错误（`code !== 0`）抛 `ApiError { code, message, status }`，调用方按 `code` 分支，
  **不要按 message 文本判断**；
- `204` 或无 body 返回 `undefined`；
- `403 / 40301` 单独识别，直接触发「必须改密」跳转。

`http` 是便捷对象：`http.get/post/patch/delete`。

### 错误处理器

`client.ts` 不 import router 或 store（会形成循环依赖、也让单测要起整个应用）。
两个跳转由 `main.ts` 通过 `setErrorHandlers` 注入：

| 回调 | 触发时机 | 默认行为 |
|---|---|---|
| `onUnauthorized` | 刷新失败、会话已失效 | 清 store，`router.replace('/login')` |
| `onPasswordChangeRequired` | 403 且 `code=40301` | `router.replace('/change-password')` |

### `session.ts`

token 存 `localStorage`（键 `advisory.accessToken` / `advisory.refreshToken`）——刷新页面不能掉登录，
而 SPA 没有服务端会话可依赖。

`tokenRevision` 是一个自增 `ref`：HTTP 层可能在 `apiFetch` 深处静默换掉令牌对，store 里的
`accessToken` 是**从 storage 派生的 computed 并读取该 revision**，因此不需要回调注册表也能让所有
响应式消费者立刻看到新令牌。**不要把 token 拷进 `ref`**，拷贝会一直持有旧值直到下次刷新。

---

## 5. SSE

`api/sse.ts` 分两层：

| 函数 | 职责 |
|---|---|
| `consumeSse(stream, handlers)` | **纯解析**：只认 `event:` / `data:` 行，字节级可测，不碰 HTTP |
| `postSse(path, body, handlers)` | 传输层，用 `fetch-event-source` 而非 `EventSource`（后者发不了 `Authorization` 头，也发不了 POST body） |

### 帧协议

由后端 `SseStepSink` 产出，改结构必须两边一起改：

```
meta       {messageId, conversationId}   首帧，断线重连的句柄
step       {round, phase, status, summary}      phase ∈ PLAN|RETRIEVE|REFLECT|ANSWER
delta      {phase, text}                 答案增量
citations  {items: [{chunkId, documentId, docName, nodeName, snippet, score}]}
done       {messageId, status, tokenUsage, latencyMs}   终结
error      {code, message, recoverable}  终结
ping       {}                            每 15s，前端忽略
```

解析器必须容忍**一帧被拆到多个网络 chunk**，也要容忍一次 chunk 里有多帧——这正是它按字节测的原因。

### 断线恢复

`stores/chat.ts` 的规则：实时帧只**填充** `send()` 创建的占位消息；如果连接在 `done` 之前断掉，
`recover()` 用 `GET /api/messages/{id}` **覆盖**占位消息。这是计划里的重连路径，不是客户端重放。

---

## 6. 状态管理

### `stores/auth.ts`

- `accessToken` / `refreshToken`：computed，派生自 storage + `tokenRevision`；
- `user`：`GET /api/auth/me` 的投影，路由守卫与布局读它；
- `isAuthenticated`、`isAdmin`、`mustChangePassword`：三个派生布尔；
- `loadMe()` / `clear()`。

`mustChangePassword` 不是装饰性的：`main.ts` 把 403/40301 接到改密页，守卫会拦下其余所有路由。

### `stores/chat.ts`

一个已打开会话的消息列表 + 当前答案的推理轨迹。

- 消息用 `key` 索引（未落库的占位消息没有 id）；
- `mergeStep()` 把 `started` / `finished` 两帧合并成一条 `ReasoningStepView`；
- `ReasoningStepView` 是**视图模型而不是持久化记录**：实时帧没有耗时，持久化记录没有 `started` 状态，
  面板需要两种形状并存；
- 列表操作：`loadConversations`、`openConversation`、`loadDetail`、`startConversation`、`send`、
  `recover`、`stop`、`reset`。

---

## 7. 路由与权限

权限判定集中在 `router/index.ts` 的 `beforeEach`，页面里不重复判断：

1. `meta.public` 的页面（登录）——已登录且不需改密则直接回首页；
2. 没有 token → `/login?redirect=<原路径>`；
3. 有 token 但没有 `user` → `loadMe()`，失败则清会话回登录；
4. `mustChangePassword` → 只允许 `/change-password`；
5. `meta.roles` 不含当前角色 → 回首页。

| 路径 | 页面 | 权限 |
|---|---|---|
| `/login` | `Login.vue` | 公开 |
| `/change-password` | `ChangePassword.vue` | 登录后，强制改密时唯一可达 |
| `/` | `Home.vue` | 登录 |
| `/chat` | `chat/ChatView.vue` | 登录 |
| `/knowledge-bases`、`/knowledge-bases/:id` | `knowledge/*` | 登录（写操作由后端拦） |
| `/agents` | `agents/AgentList.vue` | 登录 |
| `/agents/new`、`/agents/:id` | `agents/AgentEdit.vue` | ADMIN |
| `/providers` | `providers/ProviderList.vue` | ADMIN |
| `/audit` | `audit/AuditList.vue` | ADMIN |
| `/users` | `users/UserList.vue` | ADMIN |

> 路由守卫只是「不显示坏页面」，真正的权限在后端。USER 手敲 ADMIN 的 URL 会被送回首页，
> 不是因为前端安全，而是因为留个空壳更难看。

侧边栏菜单在 `AppLayout.vue` 里从**路由表**推导：路由存在且（非管理员项或当前是 ADMIN）才出现。
`meta.nav` 让进入详情页时父菜单仍高亮。

---

## 8. 页面与组件

| 页面 | 说明 |
|---|---|
| `Login` | 账号密码登录；默认管理员首次登录会被导向改密 |
| `ChangePassword` | 改密后旧令牌全部失效，需重新建立会话 |
| `Home` | 落地页 |
| `chat/ChatView` | 会话列表 + 消息流 + 推理面板 + 引用抽屉；「新对话」只列在线 Agent（`listAgents(0, 50, true)`，过滤由服务端完成） |
| `knowledge/KnowledgeBaseList` | 知识库增删改、重建索引 |
| `knowledge/KnowledgeBaseDetail` | 节点树 + 文档表 + 多文件上传 + 批量重新裁切 |
| `agents/AgentList` | Agent 列表、启停、删除 |
| `agents/AgentEdit` | 表单：模型、知识库范围、节点范围、参数；含试运行入口 |
| `providers/ProviderList` | 提供商与模型、连通性探测、远端导入 |
| `audit/AuditList` | 审计筛选 + 只读详情 |
| `users/UserList` | 用户增改、启停、重置密码 |

| 组件 | 说明 |
|---|---|
| `NodeTree` | 节点树，拖拽/重命名/删除；同级重名由后端 40901 拒绝 |
| `DocumentTable` | 文档列表、状态轮询（含排队位次）、多文件上传、单篇/批量重新裁切、重试、分块预览入口 |
| `ChunkDrawer` | 文档分块抽屉 |
| `MessageBubble` | 消息正文，`[n]` 渲染成可点上标 |
| `ReasoningPanel` | 按 round 分组的推理步骤，实时流与持久化记录共用 |
| `CitationDrawer` | 引用详情：文档名、节点路径、相关度、片段全文 |
| `KbScopePicker` | 选知识库 + 限定节点范围 |
| `AgentTestDrawer` | 试运行结果（不落库，纯展示） |

---

## 9. UI 约定

- 文案全中文，时间与数字用 `Intl` / 手写格式化，不引额外日期库；
- Element Plus 组件直接用，不包装「设计系统」层；
- 权限不足不靠隐藏按钮表达（后端会 403），但菜单项按角色隐藏以减少误点；
- 列表一律分页，页长上限与后端一致（100）；
- 错误提示用 `ElMessage`，错误文案优先取 `ApiError.message`（后端已给中文）。

---

## 10. 测试

```bash
npm --prefix frontend run test
```

- 环境 jsdom（API 层要碰 `localStorage` 与 `fetch`），只收集 `tests/**/*.spec.ts`；
- `restoreMocks` / `clearMocks` 默认开启；
- **不测快照**——本项目的契约是行为，不是 DOM 结构。

| 文件 | 覆盖 |
|---|---|
| `sse.spec.ts` | 字节级分帧、跨 chunk 拆分、`ping` 忽略、终结帧 |
| `auth.spec.ts` | 登录、401 刷新一次、刷新失败清会话 |
| `router.spec.ts` | 守卫：未登录、强制改密、角色不足 |
| `chat-view.spec.ts` | 消息流、推理步骤按 round 分组、`[n]` 上标 |
| `knowledge.spec.ts` | 列表/详情、上传三步、多文件串行上传与失败续传、重新裁切接口 |
| `document-table.spec.ts` | 排队位次渲染、批量重新裁切的选中态与请求、单篇重新裁切、多选文件 |
| `node-tree.spec.ts` | 树的增删改与重名报错 |
| `agent-form.spec.ts` | 表单校验、跨库 embedding 冲突提示 |
| `audit-list.spec.ts` | 筛选参数拼装（空筛选不发 query）、详情渲染 |

---

## 11. 常见改动指引

**加一个页面**

1. `src/pages/<域>/Xxx.vue`；
2. `router/index.ts` 注册，需要管理员就加 `meta.roles: ['ADMIN']`；
3. 需要出现在侧边栏就加 `AppLayout.vue` 的 `NAV`（名字必须与路由 `name` 一致，否则不显示）；
4. 接口调用写成 `src/api/<域>.ts` 里的函数，不要在组件里拼 URL。

**加一个接口调用**

1. 在对应 `api/<域>.ts` 定义入参/返回类型与函数，内部走 `http.*`；
2. 需要特殊错误处理就用 `try/catch` 捕 `ApiError` 按 `code` 分支；
3. **不要**新增 `fetch` 调用点。

**改 SSE 协议**

1. 先改后端 `SseStepSink` 与 `docs/backend.md` §9；
2. 再改 `api/sse.ts` 的类型与 `consumeSse` 分支；
3. 补 `tests/sse.spec.ts` 的字节级用例；
4. 最后才改 `stores/chat.ts` 与组件。

---

## 12. 已知限制

- 推理步骤是**追加式**渲染，长会话下 DOM 节点较多，未做虚拟滚动；
- 文档状态靠轮询刷新，没有走 SSE 或 WebSocket；
- 没有 i18n 框架，文案直接写在模板里；
- 没有全局 loading 遮罩，各页面自己管 `loading`；
- `localStorage` 存 token 意味着 XSS 可读——本项目靠后端严格的 HTML 转义与不渲染用户富文本缓解，
  若要更严需改成 HttpOnly cookie + CSRF token。
