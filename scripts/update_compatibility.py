"""Reconcile reproducible runtime reports without inflating symbol checks into gameplay claims."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    path = ROOT / 'docs/compatibility.json'
    rows = json.loads(path.read_text())
    lines = ['# Compatibility evidence', '',
             'This alpha targets Fabric on 44 stable Minecraft Java releases. One shared JAR is compiled to Java 8 bytecode; the game still needs its own Java runtime.', '',
             '**Symbols verified** means the exact-version catalog, tick hook, keyboard signature and inherited core member names were verified. It does not mean every tool was played through on that version.', '',
             '**Gameplay checks** are real MCP calls in an isolated offline singleplayer client. Passing an input-submission check does not prove server-side effects; explicit observed-state checks cover rotation, selected slot, movement, key expiry and chat. Empty scoreboards/boss bars/statistics validate the empty response path, not every populated form. Respawn, item dropping, command effects, complex crafting, multiplayer and every modded GUI were not exhaustively tested.', '',
             '| Minecraft | Required Java (official manifest) | Symbol catalog | Runtime evidence |',
             '| --- | ---: | --- | --- |']
    for row in rows:
        report_path = ROOT / 'docs/runtime-results' / (row['version'] + '.json')
        runtime = 'Not run'
        if report_path.exists():
            report = json.loads(report_path.read_text())
            tests = report.get('tests', [])
            passed = sum(t['status'] == 'passed' for t in tests)
            row['runtimeTested'] = bool(tests) and passed == len(tests)
            scope = 'gameplay' if any(t['name'] == 'movement observed' for t in tests) else 'client smoke'
            row['runtimeScope'] = scope
            row['runtimeEvidence'] = 'runtime-results/' + report_path.name
            row['runtimeJarSha256'] = report.get('jarSha256')
            runtime = f'[{passed}/{len(tests)} {scope} checks](runtime-results/{report_path.name})'
        if row['version'] == '26.3':
            runtime = 'Blocked: native graphics crash, also reproduced with Fabric alone'
            row['runtimeTested'] = False
            row['runtimeBlocker'] = 'Windows native exit 0xC0000005 during graphics/resource initialization, reproduced with the mod removed on Java 25 and Java 26. No completed game-client test.'
        lines.append(f"| {row['version']} | {row.get('java', '?')} | {row['status']} | {runtime} |")
    lines += ['', '## Runtime notes', '',
              '- Runtime reports record the exact tested JAR SHA-256. A report for a preceding build is evidence for that build, not automatic proof of a later binary.',
              '- The 26.3 client reached Fabric/mod initialization, then hit native Windows exit `0xC0000005` during graphics/resource startup. A Fabric-only baseline failed the same way. This environment could not complete a 26.3 graphical test.',
              '- Game data is client-cached. The server seed, unloaded chunks, remote inventories and permission escalation are unavailable.',
              '- Optional fields can be null on versions without that API. Direct tools report unsupported mandatory operations as errors; combined snapshots contain per-section errors.',
              '- Windows graphical clients were tested locally. Linux/macOS game runtime and multiplayer acceptance remain unverified. CI tests portable code on Windows/Linux, without a graphical Minecraft session.',
              '- Not supported: Forge, NeoForge, Quilt, Bedrock, snapshots, or pre-1.14.4 versions.', '',
              'Regenerate this page after collecting reports with `python scripts/update_compatibility.py`.', '']
    path.write_text(json.dumps(rows, indent=2) + '\n')
    (ROOT / 'docs/COMPATIBILITY.md').write_text('\n'.join(lines))


if __name__ == '__main__':
    main()
