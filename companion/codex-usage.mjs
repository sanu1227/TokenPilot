import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

export function normalize(result) {
  const buckets = result.rateLimitsByLimitId ?? (result.rateLimits ? { codex: result.rateLimits } : {});
  const windows = [];
  for (const [bucketId, bucket] of Object.entries(buckets)) {
    for (const kind of ['primary', 'secondary']) {
      const w = bucket?.[kind];
      if (!w) continue;
      windows.push({
        bucketId, kind,
        remainingPercent: typeof w.usedPercent === 'number' && Number.isFinite(w.usedPercent)
          ? Math.max(0, Math.min(100, 100 - w.usedPercent)) : null,
        windowMinutes: Number.isFinite(w.windowDurationMins) ? w.windowDurationMins : null,
        resetsAt: Number.isFinite(w.resetsAt) ? w.resetsAt : null,
      });
    }
  }
  return { source: 'codex-app-server', checkedAt: new Date().toISOString(), windows };
}

export async function readUsage(executable = 'codex', timeoutMs = 30000) {
  const child = spawn(executable, ['app-server', '--listen', 'stdio://'], {
    windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'],
  });
  const lines = createInterface({ input: child.stdout });
  const pending = new Map();
  let nextId = 0;
  let fatal;
  const fail = (message) => {
    fatal = new Error(message);
    for (const p of pending.values()) p.reject(fatal);
    pending.clear();
  };
  child.on('error', () => fail('Codex 실행 실패: 설치 및 PATH를 확인하세요.'));
  child.on('exit', () => fail('Codex 조회 프로세스가 종료됐습니다.'));
  child.stdin.on('error', () => fail('Codex 통신이 끊겼습니다.'));
  lines.on('line', line => {
    let msg;
    try { msg = JSON.parse(line); } catch { return; }
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    if (msg.error) p.reject(new Error(`Codex 조회 오류 (${msg.error.code}); Codex 로그인 상태를 확인하세요.`));
    else p.resolve(msg.result);
  });
  const request = (method, params) => new Promise((resolve, reject) => {
    if (fatal) { reject(fatal); return; }
    const id = ++nextId;
    pending.set(id, { resolve, reject });
    child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
  });
  const timer = setTimeout(() => { fail('Codex 조회 시간 초과'); child.kill(); }, timeoutMs);
  try {
    await request('initialize', { clientInfo: { name: 'tokenpilot', version: '0.1.0' } });
    child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
    return normalize(await request('account/rateLimits/read', {}));
  } finally {
    clearTimeout(timer);
    lines.close();
    child.stdin.end();
    child.kill();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { console.log(JSON.stringify(await readUsage(), null, 2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
