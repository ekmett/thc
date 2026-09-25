#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Load an exact, content-addressed collection of independently built GHC units."""

import hashlib
import json
from pathlib import Path
import re
import stat
import zlib
from zipfile import BadZipFile, ZipFile


FORMAT = 'thc-core-packages'
BOUNDARY = 'optimized-Core-after-Tidy-before-CorePrep'
SHA256 = re.compile(r'[0-9a-f]{64}\Z')


def foreign_execution_issue(module):
    """Explain a valid archive-only foreign marker without admitting it as Core."""
    foreign = module.get('foreign')
    if (type(module.get('schema')) is int and module['schema'] == 2 and
            isinstance(foreign, dict) and set(foreign) == {'schema', 'execution', 'stubs', 'files'} and
            type(foreign['schema']) is int and foreign['schema'] == 1 and
            foreign['execution'] == 'not-linked'):
        return (f"Unsupported foreign execution for {module.get('unit')}:{module.get('module')}: "
                'Core schema 2 is archive-only (execution=not-linked); typed foreign registration, '
                'native stubs, initializers/finalizers, and callback support are required')
    return None


def validate_archive_only_foreign(module):
    """Mirror the JVM's schema-2 archive shape; this never registers code."""
    foreign = module['foreign']
    def record(value, keys):
        if not isinstance(value, dict) or set(value) != keys:
            raise ValueError('invalid Core foreign artifact record')
        return value
    def text(value, nonempty=False):
        if not isinstance(value, str) or (nonempty and not value):
            raise ValueError('invalid Core foreign artifact text')
        return value
    def labels(value, initializer):
        if not isinstance(value, list):
            raise ValueError('invalid Core foreign artifact labels')
        for entry in value:
            label = record(entry, {'isInitializer', 'unit', 'module', 'name'})
            if type(label['isInitializer']) is not bool or label['isInitializer'] != initializer:
                raise ValueError('invalid Core foreign initializer/finalizer kind')
            for key in ('unit', 'module', 'name'):
                text(label[key], nonempty=True)
        return bool(value)
    nonempty = False
    if foreign['stubs'] is not None:
        stubs = record(foreign['stubs'], {'header', 'source', 'initializers', 'finalizers'})
        nonempty = bool(text(stubs['header']) or text(stubs['source']))
        nonempty = labels(stubs['initializers'], True) or nonempty
        nonempty = labels(stubs['finalizers'], False) or nonempty
    if not isinstance(foreign['files'], list):
        raise ValueError('invalid Core foreign artifact files')
    for entry in foreign['files']:
        file = record(entry, {'language', 'source', 'extension'})
        text(file['language'], nonempty=True)
        text(file['source'])
        text(file['extension'])
        nonempty = True
    if not nonempty:
        raise ValueError('Core schema 2 requires foreign artifacts')


def strict_json(data):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f'Duplicate JSON key: {key}')
            result[key] = value
        return result
    def invalid_constant(value):
        raise ValueError(f'Invalid JSON constant: {value}')
    return json.loads(data, object_pairs_hook=object_pairs, parse_constant=invalid_constant)


def zip_member(name):
    return (isinstance(name, str) and bool(name) and not name.startswith('/') and
            not re.match(r'^[A-Za-z]:', name) and '\\' not in name and
            all(part not in ('', '.', '..') for part in name.split('/')) and
            all(ord(char) >= 32 for char in name))


def bundle_modules(path, unit, records):
    bundle = unit['bundle']
    if (not isinstance(bundle, dict) or set(bundle) != {'path', 'sha256'} or
            not isinstance(bundle['path'], str) or not Path(bundle['path']).is_absolute() or
            not isinstance(bundle['sha256'], str) or not SHA256.fullmatch(bundle['sha256'])):
        raise ValueError(f'{path}: invalid bundle reference for {unit["id"]}')
    archive_path = Path(bundle['path'])
    try:
        with archive_path.open('rb') as stream:
            digest = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
            if digest.hexdigest() != bundle['sha256']:
                raise ValueError(f'{path}: bundle hash mismatch: {archive_path}')
            stream.seek(0)
            with ZipFile(stream) as archive:
                infos = archive.infolist()
                names = [info.filename for info in infos]
                if ('manifest.json' not in names or len(names) != len(set(names)) or
                        any(not zip_member(name) or info.is_dir() or
                            (info.create_system == 3 and
                             stat.S_IFMT(info.external_attr >> 16) == stat.S_IFLNK)
                            for name, info in zip(names, infos))):
                    raise ValueError(f'{path}: duplicate, unsafe, missing, or extra ZIP entry in {archive_path}')
                inner = strict_json(archive.read('manifest.json').decode('utf-8'))
                if (not isinstance(inner, dict) or inner.get('format') != 'thc-core-bundle' or
                        type(inner.get('schema')) is not int or inner['schema'] != 1 or
                        inner.get('unit') != unit['id'] or
                        not isinstance(inner.get('buildKey'), str) or not SHA256.fullmatch(inner['buildKey']) or
                        not isinstance(inner.get('exportKey'), str) or not SHA256.fullmatch(inner['exportKey']) or
                        inner.get('modules') != records):
                    raise ValueError(f'{path}: bundle manifest disagrees with unit {unit["id"]}')
                expected = {'manifest.json', *(item['path'] for item in records)}
                if 'buildInputs' in inner:
                    ref = inner['buildInputs']
                    if (not isinstance(ref, dict) or set(ref) != {'path', 'sha256'} or
                            ref['path'] != 'inplace-manifest.json' or
                            not isinstance(ref['sha256'], str) or not SHA256.fullmatch(ref['sha256'])):
                        raise ValueError(f'{path}: invalid build-inputs reference for {unit["id"]}')
                    expected.add(ref['path'])
                if set(names) != expected:
                    raise ValueError(f'{path}: duplicate, unsafe, missing, or extra ZIP entry in {archive_path}')
                if 'buildInputs' in inner:
                    inputs = archive.read('inplace-manifest.json')
                    if hashlib.sha256(inputs).hexdigest() != inner['buildInputs']['sha256']:
                        raise ValueError(f'{path}: build-inputs hash mismatch for {unit["id"]}')
                    record = strict_json(inputs.decode('utf-8'))
                    if (not isinstance(record, dict) or record.get('format') != 'thc-core-build-inputs' or
                            type(record.get('schema')) is not int or record['schema'] != 1 or
                            record.get('unit') != unit['id'] or
                            record.get('buildKey') != inner['buildKey'] or
                            record.get('exportKey') != inner['exportKey']):
                        raise ValueError(f'{path}: build-inputs record disagrees with unit {unit["id"]}')
                return [(str(archive_path) + '!/' + item['path'], archive.read(item['path']))
                        for item in records]
    except (BadZipFile, RuntimeError, EOFError, zlib.error) as error:
        raise ValueError(f'{path}: invalid ZIP bundle {archive_path}: {error}') from error


