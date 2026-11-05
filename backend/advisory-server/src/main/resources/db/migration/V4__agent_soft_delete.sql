-- Agent 软删：历史会话与版本快照必须保留，因此不物理删除 agents 行。
ALTER TABLE agents ADD COLUMN deleted_at TIMESTAMPTZ;

-- 唯一性只约束未删除的 Agent，名称在软删后可复用。
DROP INDEX ux_agents_name;
CREATE UNIQUE INDEX ux_agents_name ON agents (name) WHERE deleted_at IS NULL;
