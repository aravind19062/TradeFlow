import { expect, test, type Page } from '@playwright/test';
import { adminCode, signInAdmin, totpAt } from './admin';

// Phase 5: the LIVE area against the running stack. No broker is integrated, so these tests
// prove the safe default (nothing can reach a broker) and the real step-up flow.

const DEMO_PASSWORD = process.env.TRADEFLOW_DEMO_PASSWORD ?? 'Demo@12345';

function totp(base32Secret: string, offsetSteps = 0): string {
  return totpAt(base32Secret, Math.floor(Date.now() / 30_000) + offsetSteps);
}

async function registerFresh(page: Page, prefix: string, password: string): Promise<string> {
  const email = `${prefix.toLowerCase()}-${Date.now()}@tradeflow.dev`;
  await page.goto('/register');
  await page.getByLabel('Full name').fill(`${prefix} Tester`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();
  return email;
}

test('the LIVE area is clearly marked, says no broker is configured, and cannot reach a broker', async ({ page }) => {
  await registerFresh(page, 'Live', 'Live-Passw0rd!');
  const nav = page.getByRole('navigation', { name: 'Main' });
  await expect(nav.getByTestId('env-badge-LIVE')).toBeVisible();
  const status = page.waitForResponse((r) => r.url().endsWith('/api/live/status'));
  await nav.getByRole('link', { name: 'Live dashboard' }).click();
  const res = await status;
  expect(res.headers()['x-tradeflow-environment']).toBe('LIVE');
  expect((await res.json()).brokerConfigured).toBe(false);

  await expect(page.getByTestId('live-banner')).toContainText('real money');
  await expect(page.getByTestId('demo-banner')).toHaveCount(0);
  await expect(page.getByTestId('no-broker')).toContainText('No broker configured');
  // Readiness, MFA, broker, link: all missing for a new user; only "no halt" is satisfied.
  await expect(page.getByTestId('live-check-missing')).toHaveCount(4);
  await expect(page.getByTestId('connect-broker')).toBeDisabled();
  await expect(page.getByTestId('live-order-ticket')).toHaveCount(0);

  // Whatever the UI shows, the backend refuses.
  const anon = await page.request.post('/api/live/orders', { data: {} });
  expect(anon.status()).toBe(401);

  // The demo area keeps its own label.
  await nav.getByRole('link', { name: 'Dashboard', exact: true }).click();
  await expect(page.getByTestId('demo-banner')).toBeVisible();
  await expect(page.getByTestId('live-banner')).toHaveCount(0);
});

test('tightening a limit needs nothing more; raising it needs password + authenticator code', async ({ page }) => {
  test.setTimeout(120_000);
  const pass = 'Limit-Passw0rd!';
  await registerFresh(page, 'Limits', pass);
  // Two-factor authentication first (step-up is impossible without it).
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Security' }).click();
  await page.getByLabel('Confirm your password').fill(pass);
  await page.getByRole('button', { name: 'Set up authenticator' }).click();
  const secret = (await page.getByTestId('totp-secret').textContent())!.replace(/\s/g, '');
  await page.getByLabel('Code from your app').fill(totp(secret));
  await page.getByRole('button', { name: 'Turn on two-factor authentication' }).click();
  await page.getByLabel('I have stored these codes somewhere safe').check();
  await page.getByRole('button', { name: 'Done' }).click();

  await page.goto('/live');
  await expect(page.getByTestId('live-check-ok')).toHaveCount(2); // MFA on, no halt
  const perDay = page.getByLabel(/Max orders per day/);
  await expect(perDay).toHaveValue('10');
  await perDay.fill('3');
  await page.getByRole('button', { name: 'Save limits' }).click();
  await expect(page.getByText('Limits saved')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0); // stricter: no step-up asked
  await expect(page.getByText('Limits saved')).toHaveCount(0); // toast gone, so the next one is unambiguous

  await perDay.fill('5');
  await page.getByRole('button', { name: 'Save limits' }).click();
  const dialog = page.getByRole('dialog', { name: "Confirm it's you" });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Password').fill(pass);
  await dialog.getByLabel('Authenticator code').fill('000000' === totp(secret, 1) ? '111111' : '000000');
  await dialog.getByRole('button', { name: 'Confirm' }).click();
  await expect(dialog.getByRole('alert')).toBeVisible(); // wrong code refused by user-service
  await dialog.getByLabel('Password').fill(pass);
  await dialog.getByLabel('Authenticator code').fill(totp(secret, 1));
  await dialog.getByRole('button', { name: 'Confirm' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByText('Limits saved')).toBeVisible();
  await page.reload();
  await expect(page.getByLabel(/Max orders per day/)).toHaveValue('5');
});

test('administrators see the live trading controls and the global halt state', async ({ page }) => {
  test.setTimeout(120_000);
  await signInAdmin(page);
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Admin' }).click();
  await expect(page.getByText('Live trading controls')).toBeVisible();
  await expect(page.getByTestId('global-halt')).toHaveText('open');
  await expect(page.getByRole('button', { name: 'Halt all live orders' })).toBeDisabled(); // reason required
});

test('changing a live halt needs the administrator\'s own password + authenticator code, and is audited', async ({ page }) => {
  test.setTimeout(150_000);
  expect(await signInAdmin(page), 'the seeded admin must have an authenticator enrolled').toBe(true);
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Admin' }).click();
  await expect(page.getByTestId('global-halt')).toHaveText('open');
  const reason = page.getByLabel(/Reason \(min. 10 characters, recorded\)/).last();
  await reason.fill('Playwright step-up check');
  await page.getByRole('button', { name: 'Halt all live orders' }).click();
  const form = page.getByTestId('step-up-form');
  await expect(form).toContainText('needs your own confirmation');

  // Rejected: wrong authenticator code. Nothing changes.
  await form.getByLabel('Password').fill(DEMO_PASSWORD);
  const good = await adminCode(page);
  await form.getByLabel('Authenticator code').fill(good === '000000' ? '111111' : '000000');
  await form.getByRole('button', { name: 'Confirm' }).click();
  await expect(form.getByRole('alert')).toContainText(/incorrect/i);
  await expect(page.getByTestId('global-halt')).toHaveText('open');

  // Accepted: password + the current code. The halt is applied.
  await form.getByLabel('Password').fill(DEMO_PASSWORD);
  await form.getByLabel('Authenticator code').fill(good);
  await form.getByRole('button', { name: 'Confirm' }).click();
  await expect(form).toHaveCount(0);
  await expect(page.getByTestId('global-halt')).toHaveText('HALTED');

  // Lifting reuses the 10-minute step-up (no second prompt).
  await reason.fill('Playwright step-up check finished');
  await page.getByRole('button', { name: 'Lift platform halt' }).click();
  await expect(page.getByTestId('global-halt')).toHaveText('open');
  await expect(page.getByTestId('step-up-form')).toHaveCount(0);

  // The platform audit chain (user 0) shows both changes with the step-up and stays intact.
  await page.getByLabel('User id').last().fill('0');
  await page.getByRole('button', { name: 'Look up' }).last().click();
  const audit = page.getByTestId('admin-live');
  await expect(audit).toContainText('Live audit chain intact');
  await expect(audit).toContainText(/KILL_SWITCH_OFF · ALLOWED · GLOBAL: Playwright step-up check finished \[was on; admin \d+; step-up pwd\+otp/);
  await expect(audit).toContainText(/KILL_SWITCH_ON · ALLOWED · GLOBAL: Playwright step-up check \[was off/);
});
