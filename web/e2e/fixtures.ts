import { test as base, expect, type Page } from '@playwright/test';

/**
 * Every browser test fails on an unexpected console error or uncaught page error — the H1
 * walkthrough found an intermittent React warning that nobody could pin down, so silence
 * is enforced rather than hoped for.
 */
export const test = base.extend<{ consoleErrors: string[] }>({
  consoleErrors: async ({ page }, use) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      // Resource failures are checked by URL below; the console copy has no URL.
      if (msg.type() === 'error' && msg.text().startsWith('Failed to load resource')) return;
      if (msg.type() === 'error' || (msg.type() === 'warning' && /Maximum update depth/.test(msg.text()))) {
        errors.push(msg.text());
      }
    });
    page.on('response', (res) => {
      if (res.status() < 400) return;
      // "Not evaluated / no report yet" is a designed 404 state, not a failure.
      if (res.status() === 404 && /\/api\/(rounds\/\d+\/evaluation|sessions\/\d+\/report)$/.test(new URL(res.url()).pathname)) return;
      errors.push(`HTTP ${res.status()} ${res.url()}`);
    });
    page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
    await use(errors);
    expect(errors, 'console errors during the test').toEqual([]);
  },
});

export { expect };

export async function startGeneralMock(page: Page, module = 'dsa') {
  await page.goto('/');
  await page.getByRole('button', { name: /General SDE-2 practice/ }).click();
  await page.locator('#general-module-select').selectOption(module);
  await page.getByRole('button', { name: 'Start mock' }).click();
  // First load on a cold Vite dev server compiles Monaco; allow for it.
  await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 45_000 });
}

export async function sendTurn(page: Page, text: string) {
  const input = page.getByRole('textbox', { name: 'Your answer' });
  await input.fill(text);
  await page.getByRole('button', { name: 'Send', exact: true }).click();
}

/** The page must not scroll sideways at this viewport. */
export async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'horizontal page overflow in px').toBeLessThanOrEqual(0);
}
