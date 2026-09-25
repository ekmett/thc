#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Load an exact, content-addressed collection of independently built GHC units."""

import hashlib
import json
from pathlib import Path
import platform
import re
import stat
import zlib
from zipfile import BadZipFile, ZipFile


FORMAT = 'thc-core-packages'
BOUNDARY = 'optimized-Core-after-Tidy-before-CorePrep'
SHA256 = re.compile(r'[0-9a-f]{64}\Z')


def capi_kind(call, unit, symbol):
    def scalar(primitive, evaluated):
        return dict(kind='void' if primitive is None else 'address' if primitive == 'AddrRep' else 'long',
                    primReps=[] if primitive is None else [primitive], evaluated=evaluated)

    def expected(kind):
        zero = kind == 'clock-id'
        output = 'Word64Rep' if zero else 'Int32Rep'
        return dict(schema=1, target=dict(kind='static', symbol=symbol, unit=unit, isFunction=True),
                    convention='capi', safety='unsafe', arity=1 if zero else 3,
                    suppliedArity=1 if zero else 3,
                    argumentReps=([scalar(None, False)] if zero else
                                  [scalar('Word64Rep', False), scalar('AddrRep', False), scalar(None, False)]),
                    resultRep=dict(kind='unknown', primReps=[output], aggregate='unboxed-tuple',
                                   components=[scalar(None, True), scalar(output, True)], evaluated=False))

    def exact(value, wanted):
        if type(value) is not type(wanted):
            return False
        if isinstance(wanted, dict):
            return value.keys() == wanted.keys() and all(exact(value[key], item) for key, item in wanted.items())
        if isinstance(wanted, list):
            return len(value) == len(wanted) and all(exact(a, b) for a, b in zip(value, wanted))
        return value == wanted

    return next((kind for kind in ('clock-id', 'clock-buffer') if exact(call, expected(kind))), None)


