-- V2: documents.object_key 唯一索引
--
-- 上传回调按 object_key 幂等。V1 中 documents 只有 ix_documents_kb_status，没有 object_key 的
-- 唯一索引，服务端只能先查后插（check-then-act），并发回调可能插入重复行。V1 已冻结且已应用
-- 于既有环境，所以用独立的 V2 补上这个约束，而不是修改 V1 让已经迁移过的库无法校验。
--
-- 幂等重放仍然先做查询（避免无谓的异常路径）；唯一索引是兜底：并发插入时 PostgreSQL 会让后
-- 一个事务阻塞到前一个提交，然后抛 23505，服务端据此回读并返回已存在的文档。
CREATE UNIQUE INDEX ux_documents_object_key ON documents (object_key);
