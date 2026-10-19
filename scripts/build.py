"""Reproducible lightweight Java 8 build. Python 3.10+ and JDK 17+; no Gradle/Loom required."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.2.0'
DEPENDENCIES = {
    'fabric-loader': ('https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar', None),
    'mixin': ('https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.0+mixin.0.8.7/sponge-mixin-0.17.0+mixin.0.8.7.jar', None),
    'asm': ('https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar', None),
    'asm-tree': ('https://repo.maven.apache.org/maven2/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar', None),
    'gson': ('https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar', None),
}


def java_home():
    env = os.environ.get('JAVA_HOME')
    if env and (Path(env) / 'bin/javac.exe').exists() or env and (Path(env) / 'bin/javac').exists():
        return Path(env)
    javac = shutil.which('javac')
    if javac:
        return Path(javac).resolve().parent.parent
    candidates = sorted((Path.home() / '.gradle/jdks').glob('*/bin/javac.exe'))
    if candidates:
        return candidates[-1].parent.parent
    raise SystemExit('Install JDK 17+ and set JAVA_HOME (Minecraft itself uses its version-specific Java).')


def deps():
    dest = ROOT / '.cache/deps'
    dest.mkdir(parents=True, exist_ok=True)
    lock_path = ROOT / 'scripts/dependencies.lock.json'
    lock = json.loads(lock_path.read_text()) if lock_path.exists() else {}
    paths = []
    for name, (url, _) in DEPENDENCIES.items():
        target = dest / (name + '.jar')
        if not target.exists():
            print('Downloading', name, flush=True)
            target.write_bytes(urllib.request.urlopen(url, timeout=90).read())
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if name in lock and lock[name]['sha256'] != digest:
            raise SystemExit('Dependency checksum mismatch: ' + name)
        lock[name] = dict(url=url, sha256=digest)
        paths.append(target)
    if not lock_path.exists():
        lock_path.write_text(json.dumps(lock, indent=2) + '\n')
    return paths


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--test', action='store_true')
    args = parser.parse_args()
    home = java_home()
    dependencies = deps()
    classes = ROOT / 'build/classes'
    if classes.exists():
        resolved = classes.resolve()
        if resolved.parent != (ROOT / 'build').resolve() or resolved.name != 'classes':
            raise SystemExit('Refusing to clean a class directory outside the build output')
        shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join(str(x) for x in dependencies)
    sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
    if args.test:
        sources += sorted((ROOT / 'src/test/java').rglob('*.java'))
    subprocess.run([str(home / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8', '-proc:none',
                    '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
    if args.test:
        subprocess.run([str(home / 'bin/java'), '-cp', str(classes) + os.pathsep + cp,
                        'dev.minemcp.mcp.CoreTest'], check=True)
    jar = ROOT / f'build/libs/mine-mcp-{VERSION}.jar'
    jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for folder in [classes, ROOT / 'src/main/resources']:
            for path in sorted(folder.rglob('*')):
                if path.is_file() and not any(p in path.name for p in ('CoreTest', 'Fake')):
                    item = zipfile.ZipInfo(path.relative_to(folder).as_posix(), (2026, 1, 1, 0, 0, 0))
                    item.compress_type = zipfile.ZIP_DEFLATED
                    item.create_system = 3
                    item.external_attr = 0o100644 << 16
                    output.writestr(item, path.read_bytes())
        for name in ('LICENSE', 'NOTICE'):
            if (ROOT / name).exists():
                item = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                item.compress_type = zipfile.ZIP_DEFLATED
                item.create_system = 3
                item.external_attr = 0o100644 << 16
                output.writestr(item, (ROOT / name).read_bytes())
    print('Built', jar, hashlib.sha256(jar.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
