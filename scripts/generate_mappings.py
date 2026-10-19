"""Build small, version-exact runtime symbol tables. No game binaries are distributed."""
import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import zipfile
import struct
import threading
from collections import defaultdict

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache'
OUT = ROOT / 'src/main/resources/mappings'
FETCH_LOCKS = defaultdict(threading.Lock)
MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
# Limit extraction to the client surface used by the adapter, not the entire mappings.
PREFIXES = (
    'net.minecraft.client.', 'net.minecraft.world.entity.', 'net.minecraft.world.item.',
    'net.minecraft.world.level.block.', 'net.minecraft.world.level.biome.',
    'net.minecraft.world.level.dimension.', 'net.minecraft.world.level.material.',
    'net.minecraft.world.inventory.', 'net.minecraft.world.effect.',
    'net.minecraft.world.scores.', 'net.minecraft.world.phys.',
    'net.minecraft.core.', 'net.minecraft.resources.', 'net.minecraft.network.chat.',
    'net.minecraft.nbt.', 'net.minecraft.tags.', 'net.minecraft.stats.',
    'net.minecraft.advancements.', 'net.minecraft.sounds.', 'net.minecraft.world.food.',
    'net.minecraft.world.Interaction', 'net.minecraft.world.level.Level',
    'net.minecraft.world.level.BlockGetter', 'net.minecraft.world.level.LevelReader',
    'net.minecraft.world.level.LightLayer', 'net.minecraft.world.level.GameType',
    'net.minecraft.world.level.storage.LevelData', 'net.minecraft.world.level.storage.WritableLevelData', 'net.minecraft.world.level.storage.LevelSummary',
    'net.minecraft.world.level.border.WorldBorder',
)
# Deliberately exclude authentication/session internals, including reflective inspection.
DENY = ('User', 'Session', 'MinecraftSessionService', 'UserApiService', 'Yggdrasil', 'Realms', 'ProfileKey', 'Telemetry')
SURFACE = set('''Minecraft Options KeyMapping MouseHandler KeyboardHandler Window RenderTarget MainTarget
LocalPlayer AbstractClientPlayer ClientLevel ClientPacketListener MultiPlayerGameMode
MultiPlayerLevel
Entity LivingEntity Player Inventory Abilities FoodData ExperienceOrb ItemEntity
ItemStack Item Items ItemCooldowns ItemCooldown ItemContainerContents
Level BlockGetter LevelReader LevelData WritableLevelData ClientLevelData
Block BlockState BlockBehaviour BlockEntity SignBlockEntity SignText BaseContainerBlockEntity
Biome BiomeSpecialEffects DimensionType Vec3 Vec3i BlockPos MutableBlockPos Direction
HitResult BlockHitResult EntityHitResult InteractionHand InteractionResult
AABB Screen AbstractContainerScreen InventoryScreen ChatScreen EditBox AbstractWidget Button
AbstractContainerMenu Slot ClickType MenuType SlotAccess
Gui ChatComponent GuiMessage Line PlayerTabOverlay BossHealthOverlay LerpingBossEvent BossEvent
Scoreboard Objective Score ScoreHolder PlayerScoreEntry ReadOnlyScoreInfo PlayerTeam Team DisplaySlot
ResourceLocation ResourceKey Holder Reference Registry MappedRegistry BuiltInRegistries RegistryAccess
Component MutableComponent TextComponent TranslatableComponent Style
CompoundTag ListTag Tag NbtIo StringTag NumericTag IntTag DoubleTag ByteTag
MobEffectInstance MobEffect AttributeInstance Attributes EquipmentSlot GameType Difficulty
RecipeManager RecipeMap RecipeHolder ClientRecipeBook RecipeBook RecipeCollection Recipe
Stat Stats StatType Advancement AdvancementHolder AdvancementProgress ClientAdvancements
Screenshot NativeImage Hud KeyEvent InputConstants Key BlockStateBase ClientChunkCache ChunkSource
AbstractButton InputWithModifiers AbstractContainerScreen AbstractWidget
Identifier DataComponentMap PatchedDataComponentMap DataComponentType TypedDataComponent
OptionInstance EntityType Registry RegistryAccess Container NonNullList
StatsCounter MapItem MapItemSavedData MapDecoration MapId CustomData DataComponents
LevelHeightAccessor LevelTimeAccess LevelAccessor CommonLevelAccessor BlockAndTintGetter
LevelSummary
CollisionGetter SignalGetter EntityGetter LevelSimulatedReader LevelWriter DisplayInfo Difficulty
ClientRecipeContainer RecipeDisplayEntry RecipeDisplay RecipeBookMenu ServerboundClientCommandPacket Action
PlayerInfo ServerData ConnectionData GameProfile LevelChunk LightLayer
WorldBorder ServerboundCustomPayloadPacket CustomPacketPayload
'''.split())


