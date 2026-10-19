"""Call a local test instance without echoing its credential. Developer utility."""
import argparse
import base64
import json
from pathlib import Path
import urllib.request


def call(game_dir, name, arguments=None):
    connection = json.loads((Path(game_dir) / 'config/minesynapse-mcp-connection.json').read_text())
    request = urllib.request.Request(connection['url'], data=json.dumps({
        'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
        'params': {'name': name, 'arguments': arguments or {}}
    }).encode(), headers={'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
                        'Authorization': 'Bearer ' + connection['token']})
    result = json.load(urllib.request.urlopen(request, timeout=25))
    if 'error' in result:
        raise RuntimeError(result['error'])
    if result['result'].get('isError'):
        raise RuntimeError(result['result']['content'][0]['text'])
    return result['result']


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('game_dir')
    parser.add_argument('tool')
    parser.add_argument('arguments', nargs='?', default='{}')
    parser.add_argument('--image-file')
    args = parser.parse_args()
    result = call(args.game_dir, args.tool, json.loads(args.arguments))
    if args.image_file:
        Path(args.image_file).write_bytes(base64.b64decode(result['content'][0]['data']))
        print('Image saved to', Path(args.image_file).resolve())
    else:
        print(json.dumps(result.get('structuredContent', result), indent=2, ensure_ascii=False))
