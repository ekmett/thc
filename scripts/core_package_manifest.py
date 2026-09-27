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


def native_archive_calls(value):
    if isinstance(value, dict):
        return ([value['foreignCall']] if 'foreignCall' in value else []) + sum((native_archive_calls(v) for v in value.values()), [])
    if isinstance(value, list): return sum((native_archive_calls(v) for v in value), [])
    return []


def native_entry_resolution(module):
    """Bind a partial link to complete LLVM adapter closures and a checked union."""
    def require(ok, reason):
        if not ok: raise ValueError('Invalid package native entry resolution: ' + reason)
    def record(value, fields):
        require(isinstance(value, dict) and value.keys() == set(fields.split()), 'record fields')
        return value
    def names(value):
        require(isinstance(value, list) and all(isinstance(s, str) and s and '\0' not in s for s in value), 'symbol list')
        require(value == sorted(set(value)), 'sorted unique dependency symbols')
        return value
    archive = module.get('packageNativeArchive')
    require(isinstance(archive, dict), 'missing original archive')
    original, selected = archive.get('artifact'), module.get('packageNativeLink')
    require(isinstance(original, dict) and isinstance(selected, dict), 'missing original/selected artifact')
    proof = record(archive.get('entryResolution'), 'schema profile inputBitcodeSha256 entries outputBitcodeSha256 unresolved')
    require(type(proof['schema']) is int and proof['schema'] == 1 and
            proof['profile'] == 'llvm-globaldce-adapter-closures-v1', 'profile')
    require(original.get('format') == selected.get('format') == 'llvm-bitcode' and
            original.get('bitcodeSha256') == proof['inputBitcodeSha256'] and
            selected.get('bitcodeSha256') == proof['outputBitcodeSha256'], 'content identity')
    require({k: v for k, v in original.items() if k not in ('bitcodeSha256', 'bitcodeHex')} ==
            {k: v for k, v in selected.items() if k not in ('bitcodeSha256', 'bitcodeHex', 'availableEntries')},
            'component ABI/recipe differs')
    unsupported = names(archive.get('unresolvedSymbols'))
    inputs = original.get('buildInputs')
    require(isinstance(inputs, dict), 'missing original build inputs')
    externals = names(inputs.get('unresolved'))
    require(unsupported and set(unsupported) <= set(externals), 'original unresolved inventory')
    require(isinstance(proof['entries'], list), 'entry closures')
    dependencies = []
    for value in proof['entries']:
        row = record(value, 'entry bitcodeSha256 unresolved')
        require(isinstance(row['bitcodeSha256'], str) and SHA256.fullmatch(row['bitcodeSha256']), 'closure digest')
        symbols = names(row['unresolved'])
        require(set(symbols) <= set(externals), 'unrecorded adapter dependency')
        dependencies.append((row['entry'], symbols))
    require([entry for entry, _ in dependencies] == [entry['entry'] for entry in original['abi']], 'complete ordered adapter coverage')
    available = [entry for entry, symbols in dependencies if not set(symbols) & set(unsupported)]
    require(available and len(available) < len(dependencies) and selected.get('availableEntries') == available,
            'exact available adapter selection')
    union = names(proof['unresolved'])
    require(not set(union) & set(unsupported) and set(union) <=
            {symbol for entry, symbols in dependencies if entry in available for symbol in symbols}, 'union dependencies')
    require(all(symbol in ('memcpy', 'memmove', 'memset', 'memcmp', 'bcmp', '__cxa_atexit', '__dso_handle') or
                symbol.startswith('llvm.') for symbol in union), 'selected bitcode requires a native provider container')
    return set(available)


