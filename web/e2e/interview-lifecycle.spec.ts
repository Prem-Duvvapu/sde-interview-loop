import { expect, expectNoHorizontalOverflow, sendTurn, startGeneralMock, test } from './fixtures';

test.describe('single-module general practice (scripted provider, zero LLM quota)', () => {
  test('streams replies, hides private scoring, and ends with a scored completion', async ({ page, consoleErrors }) => {
    void consoleErrors;
    await startGeneralMock(page, 'dsa');

    // Opening brief comes from the module itself, not an LLM call.
    await expect(page.locator('.turn-interviewer').first()).toBeVisible();

    await sendTurn(page, 'I would use a hash map [signal] [advance:CLARIFYING]');
    await expect(page.getByText(/Scripted interviewer: I heard "I would use a hash map/)).toBeVisible();
    await expect(page.getByText('What would you check next?').first()).toBeVisible();

    // Private scoring never reaches the page — not even hidden.
    const html = await page.content();
    expect(html).not.toContain('PRIVATE');
    expect(html).not.toContain('record_signal');

    // Exactly one interviewer bubble for that turn, and no caret left streaming.
    await expect(page.locator('.turn-interviewer .caret')).toHaveCount(0);
    await expectNoHorizontalOverflow(page);

    await sendTurn(page, 'That covers it [end]');
    await expect(page.getByRole('heading', { name: /Round scored/ })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Explained the approach before writing code/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Review transcript' })).toBeVisible();
    expect(await page.content()).not.toContain('PRIVATE');
  });

  test('a failed evaluation still ends the round with an honest, actionable state', async ({ page, consoleErrors }) => {
    void consoleErrors;
    await startGeneralMock(page, 'cs_fundamentals');
    await sendTurn(page, 'Wrapping up now [eval-fail] [end]');
    await expect(page.getByRole('heading', { name: /not scored/ })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('button', { name: 'Review transcript' })).toBeVisible();
  });

  test('End round asks for confirmation, then shows scoring progress', async ({ page, consoleErrors }) => {
    void consoleErrors;
    await startGeneralMock(page, 'dsa');
    await page.getByRole('button', { name: 'End round' }).click();
    await expect(page.getByRole('alertdialog')).toContainText('End this round now?');
    await page.getByRole('button', { name: 'Keep going' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await page.getByRole('button', { name: 'End round' }).click();
    await page.getByRole('button', { name: 'End and score' }).click();
    await expect(page.getByRole('heading', { name: /Round scored|not scored/ })).toBeVisible({ timeout: 30_000 });
  });

  test('Send is locked while the interviewer is replying; the draft stays editable', async ({ page, consoleErrors }) => {
    void consoleErrors;
    await startGeneralMock(page, 'dsa');
    await sendTurn(page, 'Let me think [slow]');
    const input = page.getByRole('textbox', { name: 'Your answer' });
    await input.fill('my next thought');
    await expect(page.getByRole('button', { name: 'Waiting for reply…' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeEnabled({ timeout: 20_000 });
    await expect(input).toHaveValue('my next thought');
  });

  test('silent tool-only turn is repaired with words', async ({ page, consoleErrors }) => {
    void consoleErrors;
    await startGeneralMock(page, 'dsa');
    await sendTurn(page, 'hmm [tool-only]');
    await expect(page.getByText('Sorry — to continue: walk me through your first step.')).toBeVisible();
    await expect(page.getByText(/offered more guidance/)).toBeVisible();
  });
});

test('full loop advances to the next round after scoring', async ({ page, consoleErrors }) => {
  void consoleErrors;
  await page.goto('/');
  await page.getByRole('button', { name: /^Google/ }).click();
  await page.getByRole('button', { name: 'Full loop' }).click();
  await page.getByRole('button', { name: 'Start round' }).click();
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(page.getByText(/round 1 of/)).toBeVisible();
  await sendTurn(page, 'Done with this one [end]');
  await expect(page.getByText(/round 2 of/)).toBeVisible({ timeout: 45_000 });
  await expect(page.getByText(/Starting round 2/)).toBeVisible();
});
