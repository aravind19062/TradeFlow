import { expect, test, type Page } from '@playwright/test';
import { accountMenu, signOut } from './admin';

// Phase 4: readiness against the running stack. Exercise verification crosses services for real:
// a demo order (order-service → Kafka) is read by learning-service as evidence.

async function registerFresh(page: Page, prefix: string): Promise<string> {
  const email = `${prefix.toLowerCase()}-${Date.now()}@tradeflow.dev`;
  await page.goto('/register');
  await page.getByLabel('Full name').fill(`${prefix} Tester`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('Ready-Passw0rd!');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();
  return email;
}

test('exercises are verified from real demo orders; readiness is never just a frontend flag', async ({ page }) => {
  test.setTimeout(150_000); // crosses order-service → Kafka → learning-service
  const email = await registerFresh(page, 'Ready');
  await expect(page.getByTestId('stat-total-value')).toBeVisible({ timeout: 20_000 });
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Readiness' }).click();
  await expect(page.getByTestId('readiness-next-step')).toContainText('Start the Trading Academy');
  await expect(page.getByTestId('readiness-eligibility')).toContainText('Not available yet');

  await page.getByTestId('exercise-market-order').getByRole('button', { name: 'Start' }).click();
  await expect(page.getByTestId('exercise-market-order')).toContainText('Started');

  // Do it in the demo.
  await page.goto('/demo/market/INFY');
  await page.getByLabel('Quantity (shares)').fill('1');
  await page.getByRole('button', { name: 'Review buy order' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Place buy order' }).click();
  await expect(page.getByTestId('order-result')).toContainText('FILLED', { timeout: 20_000 });

  await page.goto('/readiness');
  await expect.poll(async () => {
    await page.reload();
    return page.getByTestId('exercise-market-order').textContent();
  }, { timeout: 30_000 }).toContain('Verified');
  await expect(page.getByTestId('readiness-history')).toContainText('in progress');

  // Position sizing: a scenario, a wrong then right answer.
  await page.getByTestId('exercise-position-sizing').getByRole('button', { name: 'Get a scenario' }).click();
  const scenario = page.getByTestId('sizing-scenario');
  const text = (await scenario.textContent())!;
  const nums = (label: string) => Number(text.split(label)[1].match(/\d[\d,]*(\.\d+)?/)![0].replace(/,/g, ''));
  const account = nums('Account value');
  const risk = nums('risk');
  const entry = nums('entry');
  const stop = nums('stop');
  const qty = Math.floor((account * risk) / 100 / (entry - stop));
  await scenario.getByLabel('Quantity').fill(String(qty + 7));
  await scenario.getByRole('button', { name: 'Check' }).click();
  await expect(scenario).toContainText('Not quite');
  await scenario.getByLabel('Quantity').fill(String(qty));
  await scenario.getByRole('button', { name: 'Check' }).click();
  await expect(scenario).toContainText(`Correct: ${qty} shares`);

  // The final assessment cannot be started yet: the server refuses, whatever a client claims.
  const login = await page.request.post('/api/auth/login', { data: { email, password: 'Ready-Passw0rd!' } });
  const token = (await login.json()).accessToken;
  const res = await page.request.post('/api/readiness/assessment/attempts', { headers: { Authorization: `Bearer ${token}` } });
  expect(res.status()).toBe(409);
  expect((await res.json()).code).toBe('ASSESSMENT_NOT_AVAILABLE');
});

test('only reviewers and admins see the review queue', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill('reviewer@tradeflow.dev');
  await page.getByLabel('Password').fill('Demo@12345');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await (await accountMenu(page)).getByRole('link', { name: 'Readiness reviews' }).click();
  await expect(page.getByRole('heading', { name: 'Readiness reviews' })).toBeVisible();
  await expect((await accountMenu(page)).getByRole('link', { name: 'Admin' })).toHaveCount(0);
  await page.keyboard.press('Escape');

  await signOut(page);
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('Email').fill('demo@tradeflow.dev');
  await page.getByLabel('Password').fill('Demo@12345');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();
  await expect((await accountMenu(page)).getByRole('link', { name: 'Readiness reviews' })).toHaveCount(0);
  const login = await page.request.post('/api/auth/login', { data: { email: 'demo@tradeflow.dev', password: 'Demo@12345' } });
  const token = (await login.json()).accessToken;
  expect((await page.request.get('/api/admin/readiness/reviews', { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(403);
});
