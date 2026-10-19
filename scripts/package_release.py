"""Package the already tested JAR and standalone stdio bridge with hashes."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
out = ROOT / 'build/release'
out.mkdir(parents=True, exist_ok=True)
version = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text())['version']
jar = ROOT / f'build/libs/mine-mcp-{version}.jar'
shutil.copy2(jar, out / jar.name)
shutil.copy2(ROOT / 'bridge/mine-mcp.mjs', out / 'mine-mcp.mjs')
archive = out / f'mine-mcp-bridge-{version}.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as target:
    for name in ['bridge/mine-mcp.mjs', 'docs/CONNECT.md', 'docs/FR.md', 'LICENSE']:
        target.write(ROOT / name, name)
artifacts = [out / jar.name, out / 'mine-mcp.mjs', archive]
(out / 'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in artifacts))
print('Release assets:', ', '.join(p.name for p in artifacts))
