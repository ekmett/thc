#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Load an exact, content-addressed collection of independently built GHC units."""

import hashlib
import json
from contextlib import closing
from functools import lru_cache
import os
from pathlib import Path
import platform
import re
import stat
import subprocess
from tempfile import TemporaryDirectory
import zlib
import core_original_foreign
from zipfile import BadZipFile, ZipFile


FORMAT = 'thc-core-packages'
BOUNDARY = 'optimized-Core-after-Tidy-before-CorePrep'
SHA256 = re.compile(r'[0-9a-f]{64}\Z')
IMPORT_PROFILES = ('ghc-9.14.1-thc-only-static-c-imports-v1',
                   'ghc-9.14.1-thc-stock-static-foreign-imports-v2')


@lru_cache(maxsize=1)
def _compact_executable():
    root = Path(__file__).resolve().parent.parent
    executable = os.environ.get('THC_COMPACT')
    if not executable:
        result = subprocess.run([os.environ.get('CABAL', 'cabal'), 'list-bin', 'exe:thc-compact', '--offline',
                                 '--with-compiler=' + os.environ.get('GHC', 'ghc'),
                                 '--with-hc-pkg=' + os.environ.get('GHC_PKG', 'ghc-pkg')],
                                cwd=root, capture_output=True, text=True, encoding='utf-8', timeout=60)
        if result.returncode:
            raise ValueError('Cannot locate CBD inspector: ' + result.stderr.strip())
        executable = result.stdout.strip()
    executable = Path(executable).resolve()
    if not executable.is_file():
        raise ValueError('Build exe:thc-compact first or set THC_COMPACT to its executable: ' + str(executable))
    return str(executable)


def inspect_cbd(data, *, sources=False):
    """Explicit offline executable or source inspection; JSON is output only."""
    if not data.startswith(b'PK\x03\x04'):
        raise ValueError('Core input must be CBD; JSON Core input is not supported')
    with TemporaryDirectory(prefix='thc-cbd-audit-') as temporary:
        source, output = Path(temporary) / 'module.cbd', Path(temporary) / 'inspection.json'
        source.write_bytes(data)
        try:
            command = ([_compact_executable(), 'sources', str(source)] if sources else
                       [_compact_executable(), 'decode', str(source), str(output)])
            result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=60)
        except subprocess.TimeoutExpired as error:
            raise ValueError('CBD inspection timed out') from error
        if result.returncode:
            raise ValueError('CBD inspection failed: ' + result.stderr.strip())
        return strict_json(result.stdout if sources else output.read_text(encoding='utf-8'))


def time_clock_symbols(unit):
    owner = unit.replace('-', 'zm').replace('.', 'zi')
    return {f'ghczuwrapperZC{index}ZC{owner}ZCDataziTimeziClockziInternalziCTimespecZC{name}': kind
            for index, name, kind in ((0, 'HSzuCLOCKzuREALTIME', 'time-clock-id'),
                                     (1, 'clockzugetres', 'time-clock-resolution'),
                                     (2, 'clockzugettime', 'time-clock-time'))}


def capi_kind(call, unit, symbol, time=False):
    def scalar(primitive, evaluated):
        return dict(kind='void' if primitive is None else 'address' if primitive == 'AddrRep' else 'long',
                    primReps=[] if primitive is None else [primitive], evaluated=evaluated)

    def expected(kind):
        zero = kind in ('clock-id', 'time-clock-id')
        word = 'Int32Rep' if time else 'Word64Rep'
        output = word if zero else 'Int32Rep'
        return dict(schema=1, target=dict(kind='static', symbol=symbol, unit=unit, isFunction=True),
                    convention='capi', safety='unsafe', arity=1 if zero else 3,
                    suppliedArity=1 if zero else 3,
                    argumentReps=([scalar(None, False)] if zero else
                                  [scalar(word, False), scalar('AddrRep', False), scalar(None, False)]),
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

    kinds = (time_clock_symbols(unit).get(symbol),) if time else ('clock-id', 'clock-buffer')
    return next((kind for kind in kinds if kind is not None and exact(call, expected(kind))), None)


def linked_foreign(module):
    """Verify an exact fully linked, callback-free clock CAPI archive."""
    link = module.get('foreignLink')
    if link is None:
        return False
    time = isinstance(link, dict) and link.get('module') == 'Data.Time.Clock.Internal.CTimespec'
    if (not isinstance(link, dict) or set(link) != ({'schema', 'format', 'unit', 'module',
            'target', 'symbols', 'abi', 'sourceSha256', 'bitcodeSha256', 'bitcodeHex'} |
            ({'headerHashes'} if time else set())) or
            type(link['schema']) is not int or link['schema'] != (3 if time else 2) or
            link['format'] != 'llvm-bitcode' or link['unit'] != module.get('unit') or
            link['module'] != module.get('module') or
            not isinstance(link['unit'], str) or not (
                re.fullmatch(r'time-1\.15-(?:inplace|[0-9a-f]+)', link['unit']) and platform.system() == 'Linux'
                if time else link['module'] == 'System.CPUTime.Posix.ClockGetTime' and link['unit'].startswith('base-'))):
        raise ValueError('invalid linked foreign owner/schema')
    if time:
        headers = link['headerHashes']
        if (not isinstance(headers, list) or len(headers) != 3 or
                any(not isinstance(header, dict) or set(header) != {'name', 'sha256'} or
                    not isinstance(header['sha256'], str) or not SHA256.fullmatch(header['sha256']) for header in headers) or
                [header['name'] for header in headers] != ['HsFFI.h', 'HsTime.h', 'HsTimeConfig.h']):
            raise ValueError('invalid selected time header provenance')
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
                entry['kind'] not in (('time-clock-id', 'time-clock-resolution', 'time-clock-time') if time
                                      else ('clock-id', 'clock-buffer')) for entry in entries)):
        raise ValueError('invalid linked CAPI ABI inventory')
    abi = {entry['symbol']: entry['kind'] for entry in entries}
    if (set(abi) != set(symbols) or len(abi) != 3 or
            (abi != time_clock_symbols(link['unit']) if time else
             list(abi.values()).count('clock-id') != 1 or list(abi.values()).count('clock-buffer') != 2)):
        raise ValueError('linked CAPI ABI differs from original symbols')
    found = set()
    def inspect(value):
        if isinstance(value, dict):
            call = value.get('foreignCall')
            if isinstance(call, dict):
                target = call.get('target')
                symbol = target.get('symbol') if isinstance(target, dict) else None
                if not time or isinstance(target, dict) and target.get('unit') == link['unit']:
                    if not isinstance(symbol, str) or symbol not in abi or \
                            capi_kind(call, link['unit'], symbol, time) != abi[symbol]:
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
                                 'ghc-9.14.1-thc-only-native-static-ccall-imports-v2',
                                 'ghc-9.14.1-thc-only-native-static-c-products-v3') and
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


def native_import_product(module, proof):
    """A mixed producer retains its original archive and a stock import partition."""
    if proof.get('schema') != 4: return proof['expectedForeign']
    if not managed_registration(module) or proof['expectedForeign'] != module.get('foreign'):
        raise ValueError('Mixed imports require exact static export registration')
    product = proof.get('importForeign')
    if (not isinstance(product, dict) or set(product) != {'schema', 'execution', 'stubs', 'files'} or
            type(product['schema']) is not int or product['schema'] != 1 or
            product['execution'] != 'not-linked' or product['files'] != []):
        raise ValueError('Invalid mixed import product')
    stubs = product['stubs']
    if stubs is not None and (not isinstance(stubs, dict) or
            set(stubs) != {'header', 'source', 'initializers', 'finalizers'} or
            not isinstance(stubs['header'], str) or not isinstance(stubs['source'], str) or
            stubs['initializers'] != [] or stubs['finalizers'] != []):
        raise ValueError('Mixed import lifecycle obligations')
    return product