def included(name):
    return ((name.startswith('net.minecraft.client.gui.') or name.startswith(PREFIXES + ('com.mojang.blaze3d.platform.', 'com.mojang.blaze3d.pipeline.', 'net.minecraft.world.Container', 'net.minecraft.world.Difficulty', 'net.minecraft.util.')) and name.rsplit('.', 1)[-1].split('$')[-1] in SURFACE)
            and not any(s in name for s in DENY))


def class_members(data):
    """Read classfile signatures only, including Java 25 classfiles, without loading code."""
    stream = io.BytesIO(data)
    def u2(): return struct.unpack('>H', stream.read(2))[0]
    def u4(): return struct.unpack('>I', stream.read(4))[0]
    assert u4() == 0xCAFEBABE
    stream.read(4)
    pool = [None] * u2()
    i = 1
    while i < len(pool):
        tag = stream.read(1)[0]
        if tag == 1:
            pool[i] = stream.read(u2()).decode('utf-8', errors='replace')
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            stream.read(4)
        elif tag in (5, 6):
            stream.read(8)
            i += 1
        elif tag == 15:
            stream.read(3)
        else:
            raise ValueError(f'Unknown constant-pool tag {tag}')
        i += 1
    stream.read(4)
    superclass = u2()
    interfaces = [pool[pool[u2()]] for _ in range(u2())]
    result = {'m': [], 'f': [], 'parents': ([pool[pool[superclass]]] if superclass else []) + interfaces}
    for kind in ('f', 'm'):
        for _ in range(u2()):
            stream.read(2)
            name, desc = pool[u2()], pool[u2()]
            result[kind].append([name, name, desc])
            for _ in range(u2()):
                stream.read(2)
                stream.read(u4())
    return result


def fetch(url, sha1=None):
    with FETCH_LOCKS[url]:
        return _fetch_locked(url, sha1)


def _fetch_locked(url, sha1=None):
    CACHE.mkdir(exist_ok=True)
    target = CACHE / (hashlib.sha256(url.encode()).hexdigest() + '.download')
    if not target.exists() or sha1 and hashlib.sha1(target.read_bytes()).hexdigest() != sha1:
        with urllib.request.urlopen(url, timeout=90) as response:
            data = response.read()
        if sha1 and hashlib.sha1(data).hexdigest() != sha1:
            raise ValueError('SHA-1 mismatch: ' + url)
        temp = target.with_suffix('.tmp')
        temp.write_bytes(data)
        temp.replace(target)
    data = target.read_bytes()
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError('Cached SHA-1 mismatch: ' + url)
    return data


def descriptor(name, classes):
    arrays = 0
    while name.endswith('[]'):
        arrays += 1
        name = name[:-2]
    value = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S',
             'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}.get(name)
    return '[' * arrays + (value or 'L' + classes.get(name, name).replace('.', '/') + ';')


def parse_proguard(text):
    classes = {}
    for line in text.splitlines():
        if line and not line.startswith((' ', '#')) and ' -> ' in line:
            named, obf = line[:-1].split(' -> ')
            classes[named] = obf
    rows = {}
    owner = None
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        if not line.startswith(' '):
            owner = line.split(' -> ')[0]
            continue
        if not owner or not included(owner):
            continue
        raw, obf = line.strip().split(' -> ')
        raw = re.sub(r'^\d+:\d+:', '', raw)
        raw = re.sub(r':\d+(?::\d+)?$', '', raw)
        if '(' in raw:
            match = re.fullmatch(r'(\S+) (\S+)\((.*)\)', raw)
            if not match:
                continue
            ret, name, args = match.groups()
            desc = '(' + ''.join(descriptor(a, classes) for a in args.split(',') if a) + ')' + descriptor(ret, classes)
            kind = 'm'
        else:
            typ, name = raw.split(' ', 1)
            desc = descriptor(typ, classes)
            kind = 'f'
        rows.setdefault(owner, {'c': classes[owner], 'm': [], 'f': []})[kind].append([name, obf, desc])
    return classes, rows


