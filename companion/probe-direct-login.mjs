// Personal feasibility check: no Codex process, local auth files, or persistent credentials.
// Protocol reference: openai/codex @ 6fe77fc12346591b8037a8704640e40196b19044.
const clientId = 'app_EMoamEEZ73f0CkXaXp7hrann';
const auth = 'https://auth.openai.com';
let tokens;
async function request(url, body, headers = {}) {
  return fetch(url, {
    method: body ? 'POST' : 'GET', redirect: 'error',
    headers: { 'User-Agent': 'TokenPilot/0.1 (personal compatibility test)', ...headers },
    body, signal: AbortSignal.timeout(20000),
  });
}
async function jsonPost(url, body) {
  return request(url, JSON.stringify(body), { 'Content-Type': 'application/json' });
}
try {
  const start = await jsonPost(`${auth}/api/accounts/deviceauth/usercode`, { client_id: clientId });
  if (!start.ok) throw new Error(`Code request HTTP ${start.status}`);
  const device = await start.json();
  const userCode = device.user_code ?? device.usercode;
  if (!device.device_auth_id || !userCode) throw new Error('Invalid device response');
  console.log(JSON.stringify({ verificationUrl: `${auth}/codex/device`, userCode, expiresInMinutes: 15 }));
  const deadline = Date.now() + 15 * 60 * 1000;
  let grant;
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, Math.max(5, Number(device.interval) || 5) * 1000));
    const poll = await jsonPost(`${auth}/api/accounts/deviceauth/token`, {
      device_auth_id: device.device_auth_id, user_code: userCode,
    });
    if (poll.ok) { grant = await poll.json(); break; }
    if (![403, 404].includes(poll.status)) throw new Error(`Login polling HTTP ${poll.status}`);
  }
  if (!grant) throw new Error('Login timed out');
  const exchange = await request(`${auth}/oauth/token`, new URLSearchParams({
    grant_type: 'authorization_code', client_id: clientId,
    code: grant.authorization_code, code_verifier: grant.code_verifier,
    redirect_uri: `${auth}/deviceauth/callback`,
  }).toString(), { 'Content-Type': 'application/x-www-form-urlencoded' });
  if (!exchange.ok) throw new Error(`Token exchange HTTP ${exchange.status}`);
  tokens = await exchange.json();
  if (typeof tokens.access_token !== 'string') throw new Error('Missing access token');
  // Metadata comes only from the TLS-protected token endpoint, not user-supplied JWTs.
  const payload = JSON.parse(Buffer.from(tokens.access_token.split('.')[1], 'base64url').toString());
  const account = payload['https://api.openai.com/auth']?.chatgpt_account_id;
  const headers = { Authorization: `Bearer ${tokens.access_token}`, Accept: 'application/json' };
  if (account) headers['ChatGPT-Account-Id'] = account;
  const usage = await request('https://chatgpt.com/backend-api/wham/usage', undefined, headers);
  if (!usage.ok) throw new Error(`Quota request HTTP ${usage.status}`);
  const data = await usage.json();
  const windows = ['primary_window', 'secondary_window'].flatMap(key => {
    const w = data.rate_limit?.[key];
    return w ? [{ minutes: w.limit_window_seconds / 60, remainingPercent: 100 - w.used_percent, resetsAt: w.reset_at }] : [];
  });
  console.log(JSON.stringify({ directQuotaRead: true, status: usage.status, windows, hasAdditionalLimits: Array.isArray(data.additional_rate_limits) }));
} catch (error) {
  // Only our fixed diagnostics are emitted; never dump request/response bodies or token objects.
  const message = /^(Code request HTTP|Invalid device response|Login polling HTTP|Login timed out|Token exchange HTTP|Missing access token|Quota request HTTP)/.test(error.message)
    ? error.message : `Network or response error (${error.name})`;
  console.log(JSON.stringify({ directQuotaRead: false, error: message }));
  process.exitCode = 1;
} finally {
  if (tokens?.refresh_token) {
    try {
      const response = await jsonPost(`${auth}/oauth/revoke`, {
        token: tokens.refresh_token, token_type_hint: 'refresh_token', client_id: clientId,
      });
      console.log(JSON.stringify({ testSessionRevoked: response.ok }));
    } catch { console.log(JSON.stringify({ testSessionRevoked: false })); }
  }
  tokens = undefined;
}
