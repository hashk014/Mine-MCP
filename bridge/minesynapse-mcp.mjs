#!/usr/bin/env node
/** Zero-dependency stdio MCP bridge. stdout contains JSON-RPC only. Node.js >= 18. */
import { readFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export function defaultGameDir() {
  if (process.platform === 'win32') return path.join(process.env.APPDATA || path.join(homedir(), 'AppData', 'Roaming'), '.minecraft');
  if (process.platform === 'darwin') return path.join(homedir(), 'Library', 'Application Support', 'minecraft');
  return path.join(homedir(), '.minecraft');
}

export function validateConnection(data) {
  const url = new URL(data.url);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname) || url.pathname !== '/mcp' || url.search || url.hash || url.username || url.password || !url.port)
    throw new Error('Connection file must point to a loopback HTTP /mcp endpoint');
  if (typeof data.token !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(data.token)) throw new Error('Invalid connection token');
  // Always use IPv4, matching the mod bind address even when localhost resolves to IPv6.
  url.hostname = '127.0.0.1';
  return { url: url.href, token: data.token };
}

export function parseArgs(argv) {
  const args = { gameDir: process.env.MINESYNAPSE_GAME_DIR || defaultGameDir() };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--game-dir' && argv[i + 1]) args.gameDir = path.resolve(argv[++i]);
    else if (argv[i] === '--connection-file' && argv[i + 1]) args.connectionFile = path.resolve(argv[++i]);
    else throw new Error('Usage: node minesynapse-mcp.mjs [--game-dir <instance directory>] [--connection-file <file>]');
  }
  return args;
}

export async function forward(message, connectionFile, fetcher = fetch) {
  const connection = validateConnection(JSON.parse(await readFile(connectionFile, 'utf8')));
  const response = await fetcher(connection.url, {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(20000),
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', Authorization: `Bearer ${connection.token}` },
    body: JSON.stringify(message),
  });
  if (!response.ok) throw new Error(`Minecraft MCP returned HTTP ${response.status}; check the game instance and connection file`);
  if (response.status === 202 || response.status === 204) return null;
  return await response.json();
}

export async function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  const connectionFile = args.connectionFile || path.join(args.gameDir, 'config', 'minesynapse-mcp-connection.json');
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  const send = value => process.stdout.write(JSON.stringify(value) + '\n');
  let active = 0;
  for await (const line of lines) {
    if (!line.trim()) continue;
    if (Buffer.byteLength(line, 'utf8') > 1024 * 1024) { send({ jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Request too large' } }); continue; }
    let message;
    try { message = JSON.parse(line); }
    catch { send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Invalid JSON' } }); continue; }
    if (!message || Array.isArray(message) || typeof message !== 'object') { send({ jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Expected one JSON-RPC object' } }); continue; }
    if (active >= 32) { if ('id' in message) send({ jsonrpc: '2.0', id: message.id, error: { code: -32000, message: 'Too many in-flight requests' } }); continue; }
    active++;
    // Independent requests must not wait behind a screenshot or a game tick timeout.
    forward(message, connectionFile).then(result => { if (result) send(result); }).catch(error => {
      if ('id' in message) send({ jsonrpc: '2.0', id: message.id, error: { code: -32000, message: String(error.message).slice(0, 500) } });
      else process.stderr.write('MineSynapse MCP: notification could not be delivered\n');
    }).finally(() => { active--; });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => { process.stderr.write(`MineSynapse MCP: ${error.message}\n`); process.exitCode = 1; });
}