def parse_intermediary(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        tiny = archive.read('mappings/mappings.tiny').decode()
    cls, fields, methods = {}, {}, {}
    owner = None
    for line in tiny.splitlines()[1:]:
        p = line.split('\t')
        if p[0] == 'c':
            owner = p[1]
            cls[owner] = p[2]
        elif len(p) >= 5 and p[0] == '' and p[1] in ('m', 'f'):
            (methods if p[1] == 'm' else fields)[(owner, p[3], p[2])] = p[4]
    return cls, fields, methods


def remap_desc(desc, classes):
    return re.sub(r'L([^;]+);', lambda m: 'L' + classes.get(m[1], m[1]) + ';', desc)


def generate(version):
    vid = version['id']
    info = json.loads(fetch(version['url'], version.get('sha1')))
    modern = not vid.startswith('1.')
    if 'client_mappings' not in info['downloads'] and not modern:
        return {'version': vid, 'status': 'not-targeted', 'reason': 'No official client mappings'}
    download = info['downloads']['client'] if modern else info['downloads']['client_mappings']
    if modern:
        rows = {}
        with zipfile.ZipFile(io.BytesIO(fetch(download['url'], download['sha1']))) as archive:
            for entry in archive.namelist():
                name = entry[:-6].replace('/', '.')
                if entry.endswith('.class') and included(name):
                    rows[name] = dict(c=name, **class_members(archive.read(entry)))
    else:
        _, rows = parse_proguard(fetch(download['url'], download['sha1']).decode())
    inter_url = None
    if not modern:
        inter_url = f'https://maven.fabricmc.net/net/fabricmc/intermediary/{vid}/intermediary-{vid}-v2.jar'
        cls, fields, methods = parse_intermediary(fetch(inter_url))
        # Intermediary often assigns an override's name on its interface/superclass only.
        # Read exact class hierarchies rather than guessing a globally ambiguous obfuscated name.
        client_download = info['downloads']['client']
        client_zip = zipfile.ZipFile(io.BytesIO(fetch(client_download['url'], client_download['sha1'])))
        hierarchy = {}
        def parents(owner):
            if owner not in hierarchy:
                try: hierarchy[owner] = class_members(client_zip.read(owner + '.class'))['parents']
                except KeyError: hierarchy[owner] = []
            return hierarchy[owner]
        def member_name(table, owner, name, desc, visited=None):
            visited = set() if visited is None else visited
            if owner in visited: return None
            visited.add(owner)
            found = table.get((owner, name, desc))
            if found: return found
            for parent in parents(owner):
                found = member_name(table, parent, name, desc, visited)
                if found: return found
            return None
        for named, row in rows.items():
            owner = row['c'].replace('.', '/')
            row['c'] = cls.get(owner, owner).replace('/', '.')
            for kind, table in [('m', methods), ('f', fields)]:
                for member in row[kind]:
                    member[1] = member_name(table, owner, member[1], member[2]) or member[1]
                    member[2] = remap_desc(member[2], cls)
        client_zip.close()
    else:
        # Names are shipped unobfuscated since 26.1. Keep an exact signature catalog.
        for named, row in rows.items():
            row['c'] = named
    target = OUT / (vid + '.json')
    target.write_text(json.dumps(rows, separators=(',', ':'), sort_keys=True), encoding='utf-8')
    mc = rows['net.minecraft.client.Minecraft']
    tick = [x for x in mc['m'] if x[0] == 'tick' and x[2] == '()V']
    assert tick, 'Missing tick hook: ' + vid
    required = {'getInstance': '()L', 'tick': '()V'}
    for name in required:
        assert any(x[0] == name for x in mc['m']), (vid, name)
    return {'version': vid, 'status': 'symbols-verified', 'java': info.get('javaVersion', {}).get('majorVersion', 8),
            'classes': len(rows), 'tickClass': mc['c'], 'tickMethod': tick[0][1],
            'clientSha1': info['downloads']['client']['sha1'],
            'mappingSha1': download['sha1'], 'mappingUrl': download['url'], 'intermediaryUrl': inter_url,
            'runtimeTested': False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--versions', nargs='*')
    args = parser.parse_args()
    manifest = json.loads(urllib.request.urlopen(MANIFEST, timeout=60).read())
    versions = [v for v in manifest['versions'] if v['type'] == 'release'
                and (v['id'].startswith(('1.14.', '1.15', '1.16', '1.17', '1.18', '1.19', '1.20', '1.21', '26.')))]
    # 1.14.4 is the earliest release with Mojang's official mapping data.
    versions = [v for v in versions if v['id'] not in ('1.14.1', '1.14.2', '1.14.3')]
    if args.versions:
        versions = [v for v in versions if v['id'] in args.versions]
    OUT.mkdir(parents=True, exist_ok=True)
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for row in pool.map(generate, versions):
            print(row['version'], row['status'], flush=True)
            results.append(row)
    (ROOT / 'docs/compatibility.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    from update_compatibility import main as update_runtime_evidence
    update_runtime_evidence()


if __name__ == '__main__':
    main()
