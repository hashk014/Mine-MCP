"""Record a scripted demonstration through real MCP calls in the disposable test world.
Only use with the isolated run/ instance. Setup commands require that player's own permissions.
"""
import base64
import json
import math
from pathlib import Path
import subprocess
import time
from mcp_probe import call

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'run/1.21.11'
OUT = ROOT / '.publish/demo-capture'
OUT.mkdir(parents=True, exist_ok=True)
PUBLIC = ROOT / 'video/public'
PUBLIC.mkdir(parents=True, exist_ok=True)
evidence = []


def invoke(tool, args=None):
    result = call(GAME, tool, args)
    data = result.get('structuredContent', {})
    if tool != 'take_screenshot':
        evidence.append({'tool': tool, 'arguments': args or {}, 'result': data})
    return result


def command(value):
    invoke('send_command', {'command': value})


def main():
    initial = invoke('get_status')['structuredContent']
    assert initial['inWorld'] and not initial['paused'] and initial['serverAddress'] is None, 'Isolated singleplayer client required'
    invoke('close_screen')
    for value in [
        'time set day', 'weather clear',
        'fill -10 -60 -8 10 -48 14 minecraft:air',
        'fill -8 -61 -2 8 -61 12 minecraft:deepslate_tiles',
        'fill -7 -60 11 7 -56 11 minecraft:smooth_quartz',
        'fill -7 -55 11 7 -55 11 minecraft:ochre_froglight',
        'fill -7 -60 3 -7 -56 3 minecraft:cut_copper',
        'fill 7 -60 3 7 -56 3 minecraft:cut_copper',
        'setblock 0 -60 5 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:12},{Slot:1b,id:"minecraft:oak_log",count:32}]} replace',
        'tp @s 0.5 -60 0.5 0 19',
    ]:
        command(value)
        time.sleep(0.12)
    invoke('select_hotbar', {'slot': 8})
    invoke('look', {'yaw': 0, 'pitch': 19})
    time.sleep(0.4)
    invoke('get_player'); invoke('get_target'); invoke('get_inventory')
    frames = []
    events = set()
    start = time.monotonic()
    try:
        while (elapsed := time.monotonic() - start) < 18:
            if elapsed >= 4 and 'open' not in events:
                invoke('use_item'); events.add('open')
            if elapsed >= 6 and 'container' not in events:
                container = invoke('get_container')['structuredContent']
                assert container['slots'][0]['item']['id'] == 'minecraft:diamond', 'Prepared chest must be confirmed in the client'
                events.add('container')
            if elapsed >= 8 and 'transfer' not in events:
                invoke('click_slot', {'containerId': container['containerId'], 'slot': 0, 'clickType': 'QUICK_MOVE'})
                time.sleep(0.15)
                inv = invoke('get_inventory')['structuredContent']
                assert any(s['id'] == 'minecraft:diamond' and s['count'] >= 12 for s in inv['slots']), 'Transfer must be observed'
                events.add('transfer')
            if elapsed >= 11 and 'close' not in events:
                invoke('close_screen'); events.add('close')
            if elapsed >= 12 and 'move' not in events:
                invoke('hold_controls', {'keys': ['forward'], 'durationMs': 450}); events.add('move')
            if elapsed >= 14 and 'verify' not in events:
                invoke('get_player'); invoke('get_status'); invoke('release_controls'); events.add('verify')
            if elapsed >= 16 and 'pause' not in events:
                invoke('pause_control'); invoke('get_status'); events.add('pause')
            image = invoke('take_screenshot', {'maxWidth': 960})['content'][0]
            frame = OUT / f'frame-{len(frames):04d}.png'
            frame.write_bytes(base64.b64decode(image['data']))
            frames.append((frame, time.monotonic() - start))
            time.sleep(max(0, 0.14 - (time.monotonic() - start - elapsed)))
    finally:
        invoke('release_controls')
    concat = []
    for i, (path, timestamp) in enumerate(frames):
        duration = frames[i + 1][1] - timestamp if i + 1 < len(frames) else 0.2
        concat += [f"file '{path.as_posix()}'", f'duration {duration:.6f}']
    concat += [f"file '{frames[-1][0].as_posix()}'"]
    listing = OUT / 'frames.txt'; listing.write_text('\n'.join(concat))
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', str(listing),
                    '-vf', 'fps=30,scale=1280:720:flags=neighbor', '-c:v', 'libx264', '-crf', '18', '-pix_fmt', 'yuv420p',
                    '-movflags', '+faststart', str(PUBLIC / 'gameplay.mp4')], check=True)
    for name, second in [('observe', 2), ('inventory', 7), ('transfer', 10), ('verify', 15)]:
        path = min(frames, key=lambda f: abs(f[1] - second))[0]
        (PUBLIC / (name + '.png')).write_bytes(path.read_bytes())
    report = {'minecraftVersion': '1.21.11', 'scope': 'isolated offline singleplayer creative scene',
              'demo': 'Scripted real MCP calls, not an autonomous model session', 'frames': len(frames),
              'duration': frames[-1][1], 'events': evidence}
    (ROOT / 'docs/media/demo-evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Recorded', len(frames), 'frames; real chest transfer verified; gameplay.mp4 created')


if __name__ == '__main__': main()
