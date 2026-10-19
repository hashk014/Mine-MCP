"""Record real-client evidence. --actions is only for a disposable singleplayer test world."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import time
from mcp_probe import call

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('game_dir')
    parser.add_argument('--actions', action='store_true')
    args = parser.parse_args()
    directory = Path(args.game_dir)
    connection = json.loads((directory / 'config/minesynapse-mcp-connection.json').read_text())
    version = connection['minecraftVersion']
    jar = directory / 'mods/minesynapse-mcp-0.1.0.jar'
    report = dict(minecraftVersion=version, recordedAt=datetime.now(timezone.utc).isoformat(),
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  environment='Windows graphical client, isolated offline singleplayer instance, Fabric 0.19.5', tests=[])

    def check(name, tool, parameters=None, predicate=None):
        try:
            result = call(directory, tool, parameters)
            data = result.get('structuredContent', result)
            if predicate and not predicate(data): raise AssertionError('Result did not satisfy the observed-state check')
            report['tests'].append(dict(name=name, tool=tool, status='passed'))
            print('PASS', name, flush=True)
            return data
        except Exception as e:
            report['tests'].append(dict(name=name, tool=tool, status='failed', reason=str(e)[:800]))
            print('FAIL', name, str(e)[:300], flush=True)
            return None

    status = check('real client status', 'get_status', predicate=lambda d: d['minecraftVersion'] == version and d['tickCount'] > 0)
    check('tool discovery', 'get_capabilities', predicate=lambda d: len(d['tools']) == 45)
    check('screen inspection', 'get_screen')
    check('item registry pagination', 'get_registry', {'limit': 3}, lambda d: len(d['values']) == 3 and d['total'] > 3)
    check('client options', 'get_options')
    image = check('framebuffer PNG', 'take_screenshot', {'maxWidth': 320})
    if image:
        data = base64.b64decode(image['content'][0]['data'])
        assert data.startswith(b'\x89PNG\r\n\x1a\n')
        (directory / 'mcp-smoke.png').write_bytes(data)
    if status and status['inWorld']:
        check('player identity and position', 'get_player', predicate=lambda d: d['id'] is not None and d['name'] == 'MCPTest' and d['position']['x'] is not None)
        check('world clock', 'get_world', predicate=lambda d: d['gameTime'] is not None)
        check('inventory', 'get_inventory', predicate=lambda d: len(d['slots']) >= 36)
        check('equipment', 'get_equipment')
        check('held item payload', 'get_held_item_data')
        check('loaded entities', 'get_entities', {'limit': 3})
        check('crosshair target', 'get_target')
        check('loaded block scan', 'scan_blocks', {'radius': 1, 'limit': 10, 'excludeAir': False})
        check('chat', 'get_chat')
        check('player list', 'get_players')
        check('scoreboard', 'get_scoreboard')
        check('boss bars', 'get_boss_bars')
        check('client recipes', 'get_recipes', {'limit': 3})
        check('advancement cache', 'get_advancements', {'limit': 3})
        check('statistics cache', 'get_statistics', {'limit': 3})
        check('advertised command tree', 'get_commands')
        check('bounded scalar inspection', 'inspect_client', {'limit': 10})
        check('event polling', 'get_events', {'limit': 3})
    if args.actions:
        if not status or not status['inWorld']: raise SystemExit('--actions requires a disposable world already loaded')
        check('close screen input', 'close_screen')
        before = call(directory, 'get_player')['structuredContent']
        check('isolate look from physical mouse movement', 'open_inventory')
        check('look input', 'look', {'yaw': 15, 'pitch': -20})
        check('look observed', 'get_player', predicate=lambda d: abs(d['yaw'] - 15) < 0.1 and abs(d['pitch'] + 20) < 0.1)
        check('close inventory after look', 'close_screen')
        check('select hotbar input', 'select_hotbar', {'slot': 2})
        check('selected hotbar observed', 'get_inventory', predicate=lambda d: d['selectedSlot'] == 2)
        check('bounded forward input', 'hold_controls', {'keys': ['forward'], 'durationMs': 200})
        time.sleep(0.5)
        check('key lease auto release', 'get_status', predicate=lambda d: not d['heldKeys'])
        check('movement observed', 'get_player', predicate=lambda d: sum(abs(d['position'][k] - before['position'][k]) for k in ('x', 'z')) > 0.01)
        check('open inventory input', 'open_inventory')
        container = check('inventory screen observed', 'get_container', predicate=lambda d: len(d['slots']) >= 36)
        if container:
            empty = next((i for i, slot in enumerate(container['slots']) if slot['item'].get('empty')), None)
            if empty is not None and container['carried'].get('empty'):
                check('normal empty-slot click', 'click_slot', {'containerId': container['containerId'], 'slot': empty})
        check('return to gameplay', 'close_screen')
        check('normal attack input', 'attack')
        check('normal use input', 'use_item')
        check('stop item use', 'stop_using_item')
        check('local singleplayer chat send', 'send_chat', {'message': 'MineSynapse MCP self-test'})
        time.sleep(0.3)
        check('chat send observed', 'get_chat', predicate=lambda d: any('MineSynapse MCP self-test' in m['text'] for m in d['messages']))
        check('restore hotbar', 'select_hotbar', {'slot': int(before['selectedSlot'])})
        check('restore view', 'look', {'yaw': before['yaw'], 'pitch': before['pitch']})
        check('release all inputs', 'release_controls')
        check('remote pause', 'pause_control')
        try:
            call(directory, 'look', {'yaw': 0, 'pitch': 0})
            report['tests'].append(dict(name='paused actions rejected', status='failed'))
        except RuntimeError:
            report['tests'].append(dict(name='paused actions rejected', status='passed'))
        check('release permitted while paused', 'release_controls')
    out = ROOT / 'docs/runtime-results'; out.mkdir(exist_ok=True)
    (out / (version + '.json')).write_text(json.dumps(report, indent=2) + '\n')
    print('Saved report for', version, 'passed:', sum(t['status'] == 'passed' for t in report['tests']), '/', len(report['tests']))


if __name__ == '__main__':
    main()
