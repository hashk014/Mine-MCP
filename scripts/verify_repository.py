"""Verify the shipped compatibility catalog and hooks without downloading game files."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
metadata = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text())
targets = metadata['depends']['minecraft']
evidence = json.loads((ROOT / 'docs/compatibility.json').read_text())
assert set(targets) == {v['version'] for v in evidence if v['status'] == 'symbols-verified'}
files = list((ROOT / 'src/main/resources/mappings').glob('*.json'))
assert {f.stem for f in files} == set(targets)
for version in targets:
    symbols = json.loads((ROOT / 'src/main/resources/mappings' / (version + '.json')).read_text())
    mc = symbols['net.minecraft.client.Minecraft']
    expected = 'method_1574' if version.startswith('1.') else 'tick'
    assert any(m[0] == 'tick' and m[1] == expected and m[2] == '()V' for m in mc['m']), version
    keyboard = symbols['net.minecraft.client.KeyboardHandler']
    keys = [m for m in keyboard['m'] if m[0] == 'keyPress']
    assert len(keys) == 1, version
    assert keys[0][2] == '(JIIII)V' or re.fullmatch(r'\(JIL[^;]+;\)V', keys[0][2]), (version, keys)
    entity = symbols['net.minecraft.world.entity.Entity']
    if version.startswith('1.'):
        for name in ('getId', 'getUUID', 'getName', 'blockPosition'):
            entries = [m for m in entity['m'] if m[0] == name]
            if entries:
                assert all(m[1].startswith('method_') for m in entries), (version, name, entries)
    assert 'net.minecraft.client.gui.components.events.AbstractContainerEventHandler' in symbols, version
print(f'Compatibility catalog: {len(targets)} versions, hooks and inherited core symbols verified')
