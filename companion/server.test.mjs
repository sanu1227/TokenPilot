import { test } from 'node:test';
import assert from 'node:assert/strict';
import { usageServer } from './server.mjs';

test('loopback server caches reads and blocks browser origins', async () => {
  let count = 0;
  const server = usageServer(async () => { count++; return { windows: [] }; });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  try {
    const url = `http://127.0.0.1:${server.address().port}/usage`;
    const responses = await Promise.all([fetch(url), fetch(url)]);
    assert.ok(responses.every(r => r.status === 200));
    assert.equal(count, 1);
    assert.equal((await fetch(url, { headers: { Origin: 'https://example.com' } })).status, 403);
    assert.equal((await fetch(url, { method: 'POST' })).status, 404);
  } finally { server.closeAllConnections(); await new Promise(r => server.close(r)); }
});
test('reader failure is 503, not fabricated quota', async () => {
  const server = usageServer(async () => { throw new Error('private details'); });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/usage`);
    assert.equal(response.status, 503);
    assert.ok(!(await response.text()).includes('private details'));
  } finally { server.closeAllConnections(); await new Promise(r => server.close(r)); }
});
