/** Optional integration check with the official MCP SDK; not a runtime dependency.
 * npm install --prefix .cache/sdk --no-save --ignore-scripts @modelcontextprotocol/sdk@1.28.0
 * node scripts/test_mcp_sdk.mjs run/1.21.11
 */
import { Client } from '../.cache/sdk/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '../.cache/sdk/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import { StreamableHTTPClientTransport } from '../.cache/sdk/node_modules/@modelcontextprotocol/sdk/dist/esm/client/streamableHttp.js';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const game = path.resolve(process.argv[2] || 'run/1.21.11');
const connection = JSON.parse(await readFile(path.join(game, 'config/mine-mcp-connection.json'), 'utf8'));
const reports = [];
for (const [name, transport] of [
  ['stdio bridge', new StdioClientTransport({ command: process.execPath, args: ['bridge/mine-mcp.mjs', '--game-dir', game] })],
  ['direct Streamable HTTP', new StreamableHTTPClientTransport(new URL(connection.url), { requestInit: { headers: { Authorization: `Bearer ${connection.token}` } } })],
]) {
  const client = new Client({ name: 'minemcp-validation', version: '1.0.0' });
  try {
    await client.connect(transport);
    assert.equal((await client.listTools()).tools.length, 45);
    assert.ok(!(await client.callTool({ name: 'get_status', arguments: {} })).isError);
    assert.equal((await client.listResources()).resources.length, 4);
    assert.equal((await client.readResource({ uri: 'minecraft://status' })).contents.length, 1);
    assert.equal((await client.getPrompt({ name: 'observe_then_act' })).messages.length, 1);
    reports.push({ transport: name, status: 'passed', checks: ['initialize', '45 tools', 'tools/call', '4 resources', 'resources/read', 'prompts/get'] });
    console.log('PASS official MCP SDK:', name);
  } finally { await client.close(); }
}
await writeFile('docs/sdk-validation.json', JSON.stringify({ sdk: '@modelcontextprotocol/sdk', version: '1.28.0', minecraft: connection.minecraftVersion, reports }, null, 2) + '\n');