def package_native_archive(module):
    """Validate original unsupported obligations without granting execution."""
    if 'packageNativeArchive' not in module: return None
    def require(ok, reason):
        if not ok: raise ValueError('Invalid package native archive: ' + reason)
    def record(value, fields):
        require(isinstance(value, dict) and set(value) == set(fields.split()), 'record fields')
        return value
    def text(value):
        require(isinstance(value, str) and value and '\0' not in value, 'text')
        return value
    def sequence(value):
        require(isinstance(value, list), 'list'); return value
    def identity(value):
        record(value, 'unit module occurrence namespace')
        for item in value.values(): text(item)
        require(value['namespace'] in ('value', 'type', 'data'), 'name namespace')
        return value
    def typ(value, depth=0):
        require(isinstance(value, dict), 'type record')
        kind = value.get('kind')
        if kind == 'tycon':
            record(value, 'kind name arguments'); identity(value['name'])
            for item in sequence(value['arguments']): typ(item, depth)
        elif kind in ('application', 'function'):
            fields = 'function argument' if kind == 'application' else 'multiplicity argument result'
            record(value, 'kind ' + fields)
            for key in fields.split(): typ(value[key], depth)
        elif kind == 'forall':
            record(value, 'kind binderKind body'); typ(value['binderKind'], depth); typ(value['body'], depth + 1)
        elif kind == 'bound-variable':
            record(value, 'kind index'); require(type(value['index']) is int and 0 <= value['index'] < depth, 'free type variable')
        else: require(False, 'unknown type')
    def proof_identity(proof):
        require(type(proof['schema']) is int and proof['schema'] == 1 and proof['scope'] == 'retained-static-import-products' and
            proof['execution'] == 'not-linked' and proof['profile'] == 'ghc-9.14.1-thc-only-static-c-imports-v1' and
            proof['unit'] == module['unit'] and proof['module'] == module['module'], 'typed provenance identity')
    raw_archive = module['packageNativeArchive']
    conflict_field = isinstance(raw_archive, dict) and 'conflictingImports' in raw_archive
    resolution = isinstance(raw_archive, dict) and 'entryResolution' in raw_archive
    archive = record(raw_archive,
        'schema profile execution unit module unsupportedImports unclassifiedReason unresolvedSymbols artifact' +
        (' conflictingImports' if conflict_field else '') + (' entryResolution' if resolution else ''))
    unit = text(module.get('unit'))
    require(type(archive['schema']) is int and archive['schema'] == 1 and archive['profile'] == 'thc-package-native-archive-v1' and
        archive['execution'] == 'not-linked' and archive['unit'] == unit and archive['module'] == module.get('module'), 'profile/owner')
    require('foreignLink' not in module and 'packageScalarLink' not in module, 'mixed foreign profiles')
    unknown, proof = archive['unclassifiedReason'], module.get('staticForeignImports')
    require(unknown in (None, 'non-static-c-import-declaration'), 'unclassified reason')
    scalar = ('IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Int32Rep', 'Word32Rep',
              'Int64Rep', 'Word64Rep', 'FloatRep', 'DoubleRep', 'AddrRep')
    def emitted_signature(value):
        emitted = record(value, 'symbol unit convention safety arguments result')
        require(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', text(emitted['symbol'])) and emitted['unit'] == unit and
            emitted['convention'] in ('ccall', 'capi') and emitted['safety'] in ('unsafe', 'safe', 'interruptible'), 'emitted identity')
        args, result = sequence(emitted['arguments']), sequence(emitted['result'])
        require(args and args[-1] == 'void' and all(rep in scalar + ('ByteArray#', 'MutableByteArray#') for rep in args[:-1]) and
            (result == ['void'] or len(result) == 2 and result[0] == 'void' and result[1] in scalar), 'emitted carriers')
        return emitted
    def c_abi(emitted):
        def argument(rep):
            if rep in ('ByteArray#', 'MutableByteArray#'): return 'AddrRep'
            if rep in ('IntRep', 'Int8Rep', 'Int16Rep', 'Int32Rep', 'Int64Rep'): return 'Word' + rep[3:]
            return rep
        return (emitted['convention'], 'unsafe' if emitted['safety'] == 'safe' else emitted['safety'],
                tuple(map(argument, emitted['arguments'])), tuple(emitted['result']))
    emitted_imports = []
    if unknown is not None:
        record(proof, 'schema scope execution profile unit module status reason'); proof_identity(proof)
        require(proof['status'] == 'unclassified' and proof['reason'] == unknown, 'unclassified provenance')
    elif proof is None:
        require('foreign' not in module and 'staticForeignImportStubs' not in module, 'missing import provenance')
    else:
        record(proof, 'schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls')
        proof_identity(proof)
        require(proof['status'] == 'verified' and type(proof['wordBits']) is int and proof['wordBits'] == 64, 'verified import profile')
        product = record(proof['expectedForeign'], 'schema execution stubs files')
        require(type(product['schema']) is int and product['schema'] == 1 and product['execution'] == 'not-linked' and product['files'] == [], 'foreign product')
        if product['stubs'] is not None:
            stubs = record(product['stubs'], 'header source initializers finalizers')
            require(stubs['header'] == '' and isinstance(stubs['source'], str) and stubs['initializers'] == [] and stubs['finalizers'] == [], 'foreign stub obligations')
            require('foreign' in module or stubs['source'] == '', 'missing retained stubs')
        require('foreign' not in module or module['foreign'] == product, 'retained product differs')
        require(proof['expectedCalls'] == native_archive_calls(module.get('bindings')), 'retained Core inventory differs')
        binders = []
        for entry in sequence(proof['imports']):
            record(entry, 'binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted')
            binder = identity(entry['binder'])
            require(binder['unit'] == unit and binder['module'] == module['module'] and binder['namespace'] == 'value' and binder not in binders, 'import binder')
            binders.append(binder); text(entry['symbol'])
            require(entry['header'] is None or isinstance(entry['header'], str) and text(entry['header']) and
                not any(char in entry['header'] for char in '\n\r"\\'), 'import header')
            require(entry['unit'] in (None, unit) and entry['convention'] in ('ccall', 'capi') and
                (entry['isFunction'] is True or entry['convention'] == 'capi' and entry['isFunction'] is False) and
                entry['safety'] in ('unsafe', 'safe', 'interruptible') and entry['normalizationRole'] == 'representational', 'import metadata')
            typ(entry['declaredType']); typ(entry['normalizedType'])
            emitted = emitted_signature(entry['emitted'])
            require(emitted['convention'] == entry['convention'] and emitted['safety'] == entry['safety'], 'emitted declaration')
            emitted_imports.append(emitted)
    require('staticForeignImportStubs' not in module or module['staticForeignImportStubs'] == proof, 'retained stub provenance differs')
    conflicts = [emitted_signature(value) for value in sequence(archive['conflictingImports'])] if conflict_field else []
    if conflict_field:
        require(conflicts and all(value not in conflicts[:index] for index, value in enumerate(conflicts)) and unknown is None,
                'conflicting import inventory')
    conflict_symbols = {value['symbol'] for value in conflicts}
    for symbol in conflict_symbols:
        variants = [value for value in conflicts if value['symbol'] == symbol]
        require(all(value['safety'] in ('unsafe', 'safe') for value in variants) and len({c_abi(value) for value in variants}) > 1,
                'imports do not have conflicting C ABIs')
        local = [value for value in emitted_imports if value['symbol'] == symbol and value['safety'] != 'interruptible']
        require(local and all(value in variants for value in local), 'conflict witnesses differ from local imports')
    expected = [entry for entry in emitted_imports if entry['safety'] == 'interruptible' or entry['symbol'] in conflict_symbols]
    require(sequence(archive['unsupportedImports']) == expected, 'unsupported import inventory differs')
    unresolved = [text(value) for value in sequence(archive['unresolvedSymbols'])]
    require(len(set(unresolved)) == len(unresolved), 'duplicate unresolved symbols')
    artifact = archive['artifact']
    require((artifact is None) == (not unresolved), 'unresolved artifact pair')
    require(unknown is not None or expected or unresolved, 'empty archive obligation')
    if artifact is not None:
        require(('packageNativeLink' not in module or resolution) and unknown is None, 'archive is also executable')
        package_scalar_link(dict(module, packageNativeLink=artifact), validate_archive=False)
    if resolution:
        native_entry_resolution(module)
        package_scalar_link(module, validate_archive=False)
    return archive


