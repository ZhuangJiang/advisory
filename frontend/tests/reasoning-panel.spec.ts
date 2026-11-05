import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ReasoningPanel from '../src/components/ReasoningPanel.vue'
import type { ReasoningStepView } from '../src/stores/chat'

/**
 * 折叠行为是这块面板的主诉求：默认只展开最后一轮，用户可以逐轮、逐步骤展开，也能一键全开/全收。
 * 详情区展示的是 payload 解析出的结构化内容，JSON 本身绝不该出现在界面上。
 */

const steps: ReasoningStepView[] = [
  {
    round: 1,
    phase: 'PLAN',
    status: 'finished',
    summary: '生成 2 条检索查询',
    latencyMs: 120,
    payload: JSON.stringify({ queries: ['碳数据质量控制方案', '外购焦炭数据来源'] })
  },
  {
    round: 1,
    phase: 'RETRIEVE',
    status: 'finished',
    summary: '召回 6 个片段（累计 6）',
    latencyMs: 260,
    payload: JSON.stringify(['碳数据质量控制方案'])
  },
  {
    round: 1,
    phase: 'REFLECT',
    status: 'finished',
    summary: '证据不足（缺口 1 项）',
    latencyMs: 180,
    payload: JSON.stringify({ sufficient: false, gaps: ['缺少核查标准'], nextQueries: ['核查标准'] })
  },
  {
    round: 2,
    phase: 'ANSWER',
    status: 'finished',
    summary: '基于 8 个片段生成答案',
    latencyMs: 300,
    payload: JSON.stringify({ citations: 8 })
  }
]

/** v-show 关闭时写成 inline `display: none`；断言这个而不是 isVisible()，后者在 jsdom 里不可靠。 */
function hidden(body: { attributes: (name: string) => string | undefined }): boolean {
  return (body.attributes('style') ?? '').includes('display: none')
}

function mountPanel(overrides: Partial<InstanceType<typeof ReasoningPanel>['$props']> = {}) {
  return mount(ReasoningPanel, {
    props: { steps, streaming: false, latencyMs: 860, tokenUsage: null, ...overrides },
    global: { plugins: [ElementPlus] }
  })
}

describe('reasoning panel collapsing', () => {
  it('expands only the latest round by default', () => {
    const wrapper = mountPanel()

    const bodies = wrapper.findAll('[data-test="round-body"]')
    expect(bodies).toHaveLength(2)
    // 第一轮收起：DOM 还在（v-show），只是不可见
    expect(hidden(bodies[0])).toBe(true)
    expect(hidden(bodies[1])).toBe(false)
  })

  it('opens and closes a round when its header is clicked', async () => {
    const wrapper = mountPanel()
    const firstBody = () => wrapper.findAll('[data-test="round-body"]')[0]
    const firstToggle = () => wrapper.findAll('[data-test="round-toggle"]')[0]

    expect(hidden(firstBody())).toBe(true)
    await firstToggle().trigger('click')
    expect(hidden(firstBody())).toBe(false)

    await firstToggle().trigger('click')
    expect(hidden(firstBody())).toBe(true)
  })

  it('toggles every round at once', async () => {
    const wrapper = mountPanel()
    const button = wrapper.find('[data-test="toggle-all"]')

    expect(button.text()).toBe('全部展开')
    await button.trigger('click')
    expect(wrapper.find('[data-test="toggle-all"]').text()).toBe('全部收起')
    expect(wrapper.findAll('[data-test="round-body"]').every((body) => !hidden(body))).toBe(true)

    await wrapper.find('[data-test="toggle-all"]').trigger('click')
    expect(wrapper.findAll('[data-test="round-body"]').every(hidden)).toBe(true)
  })

  it('shows queries from the payload only after the step detail is opened', async () => {
    const wrapper = mountPanel()
    const planStep = wrapper.findAll('[data-test="step"]')[0]

    expect(planStep.find('[data-test="step-detail"]').exists()).toBe(false)
    // 摘要之外不应出现 payload 的原始 JSON
    expect(wrapper.text()).not.toContain('{"queries"')

    await planStep.find('[data-test="step-detail-toggle"]').trigger('click')

    const detail = planStep.find('[data-test="step-detail"]')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain('检索查询（2）')
    expect(detail.text()).toContain('外购焦炭数据来源')
  })

  it('renders the reflect verdict with its gaps and next queries', async () => {
    const wrapper = mountPanel()
    const reflect = wrapper
      .findAll('[data-test="step"]')
      .find((step) => step.text().includes('REFLECT'))!

    await reflect.find('[data-test="step-detail-toggle"]').trigger('click')
    const detail = reflect.find('[data-test="step-detail"]')

    expect(detail.text()).toContain('不足')
    expect(detail.text()).toContain('缺少核查标准')
    expect(detail.text()).toContain('下一轮查询（1）')
    // sufficient / gaps 这类字段名是载荷细节，不该直接暴露给用户
    expect(detail.text()).not.toContain('sufficient')
  })

  it('offers no detail toggle when the payload carries nothing usable', () => {
    const wrapper = mountPanel({
      steps: [
        {
          round: 1,
          phase: 'PLAN',
          status: 'finished',
          summary: '规划超时，按已有证据进入回答',
          latencyMs: 9000,
          payload: '{"error":"timeout"}'
        }
      ]
    })

    expect(wrapper.find('[data-test="step-detail-toggle"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('规划超时')
  })
})
