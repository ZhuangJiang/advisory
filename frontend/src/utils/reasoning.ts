import type { Phase } from '@/api/chat'

/**
 * 把推理步骤的 `payload`（后端落库的 JSON 字符串）翻译成能直接渲染的结构。
 *
 * <p>四个阶段的载荷形状完全不同，都是编排器直接 {@code toJson} 出来的：</p>
 *
 * <ul>
 *   <li>{@code PLAN} → {@code {"queries":["..."]}}</li>
 *   <li>{@code RETRIEVE} → {@code ["..."]}（本轮实际使用的查询）</li>
 *   <li>{@code REFLECT} → {@code {"sufficient":true,"gaps":[],"nextQueries":[],"draftAnswer":"..."}}</li>
 *   <li>{@code ANSWER} → {@code {"citations":22}}</li>
 * </ul>
 *
 * <p>解析一律「宽进」：字段缺失、类型不对、整个 JSON 解不出来，都退化为 {@code null}——面板据此
 * 只显示摘要、不显示详情，绝不因为一条脏数据把整块推理过程渲染成乱码。超时/解析失败时后端写的是
 * {@code {"error":"timeout"}} 这类载荷，正好也走这条退路。</p>
 */

export type StepDetail =
  | { kind: 'plan'; queries: string[] }
  | { kind: 'retrieve'; queries: string[] }
  | { kind: 'reflect'; sufficient: boolean | null; gaps: string[]; nextQueries: string[] }
  | { kind: 'answer'; citations: number }

export function parseStepDetail(phase: Phase, payload?: string | null): StepDetail | null {
  if (!payload) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(payload)
  } catch {
    return null
  }
  if (parsed === null || typeof parsed !== 'object') {
    return null
  }

  switch (phase) {
    case 'PLAN': {
      const queries = stringList((parsed as { queries?: unknown }).queries)
      return queries.length > 0 ? { kind: 'plan', queries } : null
    }
    case 'RETRIEVE': {
      // 载荷是裸数组；兼容将来改成 {"queries": [...]} 的写法。
      const queries = Array.isArray(parsed)
        ? stringList(parsed)
        : stringList((parsed as { queries?: unknown }).queries)
      return queries.length > 0 ? { kind: 'retrieve', queries } : null
    }
    case 'REFLECT': {
      const source = parsed as { sufficient?: unknown; gaps?: unknown; nextQueries?: unknown }
      const gaps = stringList(source.gaps)
      const nextQueries = stringList(source.nextQueries)
      const sufficient = typeof source.sufficient === 'boolean' ? source.sufficient : null
      if (sufficient === null && gaps.length === 0 && nextQueries.length === 0) {
        return null
      }
      return { kind: 'reflect', sufficient, gaps, nextQueries }
    }
    case 'ANSWER': {
      const citations = (parsed as { citations?: unknown }).citations
      return typeof citations === 'number' && Number.isFinite(citations)
        ? { kind: 'answer', citations }
        : null
    }
    default:
      return null
  }
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim())
    .filter((item) => item !== '')
}
