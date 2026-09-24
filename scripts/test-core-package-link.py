#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Prove one registered GHC dependency links in native code and compiled THC."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/core-package-link'
FIXTURE = ROOT / 'compiler/test-fixtures/core-packages'
DEP = 'dep-data-0.1.0.0-inplace'
APP = 'app-run-0.1.0.0-inplace'
ENTRY = APP + ':Main.score#'
BOUNDARY = 'optimized-Core-after-Tidy-before-CorePrep'
GHC = os.environ.get('GHC', 'ghc')
GHC_PKG = os.environ.get('GHC_PKG', 'ghc-pkg')
PLUGIN_SPEC = importlib.util.spec_from_file_location('thc_plugin', ROOT / 'compiler/plugin.py')
PLUGIN = importlib.util.module_from_spec(PLUGIN_SPEC)
PLUGIN_SPEC.loader.exec_module(PLUGIN)


def run(argv, *, check=True, env=None):
    result = subprocess.run([str(arg) for arg in argv], cwd=ROOT, env=env,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        raise RuntimeError(f'{argv!r}\n{result.stdout}\n{result.stderr}')
    return result


def export_flags(destination, post=True):
    plugin = PLUGIN.read(ROOT)
    flags = ['-package-db', plugin['packageDb'],
             '-plugin-package-id', plugin['unitId'],
             '-fplugin=THC.Plugin', '-fplugin-opt=THC.Plugin:' + str(destination),
             '-fplugin-opt=THC.Plugin:unit-qualified', '-fplugin-opt=THC.Plugin:source-notes']
    if post:
        flags.append('-fplugin-opt=THC.Plugin:post-tidy')
    return flags


def artifact(unit, name):
    return OUT / 'core/units' / ('u-' + unit) / (name + '.json')


def manifest_module(unit, name):
    path = artifact(unit, name)
    return dict(name=name, boundary=BOUNDARY, path=str(path.relative_to(OUT)),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    if run([GHC, '--numeric-version']).stdout.strip() != '9.14.1':
        raise RuntimeError('Cross-package proof requires GHC 9.14.1')
    runtime = ROOT / 'build/install/thc/bin/thc'
    if not runtime.is_file():
        raise RuntimeError('Build the JVM runtime first with ./gradlew installDist')
    for directory in ('dep', 'app', 'dep-pre', 'core'):
        (OUT / directory).mkdir(parents=True, exist_ok=True)
    if not (OUT / 'package.conf.d').exists():
        run([GHC_PKG, 'init', OUT / 'package.conf.d'])
    run([ROOT / 'compiler/build.sh'], env=dict(os.environ, GHC=GHC, GHC_PKG=GHC_PKG))
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    library = OUT / 'dep' / f'libHS{DEP}-ghc9.14.1.{suffix}'
    run([GHC, '--make', '-O2', '-dynamic', '-shared', '-fPIC', '-fforce-recomp', '-g0',
         '-hisuf', 'dyn_hi', '-osuf', 'dyn_o', '-this-unit-id', DEP,
         *export_flags(OUT / 'core'), '-odir', OUT / 'dep', '-hidir', OUT / 'dep',
         FIXTURE / 'dep/Dep.hs', '-o', library])
    base = run([GHC_PKG, 'field', 'base', 'id', '--simple-output']).stdout.strip()
    dep_conf = OUT / 'dep-data.conf'
    dep_conf.write_text(f'name: dep-data\nversion: 0.1.0.0\nid: {DEP}\nkey: {DEP}\n'
                        f'exposed: True\nexposed-modules: Dep\nimport-dirs: {OUT / "dep"}\n'
                        f'library-dirs: {OUT / "dep"}\ndynamic-library-dirs: {OUT / "dep"}\n'
                        f'hs-libraries: HS{DEP}\ndepends: {base}\n')
    run([GHC_PKG, '--package-db', OUT / 'package.conf.d', 'update', '--force', dep_conf])
    app_exe = OUT / 'app/native'
    run([GHC, '--make', '-O2', '-dynamic', '-fforce-recomp', '-g0', '-this-unit-id', APP,
         '-package-db', OUT / 'package.conf.d', '-package-id', DEP,
         *export_flags(OUT / 'core'), '-odir', OUT / 'app', '-hidir', OUT / 'app',
         FIXTURE / 'app/Main.hs', '-o', app_exe])
    native = run([app_exe]).stdout.strip()
    if native != '51':
        raise RuntimeError(f'native oracle returned {native!r}, expected 51')
    document = dict(format='thc-core-packages', schema=1, ghc='9.14.1', units=[
        dict(id=DEP, depends=[base], modules=[manifest_module(DEP, 'Dep')]),
        dict(id=APP, depends=[DEP, base], modules=[manifest_module(APP, 'Main')])])
    manifest = OUT / 'packages.json'
    manifest.write_text(json.dumps(document, indent=2) + '\n')
    for unit, name, source in ((DEP, 'Dep', FIXTURE / 'dep/Dep.hs'),
                               (APP, 'Main', FIXTURE / 'app/Main.hs')):
        exported = json.loads(artifact(unit, name).read_text())
        files = exported.get('sourceFiles', [])
        spans = exported.get('sourceSpans', [])
        file_ids = {file['id'] for file in files}
        if (not spans or not any(file.get('content') == source.read_text() for file in files) or
                not all(unit in file_id for file_id in file_ids) or
                not all(span['file'] in file_ids and unit in span['id'] for span in spans)):
            raise RuntimeError(f'GHC source provenance missing from {unit}:{name}')
    audit_path = OUT / 'audit.json'
    run([sys.executable, ROOT / 'scripts/audit-core.py', '--package-manifest', manifest,
         '--entry', ENTRY, '--output', audit_path])
    audit = json.loads(audit_path.read_text())
    if not audit['accepted'] or audit['summary']['missingGlobals']:
        raise RuntimeError('Strict package audit did not accept exact cross-unit closure')
    for backend in ('ast', 'bytecode'):
        env = dict(os.environ, THC_BACKEND=backend)
        result = run([runtime, '@' + str(manifest), ENTRY, '5', '--compile'], env=env)
        diagnostics = json.loads(result.stderr.splitlines()[-1])
        if result.stdout.strip() != native or diagnostics['backend'] != backend or diagnostics['compiledEntries'] < 1:
            raise RuntimeError(f'{backend} failed compiled native comparison: {result.stdout!r}, {diagnostics!r}')
    # A declared dependency with no supplied Core body cannot be treated as a
    # native fallback for a reachable guest binding.
    missing_manifest = OUT / 'missing-dependency.json'
    missing_manifest.write_text(json.dumps({**document, 'units': document['units'][1:]}) + '\n')
    missing_audit_path = OUT / 'missing-audit.json'
    missing_audit = run([sys.executable, ROOT / 'scripts/audit-core.py', '--package-manifest',
                         missing_manifest, '--entry', ENTRY, '--output', missing_audit_path], check=False)
    rejected = json.loads(missing_audit_path.read_text())
    if missing_audit.returncode == 0 or rejected['summary']['missingGlobals'] < 2:
        raise RuntimeError('Missing package dependency was accepted by strict audit')
    missing_runtime = run([runtime, '@' + str(missing_manifest), ENTRY, '5'], check=False)
    if missing_runtime.returncode == 0 or 'Unlinked Core globals' not in missing_runtime.stderr:
        raise RuntimeError('Missing package dependency reached guest execution')
    # A pre-Tidy source export does not carry the worker names that appear in
    # the independently compiled app's installed-package interface references.
    run([GHC, '--make', '-O2', '-dynamic', '-no-link', '-fforce-recomp', '-g0',
         '-this-unit-id', DEP, *export_flags(OUT / 'pre-core', post=False),
         '-odir', OUT / 'dep-pre', '-hidir', OUT / 'dep-pre', FIXTURE / 'dep/Dep.hs'])
    pre = json.loads((OUT / 'pre-core/units' / ('u-' + DEP) / 'Dep.json').read_text())
    post = json.loads(artifact(DEP, 'Dep').read_text())
    app = json.loads(artifact(APP, 'Main').read_text())
    pre_ids = {item['id'] for item in pre['bindings']}
    post_ids = {item['id'] for item in post['bindings']}
    def strings(value):
        if isinstance(value, str):
            yield value
        elif isinstance(value, list):
            for part in value:
                yield from strings(part)
        elif isinstance(value, dict):
            for part in value.values():
                yield from strings(part)
    imported_workers = {word for item in app['bindings'] for word in strings(item['expr'])
                        if word.startswith(DEP + ':Dep.$w')}
    if not imported_workers or not imported_workers <= post_ids or not imported_workers - pre_ids:
        raise RuntimeError('Fixture no longer demonstrates pre-/post-Tidy cross-unit identity mismatch')
    print(json.dumps(dict(native=int(native), auditReachable=audit['summary']['reachableBindings'],
                          compiledBackends=['ast', 'bytecode'], importedWorkers=sorted(imported_workers))))


if __name__ == '__main__':
    main()
