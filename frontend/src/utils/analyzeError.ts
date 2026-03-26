import type { AnalyzeErrorCode } from '../api/history';

export interface AnalyzeErrorDisplay {
  title: string;
  message: string;
  hint: string;
}

const ANALYZE_ERROR_MAP: Record<AnalyzeErrorCode, AnalyzeErrorDisplay> = {
  RESUME_ANALYSIS_FAILED: {
    title: '分析暂时失败',
    message: '本次简历分析未成功完成，请稍后重试。',
    hint: '如果问题持续出现，请检查后端日志或联系管理员处理。',
  },
  AI_SERVICE_UNAVAILABLE: {
    title: 'AI 服务暂时不可用',
    message: 'AI 服务当前不可用，暂时无法完成简历分析。',
    hint: '建议稍后重试，或先检查网络与模型服务状态。',
  },
  AI_SERVICE_TIMEOUT: {
    title: 'AI 服务响应超时',
    message: 'AI 服务响应超时，本次分析未完成。',
    hint: '通常可以直接重新发起分析。',
  },
  AI_SERVICE_ERROR: {
    title: 'AI 服务调用失败',
    message: 'AI 服务调用失败，本次分析未成功完成。',
    hint: '建议稍后重试；若持续失败，请检查服务端配置。',
  },
  AI_API_KEY_INVALID: {
    title: 'AI 服务配置异常',
    message: 'AI 服务认证失败，当前环境无法完成分析。',
    hint: '这类问题通常需要管理员检查 API Key 配置。',
  },
  AI_RATE_LIMIT_EXCEEDED: {
    title: 'AI 服务请求过多',
    message: 'AI 服务当前较忙，请稍后再试。',
    hint: '等待一段时间后重新发起分析通常即可恢复。',
  },
  AI_QUOTA_EXCEEDED: {
    title: 'AI 服务额度不足',
    message: 'AI 服务当前额度不足，暂时无法完成分析。',
    hint: '需要管理员补充额度后才能恢复。',
  },
  AI_RESPONSE_FORMAT_INVALID: {
    title: 'AI 返回格式异常',
    message: 'AI 返回结果格式异常，本次分析未成功完成。',
    hint: '该问题通常可以通过重新发起分析恢复。',
  },
};

export function getAnalyzeErrorDisplay(
  errorCode?: AnalyzeErrorCode,
  analyzeError?: string,
): AnalyzeErrorDisplay {
  if (errorCode && ANALYZE_ERROR_MAP[errorCode]) {
    return {
      ...ANALYZE_ERROR_MAP[errorCode],
      message: analyzeError || ANALYZE_ERROR_MAP[errorCode].message,
    };
  }

  return {
    title: '分析暂时失败',
    message: '本次简历分析未成功完成，请稍后重试。',
    hint: '如果连续多次失败，请检查 AI 服务配置、额度或后端日志。',
  };
}