def linked_foreign(module):
    """Verify the one fully linked, callback-free CAPI archive admitted so far."""
    link = module.get('foreignLink')
    if link is None:
        return False
    if (not isinstance(link, dict) or set(link) != {'schema', 'format', 'unit', 'module',
            'target', 'symbols', 'abi', 'sourceSha256', 'bitcodeSha256', 'bitcodeHex'} or
            type(link['schema']) is not int or link['schema'] != 2 or
            link['format'] != 'llvm-bitcode' or link['unit'] != module.get('unit') or
            link['module'] != module.get('module') or
            link['module'] != 'System.CPUTime.Posix.ClockGetTime' or
            not isinstance(link['unit'], str) or not link['unit'].startswith('base-')):
        raise ValueError('invalid linked foreign owner/schema')
    target = link['target']
    machine = platform.machine().lower()
    host_arch = {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(machine, machine)
    target_arch = target.split('-', 1)[0] if isinstance(target, str) else ''
    system = platform.system().lower()
    compatible_arch = target_arch == host_arch or host_arch == 'aarch64' and target_arch == 'arm64'
    compatible_system = ((system == 'darwin' and '-darwin' in target) or
                         (system == 'linux' and target.endswith('linux-gnu'))) if isinstance(target, str) else False
    if not (compatible_arch and compatible_system):
        raise ValueError('linked foreign bitcode target differs from audit host')
    foreign = module.get('foreign')
    if not isinstance(foreign, dict):
        raise ValueError('linked foreign module lacks original archive')
    stubs = foreign.get('stubs')
    if (not isinstance(stubs, dict) or stubs.get('header') != '' or
            stubs.get('initializers') != [] or stubs.get('finalizers') != [] or
            foreign.get('files') != [] or not isinstance(stubs.get('source'), str) or
            not stubs['source']):
        raise ValueError('linked foreign module needs unsupported callbacks/files')
    if (not isinstance(link['sourceSha256'], str) or not SHA256.fullmatch(link['sourceSha256']) or
            hashlib.sha256(stubs['source'].encode('utf-8')).hexdigest() != link['sourceSha256']):
        raise ValueError('linked foreign C source hash mismatch')
    encoded = link['bitcodeHex']
    try:
        data = bytes.fromhex(encoded)
    except (TypeError, ValueError) as error:
        raise ValueError('invalid linked foreign bitcode encoding') from error
    if (not isinstance(encoded, str) or not encoded or encoded != data.hex() or
            not isinstance(link['bitcodeSha256'], str) or
            not SHA256.fullmatch(link['bitcodeSha256']) or
            hashlib.sha256(data).hexdigest() != link['bitcodeSha256']):
        raise ValueError('linked foreign bitcode hash mismatch')
    symbols = link['symbols']
    if (not isinstance(symbols, list) or len(symbols) != 3 or
            any(not isinstance(x, str) or not x for x in symbols) or
            len(set(symbols)) != 3):
        raise ValueError('invalid linked foreign symbol inventory')
    entries = link['abi']
    if (not isinstance(entries, list) or len(entries) != 3 or
            any(not isinstance(entry, dict) or set(entry) != {'symbol', 'kind'} or
                type(entry['symbol']) is not str or type(entry['kind']) is not str or
                entry['kind'] not in ('clock-id', 'clock-buffer') for entry in entries)):
        raise ValueError('invalid linked CAPI ABI inventory')
    abi = {entry['symbol']: entry['kind'] for entry in entries}
    if (set(abi) != set(symbols) or len(abi) != 3 or
            list(abi.values()).count('clock-id') != 1 or list(abi.values()).count('clock-buffer') != 2):
        raise ValueError('linked CAPI ABI differs from original symbols')
    found = set()
    def inspect(value):
        if isinstance(value, dict):
            call = value.get('foreignCall')
            if isinstance(call, dict):
                target = call.get('target')
                symbol = target.get('symbol') if isinstance(target, dict) else None
                if not isinstance(symbol, str) or symbol not in abi or \
                        capi_kind(call, link['unit'], symbol) != abi[symbol]:
                    raise ValueError('original CAPI call disagrees with linked symbol ABI')
                found.add(symbol)
            for item in value.values():
                inspect(item)
        elif isinstance(value, list):
            for item in value:
                inspect(item)
    inspect(module.get('bindings', []))
    if found != set(symbols):
        raise ValueError('linked foreign symbols differ from original Core')
    return True


def managed_registration(module):
    """Validate retained-root evidence, never native callback callability."""
    if 'staticForeignExportRegistration' not in module:
        return None
    def require(condition, detail):
        if not condition:
            raise ValueError('Invalid managed foreign registration: ' + detail)
    def record(value, keys):
        require(isinstance(value, dict) and set(value) == set(keys.split()), 'record fields')
        return value
    def text(value):
        require(isinstance(value, str) and value and '\0' not in value, 'text')
        return value
    def identity(value):
        record(value, 'unit module occurrence namespace')
        for item in value.values(): text(item)
        return value
    def typ(value):
        require(isinstance(value, dict), 'type')
        kind = value.get('kind')
        if kind == 'tycon':
            record(value, 'kind name arguments'); identity(value['name'])
            require(isinstance(value['arguments'], list), 'type arguments')
            for item in value['arguments']: typ(item)
        elif kind in ('application', 'function'):
            fields = 'function argument' if kind == 'application' else 'multiplicity argument result'
            record(value, 'kind ' + fields)
            for key in fields.split(): typ(value[key])
        else: require(False, 'type constructor')
        return value
    def nominal(value, name, namespace, arity=0):
        return (value['kind'] == 'tycon' and value['name'] == dict(unit='ghc-internal',
                module='GHC.Internal.Types', occurrence=name, namespace=namespace) and
                len(value['arguments']) == arity)
    def exact(value, wanted):
        if type(value) is not type(wanted): return False
        if isinstance(wanted, dict):
            return value.keys() == wanted.keys() and all(exact(value[key], item) for key, item in wanted.items())
        if isinstance(wanted, list):
            return len(value) == len(wanted) and all(exact(a, b) for a, b in zip(value, wanted))
        return value == wanted
    require(type(module.get('schema')) is int and module['schema'] == 2 and 'foreignLink' not in module,
            'original unlinked schema 2 required')
    unit, name = text(module.get('unit')), text(module.get('module'))
    inventory = record(module.get('staticForeignExports'), 'schema producer scope execution unit module exports')
    require(type(inventory['schema']) is int and inventory['schema'] == 1 and
            inventory['producer'] == 'THC.Plugin/typeCheckResultAction' and
            inventory['scope'] == 'static-export-associations' and inventory['execution'] == 'not-linked' and
            inventory['unit'] == unit and inventory['module'] == name, 'inventory owner/schema')
    proof = record(module['staticForeignExportRegistration'],
                   'schema scope execution profile status roots wordBits expectedForeign expectedExports')
    require(type(proof['schema']) is int and proof['schema'] == 2 and proof['scope'] == 'retained-foreign-products' and
            proof['execution'] == 'not-linked' and proof['status'] == 'verified' and
            proof['profile'] in ('ghc-9.14.1-thc-only-native-static-ccall-v1',
                                 'ghc-9.14.1-thc-only-native-static-ccall-imports-v2') and
            type(proof['wordBits']) is int and proof['wordBits'] == 64, 'verified producer profile')
    require(exact(proof['expectedForeign'], module.get('foreign')) and exact(proof['expectedExports'], inventory),
            'whole archived product/inventory changed')
    foreign = record(module.get('foreign'), 'schema execution stubs files')
    validate_archive_only_foreign(module)
    stubs = foreign['stubs']
    require(type(foreign['schema']) is int and foreign['schema'] == 1 and foreign['execution'] == 'not-linked' and
            foreign['files'] == [] and stubs is not None and stubs['finalizers'] == [], 'foreign products')
    initializers = stubs['initializers']
    require(len(initializers) == 1 and initializers[0]['unit'] == unit and initializers[0]['module'] == name,
            'initializer owner')
    declarations = inventory['exports']
    require(isinstance(declarations, list) and declarations, 'export inventory')
    roots, ids, symbols = [], [], set()
    for declaration in declarations:
        record(declaration, 'binder symbol convention declaredType normalizedType normalizationRole arguments result effect')
        binder = identity(declaration['binder'])
        require(binder['unit'] == unit and binder['module'] == name and binder['namespace'] == 'value', 'binder owner')
        key = f"{unit}:{name}.{binder['occurrence']}"
        require(sum(binding.get('id') == key for binding in module.get('bindings', [])) == 1, 'actual binder')
        symbol = text(declaration['symbol'])
        require(symbol not in symbols, 'duplicate symbol'); symbols.add(symbol)
        require(declaration['convention'] == 'ccall' and declaration['normalizationRole'] == 'representational', 'convention')
        typ(declaration['declaredType']); remaining = typ(declaration['normalizedType'])
        arguments = []
        while remaining['kind'] == 'function':
            require(nominal(remaining['multiplicity'], 'Many', 'data'), 'multiplicity')
            arguments.append(remaining['argument']); remaining = remaining['result']
        require(declaration['effect'] in ('io', 'pure'), 'effect')
        if declaration['effect'] == 'io':
            require(nominal(remaining, 'IO', 'type', 1), 'IO result')
            remaining = remaining['arguments'][0]
        else: require(not nominal(remaining, 'IO', 'type', 1), 'pure result')
        require(arguments == declaration['arguments'] and remaining == declaration['result'], 'signature projections')
        roots.append(binder); ids.append(key)
    require(proof['roots'] == roots, 'ordered registration roots')
    return ids


def managed_import_stubs(module):
    """Admit inert stock import products, never an arbitrary native call."""
    if 'staticForeignImportStubs' not in module:
        return False
    def require(ok, detail):
        if not ok: raise ValueError('Invalid managed static-import provenance: ' + detail)
    def record(value, fields):
        require(isinstance(value, dict) and set(value) == set(fields.split()), 'record fields')
        return value
    def text(value):
        require(isinstance(value, str) and bool(value) and '\0' not in value, 'text')
        return value
    def nullable(value):
        if value is not None: text(value)
    def identity(value):
        record(value, 'unit module occurrence namespace')
        for item in value.values(): text(item)
        require(value['namespace'] in ('value', 'type', 'data'), 'name namespace')
        return value
    def typ(value):
        require(isinstance(value, dict), 'type')
        kind = value.get('kind')
        if kind == 'tycon':
            record(value, 'kind name arguments'); identity(value['name'])
            require(isinstance(value['arguments'], list), 'type arguments')
            for item in value['arguments']: typ(item)
        elif kind in ('application', 'function'):
            fields = 'function argument' if kind == 'application' else 'multiplicity argument result'
            record(value, 'kind ' + fields)
            for key in fields.split(): typ(value[key])
        else: require(False, 'unknown type')
    def exact(value, expected):
        if type(value) is not type(expected): return False
        if isinstance(value, dict):
            return value.keys() == expected.keys() and all(exact(value[k], v) for k, v in expected.items())
        if isinstance(value, list): return len(value) == len(expected) and all(map(lambda p: exact(*p), zip(value, expected)))
        return value == expected
    def calls(value):
        if isinstance(value, dict):
            return ([value['foreignCall']] if 'foreignCall' in value else []) + sum((calls(v) for v in value.values()), [])
        if isinstance(value, list): return sum((calls(v) for v in value), [])
        return []
    require(type(module.get('schema')) is int and module['schema'] == 2 and 'foreignLink' not in module, 'archive schema/link')
    raw = module['staticForeignImportStubs']
    require(isinstance(raw, dict), 'proof record')
    verified = raw.get('status') == 'verified'
    proof = record(raw, 'schema scope execution profile unit module status ' +
                   ('wordBits expectedForeign imports expectedCalls' if verified else 'reason'))
    require(type(proof['schema']) is int and proof['schema'] == 1 and
            proof['scope'] == 'retained-static-import-products' and proof['execution'] == 'not-linked' and
            proof['profile'] == 'ghc-9.14.1-thc-only-static-c-imports-v1' and module.get('ghc') == '9.14.1' and
            proof['unit'] == module.get('unit') and proof['module'] == module.get('module'), 'schema/profile/owner')
    text(proof['unit']); text(proof['module'])
    if not verified:
        require(proof['status'] in ('unclassified', 'rejected'), 'status'); text(proof['reason'])
        return False
    require(type(proof['wordBits']) is int and proof['wordBits'] == 64, 'word width')
    require(exact(proof['expectedForeign'], module.get('foreign')), 'retained foreign product differs')
    foreign = record(module.get('foreign'), 'schema execution stubs files')
    require(type(foreign['schema']) is int and foreign['schema'] == 1 and foreign['execution'] == 'not-linked', 'foreign schema/execution')
    validate_archive_only_foreign(module)
    stubs = record(foreign['stubs'], 'header source initializers finalizers')
    require(stubs['header'] == '' and stubs['initializers'] == [] and stubs['finalizers'] == [] and foreign['files'] == [], 'unclassified native obligations')
    text(stubs['source'])
    imports = proof['imports']
    require(isinstance(imports, list) and imports, 'import inventory')
    binders, generated = set(), {}
    primitives = {'void', 'IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep',
                  'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep', 'AddrRep', 'FloatRep', 'DoubleRep'}
    def scalar(primitive, evaluated):
        return dict(kind={'void': 'void', 'AddrRep': 'address', 'FloatRep': 'float', 'DoubleRep': 'double'}.get(primitive, 'long'),
                    primReps=[] if primitive == 'void' else [primitive], evaluated=evaluated)
    for item in imports:
        record(item, 'binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted')
        binder = identity(item['binder']); key = tuple(binder[k] for k in ('unit', 'module', 'occurrence', 'namespace'))
        require(binder['unit'] == module['unit'] and binder['module'] == module['module'] and
                binder['namespace'] == 'value' and key not in binders, 'duplicate or foreign import binder'); binders.add(key)
        nullable(item['header']); nullable(item['unit']); text(item['symbol'])
        require(item['convention'] in ('ccall', 'capi') and item['safety'] in ('safe', 'unsafe', 'interruptible') and
                type(item['isFunction']) is bool and (item['isFunction'] or item['convention'] == 'capi') and
                item['normalizationRole'] == 'representational', 'static import declaration')
        typ(item['declaredType']); typ(item['normalizedType'])
        emitted = record(item['emitted'], 'symbol unit convention safety arguments result')
        text(emitted['symbol']); nullable(emitted['unit'])
        require(all(emitted[k] == item[k] for k in ('unit', 'convention', 'safety')), 'emitted call ownership/convention')
        args, result = emitted['arguments'], emitted['result']
        require(all(isinstance(v, list) and v and all(isinstance(p, str) and p in primitives for p in v) for v in (args, result)), 'scalar carriers')
        require(args[-1] == 'void' and 'void' not in args[:-1] and result[0] == 'void' and
                len(result) in (1, 2) and 'void' not in result[1:], 'State/result shape')
        if item['convention'] == 'ccall': require(emitted['symbol'] == item['symbol'], 'direct C symbol changed')
        else:
            key = (emitted['unit'], emitted['symbol']); require(key not in generated, 'duplicate generated CAPI target')
            generated[key] = dict(schema=1, target=dict(kind='static', symbol=emitted['symbol'], unit=emitted['unit'], isFunction=True),
                convention=emitted['convention'], safety=emitted['safety'], arity=len(args), suppliedArity=len(args),
                argumentReps=[scalar(p, False) for p in args], resultRep=dict(kind='unknown', primReps=[p for p in result if p != 'void'],
                aggregate='unboxed-tuple', components=[scalar(p, True) for p in result], evaluated=False))
    require(generated, 'no generated CAPI products')
    actual = calls(module.get('bindings'))
    require(isinstance(proof['expectedCalls'], list) and exact(proof['expectedCalls'], actual), 'Core foreign-call inventory differs')
    for call in actual:
        target = call.get('target') if isinstance(call, dict) else None
        if isinstance(target, dict):
            expected = generated.get((target.get('unit'), target.get('symbol')))
            if expected is not None: require(exact(call, expected), 'generated CAPI call ABI differs')
    return True


def foreign_execution_issue(module):
    """Explain a valid archive-only foreign marker without admitting it as Core."""
    if 'foreignLink' in module and linked_foreign(module):
        return None
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
        header = text(stubs['header'])
        source = text(stubs['source'])
        nonempty = bool(header or source)
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
            executable = (type(module.get('schema')) is int and module['schema'] == 1 and
                          'foreign' not in module)
            if (type(module.get('schema')) is int and module['schema'] == 2 and
                    'foreignLink' in module):
                validate_archive_only_foreign(module)
                executable = linked_foreign(module)
            if 'staticForeignImportStubs' in module:
                executable = managed_import_stubs(module) or executable
            if not executable:
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
