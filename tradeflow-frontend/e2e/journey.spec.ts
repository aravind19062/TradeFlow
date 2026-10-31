import { createHmac } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

// Full user journey against the real backend. Every figure the UI shows is checked
// against the API response for the same signed-in user.

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const DEMO_PASSWORD = process.env.TRADEFLOW_DEMO_PASSWORD ?? 'Demo@12345';
const runId = Date.now();
const user = { name: `E2E Trader ${runId}`, email: `e2e-${runId}@tradeflow.dev`, password: 'E2e-Passw0rd!' };

/** Access tokens live only in the page's memory, so API checks sign in separately. */
async function apiToken(request: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await request.post('/api/auth/login', { data: { email, password } });
  expect(res.status(), 'API sign-in for verification').toBe(200);
  return (await res.json()).accessToken as string;
}

/** RFC 6238 TOTP (SHA-1, 30 s, 6 digits), as an authenticator app computes it. */
function totp(base32Secret: string, offsetSteps = 0): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of base32Secret.replace(/\s/g, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000) + offsetSteps));
  const h = createHmac('sha1', key).update(counter).digest();
  const o = h[h.length - 1] & 0x0f;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

async function api<T>(request: APIRequestContext, token: string | null, path: string): Promise<{ status: number; body: T }> {
  const res = await request.get(`/api${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  return { status: res.status(), body: (await res.json().catch(() => null)) as T };
}

async function signIn(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test.describe.serial('TradeFlow user journey', () => {
  let page: Page;
  let token: string;
  let marketOrderId: string;

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage();
  });
  test.afterAll(async () => page.close());

  test('1. register a new account and land on the dashboard', async ({ request }) => {
    await page.goto('/register');
    await page.getByLabel('Full name').fill(user.name);
    await page.getByLabel('Email').fill(user.email);
    await page.getByLabel('Password').fill(user.password);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByRole('heading', { name: /Welcome, E2E/ })).toBeVisible();
    await expect(page.getByTestId('current-user')).toHaveText(user.name);
    // The access token is in memory only: nothing in web storage.
    expect(await page.evaluate(() => sessionStorage.length + localStorage.length)).toBe(0);
    token = await apiToken(request, user.email, user.password);

    const me = await api<{ email: string; role: string }>(request, token, '/users/me');
    expect(me.body).toMatchObject({ email: user.email, role: 'USER' });
  });

  test('2. dashboard figures come from the portfolio API', async ({ request }) => {
    // The account is opened asynchronously (users.registered -> portfolio-service).
    await expect(page.getByTestId('stat-total-value')).toBeVisible({ timeout: 20_000 });
    const summary = await api<{ totalValue: number; availableCash: number }>(request, token, '/portfolio/summary');
    expect(summary.status).toBe(200);
    await expect(page.getByTestId('stat-total-value')).toContainText(inr.format(summary.body.totalValue));
    await expect(page.getByTestId('stat-available-cash')).toContainText(inr.format(summary.body.availableCash));
  });

  test('3. market watch lists every instrument with live simulated quotes', async ({ request }) => {
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Market watch' }).click();
    const instruments = await api<unknown[]>(request, null, '/market/instruments');
    await expect(page.getByTestId('market-table').locator('tbody tr')).toHaveCount(instruments.body.length);
    const quote = await api<{ price: number }>(request, null, '/market/quotes/INFY');
    await expect(page.getByTestId('market-table').getByRole('row', { name: /INFY/ })).toContainText('₹');
    expect(quote.body.price).toBeGreaterThan(0);
  });

  test('4. place a MARKET BUY and verify it through the backend', async ({ request }) => {
    await page.goto('/demo/market/INFY');
    await expect(page.getByTestId('instrument-price')).toContainText('₹');
    await page.getByLabel('Quantity (shares)').fill('2');
    await page.getByRole('button', { name: 'Review buy order' }).click();
    const dialog = page.getByRole('dialog', { name: 'Confirm order' });
    await expect(dialog).toContainText('INFY');
    await dialog.getByRole('button', { name: 'Place buy order' }).click();

    await expect(page.getByTestId('order-result')).toContainText('FILLED', { timeout: 20_000 });

    const orders = await api<{ content: { id: string; symbol: string; side: string; quantity: number; status: string; clientOrderId: string }[] }>(
      request, token, '/orders?symbol=INFY&size=5');
    const placed = orders.body.content[0];
    expect(placed).toMatchObject({ symbol: 'INFY', side: 'BUY', quantity: 2, status: 'FILLED' });
    expect(placed.clientOrderId).toMatch(/^web-/); // idempotency key sent by the ticket
    marketOrderId = placed.id;
  });

  test('5. portfolio shows the new holding', async ({ request }) => {
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Portfolio' }).click();
    const row = page.getByTestId('holdings-table').getByRole('row', { name: /INFY/ });
    await expect(row).toBeVisible();
    const summary = await api<{ holdings: { symbol: string; quantity: number; marketValue: number }[] }>(request, token, '/portfolio/summary');
    const infy = summary.body.holdings.find((h) => h.symbol === 'INFY')!;
    expect(infy.quantity).toBe(2);
    await expect(row.locator('td').nth(1)).toHaveText('2');
  });

  test('6. order history and trades show the execution', async () => {
    await page.goto('/demo/orders?tab=history');
    await expect(page.locator(`[data-order-id="${marketOrderId}"]`)).toContainText('FILLED');
    await page.getByRole('tab', { name: 'Trades' }).click();
    await expect(page.getByTestId('trades-table')).toContainText('INFY');
    await page.goto(`/demo/orders/${marketOrderId}`);
    await expect(page.getByText('Status history')).toBeVisible();
    await expect(page.getByText(/Filled 2 @/).first()).toBeVisible();
  });

  test('7. LIMIT order rests OPEN with cash reserved, then is cancelled', async ({ request }) => {
    await page.goto('/demo/market/INFY');
    await page.getByRole('radio', { name: 'Limit' }).click();
    await page.getByLabel('Quantity (shares)').fill('1');
    await page.getByLabel('Limit price (₹)').fill('1.00');
    await page.getByRole('button', { name: 'Review buy order' }).click();
    await page.getByRole('dialog', { name: 'Confirm order' }).getByRole('button', { name: 'Place buy order' }).click();
    await expect(page.getByTestId('order-result')).toContainText('OPEN', { timeout: 20_000 });

    const open = await api<{ content: { id: string; status: string }[] }>(request, token, '/orders?status=OPEN');
    expect(open.body.content).toHaveLength(1);
    const limitId = open.body.content[0].id;
    expect((await api<{ reservedCash: number }>(request, token, '/portfolio/summary')).body.reservedCash).toBe(1);

    await page.goto('/demo/orders');
    const row = page.locator(`[data-order-id="${limitId}"]`);
    await expect(row).toContainText('OPEN');
    await row.getByRole('button', { name: /Cancel order/ }).click();
    const dialog = page.getByRole('dialog', { name: 'Cancel this order?' });
    await dialog.getByRole('button', { name: 'Cancel order' }).click();
    await expect(row).toHaveCount(0);

    const detail = await api<{ order: { status: string } }>(request, token, `/orders/${limitId}`);
    expect(detail.body.order.status).toBe('CANCELLED');
    await expect.poll(async () => (await api<{ reservedCash: number }>(request, token, '/portfolio/summary')).body.reservedCash).toBe(0);
  });

  test('8. AI assistant answers from the user\'s own portfolio', async () => {
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'AI assistant' }).click();
    await page.getByLabel('Your question').fill('What are my holdings and their average cost?');
    await page.getByRole('button', { name: 'Ask' }).click();
    const answer = page.getByTestId('assistant-answer').last();
    await expect(answer).toContainText('INFY', { timeout: 30_000 });
    await expect(answer).toHaveAttribute('data-mode', /DETERMINISTIC|LLM/);

    await page.getByLabel('Your question').fill('Buy 100 RELIANCE for me');
    await page.getByRole('button', { name: 'Ask' }).click();
    await expect(page.getByTestId('assistant-answer').last()).toHaveAttribute('data-mode', 'REFUSED');
  });

  test('9. watchlist and alerts round-trip through the API', async ({ request }) => {
    await page.goto('/demo/market/TCS');
    await page.getByRole('button', { name: 'Watch', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Watching' })).toBeVisible();
    await page.getByLabel('Price (₹)').fill('1.00');
    await page.getByRole('radio', { name: 'Falls to' }).click();
    await page.getByRole('button', { name: 'Create alert' }).click();
    await expect(page.getByText(/Alert set: TCS/)).toBeVisible();

    const wl = await api<{ symbol: string }[]>(request, token, '/watchlist');
    expect(wl.body.map((q) => q.symbol)).toContain('TCS');
    const alerts = await api<{ symbol: string; direction: string; threshold: number; status: string }[]>(request, token, '/alerts');
    expect(alerts.body[0]).toMatchObject({ symbol: 'TCS', direction: 'BELOW', threshold: 1, status: 'ACTIVE' });
  });

  test('10a. every demo page is labelled DEMO and demo API responses carry the gateway label', async ({ request }) => {
    for (const path of ['/demo', '/demo/market', '/demo/orders', '/demo/portfolio', '/demo/watchlist', '/demo/assistant']) {
      await page.goto(path);
      await expect(page.getByTestId('demo-banner')).toContainText('Simulated prices and virtual money');
    }
    const res = await request.get('/api/demo/portfolio/summary', { headers: { Authorization: `Bearer ${token}` } });
    expect(res.headers()['x-tradeflow-environment']).toBe('DEMO');
    // Pre-dual-mode bookmarks redirect into the demo area.
    await page.goto('/market/TCS');
    await expect(page).toHaveURL(/\/demo\/market\/TCS$/);
  });

  test('10b. a reload keeps the session through the HttpOnly refresh cookie', async () => {
    await page.reload();
    await expect(page.getByTestId('current-user')).toHaveText(user.name);
    const cookies = await page.context().cookies();
    const refresh = cookies.find((c) => c.name === 'tf_refresh');
    expect(refresh?.httpOnly).toBe(true);
    expect(refresh?.sameSite).toBe('Strict');
    expect(await page.evaluate(() => document.cookie)).not.toContain('tf_refresh');
  });

  test('10. sign out revokes the session on the server', async () => {
    const cookie = (await page.context().cookies()).find((c) => c.name === 'tf_refresh')!.value;
    await page.getByRole('button', { name: 'Sign out' }).first().click();
    await expect(page).toHaveURL(/\/login$/);
    await page.goto('/demo/portfolio');
    await expect(page).toHaveURL(/\/login$/);
    // The old refresh token is dead even if someone kept a copy.
    const res = await page.request.post('/api/auth/refresh', {
      headers: { 'X-TradeFlow-Client': 'web', Cookie: `tf_refresh=${cookie}` },
    });
    expect(res.status()).toBe(401);
  });
});

test.describe('two-factor authentication', () => {
  test('enrol TOTP, then sign in with a code and with a recovery code', async ({ page }) => {
    const id = Date.now();
    const mail = `mfa-${id}@tradeflow.dev`;
    const pass = 'Mfa-Passw0rd!';
    await page.goto('/register');
    await page.getByLabel('Full name').fill('MFA Tester');
    await page.getByLabel('Email').fill(mail);
    await page.getByLabel('Password').fill(pass);
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByTestId('current-user')).toBeVisible();

    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Security' }).click();
    await page.getByLabel('Confirm your password').fill(pass);
    await page.getByRole('button', { name: 'Set up authenticator' }).click();
    const secret = (await page.getByTestId('totp-secret').textContent())!.replace(/\s/g, '');
    expect(secret).toMatch(/^[A-Z2-7]{32}$/);
    await expect(page.getByRole('img', { name: /QR code/ })).toBeVisible();
    await page.getByLabel('Code from your app').fill(totp(secret));
    await page.getByRole('button', { name: 'Turn on two-factor authentication' }).click();
    await expect(page.getByTestId('recovery-codes').locator('li')).toHaveCount(10);
    const codes = await page.getByTestId('recovery-codes').locator('li').allTextContents();
    await page.getByLabel('I have stored these codes somewhere safe').check();
    await page.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByText(/unused recovery codes left/)).toBeVisible();

    // Sign out, sign in: the password alone is not enough.
    await page.getByRole('button', { name: 'Sign out' }).first().click();
    await expect(page).toHaveURL(/\/login$/); // logout has completed (session revoked server-side)
    await signIn(page, mail, pass);
    await expect(page.getByRole('heading', { name: 'Two-factor verification' })).toBeVisible();
    await page.getByLabel('Verification code').fill('000000' === totp(secret, 1) ? '111111' : '000000');
    await page.getByRole('button', { name: 'Verify and sign in' }).click();
    await expect(page.getByRole('alert')).toContainText('not valid');
    await page.getByLabel('Verification code').fill(totp(secret, 1));
    await page.getByRole('button', { name: 'Verify and sign in' }).click();
    await expect(page.getByTestId('current-user')).toHaveText('MFA Tester');

    // Recovery code path, then the code is used up.
    await page.getByRole('button', { name: 'Sign out' }).first().click();
    await expect(page).toHaveURL(/\/login$/); // logout has completed (session revoked server-side)
    await signIn(page, mail, pass);
    await page.getByRole('button', { name: 'Use a recovery code' }).click();
    await page.getByLabel('Recovery code').fill(codes[0]);
    await page.getByRole('button', { name: 'Verify and sign in' }).click();
    await expect(page.getByTestId('current-user')).toHaveText('MFA Tester');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Security' }).click();
    await expect(page.getByText('9 unused recovery codes left')).toBeVisible();
    await expect(page.getByTestId('security-activity')).toContainText('Recovery code used');
  });
});

test.describe('authentication and authorization', () => {
  test('protected pages redirect to sign-in without a session', async ({ page }) => {
    await page.goto('/demo/orders');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });

  test('wrong password shows the backend error', async ({ page }) => {
    await signIn(page, `nobody-${runId}@tradeflow.dev`, 'wrong-password');
    await expect(page.getByRole('alert')).toContainText('Invalid email or password');
  });

  test('registration shows field validation from the backend', async ({ page }) => {
    await page.goto('/register');
    await page.getByLabel('Full name').fill('X');
    await page.getByLabel('Email').fill('not-an-email');
    await page.getByLabel('Password').fill('short');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByText('must be a well-formed email address')).toBeVisible();
    await expect(page.getByText('must be 8-72 characters')).toBeVisible();
  });

  test('forged and expired-format tokens are rejected by the gateway', async ({ request }) => {
    const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const claims = { iss: 'tradeflow-user-service', aud: 'tradeflow', sub: '1', roles: ['ADMIN'], exp: Math.floor(Date.now() / 1000) + 3600 };
    for (const alg of ['RS256', 'HS256', 'none']) {
      const forged = `${b64({ alg, kid: 'x' })}.${b64(claims)}.${alg === 'none' ? '' : 'forged'}`;
      const res = await request.get('/api/admin/reconciliation', { headers: { Authorization: `Bearer ${forged}` } });
      expect(res.status(), alg).toBe(401);
    }
  });

  test('USER role cannot reach admin pages or admin APIs', async ({ page, request }) => {
    await signIn(page, 'demo@tradeflow.dev', DEMO_PASSWORD);
    await expect(page.getByTestId('current-user')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Admin' })).toHaveCount(0);
    await page.goto('/admin');
    await expect(page).toHaveURL(/\/demo$/);
    const token = await apiToken(request, 'demo@tradeflow.dev', DEMO_PASSWORD);
    expect((await api(request, token, '/admin/reconciliation')).status).toBe(403);
    expect((await api(request, null, '/portfolio/summary')).status).toBe(401);
  });

  test('ADMIN role sees the admin tools', async ({ page, request }) => {
    await signIn(page, 'admin@tradeflow.dev', DEMO_PASSWORD);
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Admin' }).click();
    const sim = await api<{ currentTick: number }>(request, null, '/market/simulation');
    await expect(page.getByTestId('admin-tick')).toHaveText(/^\d+$/);
    expect(Number(await page.getByTestId('admin-tick').textContent())).toBeGreaterThanOrEqual(sim.body.currentTick - 1);
    await page.getByRole('button', { name: 'Run reconciliation' }).click();
    await expect(page.getByText(/accounts checked/)).toBeVisible();
  });

  test('mobile layout uses a slide-out menu', async ({ browser }) => {
    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await signIn(mobile, 'demo@tradeflow.dev', DEMO_PASSWORD);
    await expect(mobile.getByRole('heading', { name: /Welcome back/ })).toBeVisible();
    await mobile.getByRole('button', { name: 'Open menu' }).click();
    await mobile.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Orders' }).click();
    await expect(mobile.getByRole('heading', { name: 'Orders' })).toBeVisible();
    await mobile.close();
  });
});
