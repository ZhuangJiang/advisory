import DOMPurify from 'dompurify'
import { marked } from 'marked'

/**
 * 把模型返回的 Markdown 渲染成可直接放进 `v-html` 的 HTML。
 *
 * <p>两件事不能省：</p>
 *
 * <ul>
 *   <li><b>引用标记</b>：正文里的 {@code [1]} 只有在确实存在第 1 条引用时才是标记，否则保持原样
 *       （模型偶尔会写出不存在的编号）。替换成私用区字符再回填，是为了让 Markdown 解析器把它们当普通
 *       文本，避免 {@code [1]: url} 之类被当成引用式链接。回填出来的 {@code <sup data-test="cite">}
 *       由 {@code MessageBubble} 用事件委托接管点击。</li>
 *   <li><b>消毒</b>：正文来自模型，属于不可信输入。`marked` 默认放行原始 HTML，一记
 *       {@code <img onerror=...>} 就是一个 XSS，所以渲染结果必须过 DOMPurify。</li>
 * </ul>
 */

const CITE_OPEN = '\uE000'
const CITE_CLOSE = '\uE001'

marked.setOptions({ gfm: true, breaks: true })

export function renderMarkdown(
  source: string,
  citationCount: number,
  citationTitles: readonly (string | null)[] = []
): string {
  const text = source ?? ''
  if (text.trim() === '') {
    return ''
  }
  // 先把合法的 [n] 换成占位符：放在这里而不是渲染后做正则替换，才不会误伤代码块里的数组下标。
  const prepared = text.replace(/\[(\d{1,3})\]/g, (raw, digits: string) => {
    const index = Number(digits) - 1
    return index >= 0 && index < citationCount ? `${CITE_OPEN}${index}${CITE_CLOSE}` : raw
  })
  const html = marked.parse(prepared, { async: false })
  const safe = DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
  return safe.replace(
    new RegExp(`${CITE_OPEN}(\\d+)${CITE_CLOSE}`, 'g'),
    (_raw, index: string) => {
      const title = escapeAttribute(citationTitles[Number(index)] ?? '引用')
      return `<sup class="cite" data-test="cite" data-cite="${index}" title="${title}">${Number(index) + 1}</sup>`
    }
  )
}

function escapeAttribute(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
