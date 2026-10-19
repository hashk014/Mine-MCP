"""Launch a clean offline test instance, never the user's saves/mods/accounts.

Downloads official game files privately into .cache; reuses hash-verified local libraries/assets.
This is a developer test launcher, not an authentication or multiplayer launcher.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import urllib.request
import zipfile
from generate_mappings import fetch, MANIFEST, ROOT
from build import java_home


def artifact(coordinate):
    group, name, version = coordinate.split(':')[:3]
    return '/'.join([group.replace('.', '/'), name, version, name + '-' + version + '.jar'])


def permitted(rules):
    allowed = not rules
    for rule in rules:
        if 'features' in rule:
            continue
        spec = rule.get('os', {})
        if spec.get('name', 'windows') != 'windows':
            continue
        if spec.get('arch') and spec['arch'] not in ('x86_64', 'amd64', 'x64'):
            continue
        allowed = rule['action'] == 'allow'
    return allowed


def install(url, path, digest=None, local=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and (not digest or hashlib.sha1(path.read_bytes()).hexdigest() == digest):
        return path
    if local and local.exists() and digest and hashlib.sha1(local.read_bytes()).hexdigest() == digest:
        try: os.link(local, path)
        except OSError: shutil.copy2(local, path)
    else:
        path.write_bytes(fetch(url, digest))
    return path


def prepare(version, java_override=None, instance=None, username='MCPTest'):
    manifest = json.loads(fetch(MANIFEST))
    entry = next(v for v in manifest['versions'] if v['id'] == version)
    info = json.loads(fetch(entry['url'], entry.get('sha1')))
    profile = json.loads(fetch(f'https://meta.fabricmc.net/v2/versions/loader/{version}/0.19.5/profile/json'))
    if instance and not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', instance):
        raise ValueError('Test instance name must be 1-32 letters, digits, underscores or dashes')
    game = ROOT / 'run' / (version + ('-' + instance if instance else ''))
    game.mkdir(parents=True, exist_ok=True)
    native_dir = game / 'natives'; native_dir.mkdir(exist_ok=True)
    local_game = Path(os.environ.get('APPDATA', '')) / '.minecraft'
    libraries = ROOT / '.cache/libraries'
    assets = ROOT / '.cache/assets'
    client = install(info['downloads']['client']['url'], game / 'client.jar', info['downloads']['client']['sha1'])
    classpath, tasks, natives = [client], [], []
    for lib in info['libraries'] + profile['libraries']:
        if not permitted(lib.get('rules', [])):
            continue
        if 'downloads' in lib:
            art = lib['downloads'].get('artifact')
            if art:
                dest = libraries / art['path']; classpath.append(dest)
                tasks.append((art['url'], dest, art.get('sha1'), local_game / 'libraries' / art['path']))
            classifier = lib.get('natives', {}).get('windows', '').replace('${arch}', '64')
            if classifier and classifier in lib['downloads'].get('classifiers', {}):
                native = lib['downloads']['classifiers'][classifier]
                dest = libraries / native['path']; natives.append(dest)
                tasks.append((native['url'], dest, native.get('sha1'), local_game / 'libraries' / native['path']))
        else:
            location = artifact(lib['name']); dest = libraries / location; classpath.append(dest)
            tasks.append((lib.get('url', 'https://libraries.minecraft.net/') + location, dest, lib.get('sha1'), local_game / 'libraries' / location))
    if version.startswith('1.'):
        location = f'net/fabricmc/intermediary/{version}/intermediary-{version}.jar'
        dest = libraries / location; classpath.append(dest)
        tasks.append(('https://maven.fabricmc.net/' + location, dest, None, None))
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        list(pool.map(lambda task: install(*task), {str(t[1]): t for t in tasks}.values()))
    for archive in natives:
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                if name.endswith('.dll'):
                    (native_dir / Path(name).name).write_bytes(z.read(name))
    index = info['assetIndex']
    index_file = install(index['url'], assets / 'indexes' / (index['id'] + '.json'), index['sha1'], local_game / 'assets/indexes' / (index['id'] + '.json'))
    objects = json.loads(index_file.read_text())['objects']
    asset_tasks = []
    for data in objects.values():
        h = data['hash']; relative = h[:2] + '/' + h
        asset_tasks.append(('https://resources.download.minecraft.net/' + relative, assets / 'objects' / relative, h, local_game / 'assets/objects' / relative))
    print(f'{version}: {len(tasks)} libraries, {len(asset_tasks)} assets (reuse by hash)', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        list(pool.map(lambda task: install(*task), {str(t[1]): t for t in asset_tasks}.values()))
    mods = game / 'mods'; mods.mkdir(exist_ok=True)
    version_tag = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text())['version']
    for old in mods.glob('mine-mcp-*.jar'):
        old.unlink()
    shutil.copy2(ROOT / f'build/libs/mine-mcp-{version_tag}.jar', mods / f'mine-mcp-{version_tag}.jar')
    # Tiny render distance for an isolated developer world; no existing options/saves are copied.
    (game / 'options.txt').write_text('renderDistance:4\nsimulationDistance:4\nmaxFps:30\nsoundCategory_master:0.0\nfullscreen:false\npauseOnLostFocus:false\nskipMultiplayerWarning:true\nonboardAccessibility:false\n')
    java = java_override or str(java_home() / 'bin/java.exe')
    args = [java, '-Xmx2G', '-Djava.library.path=' + str(native_dir), '-Dorg.lwjgl.librarypath=' + str(native_dir),
            '-Dfabric.gameVersion=' + version, '-cp', os.pathsep.join(map(str, classpath)), profile['mainClass'],
            '--username', username, '--version', version, '--gameDir', str(game), '--assetsDir', str(assets),
            '--assetIndex', index['id'], '--uuid', '00000000000000000000000000000001' if username=='MCPTest' else '00000000000000000000000000000002', '--accessToken', '0',
            '--userType', 'legacy', '--versionType', 'release', '--width', '854', '--height', '480']
    (game / 'launch.json').write_text(json.dumps(args, indent=2))
    return game, args


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('version')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--java', help='Path to the Java executable appropriate for this game version')
    parser.add_argument('--world', help='Quick Play a world inside this isolated test instance (1.20+)')
    parser.add_argument('--instance', help='Separate isolated test instance suffix')
    parser.add_argument('--username', default='MCPTest', help='Offline test name only')
    parser.add_argument('--server', help='Quick Play a local test LAN server (1.20+)')
    parser.add_argument('--wait', action='store_true', help='Keep the launcher alive until the client exits')
    args = parser.parse_args()
    if args.server and args.world: parser.error('Choose world or server')
    if args.server and not re.fullmatch(r'(?:127\.0\.0\.1|localhost):[0-9]{1,5}', args.server): parser.error('Only a loopback test server is permitted')
    game, command = prepare(args.version, args.java, args.instance, args.username)
    if args.world:
        command += ['--quickPlaySingleplayer', args.world]
    if args.server:
        command += ['--quickPlayMultiplayer', args.server]
    if args.prepare_only: return
    with open(game / 'console.log', 'w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=game, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    (game / 'test-client.pid').write_text(str(process.pid))
    print('Launched isolated test client', args.version, 'PID', process.pid, 'log:', game / 'console.log')
    if args.wait:
        try: print('Client exited with code', process.wait(), flush=True)
        except KeyboardInterrupt: process.terminate(); process.wait(timeout=20)


if __name__ == '__main__':
    main()
