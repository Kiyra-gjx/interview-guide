import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * 在每个用例结束后清理渲染树，避免跨用例状态泄漏。
 */
afterEach(() => {
  cleanup();
});
