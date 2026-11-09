# Advisory Platform 项目说明文档

## 一、项目概述

Advisory Platform 是一个面向企业知识库的智能顾问平台。它把企业内部的文档资料转换成可检索的知识资产，并在用户提问时按「规划 → 检索 → 反思 → 回答」四个阶段推进推理，把检索证据、推理过程与引用来源一并呈现给使用者。管理员侧提供用户、提供商、Agent 与会话审计的完整管理能力。

平台的核心链路可以概括为四件事：

- **文档入库**：上传后经解析、切分、向量化落入 PostgreSQL，原始文件与解析中间产物存放在 MinIO。
- **混合检索**：向量检索与关键词检索并行召回，融合后取最相关片段作为证据。
- **四阶段推理编排**：PLAN → RETRIEVE → REFLECT → ANSWER，证据不足时循环补检，轮次有上限。
- **实时推送与审计**：推理过程通过 SSE 逐帧推给浏览器，步骤同时落库，管理员可回看会话。

技术栈按四层划分：

| 层次 | 选型 | 说明 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.4 | 单副本部署，文档任务与 SSE 编排在进程内 |
| 数据库 | PostgreSQL 16 + pgvector + pg_trgm + ltree | 向量、关键词、树形路径三类能力同库承载，表结构由 Flyway 管理 |
| 对象存储 | MinIO | 文档原文件与解析中间产物 |
| 前端 | Vue 3 + Vite + TypeScript + Element Plus | 生产由 nginx 托管并反代 `/api` |

需要特别记住一个前提：**后端是单副本架构**。文档解析任务与对话编排都跑在应用进程内，不支持横向扩容；若要扩容，必须先把文档任务与编排移出进程。

## 二、系统架构与端口约定

### （一）工程结构

仓库按职责划分目录，后端是一个 Maven 聚合工程，MCP 服务作为独立进程单独部署：

| 目录 | 职责 |
|---|---|
| `backend/advisory-server/` | 主后端服务（artifact：advisory-server），承载认证、知识库、Agent、对话与审计全部业务 |
| `backend/mcp-server/weather-mcp/` | 天气 MCP 服务，Spring AI MCP Server，SSE 传输，`21001` |
| `backend/mcp-server/news-mcp/` | 新闻检索 MCP 服务，RSS 聚合，SSE 传输，`21002` |
| `frontend/` | Vue 3 单页应用，生产镜像由 nginx 托管 |
| `deploy/` | nginx 配置与 MinIO 建桶脚本 |
| `scripts/` | 端到端冒烟脚本与本地模型桩 |
| `docs/` | 后端、前端、验收说明与设计 spec |

`backend/mcp-server` 设计成可扩展的服务族：每个子模块就是一个独立部署的 Spring Boot 进程，与主服务没有代码与数据依赖，新增一个服务只需在聚合 pom 里加一个模块。

### （二）端口约定

端口按用途分段，避免与常见通用端口冲突：

| 用途 | 端口 |
|---|---|
| 主后端 `advisory-server` | `20001` |
| 天气 MCP / 新闻 MCP | `21001` / `21002` |
| 前端（生产 / 开发） | `80` / `5173` |
| PostgreSQL | `5432` |
| MinIO S3 API / 控制台 | `9000` / `9001` |

新增服务按段位领号：后端服务从 20002 顺延，MCP 服务从 21003 顺延。容器到宿主的映射分别使用 `BACKEND_PORT`、`WEATHER_MCP_PORT`、`NEWS_MCP_PORT` 覆盖，容器之间仍走容器内端口。

### （三）请求路径

浏览器访问的是单一入口：开发环境由 Vite 把 `/api` 代理到 `20001`，生产环境由 nginx 做同样的转发。静态资源走 SPA 的 `try_files` 回退，因此前端刷新任意路由都不会 404。对话接口返回的是 `text/event-stream`，nginx 侧已固定关闭响应缓冲并延长读超时，否则推理过程会被攒成一次性返回。

## 三、快速开始

### （一）环境依赖

本地运行需要 JDK 21、Maven、Node.js 与 Docker。Windows 环境下系统默认的 `JAVA_HOME` 可能指向 JDK 8，启动前必须显式覆盖为 JDK 21 的安装路径。

### （二）本地启动

依赖服务、后端、前端分三步起，最后做一次健康检查：

1. 启动依赖服务：`docker compose up -d postgres minio minio-init`
2. 设置 `JAVA_HOME` 后运行后端：`mvn -f backend/advisory-server/pom.xml spring-boot:run`
3. 安装依赖并运行前端：`npm --prefix frontend install`，然后 `npm --prefix frontend run dev`
4. 健康检查：访问 `http://localhost:20001/actuator/health`，返回 `{"status":"UP"}` 即正常

