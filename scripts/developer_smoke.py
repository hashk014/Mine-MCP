"""Real developer-tool tests in explicitly isolated run/ instances, never user worlds.
Run phases separately; the test-server operator promotes/demotes the guest between phases.
"""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import sys
import time
from mcp_probe import call

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['solo', 'nonop', 'op', 'revoked'])
    args = parser.parse_args()
    game = ROOT / 'run' / ('1.21.11' if args.phase == 'solo' else '1.21.11-guest')
    version = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text())['version']
    report = {'modVersion': version, 'minecraftVersion': '1.21.11', 'phase': args.phase,
              'recordedAt': datetime.now(timezone.utc).isoformat(),
              'jarSha256': hashlib.sha256((game / f'mods/mine-mcp-{version}.jar').read_bytes()).hexdigest(),
              'environment': 'Isolated offline solo, cheats disabled' if args.phase == 'solo' else 'Vanilla dedicated test server, loopback only, no server mods; offline test identities',
              'tests': []}

    def invoke(tool_name, **kwargs):
        return call(game, tool_name, kwargs)['structuredContent']

    def check(name, body):
        try:
            value = body()
            assert value is not False, 'Observed state did not match expected result'
            report['tests'].append({'name': name, 'status': 'passed'})
            print('PASS', name, flush=True)
            return value
        except Exception as error:
            report['tests'].append({'name': name, 'status': 'failed', 'reason': str(error)[:800]})
            print('FAIL', name, str(error)[:400], flush=True)
            return None

    def denied(name, **kwargs):
        try:
            invoke(name, **kwargs)
        except RuntimeError:
            return True
        return False

    def wait_for(body, timeout=4):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            value = body()
            if value:
                return value
            time.sleep(0.15)
        return False

    def roots():
        return {row['name'] for row in invoke('get_commands')['tree']['children']}

    def block(x, y, z):
        return invoke('get_block', x=x, y=y, z=z).get('id')

    def count(item):
        return sum(row.get('count', 0) for row in invoke('get_inventory')['slots'] if row.get('id') == item)

    def local_command(name, **kwargs):
        result = invoke(name, **kwargs)
        assert result.get('authority') == 'integrated_server_console'
        assert result.get('success') is True
        return True

    session = invoke('get_session')
    if args.phase == 'solo':
        assert session['mode'] == 'singleplayer_owner' and session['authority'] == 'integrated_server_console'
        check('cheats-disabled owner has local console authority', lambda: session['localOwner'] and 'setblock' not in roots())
        invoke('close_screen')
        check('75 tool schemas discovered', lambda: len(invoke('get_capabilities')['tools']) == 75)
        check('diagnostics include mod inventory and heap', lambda: bool(invoke('get_debug_info')['mods']) and invoke('get_debug_info')['memory']['usedBytes'] > 0)
        check('loaded chunk inspection', lambda: invoke('get_chunk_info', chunkX=0, chunkZ=0)['loaded'])
        check('unloaded chunk read does not load it', lambda: not invoke('get_chunk_info', chunkX=100000, chunkZ=100000)['loaded'])
        check('local server dimensions and seed', lambda: len(invoke('dev_get_server_info')['dimensions']) == 3)
        invoke('dev_set_block', x=2, y=-60, z=5, block='minecraft:air')
        check('set block with console authority', lambda: local_command('dev_set_block', x=2, y=-60, z=5, block='minecraft:gold_block'))
        check('block edit observed by client', lambda: wait_for(lambda: block(2, -60, 5) == 'minecraft:gold_block'))
        check('authoritative local block state', lambda: 'gold_block' in invoke('dev_get_block', x=2, y=-60, z=5)['state'])
        check('authoritative local container NBT', lambda: 'minecraft:chest' in invoke('dev_get_block', x=0, y=-60, z=5)['blockEntityNbt'])
        invoke('dev_fill', x1=3, y1=-60, z1=3, x2=4, y2=-60, z2=4, block='minecraft:air')
        check('bounded fill executes', lambda: local_command('dev_fill', x1=3, y1=-60, z1=3, x2=4, y2=-60, z2=4, block='minecraft:quartz_block'))
        check('fill result observed', lambda: wait_for(lambda: block(4, -60, 4) == 'minecraft:quartz_block'))
        check('clone executes', lambda: local_command('dev_clone', x1=3, y1=-60, z1=3, x2=4, y2=-60, z2=4, x=3, y=-60, z=6))
        check('clone result observed', lambda: wait_for(lambda: block(4, -60, 7) == 'minecraft:quartz_block'))
        check('oversized edit is rejected', lambda: denied('dev_fill', x1=0, y1=0, z1=0, x2=1000, y2=1000, z2=1000, block='minecraft:air'))
        before = count('minecraft:emerald')
        check('give item without enabling cheats', lambda: local_command('dev_give_item', item='minecraft:emerald', count=3))
        check('given items observed in inventory', lambda: wait_for(lambda: count('minecraft:emerald') >= before + 3))
        check('time command', lambda: local_command('dev_set_time', time=6000))
        check('time change observed', lambda: 6000 <= invoke('get_world')['dayTime'] % 24000 < 6600)
        check('weather command', lambda: local_command('dev_set_weather', weather='rain', seconds=60))
        check('weather state observed', lambda: wait_for(lambda: invoke('get_world')['raining']))
        check('effect command', lambda: local_command('dev_effect', effect='minecraft:night_vision', seconds=60))
        check('effect observed', lambda: wait_for(lambda: 'night_vision' in str(invoke('get_player')['effects'])))
        check('summon command', lambda: local_command('dev_summon', entity='minecraft:pig', x=1, y=-60, z=7))
        check('summoned entity observed', lambda: wait_for(lambda: any('pig' in e['type'] for e in invoke('get_entities', radius=12)['entities'])))
        check('server entity serialization', lambda: any('Health' in e['nbt'] for e in invoke('dev_get_entities', radius=12, limit=8)['entities']))
        check('position checkpoint saved', lambda: bool(invoke('dev_checkpoint', name='origin')['checkpoint']))
        check('position checkpoint listed', lambda: 'origin' in invoke('dev_list_checkpoints')['checkpoints'])
        checkpoint = invoke('dev_list_checkpoints')['checkpoints'].get('origin')
        destination = checkpoint['x'] + 2
        check('teleport command', lambda: local_command('dev_teleport', x=destination, y=-60, z=0.5, yaw=45, pitch=10))
        check('teleport observed', lambda: wait_for(lambda: abs(invoke('get_player')['position']['x'] - destination) < 0.1))
        check('checkpoint restore executes', lambda: invoke('dev_restore_checkpoint', name='origin')['success'])
        check('checkpoint position observed', lambda: wait_for(lambda: abs(invoke('get_player')['position']['x'] - checkpoint['x']) < 0.1))
        check('creative command', lambda: local_command('dev_set_gamemode', mode='creative'))
        check('creative state observed', lambda: wait_for(lambda: invoke('get_player')['abilities']['creative']))
        check('creative inventory request', lambda: invoke('creative_set_slot', item='minecraft:diamond', slot=40, count=7)['submitted'])
        check('creative inventory change observed', lambda: wait_for(lambda: count('minecraft:diamond') >= 7))
        check('normal statistics request', lambda: invoke('request_statistics')['submitted'])
        check('statistics response observed', lambda: wait_for(lambda: invoke('get_statistics', limit=5)['total'] > 0))
        check('normal command completions', lambda: bool(invoke('complete_command', command='h')['suggestions']))
        check('solo console completions include privileged commands', lambda: any(r['text'] == 'gamemode' for r in invoke('dev_complete_command', command='game')['suggestions']))
        invoke('open_inventory')
        point = invoke('get_player')['position']
        check('aim at world coordinate', lambda: abs(invoke('look_at', x=point['x'], y=point['y'] + 1.62, z=point['z'] + 10)['yaw']) < 0.1)
        check('camera change observed', lambda: abs(invoke('get_player')['yaw']) < 0.1)
        invoke('close_screen')
        check('invalid console command reports failure', lambda: denied('dev_command', command='mine_mcp_invalid_command'))
        check('datapack reload', lambda: local_command('dev_command', command='reload'))
        time.sleep(2)
        invoke('dev_set_block', x=6, y=-60, z=6, block='minecraft:air')
        check('installed datapack function', lambda: invoke('dev_run_function', function='mine_mcp_test:fixture')['completed'])
        check('datapack function effect observed', lambda: wait_for(lambda: block(6, -60, 6) == 'minecraft:lime_wool'))
        gamerules = invoke('dev_complete_command', command='gamerule ')['suggestions']
        keep = next((r['text'] for r in gamerules if 'keep' in r['text'].lower() and 'inventory' in r['text'].lower()), 'keepInventory')
        check('gamerule command', lambda: local_command('dev_set_gamerule', rule=keep, value='true'))
        check('native world and player save', lambda: invoke('dev_save_world')['completed'])
        data = gzip.decompress((game / 'saves/Developer Test/level.dat').read_bytes())
        marker = b'\x01\x00\x0dallowCommands'
        check('cheats remain disabled in saved world', lambda: data[data.index(marker) + len(marker)] == 0)
        invoke('pause_control')
        check('F8 pause blocks developer edits', lambda: denied('dev_set_block', x=2, y=-60, z=5, block='minecraft:air'))
        check('server observations still work while paused', lambda: bool(invoke('dev_get_server_info')['dimensions']))
    else:
        assert session['mode'] == 'multiplayer' and session['authority'] == 'normal_player' and not session['serverReadsAvailable']
        check('remote mode never gains console authority', lambda: not session['localOwner'])
        check('remote server-only reads denied, including for OP', lambda: denied('dev_get_server_info') and denied('dev_get_entities'))
        check('client loaded chunk observation', lambda: invoke('get_chunk_info', chunkX=0, chunkZ=0)['loaded'])
        if args.phase in ['nonop', 'revoked']:
            check('server advertises no privileged setblock', lambda: 'setblock' not in roots())
            before = block(2, 80, 2)
            check('privileged command uses ordinary player route', lambda: invoke('dev_set_block', x=2, y=80, z=2, block='minecraft:diamond_block')['authority'] == 'normal_player')
            time.sleep(0.5)
            check('server rejects forbidden edit', lambda: block(2, 80, 2) == before)
            check('server-only block reads denied', lambda: denied('dev_get_block', x=2, y=80, z=2))
            check('creative inventory request denied in survival', lambda: denied('creative_set_slot', item='minecraft:diamond', slot=40))
            check('server denies vanilla debug NBT query', lambda: denied('query_block_nbt', x=3, y=80, z=2))
            if args.phase == 'nonop':
                check('normal chat send', lambda: invoke('send_chat', message='Mine MCP multiplayer client check')['submitted'])
                check('normal chat observed', lambda: wait_for(lambda: any('Mine MCP multiplayer client check' in r['text'] for r in invoke('get_chat')['messages'])))
                invoke('close_screen')
                point = invoke('get_player')['position']
                check('normal walking input', lambda: invoke('hold_controls', keys=['forward'], durationMs=300)['leaseMs'] == 300)
                check('server accepted normal movement', lambda: wait_for(lambda: abs(invoke('get_player')['position']['z'] - point['z']) > 0.1))
                check('normal statistics request', lambda: invoke('request_statistics')['submitted'])
        else:
            check('server advertises OP commands', lambda: 'setblock' in roots())
            check('OP command still uses ordinary connection', lambda: invoke('dev_set_block', x=2, y=80, z=2, block='minecraft:emerald_block')['authority'] == 'normal_player')
            check('server accepts authorized OP edit', lambda: wait_for(lambda: block(2, 80, 2) == 'minecraft:emerald_block'))
            check('authorized vanilla block NBT query', lambda: 'minecraft:emerald' in invoke('query_block_nbt', x=3, y=80, z=2)['nbt'])
            entity = invoke('get_player')['id']
            check('authorized vanilla entity NBT query', lambda: bool(invoke('query_entity_nbt', entityId=entity)['nbt']))
            check('OP command completions', lambda: bool(invoke('complete_command', command='gamemode ')['suggestions']))
            check('authorized creative mode command', lambda: invoke('dev_set_gamemode', mode='creative')['authority'] == 'normal_player')
            check('server creative state observed', lambda: wait_for(lambda: invoke('get_player')['abilities']['creative']))
            check('normal creative slot request', lambda: invoke('creative_set_slot', item='minecraft:diamond', slot=40, count=5)['submitted'])
            check('server accepts creative inventory request', lambda: wait_for(lambda: count('minecraft:diamond') >= 5))
            check('creative inventory confirmed by authoritative server NBT', lambda: 'minecraft:diamond' in invoke('query_entity_nbt', entityId=entity)['nbt'])

    dest = ROOT / f'docs/runtime-results/developer-0.2.0-{args.phase}.json'
    dest.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    failed = [row for row in report['tests'] if row['status'] != 'passed']
    print(f'{len(report["tests"]) - len(failed)}/{len(report["tests"])} passed; {dest}', flush=True)
    if failed:
        sys.exit(1)


if __name__ == '__main__':
    main()
