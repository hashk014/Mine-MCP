"""Capture direct screenshots from real MCP calls in the disposable run/ instance.
Setup changes that isolated singleplayer world and requires the player's permissions.
"""
import base64
import json
from pathlib import Path
import time
from mcp_probe import call

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'run/1.21.11'
PUBLIC = ROOT / 'docs/media'


def main():
    evidence = []
    def invoke(tool, args=None):
        result = call(GAME, tool, args)
        if tool != 'take_screenshot':
            evidence.append({'tool': tool, 'arguments': args or {}, 'result': result.get('structuredContent', {})})
        return result
    def screenshot(name):
        image = invoke('take_screenshot', {'maxWidth': 960})['content'][0]
        (PUBLIC / name).write_bytes(base64.b64decode(image['data']))
    status = invoke('get_status')['structuredContent']
    assert status['inWorld'] and not status['paused'] and status['serverAddress'] is None, 'Isolated singleplayer client required'
    PUBLIC.mkdir(parents=True, exist_ok=True)
    try:
        invoke('close_screen')
        for command in [
            'gamerule sendCommandFeedback false', 'difficulty peaceful', 'time set day', 'weather clear',
            'fill -6 -60 -3 6 -54 10 minecraft:air',
            'fill -6 -61 -3 6 -61 10 minecraft:deepslate_tiles',
            'setblock 0 -60 5 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:12},{Slot:1b,id:"minecraft:oak_log",count:32}]} replace',
            'clear @s', 'tp @s 0.5 -60 0.5 0 19',
        ]:
            invoke('send_command', {'command': command})
            time.sleep(0.2)
        time.sleep(1)
        screenshot('client-view.png')
        invoke('use_item')
        time.sleep(1)
        container = invoke('get_container')['structuredContent']
        assert container['slots'][0]['item']['id'] == 'minecraft:diamond'
        screenshot('chest-before.png')
        invoke('click_slot', {'containerId': container['containerId'], 'slot': 0, 'clickType': 'QUICK_MOVE'})
        time.sleep(1)
        inventory = invoke('get_inventory')['structuredContent']
        assert any(s['id'] == 'minecraft:diamond' and s['count'] >= 12 for s in inventory['slots'])
        screenshot('chest-after.png')
    finally:
        invoke('release_controls')
    report = {'minecraftVersion': '1.21.11', 'scope': 'isolated offline singleplayer',
              'demo': 'Scripted real MCP calls, not an autonomous model session', 'events': evidence}
    (PUBLIC / 'demo-evidence.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Saved direct client screenshots; chest transfer verified')


if __name__ == '__main__':
    main()
