import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

// Phase 3: Trading Academy and the D2 demo additions (bid/ask, sell stop orders), end to end
// against the running stack. The quiz answer key comes from the repository's question bank
// (the browser never receives it before submission).

const bank = (module: string) => JSON.parse(readFileSync(fileURLToPath(new URL(
  `../../tradeflow-learning-service/src/main/resources/academy/questions/${module}.json`, import.meta.url)), 'utf8')) as
  { key: string; prompt: string; options: { key: string; text: string; correct: boolean }[] }[];

async function registerFresh(page: Page, prefix: string) {
  const id = Date.now();
  await page.goto('/register');
  await page.getByLabel('Full name').fill(`${prefix} Learner`);
  await page.getByLabel('Email').fill(`${prefix.toLowerCase()}-${id}@tradeflow.dev`);
  await page.getByLabel('Password').fill('Learn-Passw0rd!');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();
}

test('read a module, pass its quiz and see explanations; answers are not sent before submission', async ({ page }) => {
  await registerFresh(page, 'Academy');
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Trading Academy' }).click();
  await expect(page.getByTestId('academy-modules').locator('a')).toHaveCount(10);
  await page.getByRole('link', { name: /Bid, ask and the spread/ }).click();

  await expect(page.getByRole('button', { name: 'Start quiz' })).toBeDisabled();
  await page.getByRole('link', { name: /1\. Bid, ask and the spread/ }).click();
  await expect(page.getByTestId('lesson')).toContainText('the highest price a buyer');
  await page.getByRole('button', { name: /Mark as read and continue/ }).click();
  await expect(page.getByTestId('lesson')).toContainText('Liquidity');
  await page.getByRole('button', { name: /Mark as read and finish/ }).click();

  const attemptResponse = page.waitForResponse((r) => r.url().includes('/quiz/attempts'));
  await page.getByRole('button', { name: 'Start quiz' }).click();
  const raw = await (await attemptResponse).text();
  expect(raw).not.toContain('"correct"');
  const questions = bank('bid-ask-spread');
  const quiz = page.getByTestId('quiz');
  await expect(quiz.locator('fieldset')).toHaveCount(5);
  for (const fieldset of await quiz.locator('fieldset').all()) {
    const prompt = (await fieldset.locator('legend').textContent())!.replace(/^\d+\.\s*/, '');
    const q = questions.find((x) => x.prompt === prompt)!;
    for (const o of q.options.filter((x) => x.correct)) await fieldset.getByText(o.text, { exact: true }).click();
  }
  await page.getByRole('button', { name: 'Submit answers' }).click();
  await expect(page.getByRole('heading', { name: 'Passed' })).toBeVisible();
  await expect(page.getByTestId('quiz-result')).toBeVisible();
  await page.getByRole('link', { name: 'Done' }).click();
  await expect(page.getByText('Completed').first()).toBeVisible();

  // Lessons render Markdown tables (GFM) without any raw HTML.
  await page.goto('/academy/risk-management/understanding-risk');
  await expect(page.getByTestId('lesson').locator('table')).toBeVisible();
  await expect(page.getByTestId('lesson').locator('table')).toContainText('Gain needed');
});

test('quotes show bid and ask; a sell stop below the bid rests OPEN and can be cancelled', async ({ page }) => {
  await registerFresh(page, 'Stop');
  await expect(page.getByTestId('stat-total-value')).toBeVisible({ timeout: 20_000 });
  await page.goto('/demo/market/INFY');
  await expect(page.getByTestId('instrument-touch')).toContainText('Bid');
  const touch = (await page.getByTestId('ticket-touch').textContent())!;
  const [bid, ask] = touch.split('/').map((p) => Number(p.replace(/[^\d.]/g, '')));
  expect(ask).toBeGreaterThan(bid);

  // Buy 5 shares first (a stop can only sell shares you hold).
  await page.getByLabel('Quantity (shares)').fill('5');
  await page.getByRole('button', { name: 'Review buy order' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Place buy order' }).click();
  await expect(page.getByTestId('order-result')).toContainText('FILLED', { timeout: 20_000 });

  await page.getByRole('radio', { name: 'Sell' }).click();
  await page.getByRole('radio', { name: 'Stop', exact: true }).click();
  await page.getByLabel('Quantity (shares)').fill('5');
  await page.getByLabel('Stop (trigger) price (₹)').fill((bid * 2).toFixed(2));
  await page.getByRole('button', { name: 'Review sell order' }).click();
  await expect(page.getByText(/must be below the current bid/)).toBeVisible();
  await page.getByLabel('Stop (trigger) price (₹)').fill((Math.floor(bid * 0.5 * 100) / 100).toFixed(2));
  await page.getByRole('button', { name: 'Review sell order' }).click();
  const dialog = page.getByRole('dialog', { name: 'Confirm order' });
  await expect(dialog).toContainText('STOP');
  await expect(dialog).toContainText('not a guaranteed exit price');
  await dialog.getByRole('button', { name: 'Place sell order' }).click();
  await expect(page.getByTestId('order-result')).toContainText('OPEN', { timeout: 20_000 });

  await page.goto('/demo/orders');
  const row = page.locator('[data-order-id]').filter({ hasText: 'STOP' }).first();
  await expect(row).toContainText('stop ₹');
  await row.getByRole('button', { name: /Cancel order/ }).click();
  await page.getByRole('dialog', { name: 'Cancel this order?' }).getByRole('button', { name: 'Cancel order' }).click();
  await expect(page.locator('[data-order-id]').filter({ hasText: 'STOP' })).toHaveCount(0);
});