def native_archive_blocks(module, binding, archive):
    partial = 'entryResolution' in archive
    if archive['unclassifiedReason'] is not None or archive['unresolvedSymbols'] and not partial: return True
    if partial:
        available = native_entry_resolution(module)
        unavailable = [entry for entry in archive['artifact']['abi'] if entry['entry'] not in available]
        for call in native_archive_calls(binding):
            target = call.get('target', {}) if isinstance(call, dict) else {}
            if target.get('unit') != module['unit']: continue
            for entry in unavailable:
                expected = [['BoxedRep (Just Unlifted)' if rep in ('ByteArray#', 'MutableByteArray#') else rep]
                            for rep in entry['arguments']] + [[]]
                actual = call.get('argumentReps')
                if (target.get('symbol') == entry['symbol'] and call.get('convention') == entry['convention'] and
                        call.get('safety') == entry['safety'] and isinstance(actual, list) and
                        [rep.get('primReps') if isinstance(rep, dict) else None for rep in actual] == expected):
                    return True
    return any(isinstance(call, dict) and isinstance(call.get('target'), dict) and
        call['target'].get('unit') == module['unit'] and any(
            call['target'].get('symbol') == emitted['symbol'] and call.get('convention') == emitted['convention'] and
            call.get('safety') == emitted['safety'] for emitted in archive['unsupportedImports'])
        for call in native_archive_calls(binding))


