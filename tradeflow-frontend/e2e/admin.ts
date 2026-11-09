import { createHmac } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

// The seeded admin (admin@tradeflow.dev) has two-factor authentication, enrolled through the normal
// flow. Its seed comes from E2E_ADMIN_TOTP_SECRET or the git-ignored, owner-only
// .secrets/admin-totp.properties; without it the admin tests fail (they are never skipped).

export const DEMO_PASSWORD = process.env.TRADEFLOW_DEMO_PASSWORD ?? 'Demo@12345';

/** A new ordinary account with no second factor; the seeded demo user may have one turned on. */
export async function plainUser(request: APIRequestContext): Promise<{ email: string; password: string }> {
  const email = `plain-${Date.now()}-${Math.floor(Math.random() * 1000)}@tradeflow.dev`;
  const password = 'Plain-Passw0rd!';
  const res = await request.post('/api/auth/register', { data: { name: 'Plain User', email, password } });
  expect(res.status(), 'register a plain user').toBe(201);
  return { email, password };
}

export function totpAt(base32Secret: string, step: number): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of base32Secret.replace(/\s/g, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const h = createHmac('sha1', key).update(counter).digest();
  const o = h[h.length - 1] & 0x0f;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

function adminSecret(): string {
  if (process.env.E2E_ADMIN_TOTP_SECRET) return process.env.E2E_ADMIN_TOTP_SECRET;
  const file = resolve(process.cwd(), '../.secrets/admin-totp.properties');
  const line = existsSync(file) ? readFileSync(file, 'utf8').split('\n').find((l) => l.startsWith('secret=')) : undefined;
  if (!line) throw new Error(`admin@tradeflow.dev has two-factor authentication: set E2E_ADMIN_TOTP_SECRET or provide ${file}`);
  return line.slice('secret='.length).trim();
}

// user-service refuses a code whose 30-second step was already used (replay protection) and accepts
// one step either side of now, so every sign-in or step-up takes a later step than the last one. The
// last step is kept in a file so it survives Playwright restarting its worker after a failure.
const STEP_FILE = join(tmpdir(), 'tradeflow-e2e-admin-totp-step');

export async function adminCode(page: Page): Promise<string> {
  for (;;) {
    const last = existsSync(STEP_FILE) ? Number(readFileSync(STEP_FILE, 'utf8')) || 0 : 0;
    const now = Math.floor(Date.now() / 30_000);
    const step = [now, now + 1].find((s) => s > last);
    if (step !== undefined) {
      writeFileSync(STEP_FILE, String(step));
      return totpAt(adminSecret(), step);
    }
    await page.waitForTimeout(1_000);
  }
}

/** Signs in as the seeded admin; returns whether an authenticator code was asked for. */
export async function signInAdmin(page: Page): Promise<boolean> {
  await page.goto('/login');
  await page.getByLabel('Email').fill('admin@tradeflow.dev');
  await page.getByLabel('Password').fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  const mfa = page.getByLabel('Verification code');
  await expect(mfa.or(page.getByTestId('current-user'))).toBeVisible();
  const enrolled = await mfa.isVisible();
  if (enrolled) {
    await mfa.fill(await adminCode(page));
    await page.getByRole('button', { name: 'Verify and sign in' }).click();
  }
  await expect(page.getByTestId('current-user')).toBeVisible();
  return enrolled;
}

/** Opens the account menu (top right) and returns its navigation: Security, reviews, Admin. */
export async function accountMenu(page: Page) {
  await page.getByTestId('account-menu').click();
  return page.getByRole('navigation', { name: 'Account' });
}

/** Signs out through the account menu. */
export async function signOut(page: Page) {
  await page.getByTestId('account-menu').click();
  await page.getByTestId('account-menu-panel').getByRole('button', { name: 'Sign out' }).click();
}
