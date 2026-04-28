/**
 * 后端会把部分“非业务工具路径”写成 internal trace marker，方便前端还原执行过程。
 * 这些 marker 不能直接当成真实业务工具展示，否则会把 direct reply、guardrail、
 * handoff 之类的内部路径误讲成业务工具命中。
 */
export type AgentTraceToolPresentationKind = 'tool' | 'direct_reply' | 'guardrail' | 'handoff';

interface AgentTraceToolPresentation {
  kind: AgentTraceToolPresentationKind;
  label: string;
  fieldLabel: '工具' | '路径';
}

const DIRECT_REPLY_PRESENTATION: AgentTraceToolPresentation = {
  kind: 'direct_reply',
  label: '无工具，直接回复',
  fieldLabel: '路径',
};

const INTERNAL_TRACE_TOOL_PRESENTATIONS: Record<string, AgentTraceToolPresentation> = {
  direct_answer: DIRECT_REPLY_PRESENTATION,
  input_guardrail: {
    kind: 'guardrail',
    label: '输入 guardrail 拦截',
    fieldLabel: '路径',
  },
  subagent_handoff: {
    kind: 'handoff',
    label: '受控只读委派',
    fieldLabel: '路径',
  },
};

/**
 * 集中维护 internal trace marker，避免执行解释面和 Trace Browser 各自维护一份规则。
 */
export const INTERNAL_TRACE_TOOL_MARKERS = new Set(Object.keys(INTERNAL_TRACE_TOOL_PRESENTATIONS));

/**
 * 判断某个 selectedTool 是否是后端内部使用的 marker。
 */
export function isInternalTraceToolMarker(selectedTool: string | null | undefined): selectedTool is string {
  return !!selectedTool && INTERNAL_TRACE_TOOL_MARKERS.has(selectedTool);
}

/**
 * 只有真实业务工具才应该参与“本轮命中工具 / 工具计数”的叙事。
 */
export function isBusinessTraceTool(selectedTool: string | null | undefined): selectedTool is string {
  return !!selectedTool && !isInternalTraceToolMarker(selectedTool);
}

/**
 * 把 trace step 的 selectedTool 归一化成前端展示语义。
 * direct_answer 和空 selectedTool 都归到“无工具，直接回复”，保证 direct reply 叙事一致。
 */
export function getTraceToolPresentation(selectedTool: string | null | undefined): AgentTraceToolPresentation {
  if (!selectedTool) {
    return DIRECT_REPLY_PRESENTATION;
  }

  return INTERNAL_TRACE_TOOL_PRESENTATIONS[selectedTool] ?? {
    kind: 'tool',
    label: selectedTool,
    fieldLabel: '工具',
  };
}
