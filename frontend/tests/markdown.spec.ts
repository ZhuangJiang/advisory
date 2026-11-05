import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/utils/markdown'

describe('renderMarkdown', () => {
  it('renders headings, emphasis and lists instead of leaking raw syntax', () => {
    const html = renderMarkdown('### 咨询建议\n\n1. **数据质量**：先核对口径\n2. 外购煤气\n\n- 子项\n', 0)

    expect(html).toContain('<h3>咨询建议</h3>')
    expect(html).toContain('<strong>数据质量</strong>')
    expect(html).toContain('<ol>')
    expect(html).toContain('<ul>')
    // 原始的 Markdown 记号不该出现在正文里
    expect(html).not.toContain('### ')
    expect(html).not.toContain('**')
  })

  it('turns only real citation indices into markers', () => {
    const html = renderMarkdown('先看制度[1]，再看附件[2]，还有一个不存在的[9]。', 2)

    expect(html).toContain('data-cite="0"')
    expect(html).toContain('data-cite="1"')
    // 超出引用数量的编号保持原样，不能被做成点了没反应的角标
    expect(html).toContain('[9]')
    expect(html).not.toContain('data-cite="8"')
  })

  it('escapes the citation tooltip instead of trusting the document name', () => {
    const html = renderMarkdown('见[1]。', 1, ['报告" onmouseover="alert(1).pdf'])

    // 引号被转义后留在 title 的取值里，不会变成一个新属性
    expect(html).toContain('title="报告&quot; onmouseover=&quot;alert(1).pdf"')
  })

  it('sanitizes model output so raw HTML cannot execute', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)">正文', 0)

    expect(html).not.toContain('onerror')
    expect(html).toContain('正文')
  })

  it('keeps fenced code intact and renders an empty string as nothing', () => {
    expect(renderMarkdown('```\nconst a = [1, 2]\n```', 0)).toContain('<code>')
    expect(renderMarkdown('   ', 0)).toBe('')
  })
})
