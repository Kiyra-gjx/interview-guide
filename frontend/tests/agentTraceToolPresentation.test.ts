import { describe, expect, it } from 'vitest';
import {
  getTraceToolPresentation,
  isBusinessTraceTool,
  isInternalTraceToolMarker,
} from '../src/components/agent/agentTraceToolPresentation';

describe('agentTraceToolPresentation', () => {
  it('treats subagent_handoff as an internal trace marker instead of a business tool', () => {
    const presentation = getTraceToolPresentation('subagent_handoff');

    expect(isInternalTraceToolMarker('subagent_handoff')).toBe(true);
    expect(isBusinessTraceTool('subagent_handoff')).toBe(false);
    expect(presentation.fieldLabel).toBe('路径');
    expect(presentation.kind).not.toBe('tool');
  });
});