def load(path):
    return _load(path, audit_archives=False)


def load_for_audit(path):
    """Read verified archive-only foreign Core for diagnostics, never execution."""
    return _load(path, audit_archives=True)


def _load(path, audit_archives):
    path = Path(path)
    root = path.resolve().parent
    manifest = strict_json(path.read_text())
    if (not isinstance(manifest, dict) or manifest.get('format') != FORMAT or
            type(manifest.get('schema')) is not int or manifest['schema'] != 1 or
            manifest.get('ghc') != '9.14.1' or
            not isinstance(manifest.get('units'), list)):
        raise ValueError(f'{path}: requires {FORMAT} schema 1 / GHC 9.14.1')
    units = set()
    module_keys = set()
    documents = []
    for unit in manifest['units']:
        if not isinstance(unit, dict):
            raise ValueError(f'{path}: invalid unit record')
        unit_id = unit.get('id')
        dependencies = unit.get('depends')
        modules = unit.get('modules')
        if (not isinstance(unit_id, str) or not unit_id or unit_id in units or
                not isinstance(dependencies, list) or
                any(not isinstance(dep, str) or not dep for dep in dependencies) or
                len(set(dependencies)) != len(dependencies) or
                not isinstance(modules, list)):
            raise ValueError(f'{path}: invalid/duplicate unit or dependencies: {unit_id!r}')
        units.add(unit_id)
        records = []
        for item in modules:
            if not isinstance(item, dict):
                raise ValueError(f'{path}: invalid module record in {unit_id}')
            name, boundary = item.get('name'), item.get('boundary')
            relative, expected = item.get('path'), item.get('sha256')
            key = (unit_id, name)
            if (not isinstance(name, str) or not name or key in module_keys or
                    boundary != BOUNDARY or not isinstance(relative, str) or not relative or
                    not isinstance(expected, str) or not SHA256.fullmatch(expected)):
                raise ValueError(f'{path}: invalid/duplicate post-Tidy module: {key!r}')
            module_keys.add(key)
            if 'bundle' in unit:
                if not zip_member(relative) or relative == 'manifest.json':
                    raise ValueError(f'{path}: unsafe ZIP member path: {relative!r}')
            else:
                artifact = Path(relative)
                if artifact.is_absolute() or '..' in artifact.parts:
                    raise ValueError(f'{path}: module path must stay inside manifest root: {relative!r}')
                artifact = (root / artifact).resolve()
                if not artifact.is_relative_to(root):
                    raise ValueError(f'{path}: module path escapes manifest root: {relative!r}')
            records.append(item)
        artifacts = (bundle_modules(path, unit, records) if 'bundle' in unit else
                     [(str((root / item['path']).resolve()),
                       (root / item['path']).resolve().read_bytes()) for item in records])
        for item, (artifact, data) in zip(records, artifacts):
            name, boundary = item['name'], item['boundary']
            relative, expected = item['path'], item['sha256']
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError(f'{path}: content hash mismatch: {relative!r}')
            module = strict_json(data.decode('utf-8'))
            if (not isinstance(module, dict) or module.get('unit') != unit_id or
                    module.get('module') != name or module.get('boundary') != boundary):
                raise ValueError(f'{path}: unit/module/boundary mismatch: {relative!r}')
            if module.get('ghc') != '9.14.1':
                raise ValueError(f'{path}: GHC version mismatch: {relative!r}')
            if type(module.get('schema')) is not int or module['schema'] != 1 or 'foreign' in module:
                detail = foreign_execution_issue(module)
                if audit_archives and detail:
                    try:
                        validate_archive_only_foreign(module)
                    except ValueError as error:
                        raise ValueError(f'{path}: malformed foreign archive in {unit_id}:{name}: {error}') from error
                else:
                    raise ValueError(f'{path}: {detail or "unsupported Core module schema/foreign metadata"}: {relative!r}')
            bindings = module.get('bindings')
            if not isinstance(bindings, list):
                raise ValueError(f'{path}: missing bindings: {relative!r}')
            prefix = unit_id + ':' + name + '.'
            alias = 'main::' + name + '.main'
            for binding in bindings:
                key = binding.get('id') if isinstance(binding, dict) else None
                if not isinstance(key, str) or not (key.startswith(prefix) or key == alias):
                    raise ValueError(f'{path}: foreign binding owner in {relative!r}: {key!r}')
            documents.append((str(artifact), module))
    if not documents:
        raise ValueError(f'{path}: no Core modules')
    return documents
