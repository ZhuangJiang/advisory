-- V3: chunks.heading_path
--
-- 切分器已经算出了每个片段的标题路径（TextChunk.headingPath，形如 "指南 > 第一章 > 1.1 背景"），
-- 但 V1 的 chunks 表没有列可以放它，于是在写入时被丢弃。后果有两个：
--   1. 文档详情页无法回答"这个片段出自文档哪一节"，只能显示正文；
--   2. 检索命中后想给出章节级引用也没有数据来源。
--
-- 用 NOT NULL DEFAULT '' 而不是可空列：没有标题的文档（纯文本、单段表格）本来就应该得到空串，
-- 前端可以统一按"字符串可能为空"处理，不必区分 null 与空串。默认值也让已有的行无需回填。
ALTER TABLE chunks ADD COLUMN heading_path TEXT NOT NULL DEFAULT '';
