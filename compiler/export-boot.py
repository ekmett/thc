#!/usr/bin/env python3
"""Export a bounded real boot-library frontier; never synthesize missing bodies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ghc = os.environ.get('GHC', 'ghc')
ghc_pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
if subprocess.check_output([ghc, '--numeric-version'], text=True).strip() != '9.14.1':
    raise SystemExit('THC requires GHC 9.14.1')
if subprocess.check_output([ghc_pkg, '--version'], text=True).strip() != 'GHC package manager version 9.14.1':
    raise SystemExit('THC requires ghc-pkg 9.14.1')
root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--build-dir', type=Path, default=root / 'build/map',
                    help='Private output directory (default: build/map)')
parser.add_argument('--frontier', choices=['exceptions', 'lists', 'show', 'bignum'], default='exceptions',
                    help='Original ghc-internal modules to export (default: exceptions)')
args = parser.parse_args()
build = args.build_dir.resolve()
(build / 'core').mkdir(parents=True, exist_ok=True)
source_root = root / 'vendor/ghc-9.14.1'
package_url = 'https://raw.githubusercontent.com/ghc/ghc/ghc-9.14.1-release/libraries/ghc-internal/'
base_url = package_url + 'src/'
exception_sources = {
    'GHC/Internal/CString.hs': '3b2e7a0fb2880d8f98cb002adfbaa36a8469667b7494f8f695fe1a6f181de573',
    'GHC/Internal/Err.hs': 'f109ac925928a0e7fed063bcfd03c93e3d8629d8054d984e600aab26c0122478',
    'GHC/Internal/Exception.hs-boot': '7422fa92308439db3c0ca034b02522c96e7961cff00754d0bdca964a4f98bc15',
    'GHC/Internal/Exception/Type.hs-boot': 'f2a0440d35e33a8688d692cf50e4d33e74e92f08e84b04ab8aec20fd4861d2e0',
    'LICENSE': '768c070bd0b7d820d169ee8153d5487acfc262cbbc10dfce18d05c0bb2d2800d',
}
list_sources = {
    'GHC/Internal/Base.hs': 'bc38ea9356f90aeb38298ef1269fbdc2dee433528374948375da112b273a89e2',
    'GHC/Internal/List.hs': 'ae9f56a758942b6e937e7b430ac1137e3ebea171762120ad9c31ef1f4904ba39',
    'GHC/Internal/Exception/Type.hs-boot': exception_sources['GHC/Internal/Exception/Type.hs-boot'],
    'GHC/Internal/IO.hs-boot': 'a687801a14b3b423d45bca16ea03facd5fa0a428f049bcf04c2d3726e272c702',
    'GHC/Internal/Num.hs-boot': 'b765e848138b1d4a22710c45db2e446d3e2c5c07c6b774a8d5cf83a5c4a9b92f',
    'GHC/Internal/Enum.hs-boot': '47353434d99287294958f62ae98303fa3cf6775dc416f95055bd41a2f95a8449',
    'GHC/Internal/Real.hs-boot': '843ed3133589748fbc65e0d7ef7e5a5491dc131b55ff73b67f6e3c3516bb99f4',
    'LICENSE': exception_sources['LICENSE'],
}
show_sources = {
    'GHC/Internal/Show.hs': 'b37f6d9d376e837785d207f2cf784daf0a53a04db26723a456c073616512be98',
    'LICENSE': exception_sources['LICENSE'],
}
bignum_sources = {
    'GHC/Internal/Bignum/Integer.hs': '1f8ec2a8e12ecbab7eb0b59f249fae663d8066f2fb14224177a4538653bb17f4',
    'GHC/Internal/Bignum/Natural.hs': '6895337089fc3ab8a5102b0853c28a4b70281b49ba7705b00eb9750a7e13d8df',
    'GHC/Internal/Bignum/BigNat.hs': '71c334a0bf1bea1772e0801255e92960a4d8b815c3c293a5b2c099c1f97ec18e',
    'include/WordSize.h': '16e46daa3e38bfc98adb9360e54af211cada707d551a7720c00af4af907af090',
    'LICENSE': '768c070bd0b7d820d169ee8153d5487acfc262cbbc10dfce18d05c0bb2d2800d',
    'GHC/Internal/Bignum/Integer.hs-boot': 'f486bbc9637cbcc4b03ea5dcaab9dba71286cadd4a29df3e4f68d2d098eee779',
    'GHC/Internal/Bignum/BigNat.hs-boot': '230a6ac303323e0d0716a39eef450af81bc81494a72192cbe9d20e74f5af45d4',
    'GHC/Internal/Bignum/Natural.hs-boot': '2e7bb92e28f5fa9601b6874b449cfd75bad66a3144bfddb4a445996eaa991026',
}
sources, source_modules, boot_modules = {
    'exceptions': (exception_sources, ['CString', 'Err'], ['Exception/Type', 'Exception']),
    'lists': (list_sources, ['Base', 'List'], ['Exception/Type', 'IO', 'Num', 'Enum', 'Real']),
    'show': (show_sources, ['Show'], []),
    'bignum': (bignum_sources, ['Bignum/BigNat', 'Bignum/Natural', 'Bignum/Integer'],
               ['Bignum/BigNat', 'Bignum/Natural', 'Bignum/Integer']),
}[args.frontier]

def source_url(name):
    return package_url + name if name == 'LICENSE' or name.startswith('include/') else base_url + name

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
for name in source_modules:
    (overlay / 'GHC/Internal' / (name + '.hi')).unlink(missing_ok=True)
common = [ghc, '-c', '-dynamic', '-fforce-recomp', '-this-unit-id', 'ghc-internal', '-package', 'ghc-internal',
          '-odir', str(overlay), '-hidir', str(overlay)]
if args.frontier == 'bignum':
    common += ['-I' + str(source_root / 'include')]
for name in boot_modules:
    subprocess.run(common + [str(source_root / 'GHC/Internal' / (name + '.hs-boot'))], cwd=root, check=True)
plugin = ['-O2', '-dcore-lint', '-package-db', str(root / 'build/compiler/package.conf.d'),
          '-package', 'thc-core-plugin', '-fplugin=THC.Plugin',
          '-fplugin-opt=THC.Plugin:' + str(build / 'boot-core'), '-fplugin-opt=THC.Plugin:post-tidy']
if (os.environ.get('THC_SOURCE_NOTES') or 'true') == 'true':
    plugin += ['-g', '-fplugin-opt=THC.Plugin:source-notes']
if args.frontier in ('lists', 'show', 'bignum'):
    # Loading the plugin's interface would import GHC.Driver.Plugins, including
    # its Semigroup instance, into the Base unit being rebuilt. Load the already
    # compiled plugin directly so the installed Base interface cannot introduce
    # duplicate class instances into this compilation. Source remains unchanged.
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    library = root / 'build/compiler' / ('libHSthc-core-plugin-0.1-ghc9.14.1.' + suffix)
    options = [str(build / 'boot-core'), 'post-tidy']
    plugin = ['-O2', '-dcore-lint']
    if (os.environ.get('THC_SOURCE_NOTES') or 'true') == 'true':
        plugin += ['-g']
        options += ['source-notes']
    plugin += ['-fplugin-library=' + str(library) + ';thc-core-plugin-0.1;THC.Plugin;' + json.dumps(options)]
for name in source_modules:
    subprocess.run(common + plugin + [str(source_root / 'GHC/Internal' / (name + '.hs'))], cwd=root, check=True)
    shutil.copyfile(build / 'boot-core' / ('GHC.Internal.' + name.replace('/', '.') + '.json'),
                    build / 'core' / ('GHC.Internal.' + name.replace('/', '.') + '.json'))
# This compiler-only source holds an actual installed-interface reference behind
# GHC's noinline fence. The plugin then reads genuine non-boot unfoldings.
if args.frontier == 'exceptions':
    env = os.environ.copy()
    env.update(GHC=ghc, GHC_PKG=ghc_pkg)
    env.update(THC_CORE_OUT=str(build / 'interface-core'), THC_GHC_OUT=str(build / 'interface-ghc'))
    subprocess.run([str(root / 'compiler/export.sh'), '-package', 'ghc-internal',
                    '-fplugin-opt=THC.Plugin:closure=exceptionInterfaceRoot',
                    'compiler/package-roots/InterfaceRoots.hs'], cwd=root, env=env, check=True)
    shutil.copyfile(build / 'interface-core/THC.InterfaceClosure.json', build / 'core/GHC.InterfaceClosure.json')
(build / 'boot-provenance.json').write_text(json.dumps({
    'ghcTag': 'ghc-9.14.1-release', 'sourcePatches': [], 'frontier': args.frontier,
    'sourceNotes': (os.environ.get('THC_SOURCE_NOTES') or 'true') == 'true',
    'sources': [{'url': source_url(name), 'path': str((source_root / name).relative_to(root)), 'sha256': digest}
                for name, digest in sources.items()],
    'sourceModules': ['GHC.Internal.' + name.replace('/', '.') for name in source_modules],
    'boundary': 'Original source after Tidy, before CorePrep; explicit dependency boundary',
    'unitPolicy': 'Original wired ghc-internal unit, private dynamic-interface overlay, installed packages unmodified',
    'pluginLoading': 'direct-library' if args.frontier in ('lists', 'show', 'bignum') else 'package-interface',
    'interfaceRoot': 'compiler/package-roots/InterfaceRoots.hs' if args.frontier == 'exceptions' else None,
    'interfacePolicy': 'Actual installed non-boot Core/DFun unfoldings; unsupported and missing paths remain explicit',
}, indent=2) + '\n')
