#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('build/decoded')


def find_smali(relative):
    hits = []
    for d in ROOT.glob('smali*'):
        p = d / relative
        if p.exists():
            hits.append(p)
    if len(hits) != 1:
        raise RuntimeError(f'{relative}: expected one smali file, found {hits}')
    return hits[0]


def split_methods(text):
    lines = text.splitlines(True)
    blocks = []
    i = 0
    while i < len(lines):
        if lines[i].startswith('.method '):
            start = i
            i += 1
            while i < len(lines) and not lines[i].startswith('.end method'):
                i += 1
            if i >= len(lines):
                raise RuntimeError('unterminated method')
            blocks.append((start, i, ''.join(lines[start:i + 1])))
        i += 1
    return lines, blocks


def replace_method(text, name, transform):
    lines, blocks = split_methods(text)
    matches = [(s, e, b) for s, e, b in blocks if re.search(r'\b' + re.escape(name) + r'\(', b.splitlines()[0])]
    if len(matches) != 1:
        raise RuntimeError(f'method {name}: expected one match, got {len(matches)}')
    s, e, block = matches[0]
    new_block = transform(block)
    return ''.join(lines[:s]) + new_block + ('' if new_block.endswith('\n') else '\n') + ''.join(lines[e + 1:])


def make_void_noop(block):
    header = block.splitlines()[0]
    return header + '\n    .locals 0\n\n    return-void\n.end method\n'


# 1) Fix the Android-13-only LauncherModel#mApp icon-pack refresh path.
callback_path = find_smali('com/hhvvg/launcher/Launcher$LauncherCallback.smali')
callback = callback_path.read_text(encoding='utf-8')


def patch_icon_pack(block):
    pattern = re.compile(
        r'(?m)^\s*invoke-virtual \{(?P<reg>[vp]\d+)\}, '
        r'Lcom/hhvvg/launcher/model/LauncherModel;->getApp\(\)Lcom/hhvvg/launcher/model/LauncherAppState;\s*\n'
        r'\s*move-result-object (?P=reg)\s*\n'
        r'\s*invoke-virtual \{(?P=reg)\}, '
        r'Lcom/hhvvg/launcher/model/LauncherAppState;->refreshAndReloadLauncher\(\)V\s*$'
    )
    repl = ('    invoke-virtual {\\g<reg>}, '
            'Lcom/hhvvg/launcher/model/LauncherModel;->forceReload()V')
    new, count = pattern.subn(repl, block)
    if count != 1:
        raise RuntimeError(f'onIconPackProviderChanged old mApp tail count={count}')
    return new

callback = replace_method(callback, 'onIconPackProviderChanged', patch_icon_pack)

# These six callbacks all used LauncherModel#getApp()->LauncherAppState#getIdp(), which no longer
# exists on LOS23.2. Initial state synchronization must not restart or crash Trebuchet. The new
# LOS23 hook applies the values when views are recreated; app-side setters request one debounced
# root restart after an actual user change.
for name in [
    'lambda$onAllAppsIconVisibilityChanged$8$com-hhvvg-launcher-Launcher$LauncherCallback',
    'lambda$onIconDrawablePaddingScaleChanged$7$com-hhvvg-launcher-Launcher$LauncherCallback',
    'lambda$onIconScaleChanged$5$com-hhvvg-launcher-Launcher$LauncherCallback',
    'lambda$onIconTextScaleChanged$6$com-hhvvg-launcher-Launcher$LauncherCallback',
    'lambda$onIconTextVisibilityChanged$2$com-hhvvg-launcher-Launcher$LauncherCallback',
    'lambda$onSetUseCustomSpringLoadedEffect$4$com-hhvvg-launcher-Launcher$LauncherCallback',
]:
    callback = replace_method(callback, name, make_void_noop)
callback_path.write_text(callback, encoding='utf-8')

# 2) Disable only the obsolete Android-13 DeviceProfile proxy target. LOS23CompatHook replaces it.
targets_path = find_smali('com/hhvvg/launcher/hook/HookTargetsKt.smali')
targets = targets_path.read_text(encoding='utf-8')
targets, count = re.subn(
    r'(const-class\s+[vp]\d+,\s+)Lcom/hhvvg/launcher/DeviceProfile;',
    r'\1Lcom/hhvvg/launcher/component/Component;',
    targets,
)
if count != 1:
    raise RuntimeError(f'DeviceProfile target const-class count={count}')
targets_path.write_text(targets, encoding='utf-8')

# 3) A missing private Launcher method on Android 16 is an Error (NoSuchMethodError), not Exception.
provider_path = find_smali('com/hhvvg/launcher/hook/HookProviderKt.smali')
provider = provider_path.read_text(encoding='utf-8')


def patch_apply_method_hook(block):
    new, count = re.subn(r'\.catch Ljava/lang/Exception;', '.catch Ljava/lang/Throwable;', block)
    if count < 1:
        raise RuntimeError('applyMethodHook: Exception catch not found')
    return new

provider = replace_method(provider, 'applyMethodHook', patch_apply_method_hook)
provider_path.write_text(provider, encoding='utf-8')

# 4) Root restart is requested from the SETTINGS APP side only, never from Trebuchet callbacks.
# This is the key safety property that prevents the previous boot/restart loop.
proxy_path = find_smali('com/hhvvg/launcher/ILauncherService$Stub$Proxy.smali')
proxy = proxy_path.read_text(encoding='utf-8')
visual_setters = [
    'setIconPackProvider',
    'setIconScale',
    'setIconTextScale',
    'setIconDrawablePaddingScale',
    'setIconTextVisible',
    'setAllAppsIconTextVisible',
    'setClickEffectEnable',
    'setAdaptiveIconEnable',
    'setDotParamsColor',
    'setDrawNotificationCount',
    'setSpringLoadedBgEnable',
    'setUseCustomSpringLoadedEffect',
    'setQsbEnable',
    'setOpenedFolderCenter',
    'setShowAppEntryOnOptions',
    'setComponentLabel',
    'restoreDotParamsColor',
    'resetAppFavorites',
]


def add_app_restart(block):
    if 'Lcom/hhvvg/launcher/compat/RootRestart;->schedule()V' in block:
        return block
    new, count = re.subn(
        r'(?m)^(\s*)return-void\s*$',
        r'\1invoke-static {}, Lcom/hhvvg/launcher/compat/RootRestart;->schedule()V\n\n\1return-void',
        block,
    )
    # AIDL proxy methods may have one return after transact plus another default-impl return.
    # Both are successful exit paths; instrumenting each is correct because only one path runs.
    if count < 1 or count > 3:
        raise RuntimeError(f'proxy setter return count={count}')
    return new

for name in visual_setters:
    proxy = replace_method(proxy, name, add_app_restart)
proxy_path.write_text(proxy, encoding='utf-8')

# 5) Add the LOS23 hook entry. RootRestart is not an Xposed entry point; it is called by app smali.
xinit = ROOT / 'assets/xposed_init'
lines = [x.strip() for x in xinit.read_text(encoding='utf-8').splitlines() if x.strip()]
for entry in ['com.hhvvg.launcher.Init', 'com.hhvvg.launcher.compat.Los23CompatHook']:
    if entry not in lines:
        lines.append(entry)
xinit.write_text('\n'.join(lines) + '\n', encoding='utf-8')

print('Patched:', callback_path)
print('Patched:', targets_path)
print('Patched:', provider_path)
print('Patched:', proxy_path)
print('Xposed init:', lines)
print('App-side restart setters:', len(visual_setters))
