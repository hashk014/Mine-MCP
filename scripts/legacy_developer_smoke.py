"""Exercise the developer adapter on the owned 1.14.4 test world, using a Java 8 client."""
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import time
from mcp_probe import call

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'run/1.14.4'


def invoke(tool, **args):
    return call(GAME, tool, args)['structuredContent']


def main():
    screen = invoke('get_screen')
    if screen['class'].endswith('.TitleScreen'):
        button = next(w for w in screen['widgets'] if w['message'] == 'Singleplayer')
        invoke('click_widget', screenClass=screen['class'], index=button['index'])
        screen = invoke('get_screen')
        listing = next(w for w in screen['widgets'] if w.get('entries'))
        entry = next(e for e in listing['entries'] if e['name'] == 'MCP-Test')
        invoke('select_list_entry', screenClass=screen['class'], index=listing['index'], entry=entry['index'])
        screen = invoke('get_screen')
        button = next(w for w in screen['widgets'] if w['message'] == 'Play Selected World')
        assert button['active']
        invoke('click_widget', screenClass=screen['class'], index=button['index'])
    end = time.monotonic() + 25
    while not invoke('get_status')['inWorld']:
        if time.monotonic() > end:
            raise RuntimeError('Legacy test world did not load')
        time.sleep(0.25)
    report = {'modVersion': '0.2.0', 'minecraftVersion': '1.14.4', 'phase': 'legacy-solo',
              'environment': 'Windows graphical client, Java 8, isolated offline MCP-Test world',
              'recordedAt': datetime.now(timezone.utc).isoformat(),
              'jarSha256': hashlib.sha256((GAME / 'mods/mine-mcp-0.2.0.jar').read_bytes()).hexdigest(), 'tests': []}

    def check(name, run):
        try:
            assert run() is not False
            report['tests'].append({'name': name, 'status': 'passed'})
            print('PASS', name, flush=True)
        except Exception as e:
            report['tests'].append({'name': name, 'status': 'failed', 'reason': str(e)[:800]})
            print('FAIL', name, str(e)[:400], flush=True)

    def wait_for(predicate):
        end = time.monotonic() + 3
        while time.monotonic() < end:
            if predicate(): return True
            time.sleep(0.15)
        return False

    invoke('close_screen')
    check('legacy owner console authority', lambda: invoke('get_session')['authority'] == 'integrated_server_console')
    check('75 tool schemas', lambda: len(invoke('get_capabilities')['tools']) == 75)
    check('server dimensions and seeds', lambda: len(invoke('dev_get_server_info')['dimensions']) == 3)
    check('legacy command source captures output', lambda: bool(invoke('dev_set_time', time=6000)['messages']))
    check('time effect observed', lambda: 6000 <= invoke('get_world')['dayTime'] % 24000 < 6500)
    point = invoke('get_player')['position']
    x, y, z = math.floor(point['x']) + 2, math.floor(point['y']) + 4, math.floor(point['z']) + 2
    before = invoke('get_block', x=x, y=y, z=z)['id']
    check('legacy setblock', lambda: invoke('dev_set_block', x=x, y=y, z=z, block='minecraft:gold_block')['success'])
    check('legacy block edit observed', lambda: wait_for(lambda: invoke('get_block', x=x, y=y, z=z)['id'] == 'minecraft:gold_block'))
    check('legacy authoritative server block read', lambda: 'gold_block' in invoke('dev_get_block', x=x, y=y, z=z)['state'])
    check('legacy authoritative entity NBT', lambda: bool(invoke('dev_get_entities', radius=8, limit=4)['entities']))
    check('unloaded client chunk stays unloaded', lambda: not invoke('get_chunk_info', chunkX=100000, chunkZ=100000)['loaded'])
    check('legacy console autocomplete', lambda: any(r['text'] == 'gamemode' for r in invoke('dev_complete_command', command='game')['suggestions']))
    check('legacy statistics request', lambda: invoke('request_statistics')['submitted'])
    check('legacy checkpoint create', lambda: bool(invoke('dev_checkpoint', name='legacy-origin')['checkpoint']))
    check('legacy teleport', lambda: invoke('dev_teleport', x=point['x'] + 2, y=point['y'], z=point['z'])['success'])
    check('legacy checkpoint restore', lambda: invoke('dev_restore_checkpoint', name='legacy-origin')['success'])
    check('legacy position restore observed', lambda: wait_for(lambda: abs(invoke('get_player')['position']['x'] - point['x']) < 0.1))
    check('legacy player debug NBT query', lambda: bool(invoke('query_entity_nbt', entityId=invoke('get_player')['id'])['nbt']))
    invoke('dev_set_block', x=x, y=y, z=z, block=before)
    check('legacy native save', lambda: invoke('dev_save_world')['completed'])
    dest = ROOT / 'docs/runtime-results/developer-0.2.0-legacy.json'
    dest.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    passed = sum(t['status'] == 'passed' for t in report['tests'])
    print(f'{passed}/{len(report["tests"])} passed', flush=True)
    if passed != len(report['tests']): raise SystemExit(1)


if __name__ == '__main__': main()
