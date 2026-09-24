#!/usr/bin/env python3
"""Hash the existing Map export inputs; never compile or rewrite Core artifacts."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fresh-export', action='store_true', help='driver just completed the export recipes')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
build = root / 'build/map'
ghc = os.environ.get('GHC', 'ghc')
ghc_pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
plugin_manifest_path = root / 'build/compiler/plugin.json'
plugin = json.loads(plugin_manifest_path.read_text())
if plugin.get('schema') != 1 or not all(plugin.get(key) for key in
                                        ('unitId', 'packageDb', 'sharedLibrary', 'cabalSharedLibrary')):
    raise SystemExit('Invalid Cabal plugin manifest')
plugin_library = Path(plugin['sharedLibrary'])
if not plugin_library.is_file() or not Path(plugin['packageDb']).is_dir():
    raise SystemExit('Cabal plugin manifest points to missing build products')

def output(command):
    return subprocess.check_output(command, text=True).strip()

if output([ghc, '--numeric-version']) != '9.14.1':
    raise SystemExit('THC requires GHC 9.14.1')
if output([ghc_pkg, '--version']) != 'GHC package manager version 9.14.1':
    raise SystemExit('THC requires ghc-pkg 9.14.1')

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def record(path):
    path = Path(path)
    try:
        label = str(path.relative_to(root))
    except ValueError:
        label = str(path)
    return {'path': label, 'sha256': digest(path)}

manifest = build / 'modules.txt'
modules = [Path(p) for p in manifest.read_text().splitlines() if p]
original = json.loads((build / 'provenance.json').read_text())
for item in original['modules']:
    if digest(root / item['path']) != item['sha256']:
        raise SystemExit('Measured bundle differs from its provenance: ' + item['path'])
if digest(root / 'examples/THC/MapWorkload.hs') != original['workloadSha256']:
    raise SystemExit('Workload differs from its recorded bundle provenance')
module_data = [json.loads(p.read_text()) for p in modules]
source_paths = {
    root / 'examples/THC/MapWorkload.hs',
    root / 'compiler/package-roots/InterfaceRoots.hs',
    root / 'vendor/containers-0.8/containers.cabal',
    root / 'vendor/containers-0.8/LICENSE',
    root / 'vendor/archives/containers-0.8.tar.gz',
}
for module in module_data:
    if module['unit'] == 'main' and module['module'] != 'THC.MapWorkload':
        stem = root / 'vendor/containers-0.8/src' / module['module'].replace('.', '/')
        candidates = [stem.with_suffix(ext) for ext in ['.hs', '.lhs']]
        source = next((p for p in candidates if p.is_file()), None)
        if source is None:
            raise SystemExit('Missing original source for supplied module: ' + module['module'])
        source_paths.add(source)
source_paths.update((root / 'vendor/containers-0.8/include').rglob('*'))
source_paths = {p for p in source_paths if p.is_file()}
for item in original['bootExports']['sources']:
    path = root / item['path']
    if digest(path) != item['sha256']:
        raise SystemExit('Pinned boot source changed: ' + str(path))
    source_paths.add(path)
license_path = root / 'vendor/ghc-9.14.1/LICENSE'
license_sha = '768c070bd0b7d820d169ee8153d5487acfc262cbbc10dfce18d05c0bb2d2800d'
if digest(license_path) != license_sha:
    raise SystemExit('Pinned GHC license changed')
source_paths.add(license_path)

# These are exactly the owner modules of executable interface RHSs in the
# bundle, not a claim to inventory every type/rule interface GHC consulted.
interface_owners = sorted({b['originModule'] for m in module_data for b in m['bindings'] if 'originModule' in b})
interfaces = []
for owner in interface_owners:
    unit, module = owner.split(':', 1)
    directory = Path(output([ghc_pkg, 'field', unit, 'import-dirs', '--simple-output'])).resolve()
    interface = directory / (module.replace('.', '/') + '.dyn_hi')
    interfaces.append(dict(record(interface), owner=owner, profile='dynamic'))

exporter_paths = sorted(set((root / 'compiler/THC').rglob('*.hs')) | {
    root / 'compiler/build.sh', root / 'compiler/export.sh', root / 'compiler/toolchain.sh',
    root / 'compiler/export-map.sh', root / 'compiler/export-boot.py', root / 'compiler/plugin.py',
    root / 'thc.cabal', root / 'cabal.project', Path(__file__).resolve(),
})
# The loader flags use the actual Cabal unit and package DB, rather than an
# invented package record. Script hashes remain the executable specification.
shared_export = ['--make', '-no-link', '-O2', '-dynamic', '-fforce-recomp', '-dcore-lint',
                 '-package-db', plugin['packageDb'], '-plugin-package-id', plugin['unitId'],
                 '-fplugin=THC.Plugin', '-i$ROOT/examples']
# Existing-bundle supplements describe the actual files, even if today's driver
# default differs from the setting used to produce them.
source_notes_exported = any('sourceFiles' in m for m in module_data)
source_notes_requested = (os.environ.get('THC_SOURCE_NOTES') or 'true') == 'true'
if args.fresh_export and source_notes_exported != source_notes_requested:
    raise SystemExit('Fresh export source-note metadata differs from its driver setting')
source_note_flags = ['-g', '-fplugin-opt=THC.Plugin:source-notes'] if source_notes_exported else []
boot_common = ['-c', '-dynamic', '-fforce-recomp', '-this-unit-id', 'ghc-internal', '-package', 'ghc-internal',
               '-odir', '$ROOT/build/map/boot-ghc', '-hidir', '$ROOT/build/map/boot-ghc']
recipes = {
    'pluginBuildDriver': ['compiler/build.sh'],
    'mapSource': shared_export + ['-fplugin-opt=THC.Plugin:$ROOT/build/map/core', '-odir', '$ROOT/build/map/ghc',
                  '-hidir', '$ROOT/build/map/ghc', '-i$ROOT/vendor/containers-0.8/src', '-I$ROOT/vendor/containers-0.8/include',
                  '-fplugin-opt=THC.Plugin:closure=mapAggregate'] + source_note_flags + ['examples/THC/MapWorkload.hs'],
    'bootSignatures': boot_common + ['$ROOT/vendor/ghc-9.14.1/{GHC/Internal/Exception/Type.hs-boot,GHC/Internal/Exception.hs-boot}'],
    'bootSources': boot_common + ['-O2', '-dcore-lint', '-package-db', plugin['packageDb'],
                    '-plugin-package-id', plugin['unitId'], '-fplugin=THC.Plugin',
                    '-fplugin-opt=THC.Plugin:$ROOT/build/map/boot-core',
                    '-fplugin-opt=THC.Plugin:post-tidy'] + source_note_flags + ['$ROOT/vendor/ghc-9.14.1/GHC/Internal/{CString,Err}.hs'],
    'installedInterfaceRoot': shared_export + ['-fplugin-opt=THC.Plugin:$ROOT/build/map/interface-core',
                    '-odir', '$ROOT/build/map/interface-ghc', '-hidir', '$ROOT/build/map/interface-ghc',
                    '-package', 'ghc-internal', '-fplugin-opt=THC.Plugin:closure=exceptionInterfaceRoot',
                    ] + source_note_flags + ['compiler/package-roots/InterfaceRoots.hs'],
}
flags_bytes = json.dumps(recipes, sort_keys=True, separators=(',', ':')).encode()
result = {
    'schema': 1,
    'recordedAtUtc': datetime.now(timezone.utc).isoformat(),
    'recordingMode': 'after-fresh-driver-export' if args.fresh_export else 'existing-bundle-read-only-hash-supplement',
    'recordingLimit': 'Hashes describe files present at recording time; this helper does not replay compilation or prove byte-identical regeneration.',
    'toolchain': {
        'ghc': str(Path(shutil.which(ghc) or ghc).resolve()),
        'ghcPkg': str(Path(shutil.which(ghc_pkg) or ghc_pkg).resolve()),
        'version': '9.14.1', 'libdir': output([ghc, '--print-libdir']),
        'ghcInfo': output([ghc, '--info']),
        'packages': {p: output([ghc_pkg, 'describe', p]) for p in ['containers', 'base', 'ghc-internal', 'ghc-prim']},
        'environment': {key: os.environ[key] for key in ['GHC', 'GHC_PKG', 'GHC_ENVIRONMENT', 'GHC_PACKAGE_PATH', 'GHCRTS', 'THC_SOURCE_NOTES'] if key in os.environ},
    },
    'bundle': {'manifest': record(manifest), 'originalProvenance': record(build / 'provenance.json'), 'modules': [record(p) for p in modules]},
    'sources': [record(p) for p in sorted(source_paths)],
    'exporters': [record(p) for p in exporter_paths],
    'exporterArtifacts': [record(plugin_manifest_path), record(plugin_library)],
    'sourceNotes': {'driverDefault': True, 'bundleContainsMetadata': source_notes_exported,
                    'requestedForFreshExport': source_notes_requested if args.fresh_export else None},
    'compilerFlagRecipes': recipes,
    'compilerFlagRecipesSha256': hashlib.sha256(flags_bytes).hexdigest(),
    'compilerFlagEncoding': 'SHA256 of UTF-8 JSON with sorted keys and compact separators',
    'installedInterfaces': interfaces,
    'interfaceCoverage': 'All dynamic interface files owning executable Core/DFun unfoldings actually included in this bundle. Does not cover every transitive type/rule interface consulted during source optimization.',
    'sourceCoverage': 'All supplied main-unit source modules, containers include files/archive/package metadata/license, root Cabal plugin declaration/project, boot source inputs, interface root and GHC license. GHC package source and boot source hashes are also pinned by the export drivers.',
    'license': {'url': 'https://raw.githubusercontent.com/ghc/ghc/ghc-9.14.1-release/libraries/ghc-internal/LICENSE', **record(license_path)},
}
out = build / 'reproduction-provenance.json'
out.write_text(json.dumps(result, indent=2) + '\n')
print('Reproduction provenance:', out)