def package_scalar_link(module, validate_archive=True):
    """Verify the original scalar profile or package-owned typed C/capi calls."""
    if validate_archive: package_native_archive(module)
    native = 'packageNativeLink' in module
    if not native and 'packageScalarLink' not in module:
        return None
    def require(value, detail):
        if not value:
            raise ValueError('Invalid package scalar link: ' + detail)
    def record(value, keys):
        require(isinstance(value, dict) and set(value) == set(keys.split()), 'record fields')
        return value
    def text(value):
        require(isinstance(value, str) and value and '\0' not in value, 'missing text')
        return value
    def identity(value):
        record(value, 'unit module occurrence namespace')
        for item in value.values(): text(item)
        require(value['namespace'] in ('value', 'type', 'data'), 'name namespace')
        return value
    def typ(value, depth=0):
        require(isinstance(value, dict), 'type record')
        if value.get('kind') == 'tycon':
            record(value, 'kind name arguments'); identity(value['name'])
            require(isinstance(value['arguments'], list), 'type arguments')
            for item in value['arguments']: typ(item, depth)
        elif value.get('kind') in ('application', 'function'):
            fields = 'function argument' if value['kind'] == 'application' else 'multiplicity argument result'
            record(value, 'kind ' + fields)
            for key in fields.split(): typ(value[key], depth)
        elif value.get('kind') == 'forall':
            record(value, 'kind binderKind body'); typ(value['binderKind'], depth); typ(value['body'], depth + 1)
        elif value.get('kind') == 'bound-variable':
            record(value, 'kind index')
            require(type(value['index']) is int and 0 <= value['index'] < depth, 'free import type variable')
        else: require(False, 'unknown type')
    def exact(value, expected):
        if type(value) is not type(expected): return False
        if isinstance(value, dict):
            return value.keys() == expected.keys() and all(exact(value[k], v) for k, v in expected.items())
        if isinstance(value, list):
            return len(value) == len(expected) and all(exact(a, b) for a, b in zip(value, expected))
        return value == expected
    def calls(value):
        if isinstance(value, dict):
            return ([value['foreignCall']] if 'foreignCall' in value else []) + sum((calls(v) for v in value.values()), [])
        if isinstance(value, list): return sum((calls(v) for v in value), [])
        return []
    require(not native or 'packageScalarLink' not in module, 'two package link profiles')
    require(type(module.get('schema')) is int and module['schema'] in ((1, 2) if native else (1,)) and module.get('ghc') == '9.14.1', 'GHC/schema')
    require((native or 'foreign' not in module and 'staticForeignImportStubs' not in module) and not any(key in module for key in ('foreignLink',
        'staticForeignExports', 'staticForeignExportRegistration')), 'mixed foreign obligations')
    raw_link = module['packageNativeLink' if native else 'packageScalarLink']
    inputs = native and isinstance(raw_link, dict) and 'buildInputs' in raw_link
    partial = native and isinstance(raw_link, dict) and 'availableEntries' in raw_link
    link = record(raw_link, 'schema format profile unit target componentSha256 bitcodeSha256 bitcodeHex abi' +
                  (' buildInputs' if inputs else '') + (' availableEntries' if partial else ''))
    if inputs: require(isinstance(link['buildInputs'], dict), 'build inputs record')
    require(type(link['schema']) is int and link['schema'] == 1 and (link['format'] == 'llvm-bitcode' or
            native and link['format'] == 'llvm-embedded-elf' and platform.system() == 'Linux') and
            link['profile'] == ('thc-package-c-ffi-v1' if native else 'thc-local-scalar-ccall-v1'), 'link profile')
    unit = text(link['unit'])
    require(unit == module.get('unit'), 'component owner')
    target = text(link['target'])
    cpu = {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(platform.machine().lower(), platform.machine().lower())
    target_cpu = {'arm64': 'aarch64'}.get(target.split('-')[0], target.split('-')[0])
    require(target_cpu == cpu and ((platform.system() == 'Linux' and target.endswith('-linux-gnu')) or
            (platform.system() == 'Darwin' and ('-darwin' in target or '-apple-macosx' in target))), 'target differs from audit host')
    require(SHA256.fullmatch(text(link['componentSha256'])) and SHA256.fullmatch(text(link['bitcodeSha256'])), 'digest')
    encoded = text(link['bitcodeHex'])
    try: data = bytes.fromhex(encoded)
    except ValueError as error: raise ValueError('Invalid package scalar bitcode encoding') from error
    require(data and data.hex() == encoded and hashlib.sha256(data).hexdigest() == link['bitcodeSha256'], 'bitcode digest')
    require(isinstance(link['abi'], list) and link['abi'], 'empty ABI')
    reps = ('Int32Rep', 'Int64Rep', 'FloatRep', 'DoubleRep')
    if native:
        reps += ('IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Word32Rep',
                 'Word64Rep', 'AddrRep', 'ByteArray#', 'MutableByteArray#')
    results = tuple(rep for rep in reps if rep not in ('ByteArray#', 'MutableByteArray#')) + (('void',) if native else ())
    abi = {}
    for index, entry in enumerate(link['abi']):
        record(entry, 'symbol entry convention safety arguments result' if native else 'symbol entry arguments result')
        name = text(entry['symbol'])
        require(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name), 'C symbol')
        require(entry['entry'] == ('thc_native_' if native else 'thc_scalar_') + link['componentSha256'] + '_' + str(index), 'component entry namespace')
        require(isinstance(entry['arguments'], list) and all(arg in reps for arg in entry['arguments']) and entry['result'] in results, 'C ABI')
        require(not native or entry['convention'] in ('ccall', 'capi') and entry['safety'] in ('unsafe', 'safe'),
                'unsupported C calling convention/safety')
        key = (name, entry.get('convention', 'ccall'), entry.get('safety', 'unsafe'), tuple(entry['arguments']), entry['result'])
        require(key not in abi, 'duplicate ABI signature')
        abi[key] = entry
    require(list(abi) == sorted(abi), 'sorted unique ABI')
    def pointer_abi(rep):
        return 'AddrRep' if rep in ('ByteArray#', 'MutableByteArray#') else rep
    def integer_abi(rep):
        return 'Word' + rep[3:] if rep in ('IntRep', 'Int8Rep', 'Int16Rep', 'Int32Rep', 'Int64Rep') else rep
    header_adapted = set()
    for name in {entry['symbol'] for entry in abi.values()}:
        variants = [entry for entry in abi.values() if entry['symbol'] == name]
        require(native or len(variants) == 1, 'duplicate scalar ABI symbol')
        def shape(entry, normalize, effective_safety=True):
            safety = entry.get('safety', 'unsafe')
            if effective_safety and safety == 'safe': safety = 'unsafe'
            return (entry.get('convention', 'ccall'), safety, tuple(normalize(rep) for rep in entry['arguments']), entry['result'])
        if len({shape(entry, pointer_abi) for entry in variants}) > 1:
            header_adapted.add(name)
        require(len({shape(entry, lambda rep: integer_abi(pointer_abi(rep)))
                     for entry in variants}) == 1, 'conflicting C ABI variants')
        require(len({shape(entry, lambda rep: 'ByteArray#' if rep == 'MutableByteArray#' else rep, False)
                     for entry in variants}) == len(variants), 'ambiguous byte-array mutability variants')
    available = native_entry_resolution(module) if partial else {entry['entry'] for entry in link['abi']}
    selected_link = dict(link, abi=[entry for entry in link['abi'] if entry['entry'] in available]) if partial else link
    if native and 'staticForeignImports' not in module:
        require('foreign' not in module and 'staticForeignImportStubs' not in module,
                'foreign products lack import provenance')
        return selected_link, set()
    proof = record(module.get('staticForeignImports'),
        'schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls')
    if native and 'staticForeignImportStubs' in module:
        require(exact(module['staticForeignImportStubs'], proof), 'retained CAPI import provenance differs')
    require(type(proof['schema']) is int and proof['schema'] == 1 and proof['scope'] == 'retained-static-import-products' and
        proof['execution'] == 'not-linked' and proof['profile'] == 'ghc-9.14.1-thc-only-static-c-imports-v1' and
        proof['unit'] == unit and proof['module'] == module.get('module') and proof['status'] == 'verified' and
        type(proof['wordBits']) is int and proof['wordBits'] == 64, 'typed import profile/owner')
    product = record(proof['expectedForeign'], 'schema execution stubs files')
    require(type(product['schema']) is int and product['schema'] == 1 and product['execution'] == 'not-linked' and product['files'] == [], 'foreign product')
    if native and 'foreign' in module:
        require(exact(product, module['foreign']), 'retained C stubs differ')
    if product['stubs'] is not None:
        stub = record(product['stubs'], 'header source initializers finalizers')
        require(stub['header'] == '' and (isinstance(stub['source'], str) if native else stub['source'] == '') and
                stub['initializers'] == [] and stub['finalizers'] == [], 'nonempty foreign products')
        require(not native or 'foreign' in module or stub['source'] == '', 'missing retained C stubs')
    require(isinstance(proof['imports'], list) and (native or proof['imports']), 'empty import inventory')
    binders, proved = [], set()
    for item in proof['imports']:
        record(item, 'binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted')
        if item['emitted'] in module.get('packageNativeArchive', {}).get('unsupportedImports', []): continue
        binder = identity(item['binder'])
        require(binder['unit'] == unit and binder['module'] == module.get('module') and binder['namespace'] == 'value' and binder not in binders, 'import binder')
        binders.append(binder)
        convention = item['convention']
        require((item['header'] is None or native and isinstance(item['header'], str) and
            item['header'] and '\0' not in item['header']) and
            item['unit'] in (None, unit) and (item['isFunction'] is True or native and convention == 'capi' and item['isFunction'] is False) and
            convention in (('ccall', 'capi') if native else ('ccall',)) and item['safety'] in (('unsafe', 'safe') if native else ('unsafe',)) and
            item['normalizationRole'] == 'representational', 'static supported C import')
        typ(item['declaredType']); typ(item['normalizedType'])
        text(item['symbol'])
        emitted = record(item['emitted'], 'symbol unit convention safety arguments result')
        name = text(emitted['symbol'] if native else item['symbol'])
        if name in header_adapted:
            require(convention == 'ccall' and isinstance(item['header'], str) and item['header'] and
                    not any(char in item['header'] for char in '\0\n\r"\\'),
                    'signedness variants require a retained configured C header')
        variants = [entry for entry in abi.values() if entry['symbol'] == name and
            convention == entry.get('convention', 'ccall') and item['safety'] == entry.get('safety', 'unsafe') and exact(emitted,
                dict(symbol=name, unit=unit, convention=convention, safety=entry.get('safety', 'unsafe'), arguments=entry['arguments'] + ['void'],
                     result=['void'] if entry['result'] == 'void' else ['void', entry['result']]))]
        require(len(variants) == 1, 'emitted ABI differs from compiled C')
        proved.add(variants[0]['entry'])
    require(exact(proof['expectedCalls'], calls(module.get('bindings'))), 'retained Core foreign inventory differs')
    return selected_link, proved & available