首次启动时 Flyway 会自动执行 `backend/advisory-server/src/main/resources/db/migration/` 下的迁移脚本完成建表，不需要手工导入 SQL。

本地默认连接信息：PostgreSQL 在 `localhost:5432`，库、用户、密码均为 `advisory`；MinIO 的 S3 API 在 `localhost:9000`，控制台在 `localhost:9001`。

### （三）MCP 服务单独启动

两个 MCP 服务与主服务彼此独立，按需单独拉起即可：`mvn -f backend/mcp-server/weather-mcp/pom.xml spring-boot:run` 起天气服务，`mvn -f backend/mcp-server/news-mcp/pom.xml spring-boot:run` 起新闻服务。离线开发时把 `WEATHER_PROVIDER` 或 `NEWS_PROVIDER` 设为 `stub`，服务会返回固定假数据、不访问公网。

## 四、生产部署

全栈用容器编排一次拉起：

- 构建并启动：`docker compose up -d --build`
- 状态检查：`docker compose ps`，postgres、minio、backend、frontend、weather-mcp 应全部为 healthy
- 访问入口：`http://localhost/`

部署时有四个必须确认的点：

**第一，预签名地址必须写对。** 上传走的是 MinIO 预签名直传，签名把主机名一起签进去了，所以 `MINIO_PUBLIC_ENDPOINT` 必须是浏览器能直接访问的地址（本地默认 `http://localhost:9000`，生产填对外域名）。留空会回落到 `MINIO_ENDPOINT`，此时签出的是容器内地址 `minio:9000`，浏览器不可达，上传必然失败。

**第二，SSE 的 nginx 参数不能改。** `deploy/nginx.conf` 里固定了 `proxy_buffering off` 与 `proxy_read_timeout 300s`，改成默认值会让推理过程变成「最后一次性返回」。

**第三，后端不能横向扩容。** 文档解析任务与 SSE 编排都在进程内，多副本会导致任务重复执行与推送错乱。

**第四，MCP 服务独立于主链路。** `weather-mcp` 是单独容器，主后端停机它照常服务，反之亦然。

镜像构建默认走阿里云 Maven 镜像；如需官方源，构建时加 `--build-arg MAVEN_MIRROR=` 覆盖即可。

## 五、配置与安全

所有可变配置都通过环境变量注入，默认值集中在 `.env.example`。使用时复制为 `.env` 并填写实际值，`.env` 不入库。

几个关键变量值得单独列出：

| 变量 | 用途 |
|---|---|
| `ADMIN_PASSWORD` | 初始管理员密码，仅在库中无管理员时生效 |
| `JWT_SECRET` | 访问令牌签名密钥 |
| `APP_ENCRYPTION_KEY` | 提供商 API Key 的加密密钥 |
| `MINIO_PUBLIC_ENDPOINT` | 预签名使用的公网地址，浏览器必须可达 |
| `MCP_ENABLED` | 是否启用 MCP 工具调用，设为 `false` 可整体关闭 |

其中 `APP_ENCRYPTION_KEY` 一旦更换，库里已存的密文将无法解密，需要在更换前重新录入提供商的密钥。

> 初始管理员账号默认为 `admin` / `admin`，且首次登录被标记为「必须修改密码」。请在首次启动后立刻修改默认密码；生产部署前务必设置 `ADMIN_PASSWORD`、`JWT_SECRET`、`APP_ENCRYPTION_KEY` 与 `MINIO_PUBLIC_ENDPOINT` 这四项，否则等同于把系统对外敞开。

## 六、MCP 服务

MCP 服务为对话提供「知识库之外」的能力：查天气、查最近新闻。两个服务都是 Spring AI MCP Server + SSE 传输、无数据库、数据源可切到离线模式。

| 服务 | 端口 | 提供工具 | 数据源 |
|---|---|---|---|
| `weather-mcp` | `21001` | `get_current_weather`、`get_weather_forecast` | Open-Meteo，免费无 Key |
| `news-mcp` | `21002` | `search_news`、`get_headlines` | RSS 聚合，6 个源，免费无 Key |

服务端点为 `http://localhost:21001/sse` 与 `http://localhost:21002/sse`（消息端点 `/mcp/message`），健康检查走 `/actuator/health`。新闻源清单在 `news-mcp` 的 `application.yml` 中，增删源只改配置。

