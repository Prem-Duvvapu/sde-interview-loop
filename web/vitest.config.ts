import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Component/unit tests only. Browser flows live in e2e/ (Playwright) and run against the real
 * app with a scripted LLM provider — see docs/ui-ux/BASELINE.md.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
    restoreMocks: true,
  },
});
