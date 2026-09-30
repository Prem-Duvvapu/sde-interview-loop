import { defineConfig, devices } from '@playwright/test';

/**
 * Browser suite. Runs the *real* app — Spring Boot backend + Vite client, real REST and
 * WebSocket — with a scripted LLM provider and a throwaway H2 database, so no provider quota
 * is spent and the owner's ./data is never touched. See scripts/e2e-backend.sh.
 *
 * Ports are separate from normal development (8123/5273) so a dev session can keep running.
 */
const BACKEND_PORT = 8124;
const WEB_PORT = 5274;

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  outputDir: '../target/e2e-results',
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'], viewport: { width: 375, height: 812 } } },
  ],
  webServer: [
    {
      command: `E2E_BACKEND_PORT=${BACKEND_PORT} ../scripts/e2e-backend.sh`,
      url: `http://localhost:${BACKEND_PORT}/api/profiles`,
      timeout: 300_000,
      reuseExistingServer: true,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: `npx vite --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      env: { INTERVIEW_LOOP_BACKEND: `http://localhost:${BACKEND_PORT}` },
      timeout: 120_000,
      reuseExistingServer: true,
    },
  ],
});