### （一）对话中如何生效

主后端在后台分别连上两个 MCP 端点（`WEATHER_MCP_URL` / `NEWS_MCP_URL`），工具清单里同时有天气与新闻，编排器按问题内容交给模型选择。问「北京今天天气怎么样」时，编排器直接调工具作答、不再检索知识库；MCP 服务没起来时自动回退到原来的检索流程，不会让会话失败。

要真正调起来，两个前提缺一不可：

1. Agent 绑定的 chat 模型必须支持 function calling。`qwen2.5:7b`、`llama3.1:8b` 可以；`deepseek-r1` 不行——它接受工具参数却从不调用，会凭记忆编造天气。
2. 提供商地址对后端容器可达。推荐统一使用 `http://host.docker.internal:11434`：容器内由 Docker DNS 解析到宿主网关，Windows 宿主由 hosts 记录解析到 `127.0.0.1`，本地 `mvn` 与容器两种跑法共用同一个地址。

### （二）能力边界

RSS 只能拿到各源最近的几十条，所以 `search_news` 的语义是「在这些最近新闻里筛」，不是全网检索；匹配范围覆盖标题、摘要与正文。免 Key 的全网新闻检索 API 已基本绝迹（Bing、Baidu 的 RSS 均已停用），若需要真正的全网检索，得接入带 Key 的服务并实现新的 `NewsProvider`。

另外几个已知不可用的新闻源不必再试：36kr `/feed` 与 cnBeta 返回反爬 HTML，BBC 中文在国内不可达，GDELT 对国内出口 IP 长期限流。

## 七、测试与验收

后端测试用 Testcontainers 拉起真实的 PostgreSQL 与 MinIO，因此需要本机有 Docker；MCP 子模块的测试不依赖 Docker，可以单独跑。

| 命令 | 覆盖范围 |
|---|---|
| `mvn -f backend/pom.xml test` | 聚合构建：主服务 276 用例 + 天气 MCP 12 + 新闻 MCP 33 |
| `mvn -f backend/advisory-server/pom.xml test` | 只跑主后端 |
| `mvn -f backend/mcp-server/weather-mcp/pom.xml test` | 只跑天气 MCP，无需 Docker |
| `npm --prefix frontend run test` | 前端 vitest 用例（jsdom，不测快照） |
| `npm --prefix frontend run build` | 类型检查加生产构建 |

端到端冒烟用 `scripts/smoke.ps1`，需要配置 chat 与 1024 维 embedding 端点（`SMOKE_CHAT_BASE_URL`、`SMOKE_CHAT_MODEL`、`SMOKE_EMBED_MODEL`、`SMOKE_PROVIDER_TYPE`）。未配置时脚本以退出码 2 明确跳过，不会假报成功。

手边没有模型服务时，可以用 `scripts/fake-model-server.mjs` 起一个 OpenAI 兼容桩，把 chat 与 embedding 地址都指向它，仅用于验证链路是否打通。逐条验收结果记录在 `docs/acceptance.md`。

## 八、仓库与文档索引

### （一）代码仓库

- 仓库地址：`https://gitee.com/buile/advisory.git`
- 克隆命令：`git clone https://gitee.com/buile/advisory.git`
- 当前分支：`feat/advisory-platform-mvp`

### （二）文档索引

| 文档 | 内容 |
|---|---|
| `docs/backend.md` | 后端分层、请求链路、权限矩阵、数据模型、入库状态机、检索与编排、错误码、测试与改动指引 |
| `docs/frontend.md` | 前端目录、HTTP/SSE 封装、状态管理、路由与权限、页面组件、测试与改动指引 |
| `docs/acceptance.md` | 逐条验收结果与验收中修掉的缺陷 |
| `AGENTS.md` | 跨前后端的硬约定、Agent 模块要点、踩过的坑、验证清单 |
| `docs/superpowers/specs/` | 设计 spec 与实施计划 |

## 九、已知限制

- **单副本**：文档任务队列与 SSE 编排在进程内，扩容前必须先拆分。
- **检索与新闻均为「最近范围」语义**：知识库检索依赖已入库文档，新闻检索依赖 RSS 最近条目，都不是全网搜索。
- **MCP 依赖模型能力**：不支持 function calling 的模型无法调起工具，需在 Agent 配置时避开。
- **新闻源受站点策略影响**：RSS 源可能随时改版或反爬，源清单需按实际可用性维护。
- **预签名地址与部署环境强绑定**：更换对外域名后必须同步更新 `MINIO_PUBLIC_ENDPOINT`，否则上传链路失效。
