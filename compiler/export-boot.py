#!/usr/bin/env python3
"""Export a bounded real boot-library frontier; never synthesize missing bodies."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ghc = os.environ.get('GHC', 'ghc')
ghc_pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
if subprocess.check_output([ghc, '--numeric-version'], text=True).strip() != '9.14.1':
    raise SystemExit('THC requires GHC 9.14.1')
if subprocess.check_output([ghc_pkg, '--version'], text=True).strip() != 'GHC package manager version 9.14.1':
    raise SystemExit('THC requires ghc-pkg 9.14.1')
root = Path(__file__).resolve().parent.parent
build = root / 'build/map'
(build / 'core').mkdir(parents=True, exist_ok=True)
source_root = root / 'vendor/ghc-9.14.1'
package_url = 'https://raw.githubusercontent.com/ghc/ghc/ghc-9.14.1-release/libraries/ghc-internal/'
base_url = package_url + 'src/'
sources = {
    'GHC/Internal/CString.hs': '3b2e7a0fb2880d8f98cb002adfbaa36a8469667b7494f8f695fe1a6f181de573',
    'GHC/Internal/Err.hs': 'f109ac925928a0e7fed063bcfd03c93e3d8629d8054d984e600aab26c0122478',
    'GHC/Internal/Exception.hs-boot': '7422fa92308439db3c0ca034b02522c96e7961cff00754d0bdca964a4f98bc15',
    'GHC/Internal/Exception/Type.hs-boot': 'f2a0440d35e33a8688d692cf50e4d33e74e92f08e84b04ab8aec20fd4861d2e0',
    'LICENSE': '768c070bd0b7d820d169ee8153d5487acfc262cbbc10dfce18d05c0bb2d2800d',
}

def source_url(name):
    return package_url + 'LICENSE' if name == 'LICENSE' else base_url + name

for name, expected in sources.items():
    path = source_root / name
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(source_url(name)) as response:
            path.write_bytes(response.read())
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit('Pinned GHC source SHA256 mismatch: ' + str(path))

installed = Path(subprocess.check_output([ghc_pkg, 'field', 'ghc-internal', 'import-dirs', '--simple-output'], text=True).strip()).resolve()
overlay = build / 'boot-ghc'
# -c treats same-unit imports as home interfaces. Supply a private interface
# overlay and match -dynamic's expected profile. Installed files remain read-only.
for interface in installed.rglob('*.dyn_hi'):
    destination = overlay / interface.relative_to(installed).with_suffix('.hi')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)
    destination.symlink_to(interface)
for name in ['CString', 'Err']:
    (overlay / 'GHC/Internal' / (name + '.hi')).unlink(missing_ok=True)
common = [ghc, '-c', '-dynamic', '-fforce-recomp', '-this-unit-id', 'ghc-internal', '-package', 'ghc-internal',
          '-odir', str(overlay), '-hidir', str(overlay)]
for name in ['GHC/Internal/Exception/Type.hs-boot', 'GHC/Internal/Exception.hs-boot']:
    subprocess.run(common + [str(source_root / name)], cwd=root, check=True)
plugin = ['-O2', '-dcore-lint', '-package-db', str(root / 'build/compiler/package.conf.d'),
          '-package', 'thc-core-plugin', '-fplugin=Thc.Plugin',
          '-fplugin-opt=Thc.Plugin:' + str(build / 'boot-core'), '-fplugin-opt=Thc.Plugin:post-tidy']
if (os.environ.get('THC_SOURCE_NOTES') or 'true') == 'true':
    plugin += ['-g', '-fplugin-opt=Thc.Plugin:source-notes']
for name in ['CString', 'Err']:
    subprocess.run(common + plugin + [str(source_root / 'GHC/Internal' / (name + '.hs'))], cwd=root, check=True)
    shutil.copyfile(build / 'boot-core' / ('GHC.Internal.' + name + '.json'),
                    build / 'core' / ('GHC.Internal.' + name + '.json'))
# This compiler-only source holds an actual installed-interface reference behind
# GHC's noinline fence. The plugin then reads genuine non-boot unfoldings.
env = os.environ.copy()
env.update(GHC=ghc, GHC_PKG=ghc_pkg)
env.update(THC_CORE_OUT=str(build / 'interface-core'), THC_GHC_OUT=str(build / 'interface-ghc'))
subprocess.run([str(root / 'compiler/export.sh'), '-package', 'ghc-internal',
                '-fplugin-opt=Thc.Plugin:closure=exceptionInterfaceRoot',
                'compiler/package-roots/InterfaceRoots.hs'], cwd=root, env=env, check=True)
shutil.copyfile(build / 'interface-core/THC.InterfaceClosure.json', build / 'core/GHC.InterfaceClosure.json')
(build / 'boot-provenance.json').write_text(json.dumps({
    'ghcTag': 'ghc-9.14.1-release', 'sourcePatches': [],
    'sourceNotes': (os.environ.get('THC_SOURCE_NOTES') or 'true') == 'true',
    'sources': [{'url': source_url(name), 'path': str((source_root / name).relative_to(root)), 'sha256': digest}
                for name, digest in sources.items()],
    'boundary': 'Original CString/Err source after Tidy, before CorePrep; explicit dependency boundary',
    'unitPolicy': 'Original wired ghc-internal unit, private dynamic-interface overlay, installed packages unmodified',
    'interfaceRoot': 'compiler/package-roots/InterfaceRoots.hs',
    'interfacePolicy': 'Actual installed non-boot Core/DFun unfoldings; unsupported and missing paths remain explicit',
}, indent=2) + '\n')
