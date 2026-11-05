-- V1: 全量基线表结构（spec §9）
-- 设计约定：主键 BIGSERIAL；时间列统一 timestamptz 且默认 now()（存储 UTC 时刻）；
-- 枚举使用 varchar + check 约束，便于 JPA 以字符串映射并通过 ddl-auto=validate。

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. 用户
CREATE TABLE users (
    id                   BIGSERIAL PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL,
    password_hash        VARCHAR(255) NOT NULL,
    role                 VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    tenant_id            VARCHAR(64),
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 2. 模型提供商
CREATE TABLE model_providers (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    type           VARCHAR(32)  NOT NULL CHECK (type IN ('OLLAMA', 'OPENAI_COMPATIBLE')),
    base_url       VARCHAR(512) NOT NULL,
    api_key_cipher TEXT,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 3. 模型
CREATE TABLE models (
    id          BIGSERIAL PRIMARY KEY,
    provider_id BIGINT       NOT NULL REFERENCES model_providers (id) ON DELETE CASCADE,
    model_name  VARCHAR(255) NOT NULL,
    capability  VARCHAR(16)  NOT NULL CHECK (capability IN ('CHAT', 'EMBEDDING')),
    dimension   INTEGER,
    params      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 4. 刷新令牌（哈希入库、可吊销）
CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

-- 5. 知识库
CREATE TABLE knowledge_bases (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    description        TEXT,
    embedding_model_id BIGINT REFERENCES models (id) ON DELETE RESTRICT,
    chunk_config       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    reindex_status     VARCHAR(16) NOT NULL DEFAULT 'READY'
        CHECK (reindex_status IN ('READY', 'REINDEXING', 'FAILED')),
    created_by         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 6. 知识库节点树（path 为 ltree，形如 n1.n5.n9）
CREATE TABLE kb_nodes (
    id         BIGSERIAL PRIMARY KEY,
    kb_id      BIGINT       NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    parent_id  BIGINT REFERENCES kb_nodes (id) ON DELETE RESTRICT,
    name       VARCHAR(255) NOT NULL,
    path       LTREE        NOT NULL,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 7. 文档（软删：status = DELETED）
CREATE TABLE documents (
    id         BIGSERIAL PRIMARY KEY,
    kb_id      BIGINT       NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    node_id    BIGINT REFERENCES kb_nodes (id) ON DELETE RESTRICT,
    filename   VARCHAR(512) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    parsed_key VARCHAR(1024),
    mime       VARCHAR(255),
    size       BIGINT       NOT NULL DEFAULT 0,
    checksum   VARCHAR(128),
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED', 'DELETED')),
    error_msg  TEXT,
    created_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 8. 分块与向量（固定 1024 维）
CREATE TABLE chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    kb_id       BIGINT NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    node_id     BIGINT REFERENCES kb_nodes (id) ON DELETE SET NULL,
    seq         INTEGER NOT NULL,
    content     TEXT    NOT NULL,
    token_count INTEGER,
    embedding   VECTOR(1024),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 9. Agent 配置（当前生效版本）
CREATE TABLE agents (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    description        TEXT,
    system_prompt      TEXT         NOT NULL,
    chat_model_id      BIGINT REFERENCES models (id) ON DELETE RESTRICT,
    embedding_model_id BIGINT REFERENCES models (id) ON DELETE RESTRICT,
    kb_ids             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    node_scope         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    max_rounds         INTEGER      NOT NULL DEFAULT 3 CHECK (max_rounds BETWEEN 1 AND 6),
    top_k              INTEGER      NOT NULL DEFAULT 20 CHECK (top_k BETWEEN 1 AND 100),
    temperature        NUMERIC(3, 2) NOT NULL DEFAULT 0.70 CHECK (temperature BETWEEN 0 AND 2),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    current_version    INTEGER      NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 10. Agent 历史版本（只读快照）
CREATE TABLE agent_versions (
    id         BIGSERIAL PRIMARY KEY,
    agent_id   BIGINT      NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    version    INTEGER     NOT NULL,
    snapshot   JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 11. 会话（锁定 Agent 版本）
CREATE TABLE conversations (
    id            BIGSERIAL PRIMARY KEY,
    agent_id      BIGINT       NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    agent_version INTEGER      NOT NULL,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 12. 消息
CREATE TABLE messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content         TEXT,
    citations       JSONB,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'STREAMING', 'COMPLETED', 'FAILED', 'TRUNCATED')),
    token_usage     JSONB,
    latency_ms      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 13. 推理步骤（主数据，SSE 只是实时通道）
CREATE TABLE reasoning_steps (
    id          BIGSERIAL PRIMARY KEY,
    message_id  BIGINT      NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    round       INTEGER     NOT NULL DEFAULT 1,
    phase       VARCHAR(16) NOT NULL CHECK (phase IN ('PLAN', 'RETRIEVE', 'REFLECT', 'ANSWER')),
    payload     JSONB,
    token_usage JSONB,
    latency_ms  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 唯一约束
CREATE UNIQUE INDEX ux_kb_nodes_name ON kb_nodes (kb_id, parent_id, name);
-- PostgreSQL 的唯一索引把 NULL 视为互不相等，根节点 parent_id 为 NULL 时上面那条无法约束重名；
-- 追加部分唯一索引补齐根节点唯一性（不改动上面约定的索引定义）。
CREATE UNIQUE INDEX ux_kb_nodes_root_name ON kb_nodes (kb_id, name) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX ux_agents_name ON agents (name);
CREATE UNIQUE INDEX ux_users_username ON users (username);
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE UNIQUE INDEX ux_agent_versions_agent_version ON agent_versions (agent_id, version);

-- 树与向量 / 关键词检索索引
CREATE INDEX ix_kb_nodes_path ON kb_nodes USING gist (path);
CREATE INDEX ix_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ix_chunks_content_trgm ON chunks USING gin (content gin_trgm_ops);
CREATE INDEX ix_chunks_document ON chunks (document_id);
CREATE INDEX ix_chunks_kb ON chunks (kb_id);
CREATE INDEX ix_reasoning_steps_message ON reasoning_steps (message_id, round, phase);
CREATE INDEX ix_conversations_user ON conversations (user_id, created_at DESC);
CREATE INDEX ix_conversations_agent ON conversations (agent_id, created_at DESC);
CREATE INDEX ix_messages_conversation ON messages (conversation_id, created_at);
CREATE INDEX ix_documents_kb_status ON documents (kb_id, status);
