import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';

/**
 * 后端统一响应结构
 */
interface Result<T = unknown> {
  code: number;
  message: string;
  data: T;
}

export const API_BASE_URL = import.meta.env.PROD
  ? ''
  : (import.meta.env.VITE_API_BASE_URL || '');

const instance: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000,
});

/**
 * 响应拦截器
 *
 * 后端约定：
 * - 成功响应使用 `HTTP 200 + Result`
 * - 部分业务错误会返回真实的 `404 / 409` 等状态码，但响应体仍保持 `Result` 结构
 * - `code === 200` 表示成功，返回 `data`
 * - `code !== 200` 表示失败，直接显示 `message`
 */
instance.interceptors.response.use(
  (response) => {
    const result = response.data as Result;

    // 识别标准 Result 包装
    if (result && typeof result === 'object' && 'code' in result) {
      if (result.code === 200) {
        response.data = result.data;
        return response;
      }
      return Promise.reject(new Error(result.message || '请求失败'));
    }

    // 非 Result 响应直接透传
    return response;
  },
  (error) => {
    // 已收到后端响应，即使状态码不是 200
    if (error.response) {
      const { data } = error.response;
      if (data && typeof data === 'object' && 'code' in data && 'message' in data) {
        const result = data as Result;
        return Promise.reject(new Error(result.message || '请求失败'));
      }
      return Promise.reject(new Error('请求失败，请重试'));
    }

    // 没有收到 HTTP 响应，通常是网络问题
    const config = error.config;
    const isUpload = config && (
      config.url?.includes('/upload') ||
      config.headers?.['Content-Type']?.toString().includes('multipart')
    );

    if (isUpload) {
      return Promise.reject(new Error('上传失败，未收到后端响应。请检查网络，或确认当前前端地址已被后端 CORS 允许。'));
    }

    return Promise.reject(new Error('网络连接失败，请检查网络'));
  }
);

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then(res => res.data);
  },

  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then(res => res.data);
  },

  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then(res => res.data);
  },

  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then(res => res.data);
  },

  /**
   * 文件上传
   */
  upload<T>(url: string, formData: FormData, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, formData, {
      timeout: 120000,
      ...config,
    }).then(res => res.data);
  },

  /**
   * 获取原始 axios 实例，供下载 Blob 等特殊场景复用
   */
  getInstance(): AxiosInstance {
    return instance;
  },
};

/**
 * 提取用户可见的错误信息
 */
export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return '未知错误';
}

export default request;
