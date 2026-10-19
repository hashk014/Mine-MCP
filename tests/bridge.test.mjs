import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { validateConnection, parseArgs, forward } from '../bridge/minesynapse-mcp.mjs';

const token = 'a'.repeat(43);
test('connection validation rejects remote URLs and credential forwarding', () => {
  for (const url of ['https://127.0.0.1:10/mcp', 'http://evil.example:10/mcp', 'http://127.0.0.1:10/mcp?q=1', 'http://user:pass@localhost:1/mcp', 'http://localhost:1/wrong']) {
    assert.throws(() => validateConnection({ url, token }));
  }
  assert.throws(() => validateConnection({ url: 'http://localhost:10/mcp', token: 'short' }));
  assert.equal(validateConnection({ url: 'http://localhost:10/mcp', token }).url, 'http://127.0.0.1:10/mcp');
});
test('launcher paths are explicit and argument errors are clear', () => {
  assert.equal(parseArgs(['--game-dir', './run']).gameDir, path.resolve('run'));
  assert.throws(() => parseArgs(['--url', 'https://remote.example']));
  assert.throws(() => parseArgs(['--game-dir']));
});
test('forward, notification and concurrent stdio preserve JSON-RPC IDs and do not log secrets', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'minesynapse-bridge-'));
  const file = path.join(dir, 'connection.json');
  const received = [];
  const server = createServer(async (req, res) => {
    assert.equal(req.headers.authorization, `Bearer ${token}`);
    let body = ''; for await (const part of req) body += part;
    const message = JSON.parse(body); received.push(message);
    if (!('id' in message)) { res.writeHead(202); res.end(); return; }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result: {} }));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    await writeFile(file, JSON.stringify({ url: `http://127.0.0.1:${server.address().port}/mcp`, token }));
    assert.equal((await forward({ jsonrpc: '2.0', id: 'abc', method: 'ping' }, file)).id, 'abc');
    assert.equal(await forward({ jsonrpc: '2.0', method: 'notifications/initialized' }, file), null);
    const child = spawn(process.execPath, ['bridge/minesynapse-mcp.mjs', '--connection-file', file], { cwd: path.resolve('.') });
    let stdout = '', stderr = '';
    child.stdout.on('data', data => { stdout += data; }); child.stderr.on('data', data => { stderr += data; });
    child.stdin.end([1, 2, 3].map(id => JSON.stringify({ jsonrpc: '2.0', id, method: 'ping' })).join('\n') + '\n');
    const [exitCode] = await once(child, 'exit'); assert.equal(exitCode, 0);
    assert.deepEqual(stdout.trim().split('\n').map(line => JSON.parse(line).id).sort(), [1, 2, 3]);
    assert.equal(stderr, ''); assert.ok(!stdout.includes(token));
    assert.equal(received.length, 5);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(dir, { recursive: true, force: true }); }
});