def package_address_declarations(module, proof, typ, identity):
    """Stock typed labels alone are inert; only linked callback entries execute."""
    if proof.get('schema') not in (2, 3, 4): return set()
    def require(valid, detail):
        if not valid: raise ValueError('Invalid package address provenance: ' + detail)
    def named(value, module_name, name, count):
        return isinstance(value, dict) and value.get('kind') == 'tycon' and value.get('name') == dict(
            unit='ghc-internal', module=module_name, occurrence=name, namespace='type') and len(value['arguments']) == count
    def finalizer_type(value):
        while value.get('kind') == 'forall': value = value['body']
        if not named(value, 'GHC.Internal.Ptr', 'FunPtr', 1): return False
        function = value['arguments'][0]
        if function.get('kind') != 'function' or not named(function['argument'], 'GHC.Internal.Ptr', 'Ptr', 1): return False
        result = function['result']
        return named(result, 'GHC.Internal.Types', 'IO', 1) and named(result['arguments'][0], 'GHC.Internal.Tuple', 'Unit', 0)
    entries = proof.get('addresses')
    require(proof.get('status') == 'verified' and isinstance(entries, list) and (entries or proof.get('schema') in (3, 4)), 'inventory')
    binders, symbols = [], set()
    for item in entries:
        require(isinstance(item, dict) and set(item) == set(
            'binder header symbol isFunction convention declaredType normalizedType normalizationRole callback'.split()), 'fields')
        binder = identity(item['binder'])
        require(binder['unit'] == module.get('unit') and binder['module'] == module.get('module') and
            binder['namespace'] == 'value' and binder not in binders, 'binder identity')
        binders.append(binder)
        require(isinstance(item['symbol'], str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', item['symbol']) and
            type(item['isFunction']) is bool and item['convention'] in ('ccall', 'capi') and
            item['normalizationRole'] == 'representational', 'declaration')
        require(item['header'] is None or isinstance(item['header'], str) and item['header'] and
            not any(c in item['header'] for c in '\0\n\r"\\'), 'header')
        typ(item['declaredType']); typ(item['normalizedType'])
        if item['callback'] is not None:
            require(item['isFunction'] and item['callback'] == dict(arguments=['AddrRep'], result='void') and
                finalizer_type(item['normalizedType']), 'callback ABI differs from normalized type')
            symbols.add(item['symbol'])
    return symbols


def native_callback_declarations(module, proof, typ, identity):
    """The callback ABI and helper belong to the original wrapper declaration."""
    if proof.get('schema') not in (3, 4): return set()
    def require(valid, detail):
        if not valid: raise ValueError('Invalid native wrapper declaration: ' + detail)
    def argument(value, module_name, occurrence):
        require(isinstance(value, dict) and value.get('kind') == 'tycon' and value.get('name') == dict(
            unit='ghc-internal', module=module_name, occurrence=occurrence, namespace='type') and
            isinstance(value.get('arguments'), list) and len(value['arguments']) == 1, 'nominal ' + occurrence)
        return value['arguments'][0]
    entries = proof.get('wrappers')
    require(isinstance(entries, list) and (entries or proof.get('schema') == 4), 'missing wrappers')
    names, binders = set(), []
    for item in entries:
        require(isinstance(item, dict) and set(item) == set(
            'binder helper convention declaredType normalizedType normalizationRole arguments result effect typeString'.split()), 'fields')
        binder = identity(item['binder'])
        require(binder['unit'] == module['unit'] and binder['module'] == module['module'] and
            binder['namespace'] == 'value' and binder not in binders, 'binder')
        binders.append(binder)
        helper = item['helper']
        require(isinstance(helper, str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', helper) and helper not in names and
            item['convention'] == 'ccall' and item['normalizationRole'] == 'representational' and
            isinstance(item['typeString'], str) and item['typeString'] and '\0' not in item['typeString'], 'helper/convention/encoding')
        names.add(helper)
        typ(item['declaredType']); typ(item['normalizedType'])
        wrapper = item['normalizedType']
        require(wrapper['kind'] == 'function', 'wrapper function')
        callback = wrapper['argument']
        require(argument(argument(wrapper['result'], 'GHC.Internal.Types', 'IO'), 'GHC.Internal.Ptr', 'FunPtr') == callback,
            'wrapper result callback type')
        arguments, remaining = [], callback
        while remaining['kind'] == 'function':
            arguments.append(remaining['argument']); remaining = remaining['result']
        require(item['effect'] in ('io', 'pure'), 'effect')
        if item['effect'] == 'io': remaining = argument(remaining, 'GHC.Internal.Types', 'IO')
        require(arguments == item['arguments'] and remaining == item['result'], 'callback ABI projections')
    return names


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
    def typ(value, depth=0):
        require(isinstance(value, dict), 'type')
        kind = value.get('kind')
        if kind == 'tycon':
            record(value, 'kind name arguments'); identity(value['name'])
            require(isinstance(value['arguments'], list), 'type arguments')
            for item in value['arguments']: typ(item, depth)
        elif kind in ('application', 'function'):
            fields = 'function argument' if kind == 'application' else 'multiplicity argument result'
            record(value, 'kind ' + fields)
            for key in fields.split(): typ(value[key], depth)
        elif kind == 'forall':
            record(value, 'kind binderKind body'); typ(value['binderKind'], depth); typ(value['body'], depth + 1)
        elif kind == 'bound-variable':
            record(value, 'kind index')
            require(type(value['index']) is int and 0 <= value['index'] < depth, 'free import type variable')
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
                   ('wordBits expectedForeign imports expectedCalls' + (' addresses' if raw.get('schema') in (2, 3, 4) else '') +
                    (' wrappers' if raw.get('schema') in (3, 4) else '') +
                    (' importForeign' if raw.get('schema') == 4 else '') if verified else 'reason'))
    require(type(proof['schema']) is int and proof['schema'] in (1, 2, 3, 4) and
            proof['scope'] == 'retained-static-import-products' and proof['execution'] == 'not-linked' and
            proof['profile'] == 'ghc-9.14.1-thc-only-static-c-imports-v1' and module.get('ghc') == '9.14.1' and
            proof['unit'] == module.get('unit') and proof['module'] == module.get('module'), 'schema/profile/owner')
    text(proof['unit']); text(proof['module'])
    if not verified:
        require(proof['status'] in ('unclassified', 'rejected'), 'status'); text(proof['reason'])
        return False
    require(type(proof['wordBits']) is int and proof['wordBits'] == 64, 'word width')
    package_address_declarations(module, proof, typ, identity)
    callbacks = native_callback_declarations(module, proof, typ, identity)
    require(exact(proof['expectedForeign'], module.get('foreign')), 'retained foreign product differs')
    foreign = record(native_import_product(module, proof), 'schema execution stubs files')
    require(type(foreign['schema']) is int and foreign['schema'] == 1 and foreign['execution'] == 'not-linked', 'foreign schema/execution')
    validate_archive_only_foreign(module)
    stubs = record(foreign['stubs'], 'header source initializers finalizers')
    require((stubs['header'] == '' or callbacks and isinstance(stubs['header'], str)) and
        stubs['initializers'] == [] and stubs['finalizers'] == [] and foreign['files'] == [], 'unclassified native obligations')
    text(stubs['source'])
    imports = proof['imports']
    require(isinstance(imports, list) and (imports or callbacks), 'import inventory')
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
    if callbacks:
        args, result = ['AddrRep', 'AddrRep', 'AddrRep', 'void'], ['void', 'AddrRep']
        generated[None, 'createAdjustor'] = dict(schema=1,
            target=dict(kind='static', symbol='createAdjustor', unit=None, isFunction=True), convention='ccall', safety='unsafe',
            arity=4, suppliedArity=4, argumentReps=[scalar(p, False) for p in args],
            resultRep=dict(kind='unknown', primReps=['AddrRep'], aggregate='unboxed-tuple',
                components=[scalar(p, True) for p in result], evaluated=False))
    require(generated, 'no generated CAPI or callback products')
    actual = calls(module.get('bindings'))
    require(isinstance(proof['expectedCalls'], list) and exact(proof['expectedCalls'], actual), 'Core foreign-call inventory differs')
    for call in actual:
        target = call.get('target') if isinstance(call, dict) else None
        if isinstance(target, dict):
            expected = generated.get((target.get('unit'), target.get('symbol')))
            if expected is not None: require(exact(call, expected), 'generated CAPI call ABI differs')
    return True


def native_archive_calls(value):
    calls, pending = [], [value]
    while pending:
        current = pending.pop()
        if isinstance(current, dict):
            if 'foreignCall' in current: calls.append(current['foreignCall'])
            pending.extend(reversed(current.values()))
        elif isinstance(current, list):
            pending.extend(reversed(current))
    return calls




def package_native_archive(module):
    """Validate original unsupported obligations without granting execution."""
    if 'packageNativeArchive' not in module:
        if module.get('staticForeignImports', {}).get('profile') == IMPORT_PROFILES[1]:
            raise ValueError('Primitive import provenance requires its package native archive')
        return None
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
        require(type(proof['schema']) is int and proof['schema'] in (1, 2, 3, 4) and proof['scope'] == 'retained-static-import-products' and
            proof['execution'] == 'not-linked' and proof['profile'] in IMPORT_PROFILES and
            proof['unit'] == module['unit'] and proof['module'] == module['module'], 'typed provenance identity')
    raw_archive = module['packageNativeArchive']
    conflict_field = isinstance(raw_archive, dict) and 'conflictingImports' in raw_archive
    archive = record(raw_archive,
        'schema profile execution unit module unsupportedImports unclassifiedReason unresolvedSymbols artifact' +
        (' conflictingImports' if conflict_field else ''))
    unit = text(module.get('unit'))
    require(type(archive['schema']) is int and archive['schema'] == 1 and archive['profile'] == 'thc-package-native-archive-v1' and
        archive['execution'] == 'not-linked' and archive['unit'] == unit and archive['module'] == module.get('module'), 'profile/owner')
    require('foreignLink' not in module and 'packageScalarLink' not in module, 'mixed foreign profiles')
    unknown, proof = archive['unclassifiedReason'], module.get('staticForeignImports')
    require(unknown in (None, 'non-static-c-import-declaration'), 'unclassified reason')
    scalar = ('IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Int32Rep', 'Word32Rep',
              'Int64Rep', 'Word64Rep', 'FloatRep', 'DoubleRep', 'AddrRep')
    gc_boxed = ('BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)')
    def emitted_signature(value):
        emitted = record(value, 'symbol unit convention safety arguments result')
        require(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', text(emitted['symbol'])) and emitted['unit'] == unit and
            emitted['convention'] in ('ccall', 'capi', 'prim') and emitted['safety'] in ('unsafe', 'safe', 'interruptible'), 'emitted identity')
        args, result = sequence(emitted['arguments']), sequence(emitted['result'])
        if emitted['convention'] == 'prim':
            require(proof['profile'] == IMPORT_PROFILES[1] and emitted['safety'] == 'safe' and
                all(rep in scalar + gc_boxed + ('void',) for rep in args + result), 'primitive carriers/profile')
            return emitted
        require(args and args[-1] == 'void' and all(rep in scalar + ('ByteArray#', 'MutableByteArray#') + gc_boxed for rep in args[:-1]) and
            (result == ['void'] or len(result) == 2 and result[0] == 'void' and result[1] in scalar + gc_boxed), 'emitted carriers')
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
        record(proof, 'schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls' +
            (' addresses' if proof.get('schema') in (2, 3, 4) else '') +
            (' wrappers' if proof.get('schema') in (3, 4) else '') +
            (' importForeign' if proof.get('schema') == 4 else ''))
        package_address_declarations(module, proof, typ, identity)
        proof_identity(proof)
        require(proof['status'] == 'verified' and type(proof['wordBits']) is int and proof['wordBits'] == 64, 'verified import profile')
        callbacks = native_callback_declarations(module, proof, typ, identity)
        product = record(native_import_product(module, proof), 'schema execution stubs files')
        require(type(product['schema']) is int and product['schema'] == 1 and product['execution'] == 'not-linked' and product['files'] == [], 'foreign product')
        if product['stubs'] is not None:
            stubs = record(product['stubs'], 'header source initializers finalizers')
            require((stubs['header'] == '' or callbacks and isinstance(stubs['header'], str)) and
                isinstance(stubs['source'], str) and stubs['initializers'] == [] and stubs['finalizers'] == [], 'foreign stub obligations')
            require('foreign' in module or stubs['source'] == '', 'missing retained stubs')
        require('foreign' not in module or module['foreign'] == proof['expectedForeign'], 'retained product differs')
        require(proof['expectedCalls'] == native_archive_calls(module.get('bindings')), 'retained Core inventory differs')
        binders = []
        for entry in sequence(proof['imports']):
            record(entry, 'binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted')
            binder = identity(entry['binder'])
            require(binder['unit'] == unit and binder['module'] == module['module'] and binder['namespace'] == 'value' and binder not in binders, 'import binder')
            binders.append(binder); text(entry['symbol'])
            require(entry['header'] is None or isinstance(entry['header'], str) and text(entry['header']) and
                not any(char in entry['header'] for char in '\n\r"\\'), 'import header')
            require(entry['unit'] in (None, unit) and entry['convention'] in ('ccall', 'capi', 'prim') and
                (entry['isFunction'] is True or entry['convention'] == 'capi' and entry['isFunction'] is False) and
                entry['safety'] in ('unsafe', 'safe', 'interruptible') and entry['normalizationRole'] == 'representational', 'import metadata')
            typ(entry['declaredType']); typ(entry['normalizedType'])
            emitted = emitted_signature(entry['emitted'])
            require(emitted['convention'] == entry['convention'] and emitted['safety'] == entry['safety'], 'emitted declaration')
            core_original_foreign.validate_boxed_declaration(entry, proof['expectedCalls'])
            if entry['convention'] == 'prim': core_original_foreign.validate_prim_declaration(entry, proof['expectedCalls'])
            emitted_imports.append(emitted)
        require((proof['profile'] == IMPORT_PROFILES[1]) == any(e['convention'] == 'prim' for e in emitted_imports),
                'primitive producer profile inventory')
        if unit == 'ghc-internal' and module['module'] == 'GHC.Internal.Stack.Decode':
            for call in proof['expectedCalls']:
                target = call.get('target', {})
                if target.get('unit') == unit and target.get('symbol') in core_original_foreign.STACK_INFO and call.get('convention') == 'prim':
                    require(any(entry['symbol'] == target['symbol'] for entry in emitted_imports),
                            'missing original Stack primitive declaration')
    require('staticForeignImportStubs' not in module or module['staticForeignImportStubs'] == proof, 'retained stub provenance differs')
    conflicts = [emitted_signature(value) for value in sequence(archive['conflictingImports'])] if conflict_field else []
    if conflict_field:
        require(conflicts and all(value not in conflicts[:index] for index, value in enumerate(conflicts)) and unknown is None,
                'conflicting import inventory')
    conflict_symbols = {value['symbol'] for value in conflicts}
    for symbol in conflict_symbols:
        variants = [value for value in conflicts if value['symbol'] == symbol]
        require(all(value['convention'] in ('ccall', 'capi') and value['safety'] in ('unsafe', 'safe') and
            not any(rep in gc_boxed for rep in value['arguments'] + value['result']) for value in variants) and
            len({c_abi(value) for value in variants}) > 1,
                'imports do not have conflicting C ABIs')
        local = [value for value in emitted_imports if value['symbol'] == symbol and value['safety'] != 'interruptible' and
                 not any(rep in gc_boxed for rep in value['arguments'] + value['result'])]
        require(local and all(value in variants for value in local), 'conflict witnesses differ from local imports')
    expected = [entry for entry in emitted_imports if entry['convention'] == 'prim' or entry['safety'] == 'interruptible' or entry['symbol'] in conflict_symbols or
                any(rep in gc_boxed for rep in entry['arguments'] + entry['result'])]
    require(sequence(archive['unsupportedImports']) == expected, 'unsupported import inventory differs')
    unresolved = [text(value) for value in sequence(archive['unresolvedSymbols'])]
    require(len(set(unresolved)) == len(unresolved), 'duplicate unresolved symbols')
    artifact = archive['artifact']
    require((artifact is None) == (not unresolved), 'unresolved artifact pair')
    require(unknown is not None or expected or unresolved, 'empty archive obligation')
    if artifact is not None:
        require('packageNativeLink' not in module and unknown is None, 'archive is also executable')
        package_scalar_link(dict(module, packageNativeLink=artifact), validate_archive=False)
    return archive


def native_archive_blocks(module, binding, archive, owned_call):
    if archive['unclassifiedReason'] is not None or archive['unresolvedSymbols']: return True
    return any(isinstance(call, dict) and isinstance(call.get('target'), dict) and
        call['target'].get('unit') == module['unit'] and any(
            call['target'].get('symbol') == emitted['symbol'] and call.get('convention') == emitted['convention'] and
            call.get('safety') == emitted['safety'] for emitted in archive['unsupportedImports']) and not owned_call(call)
        for call in native_archive_calls(binding))


@lru_cache(maxsize=1)
def _core_native_overrides():
    profile = strict_json((Path(__file__).resolve().parents[1] /
        'src/main/resources/thc/core-native-overrides.json').read_text(encoding='utf-8'))
    if (set(profile) != {'schema', 'profile', 'ghc', 'calls'} or
            type(profile['schema']) is not int or profile['schema'] != 1 or profile['ghc'] != '9.14.1' or
            profile['profile'] != 'ghc-9.14.1-thc-core-native-overrides-v1' or
            not isinstance(profile['calls'], list) or not profile['calls']):
        raise ValueError('Invalid Core native override capability profile')
    identities = [(call['target']['unit'], call['target']['symbol']) for call in profile['calls']]
    if len(set(identities)) != len(identities):
        raise ValueError('Ambiguous Core native override capability profile')
    return profile['calls']



def _core_native_import(entry, calls):
    """Mirror the exporter/runtime's exact THC-owned ABI, never raw C authority."""
    emitted = entry['emitted']
    identity = (emitted['unit'], emitted['symbol'])
    for expected in _core_native_overrides():
        target = expected['target']
        if identity != (target['unit'], target['symbol']):
            continue
        def carrier(rep):
            values = rep['primReps']
            if len(values) > 1:
                raise ValueError('Non-scalar Core native override capability')
            return values[0] if values else 'void'
        header = entry['header']
        wanted = dict(unit=target['unit'], symbol=target['symbol'], convention=expected['convention'],
            safety=expected['safety'], arguments=list(map(carrier, expected['argumentReps'])),
            result=list(map(carrier, expected['resultRep']['components'])))
        if (entry['isFunction'] is not True or entry['symbol'] != target['symbol'] or
                entry['convention'] != expected['convention'] or entry['safety'] != expected['safety'] or
                not (header is None or isinstance(header, str) and header and
                     not any(char in header for char in '\0\n\r"\\')) or
                not _same_json_value(emitted, wanted)):
            raise ValueError('Core native override import has the wrong exact emitted ABI: ' + str(identity))
        for call in calls:
            owner = call.get('target', {}) if isinstance(call, dict) else {}
            if (owner.get('unit'), owner.get('symbol')) == identity and not _same_json_value(call, expected):
                raise ValueError('Core native override has the wrong exact foreign-call ABI: ' + str(identity))
        return True
    return False


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
    require((native or 'foreign' not in module and 'staticForeignImportStubs' not in module) and 'foreignLink' not in module and
        (native or not any(key in module for key in ('staticForeignExports', 'staticForeignExportRegistration'))), 'mixed foreign obligations')
    if native and any(key in module for key in ('staticForeignExports', 'staticForeignExportRegistration')):
        require(managed_registration(module), 'missing static export registration')
    raw_link = module['packageNativeLink' if native else 'packageScalarLink']
    inputs = native and isinstance(raw_link, dict) and 'buildInputs' in raw_link
    demand = native and isinstance(raw_link, dict) and raw_link.get('schema') == 3
    callbacks = native and isinstance(raw_link, dict) and raw_link.get('schema') == 2
    companion = native and isinstance(raw_link, dict) and 'nativeLibrary' in raw_link
    data_symbols = native and isinstance(raw_link, dict) and 'dataSymbols' in raw_link
    components = native and isinstance(raw_link, dict) and 'dependencies' in raw_link
    link = record(raw_link, 'schema format profile unit target componentSha256 bitcodeSha256 bitcodeHex abi' +
                  (' buildInputs' if inputs else '') + (' finalizers' if callbacks else '') +
                  (' nativeLibrary' if companion else '') + (' dataSymbols' if data_symbols else '') +
                  (' exports dependencies' if components else '') + (' callSeeds' if demand else ''))
    if inputs: require(isinstance(link['buildInputs'], dict), 'build inputs record')
    require(type(link['schema']) is int and (link['schema'] == 1 or callbacks or demand) and (link['format'] == 'llvm-bitcode' or
            native and (link['format'] == 'llvm-embedded-elf' and platform.system() == 'Linux' or
                        link['format'] == 'llvm-embedded-mach-o' and platform.system() == 'Darwin')) and
            link['profile'] == ('thc-package-c-ffi-demand-v1' if demand else
                               'thc-package-c-ffi-v1' if native else 'thc-local-scalar-ccall-v1'), 'link profile')
    unit = text(link['unit'])
    require(unit == module.get('unit'), 'component owner')
    target = text(link['target'])
    cpu = {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(platform.machine().lower(), platform.machine().lower())
    target_cpu = {'arm64': 'aarch64'}.get(target.split('-')[0], target.split('-')[0])
    require(target_cpu == cpu and ((platform.system() == 'Linux' and target.endswith('-linux-gnu')) or
            (platform.system() == 'Darwin' and ('-darwin' in target or '-apple-macosx' in target)) or
            (platform.system() == 'Windows' and cpu == 'x86_64' and target.startswith('x86_64-pc-windows-msvc'))),
            'target differs from audit host')
    require(SHA256.fullmatch(text(link['componentSha256'])) and SHA256.fullmatch(text(link['bitcodeSha256'])), 'digest')
    encoded = link['bitcodeHex']
    require(isinstance(encoded, str), 'bitcode encoding')
    try: data = bytes.fromhex(encoded)
    except ValueError as error: raise ValueError('Invalid package scalar bitcode encoding') from error
    require((demand or data) and data.hex() == encoded and hashlib.sha256(data).hexdigest() == link['bitcodeSha256'], 'bitcode digest')
    if companion:
        dependency = record(link['nativeLibrary'], 'sha256 hex')
        encoded = text(dependency['hex'])
        try: native_bytes = bytes.fromhex(encoded)
        except ValueError as error: raise ValueError('Invalid native dependency encoding') from error
        require(native_bytes and native_bytes.hex() == encoded and
                hashlib.sha256(native_bytes).hexdigest() == text(dependency['sha256']), 'native dependency digest')
    if components:
        def exports(values):
            require(isinstance(values, list) and all(isinstance(v, str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', v)
                    for v in values) and len(values) == len(set(values)), 'unique C provider export')
        observed, namespaces = {}, {link['componentSha256']: unit}
        def dependencies(values, path):
            require(isinstance(values, list), 'native dependency list')
            owners = set()
            for component in values:
                has_companion = isinstance(component, dict) and 'nativeLibrary' in component
                record(component, 'schema profile unit target componentSha256 bitcodeSha256 bitcodeHex format exports dependencies' +
                       (' nativeLibrary' if has_companion else ''))
                require(type(component['schema']) is int and component['schema'] == 1 and
                        component['profile'] == 'thc-package-native-component-v1', 'native component profile')
                owner = text(component['unit'])
                require(owner not in owners and owner not in path, 'duplicate or cyclic native dependency')
                owners.add(owner)
                require(component['target'] == target, 'native dependency target')
                require(SHA256.fullmatch(text(component['componentSha256'])) and
                        SHA256.fullmatch(text(component['bitcodeSha256'])), 'native dependency digest')
                require(namespaces.setdefault(component['componentSha256'], owner) == owner, 'native dependency namespace owner')
                require(component['format'] == 'llvm-bitcode' or component['format'] == 'llvm-embedded-elf' and platform.system() == 'Linux' or
                        component['format'] == 'llvm-embedded-mach-o' and platform.system() == 'Darwin', 'native dependency format')
                encoded = text(component['bitcodeHex']); payload = bytes.fromhex(encoded)
                require(payload and payload.hex() == encoded and hashlib.sha256(payload).hexdigest() == component['bitcodeSha256'],
                        'native dependency bitcode')
                if has_companion:
                    companion = record(component['nativeLibrary'], 'sha256 hex')
                    encoded = text(companion['hex']); payload = bytes.fromhex(encoded)
                    require(payload and payload.hex() == encoded and hashlib.sha256(payload).hexdigest() == text(companion['sha256']),
                            'native dependency companion')
                exports(component['exports'])
                dependencies(component['dependencies'], path | {owner})
                require(exact(observed.setdefault(owner, component), component), 'conflicting native dependency identity')
        exports(link['exports'])
        dependencies(link['dependencies'], {unit})
    require(isinstance(link['abi'], list) and link['abi'], 'empty ABI')
    reps = ('Int32Rep', 'Int64Rep', 'FloatRep', 'DoubleRep')
    if native:
        reps += ('IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Word32Rep',
                 'Word64Rep', 'AddrRep', 'ByteArray#', 'MutableByteArray#')
    results = tuple(rep for rep in reps if rep not in ('ByteArray#', 'MutableByteArray#')) + (('void',) if native else ())
    data_symbols = link.get('dataSymbols', [])
    require(isinstance(data_symbols, list) and all(isinstance(entry, str) for entry in data_symbols) and
            len(data_symbols) == len(set(data_symbols)), 'address symbol inventory')
    abi = {}
    for index, entry in enumerate(link['abi']):
        record(entry, 'symbol entry convention safety arguments result' if native else 'symbol entry arguments result')
        name = text(entry['symbol'])
        require(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name), 'C symbol')
        require(entry['entry'] == ('thc_native_' if native else 'thc_scalar_') + link['componentSha256'] + '_' + str(index), 'component entry namespace')
        require(isinstance(entry['arguments'], list) and all(arg in reps for arg in entry['arguments']) and entry['result'] in results, 'C ABI')
        require(not native or entry['convention'] in ('ccall', 'capi') and entry['safety'] in ('unsafe', 'safe'),
                'unsupported C calling convention/safety')
        key = (name, entry.get('convention', 'ccall'), entry.get('safety', 'unsafe'), tuple(entry['arguments']), entry['result'], entry['entry'] in data_symbols)
        require(key not in abi, 'duplicate ABI signature')
        abi[key] = entry
    require(list(abi) == sorted(abi), 'sorted unique ABI')
    def pointer_abi(rep):
        return 'AddrRep' if rep in ('ByteArray#', 'MutableByteArray#') else rep
    def integer_abi(rep):
        return 'Word' + rep[3:] if rep in ('IntRep', 'Int8Rep', 'Int16Rep', 'Int32Rep', 'Int64Rep') else rep
    header_adapted = set()
    for name in {entry['symbol'] for entry in abi.values() if entry['entry'] not in data_symbols}:
        variants = [entry for entry in abi.values() if entry['symbol'] == name and entry['entry'] not in data_symbols]
        require(native or len(variants) == 1, 'duplicate scalar ABI symbol')
        def shape(entry, normalize, effective_safety=True):
            safety = entry.get('safety', 'unsafe')
            if effective_safety and safety == 'safe': safety = 'unsafe'
            return (entry.get('convention', 'ccall'), safety, tuple(normalize(rep) for rep in entry['arguments']), entry['result'])
        if len({shape(entry, pointer_abi) for entry in variants}) > 1:
            header_adapted.add(name)
        require(len({shape(entry, lambda rep: integer_abi(pointer_abi(rep)))
                     for entry in variants}) == 1, 'conflicting C ABI variants')
        # Mutability belongs to the typed call, not the component's pointer ABI.
        # Selection below still rejects an erased call that matches both entries.
    available = {entry['entry'] for entry in link['abi']}
    for name in data_symbols:
        selected = [entry for entry in link['abi'] if entry['entry'] == name]
        require(len(selected) == 1 and selected[0]['arguments'] == [] and selected[0]['result'] == 'AddrRep' and
                selected[0]['convention'] == 'ccall' and selected[0]['safety'] == 'unsafe', 'data address ABI')
    finalizers = link.get('finalizers', [])
    require(not set(data_symbols) & set(finalizers), 'callable data symbol')
    if callbacks:
        require(isinstance(finalizers, list) and finalizers and
                all(isinstance(entry, str) for entry in finalizers) and len(finalizers) == len(set(finalizers)), 'finalizer inventory')
        for name in finalizers:
            selected = [entry for entry in abi.values() if entry['entry'] == name]
            require(len(selected) == 1, 'finalizer missing from ABI')
            entry = selected[0]
            require(entry['arguments'] == ['AddrRep'] and entry['result'] == 'void' and
                entry['convention'] == 'ccall' and entry['safety'] == 'unsafe' and
                entry['symbol'] != 'free', 'finalizer ABI')
    if demand:
        require(link['format'] == 'llvm-bitcode' and components and 'dataSymbols' not in link and not finalizers and
                all(entry['convention'] == 'ccall' for entry in abi.values()), 'ordinary demand component profile')
        providers = dict(observed)
        if data: providers[unit] = link
        seeds = set()
        require(isinstance(link['callSeeds'], list) and link['callSeeds'], 'missing call seeds')
        for value in link['callSeeds']:
            seed = record(value, 'entry bitcodeHex bitcodeSha256 providerUnit providerComponentSha256 providerSymbol')
            entry = text(seed['entry'])
            encoded, digest = text(seed['bitcodeHex']), text(seed['bitcodeSha256'])
            payload = bytes.fromhex(encoded)
            require(SHA256.fullmatch(digest) and payload and payload.hex() == encoded and
                    hashlib.sha256(payload).hexdigest() == digest, 'call seed digest')
            require(entry in available and entry not in data_symbols and entry not in finalizers, 'ordinary call seed ABI')
            if seed['providerUnit'] is None:
                require(seed['providerComponentSha256'] is None and seed['providerSymbol'] is None, 'absent call provider')
            else:
                owner, component_hash, symbol = text(seed['providerUnit']), text(seed['providerComponentSha256']), text(seed['providerSymbol'])
                provider = providers.get(owner)
                require(provider is not None and provider['componentSha256'] == component_hash and
                        symbol.startswith('thc_provider_' + component_hash + '_') and
                        re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', symbol) and symbol in provider['exports'],
                        'exact call seed provider owner')
            require(entry not in seeds, 'duplicate call seed')
            seeds.add(entry)
        if not data:
            require(not companion and not link['exports'] and not link['dependencies'] and not data_symbols and
                    not finalizers and len(seeds) == len(abi), 'absent component obligations')
    selected_link = link
    if native and 'staticForeignImports' not in module:
        require('staticForeignImportStubs' not in module, 'unproved retained import obligations')
        if 'foreign' in module:
            product = record(module['foreign'], 'schema execution stubs files')
            require(product['schema'] == 1 and product['execution'] == 'not-linked' and product['files'] == [], 'foreign product')
            if product['stubs'] is not None:
                stubs = record(product['stubs'], 'header source initializers finalizers')
                require(stubs['header'] == '' and isinstance(stubs['source'], str) and
                        stubs['initializers'] == [] and stubs['finalizers'] == [], 'foreign registration requires its managed protocol')
        # Installed FCallIds retain their ABI without source annotations. The
        # compiled contract is checked against each reached call below.
        # A shared component is not typed CLabel authority for its finalizers.
        return selected_link, available - set(finalizers)
    proof = record(module.get('staticForeignImports'),
        'schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls' +
        (' addresses' if module.get('staticForeignImports', {}).get('schema') in (2, 3, 4) else '') +
        (' wrappers' if module.get('staticForeignImports', {}).get('schema') in (3, 4) else '') +
        (' importForeign' if module.get('staticForeignImports', {}).get('schema') == 4 else ''))
    if native and 'staticForeignImportStubs' in module:
        require(exact(module['staticForeignImportStubs'], proof), 'retained CAPI import provenance differs')
    require(type(proof['schema']) is int and proof['schema'] in (1, 2, 3, 4) and proof['scope'] == 'retained-static-import-products' and
        proof['execution'] == 'not-linked' and proof['profile'] in (IMPORT_PROFILES if native else IMPORT_PROFILES[:1]) and
        proof['unit'] == unit and proof['module'] == module.get('module') and proof['status'] == 'verified' and
        type(proof['wordBits']) is int and proof['wordBits'] == 64, 'typed import profile/owner')
    callbacks = native_callback_declarations(module, proof, typ, identity)
    product = record(native_import_product(module, proof), 'schema execution stubs files')
    require(type(product['schema']) is int and product['schema'] == 1 and product['execution'] == 'not-linked' and product['files'] == [], 'foreign product')
    if native and 'foreign' in module:
        require(exact(proof['expectedForeign'], module['foreign']), 'retained C stubs differ')
    if product['stubs'] is not None:
        stub = record(product['stubs'], 'header source initializers finalizers')
        require((stub['header'] == '' or native and callbacks and isinstance(stub['header'], str)) and (isinstance(stub['source'], str) if native else stub['source'] == '') and
                stub['initializers'] == [] and stub['finalizers'] == [], 'nonempty foreign products')
        require(not native or 'foreign' in module or stub['source'] == '', 'missing retained C stubs')
    require(isinstance(proof['imports'], list) and (native or proof['imports']), 'empty import inventory')
    # Explicit JavaScript descriptors select Truffle, not a native adapter.
    # Keep their declarations and complete call inventory under the same checks.
    javascript = set()
    require(isinstance(proof['expectedCalls'], list), 'foreign call inventory')
    for call in proof['expectedCalls']:
        if not isinstance(call, dict): continue
        target, source = call.get('target'), call.get('javascriptSource')
        if (call.get('intrinsic') == 'javascript-v1' and type(call.get('schema')) is int and call['schema'] == 1 and
                call.get('convention') == 'ccall' and call.get('safety') in ('safe', 'unsafe') and
                isinstance(source, str) and source and isinstance(target, dict) and target.get('unit') == unit and
                target.get('kind') == 'static' and target.get('isFunction') is True and
                target.get('symbol') == 'thc_javascript_v1_' + source.encode('utf-8').hex()):
            javascript.add(target['symbol'])
    address_symbols = package_address_declarations(module, proof, typ, identity)
    binders, proved = [], set(data_symbols) | {entry['entry'] for entry in link['abi'] if entry['entry'] in finalizers and entry['symbol'] in address_symbols}
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
            convention in (('ccall', 'capi') if native else ('ccall',)) and item['safety'] in (('unsafe', 'safe', 'interruptible') if native else ('unsafe',)) and
            item['normalizationRole'] == 'representational', 'static supported C import')
        typ(item['declaredType']); typ(item['normalizedType'])
        text(item['symbol'])
        emitted = record(item['emitted'], 'symbol unit convention safety arguments result')
        if native and _core_native_import(item, proof['expectedCalls']):
            continue
        if native and text(emitted['symbol']) in javascript:
            arguments, result = emitted['arguments'], emitted['result']
            require(item['symbol'] == emitted['symbol'] and emitted['unit'] == unit and
                    emitted['convention'] == convention == 'ccall' and emitted['safety'] == item['safety'] and
                    item['safety'] in ('safe', 'unsafe') and isinstance(arguments, list) and arguments and
                    arguments[-1] == 'void' and all(rep in reps for rep in arguments[:-1]) and
                    (result == ['void'] or isinstance(result, list) and len(result) == 2 and
                     result[0] == 'void' and result[1] in results and result[1] != 'void'),
                    'JavaScript declaration differs from emitted ABI')
            continue
        if native and item['safety'] == 'interruptible':
            require(emitted['unit'] == unit and emitted['convention'] == convention and
                    emitted['safety'] == item['safety'], 'unlinked declaration identity')
            continue
        name = text(emitted['symbol'] if native else item['symbol'])
        if name in header_adapted:
            require(convention == 'ccall' and isinstance(item['header'], str) and item['header'] and
                    not any(char in item['header'] for char in '\0\n\r"\\'),
                    'signedness variants require a retained configured C header')
        variants = [entry for entry in abi.values() if entry['symbol'] == name and entry['entry'] not in data_symbols and
            convention == entry.get('convention', 'ccall') and item['safety'] == entry.get('safety', 'unsafe') and exact(emitted,
                dict(symbol=name, unit=unit, convention=convention, safety=entry.get('safety', 'unsafe'), arguments=entry['arguments'] + ['void'],
                     result=['void'] if entry['result'] == 'void' else ['void', entry['result']]))]
        require(len(variants) == 1, 'emitted ABI differs from compiled C')
        # A callable import is not authority to publish a typed CLabel finalizer.
        if variants[0]['entry'] not in finalizers: proved.add(variants[0]['entry'])
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
    typed_arrays = isinstance(call, dict) and 'argumentTypes' in call
    keys = {'schema', 'target', 'convention', 'safety', 'arity', 'suppliedArity', 'argumentReps', 'resultRep'}
    if typed_arrays: keys.add('argumentTypes')
    if (not isinstance(call, dict) or set(call) != keys or
        type(call['schema']) is not int or call['schema'] != (2 if typed_arrays else 1) or
        typed_arrays and call['argumentTypes'] != [rep if rep in ('ByteArray#', 'MutableByteArray#') else None for rep in wanted] or
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


def _object_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'Duplicate JSON key: {key}')
        result[key] = value
    return result


def _invalid_constant(value):
    raise ValueError(f'Invalid JSON constant: {value}')


def _json_decoder(strict):
    return json.JSONDecoder(object_pairs_hook=_object_pairs, parse_constant=_invalid_constant) if strict else json.JSONDecoder()


def json_raw_decode(text, at=0, *, strict=False):
    """Keep the stdlib fast path; deep containers use the same scalar decoder."""
    decoder = _json_decoder(strict)
    try:
        return decoder.raw_decode(text, at)
    except RecursionError:
        pass

    def value(at):
        if at < len(text) and text[at] in '[{':
            return ([] if text[at] == '[' else {}), at + 1, True
        result, end = decoder.raw_decode(text, at)
        return result, end, False

    root, at, opened = value(at)
    if not opened:
        return root, at
    # State 0 permits an empty container, 1 requires an item, 2 a separator.
    stack = [[root, 0]]
    while stack:
        container, state = stack[-1]
        at = _skip_json_space(text, at)
        char = text[at:at + 1]
        close = ']' if isinstance(container, list) else '}'
        if char == close and state != 1:
            stack.pop()
            at += 1
            continue
        if state == 2:
            if char != ',':
                raise json.JSONDecodeError("Expecting ',' delimiter", text, at)
            stack[-1][1] = 1
            at = _skip_json_space(text, at + 1)
        key = None
        if isinstance(container, dict):
            if text[at:at + 1] != '"':
                raise json.JSONDecodeError('Expecting property name enclosed in double quotes', text, at)
            key, at = decoder.raw_decode(text, at)
            if strict and key in container:
                raise ValueError(f'Duplicate JSON key: {key}')
            at = _skip_json_space(text, at)
            if text[at:at + 1] != ':':
                raise json.JSONDecodeError("Expecting ':' delimiter", text, at)
            at = _skip_json_space(text, at + 1)
        child, at, opened = value(at)
        if isinstance(container, list):
            container.append(child)
        else:
            container[key] = child
        stack[-1][1] = 2
        if opened:
            stack.append([child, 0])
    return root, at


def json_loads(data, *, strict=False):
    try:
        return json.loads(data, **(dict(object_pairs_hook=_object_pairs, parse_constant=_invalid_constant) if strict else {}))
    except RecursionError:
        pass
    if not isinstance(data, str):
        data = data.decode(json.detect_encoding(data), 'surrogatepass')
    result, end = json_raw_decode(data, _skip_json_space(data, 0), strict=strict)
    end = _skip_json_space(data, end)
    if end != len(data):
        raise json.JSONDecodeError('Extra data', data, end)
    return result


def strict_json(data):
    return json_loads(data, strict=True)


def json_dumps(value):
    """Exact compact ensure_ascii storage bytes, without a nesting limit."""
    try:
        return json.dumps(value, ensure_ascii=True, separators=(',', ':'))
    except RecursionError:
        pass
    parts, active = [], set()
    stack = [('value', value)]
    while stack:
        kind, item = stack.pop()
        if kind == 'close':
            active.remove(id(item))
            parts.append('}' if isinstance(item, dict) else ']')
        elif kind in ('array', 'object'):
            iterator, first = item
            try:
                child = next(iterator)
            except StopIteration:
                continue
            if not first:
                parts.append(',')
            stack.append((kind, (iterator, False)))
            if kind == 'object':
                key, child = child
                if not isinstance(key, str):
                    if key is True: key = 'true'
                    elif key is False: key = 'false'
                    elif key is None: key = 'null'
                    elif isinstance(key, (int, float)): key = json.dumps(key)
                    else: raise TypeError('keys must be str, int, float, bool or None')
                parts.append(json.dumps(key, ensure_ascii=True) + ':')
            stack.append(('value', child))
        elif isinstance(item, (dict, list, tuple)):
            if id(item) in active:
                raise ValueError('Circular reference detected')
            active.add(id(item))
            object_ = isinstance(item, dict)
            parts.append('{' if object_ else '[')
            stack.append(('close', item))
            stack.append(('object' if object_ else 'array', (iter(item.items() if object_ else item), True)))
        else:
            parts.append(json.dumps(item, ensure_ascii=True, separators=(',', ':')))
    return ''.join(parts)


def zip_member(name):
    return (isinstance(name, str) and bool(name) and not name.startswith('/') and
            not re.match(r'^[A-Za-z]:', name) and '\\' not in name and
            all(part not in ('', '.', '..') for part in name.split('/')) and
            all(ord(char) >= 32 for char in name))


def _module_bytes(path, item, read):
    return read(item['path'])


def bundle_modules(path, unit, records):
    """Compatibility list API; streaming consumers use open_modules instead."""
    with closing(_iter_bundle_modules(path, unit, records)) as modules:
        return [(artifact, data) for _, artifact, data in modules]


def _iter_bundle_modules(path, unit, records):
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
                expected = {'manifest.json'}
                claimed = {'manifest.json', 'inplace-manifest.json'}
                for item in records:
                    members = [item['path']]
                    for member in members:
                        if not zip_member(member) or member in claimed:
                            raise ValueError(f'{path}: duplicate or unsafe JSON ZIP member path: {member!r}')
                        claimed.add(member)
                        expected.add(member)
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
                    del inputs, record
                del inner
                # Keep the verified archive open, but never retain all its
                # decompressed members beside the caller's parsed Core.
                for item in records:
                    yield item, str(archive_path) + '!/' + item['path'], _module_bytes(path, item, archive.read)
    except (BadZipFile, RuntimeError, EOFError, zlib.error) as error:
        raise ValueError(f'{path}: invalid ZIP bundle {archive_path}: {error}') from error


def load(path):
    with open_modules(path) as modules:
        return list(modules)


def load_for_audit(path):
    """Read verified archive-only foreign Core for diagnostics, never execution."""
    with open_modules(path, audit_archives=True) as modules:
        return list(modules)


def open_modules(path, *, audit_archives=False):
    """Open a one-shot stream of (source, validated module) pairs.

    Use as a context manager and exhaust it before publishing any result:
    later modules can still reject the manifest. Normal context exit before
    exhaustion raises ValueError. complete becomes true only after successful
    exhaustion; cancellation, close and validation failure never set it.

    audit_archives permits verified archive-only foreign Core for diagnostics,
    not execution. Completion establishes the same manifest/module checks as
    load_for_audit, not whole-program admission or cross-module audit proofs.
    Consumers own any modules they retain; this reader retains no past ASTs.
    manifest_identity records the exact bytes/inventory actually parsed, once
    read; its presence does not imply successful completion.
    """
    return _ModuleStream(path, audit_archives)


class _ModuleStream:
    def __init__(self, path, audit_archives):
        self._path = path
        self.manifest_identity = None
        self._iterator = _iter_load(path, audit_archives, self._manifest_read)
        self._complete = False
        self._closed = False

    def _manifest_read(self, identity):
        self.manifest_identity = identity

    @property
    def complete(self):
        return self._complete

    def __iter__(self):
        return self

    def __next__(self):
        if self._closed:
            raise StopIteration
        try:
            return next(self._iterator)
        except StopIteration:
            self._complete = True
            self.close()
            raise
        except BaseException:
            self.close()
            raise

    def close(self):
        if not self._closed:
            self._closed = True
            self._iterator.close()

    def __enter__(self):
        if self._closed:
            raise ValueError(f'{self._path}: Core module stream is closed')
        return self

    def __exit__(self, exception_type, exception, traceback):
        self.close()
        if exception_type is None and not self._complete:
            raise ValueError(f'{self._path}: Core module stream was not fully consumed')


def _iter_loose_modules(path, root, records):
    def read(relative):
        artifact = (root / relative).resolve()
        if not artifact.is_relative_to(root):
            raise ValueError(f'{path}: module path escapes manifest root: {relative!r}')
        return artifact.read_bytes()
    for item in records:
        if 'compact' in item:
            artifact = Path(item['compact']['path']).resolve()
            yield item, str(artifact), artifact.read_bytes()
        else:
            artifact = (root / item['path']).resolve()
            yield item, str(artifact), _module_bytes(path, item, read)


def _skip_json_space(text, at):
    """Advance a bounded cursor without copying any unvisited suffix."""
    size = len(text)
    while at < size and text[at] in ' \t\r\n':
        at += 1
    return at


def _same_json_value(left, right):
    pending = [(left, right)]
    while pending:
        left, right = pending.pop()
        if type(left) is not type(right):
            return False
        if isinstance(left, dict):
            if left.keys() != right.keys():
                return False
            pending.extend((left[key], value) for key, value in right.items())
        elif isinstance(left, list):
            if len(left) != len(right):
                return False
            pending.extend(zip(left, right))
        elif left != right:
            return False
    return True


def _contains_delimited_control(value):
    pending = [value]
    while pending:
        value = pending.pop()
        if isinstance(value, list):
            if len(value) >= 2 and value[:2] in (['prim', 'prompt#'], ['prim', 'control0#']):
                return True
            pending.extend(value)
        elif isinstance(value, dict):
            pending.extend(value.values())
    return False


def _check_unit_summaries(path, item, module):
    control = any(_contains_delimited_control(binding.get('expr')) for binding in module['bindings'])
    foreign = module.get('foreign', {})
    stubs = foreign.get('stubs') or {}
    registration = bool(foreign.get('files') or stubs.get('initializers') or stubs.get('finalizers'))
    alias = any(binding['id'] == 'main::Main.main' for binding in module['bindings'])
    inventory = module.get('staticForeignImports')
    imports = inventory.get('imports') if isinstance(inventory, dict) else None
    declarations = 'packageNativeLink' in module or isinstance(imports, list) and bool(imports)
    for key, actual in (('containsDelimitedControl', control), ('registrationObligations', registration),
                        ('mainAlias', alias), ('packageScalarDeclarations', declarations)):
        if type(item.get(key)) is not bool or item[key] != actual:
            raise ValueError(f'{path}: unit {key} summary differs from original Core')


def _iter_load(path, audit_archives, manifest_read=None):
    path = Path(path)
    root = path.resolve().parent
    data = path.read_bytes()
    manifest = strict_json(data.decode('utf-8'))
    if manifest_read is not None:
        manifest_read(dict(path=str(path.resolve()), sha256=hashlib.sha256(data).hexdigest(), manifest=manifest))
    del data
    if (not isinstance(manifest, dict) or manifest.get('format') != FORMAT or
            type(manifest.get('schema')) is not int or manifest['schema'] != 1 or
            manifest.get('ghc') != '9.14.1' or
            not isinstance(manifest.get('units'), list)):
        raise ValueError(f'{path}: requires {FORMAT} schema 1 / GHC 9.14.1')
    units = set()
    module_keys = set()
    loose_paths = {path.resolve()}
    found = False
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
        if 'json' in unit or 'symbols' in unit:
            raise ValueError(f'{path}: JSON Core unit artifacts are not supported; publish CBD modules')
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
            if set(item).intersection(('index', 'start', 'end', 'bindingsStart', 'bindingsEnd',
                                      'metadataStart', 'metadataEnd', 'sourceMetadataStart', 'sourceMetadataEnd')):
                raise ValueError(f'{path}: JSON Core indexes/extents are not supported; publish CBD modules')
            if 'compact' in item:
                compact = item['compact']
                if ('bundle' in unit or not isinstance(compact, dict) or
                        set(compact) != {'path', 'sha256', 'format'} or compact['format'] != 'thc-cbd-v1' or
                        not isinstance(compact['path'], str) or not Path(compact['path']).is_absolute() or
                        compact['sha256'] != expected):
                    raise ValueError(f'{path}: invalid CBD artifact reference: {key!r}')
                artifact = Path(compact['path']).resolve()
                if artifact in loose_paths:
                    raise ValueError(f'{path}: duplicate CBD path: {artifact}')
                loose_paths.add(artifact)
            elif 'bundle' in unit:
                if not zip_member(relative) or relative == 'manifest.json':
                    raise ValueError(f'{path}: unsafe ZIP member path: {relative!r}')
            else:
                for member in [relative]:
                    artifact = Path(member)
                    if artifact.is_absolute() or '..' in artifact.parts:
                        raise ValueError(f'{path}: module path must stay inside manifest root: {member!r}')
                    artifact = (root / artifact).resolve()
                    if not artifact.is_relative_to(root):
                        raise ValueError(f'{path}: module path escapes manifest root: {member!r}')
                    if artifact in loose_paths:
                        raise ValueError(f'{path}: duplicate CBD path: {member!r}')
                    loose_paths.add(artifact)
            records.append(item)
        artifacts = (_iter_bundle_modules(path, unit, records) if 'bundle' in unit else
                     _iter_loose_modules(path, root, records))
        with closing(artifacts):
            for item, artifact, data in artifacts:
                module = _validated_module(path, unit_id, item, data, audit_archives)
                if 'compact' in item:
                    _check_unit_summaries(path, item, module)
                del data
                found = True
                yield artifact, module
                # Release this AST before reading/parsing the next member.
                del module
    if not found:
        raise ValueError(f'{path}: no Core modules')


def _validated_module(path, unit_id, item, data, audit_archives):
    name, boundary = item['name'], item['boundary']
    relative, expected = item['path'], item['sha256']
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f'{path}: content hash mismatch: {relative!r}')
    module = inspect_cbd(data)
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
    alias = 'main::Main.main'
    for binding in bindings:
        key = binding.get('id') if isinstance(binding, dict) else None
        if not isinstance(key, str) or not (key.startswith(prefix) or key == alias):
            raise ValueError(f'{path}: foreign binding owner in {relative!r}: {key!r}')
    return module
