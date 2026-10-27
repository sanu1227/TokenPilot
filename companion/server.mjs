import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
import { readUsage } from './codex-usage.mjs';

export function usageServer(reader = readUsage) {
  let cached;
  let cachedAt = 0;
  let pending;
  return createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    // Browser origins are not supported. The Android client uses the ADB tunnel.
    if (req.headers.origin || !/^127\.0\.0\.1(?::\d+)?$/.test(req.headers.host ?? '')) {
      res.writeHead(403).end('{}'); return;
    }
    if (req.method !== 'GET' || req.url !== '/usage') {
      res.writeHead(404).end('{}'); return;
    }
    try {
      if (!cached || Date.now() - cachedAt >= 55000) {
        pending ??= reader().then(value => { cached = value; cachedAt = Date.now(); }).finally(() => { pending = null; });
        await pending;
      }
      res.end(JSON.stringify(cached));
    } catch {
      res.writeHead(503).end(JSON.stringify({ error: 'Codex 조회 실패. PC 로그인 상태를 확인하세요.' }));
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = usageServer();
  server.on('error', () => { console.error('연결 서버 실행 실패: 포트 18745 사용 여부를 확인하세요.'); process.exitCode = 1; });
  server.listen(18745, '127.0.0.1', () => console.log('TokenPilot companion: 127.0.0.1:18745'));
}
