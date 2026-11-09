import { expect, test } from '@playwright/test';
import { accountMenu, signOut, totpAt } from './admin';

const PASSWORD = 'Profile-Passw0rd!';

test('a user edits their profile and saves masked bank details, typing without losing the cursor', async ({ page }) => {
  test.setTimeout(120_000);
  const email = `profile-${Date.now()}@tradeflow.dev`;
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Profile Tester');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();

  await (await accountMenu(page)).getByRole('link', { name: 'Profile' }).click();
  await expect(page.getByTestId('profile-email')).toHaveText(email);
  await page.getByLabel('Full name').fill('Renamed Tester');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByTestId('current-user')).toHaveText('Renamed Tester');

  // Bank details are unavailable until two-factor authentication is on.
  await expect(page.getByText(/to add or change bank details/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add bank account' })).toHaveCount(0);

  await (await accountMenu(page)).getByRole('link', { name: 'Security' }).click();
  await page.getByLabel('Confirm your password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Set up authenticator' }).click();
  const secret = (await page.getByTestId('totp-secret').textContent())!.replace(/\s/g, '');
  const step = Math.floor(Date.now() / 30_000);
  await page.getByLabel('Code from your app').fill(totpAt(secret, step));
  await page.getByRole('button', { name: 'Turn on two-factor authentication' }).click();
  await page.getByLabel('I have stored these codes somewhere safe').check();
  await page.getByRole('button', { name: 'Done' }).click();

  await (await accountMenu(page)).getByRole('link', { name: 'Profile' }).click();
  await page.getByRole('button', { name: 'Add bank account' }).click();
  const form = page.getByTestId('bank-form');
  await form.getByLabel('Account holder name').fill('Renamed Tester');
  await form.getByLabel('Bank name').fill('Test Bank');
  await form.getByLabel('Account number', { exact: true }).fill('501234567890');
  await form.getByLabel('Confirm account number').fill('501234567890');
  await form.getByLabel('IFSC').fill('HDFC0001234');
  await form.getByLabel('Password').fill(PASSWORD);

  // Real key presses: the field must keep focus after every digit.
  const code = form.getByLabel('Authenticator code');
  await code.click();
  await page.keyboard.type(totpAt(secret, step + 1), { delay: 40 });
  await expect(code).toBeFocused();
  await expect(code).toHaveValue(/^\d{6}$/);

  const saved = page.waitForResponse((r) => r.url().endsWith('/api/users/me/bank-account') && r.request().method() === 'PUT');
  await form.getByRole('button', { name: 'Save' }).click();
  const res = await saved;
  expect(res.status()).toBe(200);
  expect(await res.text()).not.toContain('501234567890');
  await expect(page.getByTestId('bank-masked-number')).toHaveText('••••••7890');
  await expect(page.getByText('501234567890')).toHaveCount(0);
});

test('the command palette and keyboard shortcuts work from the keyboard', async ({ page }) => {
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Keys Tester');
  await page.getByLabel('Email').fill(`keys-${Date.now()}@tradeflow.dev`);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();

  await page.keyboard.press('ControlOrMeta+k');
  const search = page.getByRole('combobox');
  await expect(search).toBeFocused();
  await page.keyboard.type('profile');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/settings\/profile$/);

  await page.locator('body').click({ position: { x: 2, y: 2 } });
  await page.keyboard.press('g');
  await page.keyboard.press('o');
  await expect(page).toHaveURL(/\/demo\/orders$/);
  await page.keyboard.press('?');
  await expect(page.getByTestId('shortcut-list')).toContainText('Go to Portfolio');
});

test('a passkey can be added and then used to sign in, with devices and activity recorded', async ({ page }) => {
  test.setTimeout(120_000);
  // A software authenticator that behaves like a fingerprint-protected device.
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true },
  });

  const email = `passkey-${Date.now()}@tradeflow.dev`;
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Passkey Tester');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();

  await (await accountMenu(page)).getByRole('link', { name: 'Security' }).click();
  await page.getByLabel('Confirm your password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Set up authenticator' }).click();
  const secret = (await page.getByTestId('totp-secret').textContent())!.replace(/\s/g, '');
  const step = Math.floor(Date.now() / 30_000);
  await page.getByLabel('Code from your app').fill(totpAt(secret, step));
  await page.getByRole('button', { name: 'Turn on two-factor authentication' }).click();
  await page.getByLabel('I have stored these codes somewhere safe').check();
  await page.getByRole('button', { name: 'Done' }).click();

  await page.getByRole('button', { name: 'Add a passkey' }).click();
  const form = page.getByTestId('passkey-form');
  await form.getByLabel('Passkey name').fill('Test device');
  await form.getByLabel('Password').fill(PASSWORD);
  await form.getByLabel('Authenticator code').fill(totpAt(secret, step + 1));
  await form.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByTestId('passkey-list')).toContainText('Test device');
  await expect(page.getByTestId('session-list')).toContainText('This device');

  await signOut(page);
  await page.getByRole('button', { name: 'Sign in with a passkey' }).click();
  await expect(page.getByTestId('current-user')).toHaveText('Passkey Tester');

  await page.goto('/settings/activity');
  await expect(page.getByTestId('activity-list')).toContainText('Passkey added');
  await expect(page.getByTestId('activity-list')).toContainText('passkey');
});
