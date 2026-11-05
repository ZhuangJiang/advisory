-- 会话标题回填：早期版本从不给会话命名，侧栏里一律显示「未命名会话」。
-- 新逻辑在首个提问时按问题生成标题（ConversationTitles#derive，取前 24 个码点），
-- 这里把历史会话按同样的口径补上，让老会话在列表里也能被认出来。
-- 只填 NULL / 空白标题，已有标题（用户或旧版显式命名）绝不覆盖。
UPDATE conversations c
SET title = t.title
FROM (
    SELECT f.conversation_id,
           rtrim(left(f.flat, 24), '。．，,；;：:！!？?、')
               || CASE WHEN length(f.flat) > 24 THEN '…' ELSE '' END AS title
    FROM (
        SELECT m.conversation_id,
               rtrim(
                   regexp_replace(btrim(left(m.content, 200)), '\s+', ' ', 'g'),
                   '。．，,；;：:！!？?、'
               ) AS flat
        FROM messages m
        JOIN (
            SELECT conversation_id, min(id) AS first_id
            FROM messages
            WHERE role = 'USER' AND content IS NOT NULL AND btrim(content) <> ''
            GROUP BY conversation_id
        ) first ON first.first_id = m.id
    ) f
    WHERE f.flat <> ''
) t
WHERE c.id = t.conversation_id
  AND (c.title IS NULL OR btrim(c.title) = '');
