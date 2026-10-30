# Advisory Platform

面向企业知识库的智能顾问平台：文档入库（解析 / 切分 / 向量化）、混合检索、四阶段推理编排（PLAN → RETRIEVE → REFLECT → ANSWER）、SSE 实时推送与管理员审计。

- 后端：Java 21 + Spring Boot 3.4（单副本）
- 数据库：PostgreSQL 16 + pgvector + pg_trgm + ltree（Flyway 管理表结构）
- 对象存储：MinIO（文档原文件与解析中间产物）
- 前端：Vue 3 + Vite + TypeScript（后续任务加入）

## 本地启动

```powershell
# 1. 启动依赖服务（PostgreSQL / MinIO）
docker compose up -d postgres minio

# 2. 运行后端（Windows 下需显式指定 JDK 21）
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/pom.xml spring-boot:run

# 3. 健康检查
curl http://localhost:8080/actuator/health
```

首次启动由 Flyway 自动执行 `backend/src/main/resources/db/migration/V1__init.sql` 建表。

- PostgreSQL：`localhost:5432`，库 / 用户 / 密码默认 `advisory` / `advisory` / `advisory`
- MinIO S3 API：`localhost:9000`，控制台 `localhost:9001`
- 后端：`localhost:8080`

## 配置

所有可变配置通过环境变量注入，默认值见 `.env.example`（复制为 `.env` 使用，`.env` 不入库）。

## 安全提示

> **初始管理员账号默认为 `admin` / `admin`，且首次登录被标记为「必须修改密码」。请在首次启动后立刻修改默认密码，生产部署前务必设置 `ADMIN_PASSWORD`、`JWT_SECRET` 与 `APP_ENCRYPTION_KEY`。**

## 测试

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/pom.xml test
```

## 目录

```
backend/    Spring Boot 后端（Maven，artifact: advisory-backend）
docs/       设计文档与实施计划
```