def foreign_execution_issue(module):
    """Explain a valid archive-only foreign marker without admitting it as Core."""
    if 'packageNativeArchive' in module:
        return f"Unsupported foreign execution for {module.get('unit')}:{module.get('module')}: archive-only package native obligations"
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


def select_package_scalar_call(call, entries, unit, arguments, flags, output):
    """Select one proved semantic adapter, never just the shared C symbol."""
    matched = []
    target = call.get('target') if isinstance(call, dict) else None
    symbol = target.get('symbol') if isinstance(target, dict) else None
    for entry in entries:
        if entry['symbol'] != symbol:
            continue
        try:
            validate_package_scalar_call(call, entry, unit, arguments, flags, output)
        except ValueError:
            continue
        matched.append(entry)
    if len(matched) != 1:
        raise ValueError('Package C call lacks its unique exact scalar/State ABI')
    return matched[0]


def validate_package_scalar_call(call, abi, unit, arguments, flags, output):
    """The declared and actual unlifted shapes must agree before a foreign effect."""
    def fail(): raise ValueError('Package C call lacks its exact scalar/State ABI')
    def scalar(value, rep, declared=False):
        kind = ('void' if rep is None else 'float' if rep == 'FloatRep' else 'double' if rep == 'DoubleRep' else
                'address' if rep == 'AddrRep' else 'object' if rep in ('ByteArray#', 'MutableByteArray#') else 'long')
        prim_reps = [] if rep is None else ['BoxedRep (Just Unlifted)'] if rep in ('ByteArray#', 'MutableByteArray#') else [rep]
        return (isinstance(value, dict) and set(value) == {'kind', 'primReps', 'evaluated'} and
            value['kind'] == kind and value['primReps'] == prim_reps and type(value['evaluated']) is bool and
            (not declared or value['evaluated'] is False))
    def result(value, declared=False):
        if not isinstance(value, dict) or set(value) != {'kind', 'primReps', 'evaluated', 'aggregate', 'components'}: return False
        parts = value['components']
        reps = [None] if abi['result'] == 'void' else [None, abi['result']]
        return (value['kind'] == 'unknown' and value['aggregate'] == 'unboxed-tuple' and value['primReps'] == [rep for rep in reps if rep is not None] and
            type(value['evaluated']) is bool and (not declared or value['evaluated'] is False) and
            isinstance(parts, list) and len(parts) == len(reps) and
            all(scalar(part, rep) and part['evaluated'] is True for part, rep in zip(parts, reps)))
    wanted = abi['arguments'] + [None]
    if (not isinstance(call, dict) or set(call) != {'schema', 'target', 'convention', 'safety', 'arity', 'suppliedArity', 'argumentReps', 'resultRep'} or
        type(call['schema']) is not int or call['schema'] != 1 or
        call['target'] != dict(kind='static', symbol=abi['symbol'], unit=unit, isFunction=True) or
        call['target'].get('isFunction') is not True or call['convention'] != abi.get('convention', 'ccall') or call['safety'] != abi.get('safety', 'unsafe') or
        type(call['arity']) is not int or call['arity'] != len(wanted) or
        type(call['suppliedArity']) is not int or call['suppliedArity'] != len(wanted) or
        not isinstance(call['argumentReps'], list) or len(call['argumentReps']) != len(wanted) or
        len(arguments) != len(wanted) or not isinstance(flags, list) or len(flags) != len(wanted) or any(v is not False for v in flags) or
        not all(scalar(declared, rep, True) and scalar(actual, rep) for declared, actual, rep in zip(call['argumentReps'], arguments, wanted)) or
        not result(call['resultRep'], True) or not result(output)):
        fail()


def validate_archive_only_foreign(module):
    """Mirror the JVM's schema-2 archive shape; this never registers code."""
    if 'packageNativeArchive' in module:
        package_native_archive(module)
        if module.get('schema') == 1 and 'foreign' not in module: return
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
    manifest = strict_json(path.read_text(encoding='utf-8'))
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
            if 'packageScalarLink' in module or 'packageNativeLink' in module:
                executable = bool(package_scalar_link(module)) or executable
            elif 'staticForeignImportStubs' in module:
                executable = managed_import_stubs(module) or executable
            if 'packageNativeArchive' in module:
                package_native_archive(module)
                executable = False
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
