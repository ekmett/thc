#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Package manifests reject stale, mismatched and pre-Tidy Core artifacts."""

import hashlib
import gc
import json
from contextlib import closing
import os
from pathlib import Path
import platform
import subprocess
import struct
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import warnings
import weakref
from zipfile import ZipFile

import core_package_manifest


class TimeClockLinkTest(unittest.TestCase):
    """Closed metadata controls; structural placeholder bytes are never executed."""
    def module(self):
        unit = 'time-1.15-01ab'
        abi = core_package_manifest.time_clock_symbols(unit)
        def scalar(rep):
            return dict(kind='void' if rep is None else 'address' if rep == 'AddrRep' else 'long',
                        primReps=[] if rep is None else [rep], evaluated=False)
        calls = []
        for symbol, kind in abi.items():
            zero = kind == 'time-clock-id'
            result = dict(kind='unknown', primReps=['Int32Rep'], aggregate='unboxed-tuple',
                          components=[scalar(None) | dict(evaluated=True),
                                      scalar('Int32Rep') | dict(evaluated=True)], evaluated=False)
            calls.append(dict(foreignCall=dict(schema=1, target=dict(kind='static', symbol=symbol,
                unit=unit, isFunction=True), convention='capi', safety='unsafe', arity=1 if zero else 3,
                suppliedArity=1 if zero else 3, argumentReps=[scalar(None)] if zero else
                [scalar('Int32Rep'), scalar('AddrRep'), scalar(None)], resultRep=result)))
        source, bitcode = 'retained clock C source', b'BC'
        arch = {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(platform.machine().lower(), platform.machine().lower())
        name = 'Data.Time.Clock.Internal.CTimespec'
        return dict(unit=unit, module=name, bindings=calls,
            foreign=dict(stubs=dict(header='', source=source, initializers=[], finalizers=[]), files=[]),
            foreignLink=dict(schema=3, format='llvm-bitcode', unit=unit, module=name,
                target=arch + '-unknown-linux-gnu', symbols=list(abi),
                abi=[dict(symbol=symbol, kind=kind) for symbol, kind in abi.items()],
                headerHashes=[dict(name=name, sha256='a' * 64) for name in ['HsFFI.h', 'HsTime.h', 'HsTimeConfig.h']],
                sourceSha256=hashlib.sha256(source.encode()).hexdigest(),
                bitcodeSha256=hashlib.sha256(bitcode).hexdigest(), bitcodeHex=bitcode.hex()))

    def test_exact_time_owner_wrapper_index_header_and_cint_contract(self):
        module = self.module()
        if platform.system() != 'Linux':
            with self.assertRaises(ValueError):
                core_package_manifest.linked_foreign(module)
            return
        self.assertTrue(core_package_manifest.linked_foreign(module))
        link = module['foreignLink']
        for bad in (link | dict(schema=2), link | dict(headerHashes=[]),
                    link | dict(headerHashes=link['headerHashes'][::-1]),
                    link | dict(abi=list(reversed(link['abi']))[:2]),
                    link | dict(unit='time-1.16-01ab')):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                core_package_manifest.linked_foreign(module | dict(foreignLink=bad))
        original = module['bindings'][1]['foreignCall']
        for bad in (original | dict(safety='safe'), original | dict(convention='ccall'),
                    original | dict(argumentReps=[original['argumentReps'][0] | dict(primReps=['Word64Rep'])] + original['argumentReps'][1:]),
                    original | dict(target=original['target'] | dict(symbol=list(core_package_manifest.time_clock_symbols(link['unit']))[2]))):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                core_package_manifest.linked_foreign(module | dict(bindings=[module['bindings'][0], dict(foreignCall=bad)] + module['bindings'][2:]))


class PackageNativeVariantsTest(unittest.TestCase):
    """Structural controls only; the placeholder bitcode is never executed."""
    def module(self, reps):
        unit, name = 'variants', 'Variants'
        scalar_type = dict(kind='tycon', arguments=[], name=dict(unit='ghc-internal',
            module='GHC.Internal.Word', occurrence='Word', namespace='type'))
        abi, imports = [], []
        for index, rep in enumerate(reps):
            abi.append(dict(symbol='read_bytes', entry='thc_native_' + 'a' * 64 + '_' + str(index),
                convention='ccall', safety='unsafe', arguments=[rep], result='WordRep'))
            imports.append(dict(binder=dict(unit=unit, module=name, occurrence='read' + str(index), namespace='value'),
                header=None, symbol='read_bytes', unit=unit, isFunction=True, convention='ccall', safety='unsafe',
                declaredType=scalar_type, normalizedType=scalar_type, normalizationRole='representational',
                emitted=dict(symbol='read_bytes', unit=unit, convention='ccall', safety='unsafe',
                             arguments=[rep, 'void'], result=['void', 'WordRep'])))
        cpu = {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(platform.machine().lower(), platform.machine().lower())
        target = cpu + ('-apple-darwin' if platform.system() == 'Darwin' else '-unknown-linux-gnu')
        link = dict(schema=1, format='llvm-bitcode', profile='thc-package-c-ffi-v1', unit=unit, target=target,
            componentSha256='a' * 64, bitcodeSha256=hashlib.sha256(b'BC').hexdigest(), bitcodeHex='4243', abi=abi)
        proof = dict(schema=1, scope='retained-static-import-products', execution='not-linked',
            profile='ghc-9.14.1-thc-only-static-c-imports-v1', unit=unit, module=name, status='verified', wordBits=64,
            expectedForeign=dict(schema=1, execution='not-linked', stubs=None, files=[]), imports=imports, expectedCalls=[])
        return dict(schema=1, ghc='9.14.1', unit=unit, module=name, bindings=[], constructors=[],
                    packageNativeLink=link, staticForeignImports=proof)

    def test_pointer_variants_keep_distinct_provenance_entries(self):
        module = self.module(['AddrRep', 'ByteArray#'])
        link, proved = core_package_manifest.package_scalar_link(module)
        self.assertEqual({entry['entry'] for entry in link['abi']}, proved)
        self.assertEqual(2, len(proved))
        module['staticForeignImports']['imports'].pop()
        _, partial = core_package_manifest.package_scalar_link(module)
        self.assertEqual(1, len(partial), 'one symbol does not prove both semantic variants')

    def test_indexed_audit_keeps_cross_module_native_completeness_and_conflicts(self):
        import copy
        import importlib.util
        import io
        root = Path(__file__).resolve().parent
        spec = importlib.util.spec_from_file_location('native_store_audit', root / 'audit-core.py')
        audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(audit)
        capabilities = json.loads((root / 'core-capabilities.json').read_text())
        original = self.module(['AddrRep', 'ByteArray#'])
        first, second = copy.deepcopy(original), copy.deepcopy(original)
        first['staticForeignImports']['imports'] = first['staticForeignImports']['imports'][:1]
        second['staticForeignImports']['imports'] = second['staticForeignImports']['imports'][1:]
        conflicting = copy.deepcopy(second)
        conflicting['packageNativeLink'].update(bitcodeHex='4342', bitcodeSha256=hashlib.sha256(b'CB').hexdigest())
        with TemporaryDirectory() as directory:
            for name, modules, accepted in [('complete', [first, second], True), ('incomplete', [first], False),
                                             ('conflicting', [first, conflicting], False)]:
                with self.subTest(name=name):
                    inputs = [(str(index), module) for index, module in enumerate(modules)]
                    expected = audit.Audit(inputs, capabilities).run([])
                    self.assertEqual(accepted, expected['accepted'])
                    with audit.AuditStore(Path(directory) / (name + '.sqlite'), {}) as store:
                        report = audit.Audit(iter(inputs), capabilities, store=store).run([])
                        output = io.StringIO()
                        audit.write_report(report, output)
                        self.assertEqual(json.dumps(expected, indent=2) + '\n', output.getvalue())

    def test_partial_native_link_keeps_indices_and_requires_complete_closure_receipt(self):
        import copy
        module = self.module(['AddrRep', 'ByteArray#'])
        original = module['packageNativeLink']
        original['buildInputs'] = dict(unresolved=['unknown_external'])
        entries = original['abi']
        selected = dict(original, availableEntries=[entries[1]['entry']], bitcodeHex='4342',
                        bitcodeSha256=hashlib.sha256(b'CB').hexdigest())
        rows = [dict(entry=entry['entry'], bitcodeSha256='b' * 64,
                     unresolved=['unknown_external'] if index == 0 else []) for index, entry in enumerate(entries)]
        resolution = dict(schema=1, profile='llvm-globaldce-adapter-closures-v1',
                          inputBitcodeSha256=original['bitcodeSha256'], outputBitcodeSha256=selected['bitcodeSha256'],
                          entries=rows, unresolved=[])
        archive = dict(schema=1, profile='thc-package-native-archive-v1', execution='not-linked',
                       unit=module['unit'], module=module['module'], unsupportedImports=[], unclassifiedReason=None,
                       unresolvedSymbols=['unknown_external'], artifact=original, entryResolution=resolution)
        module.update(packageNativeLink=selected, packageNativeArchive=archive)
        link, proved = core_package_manifest.package_scalar_link(module)
        self.assertEqual([entries[1]], link['abi'])
        self.assertEqual({entries[1]['entry']}, proved)
        for rep, blocked in [('AddrRep', True), ('BoxedRep (Just Unlifted)', False)]:
            binding = dict(foreignCall=dict(target=dict(unit=module['unit'], symbol='read_bytes'),
                           convention='ccall', safety='unsafe', argumentReps=[dict(primReps=[rep]), dict(primReps=[])]))
            self.assertEqual(blocked, core_package_manifest.native_archive_blocks(module, binding, archive))
        old = copy.deepcopy(module)
        del old['packageNativeLink']; del old['packageNativeArchive']['entryResolution']
        self.assertTrue(core_package_manifest.native_archive_blocks(old, {}, core_package_manifest.package_native_archive(old)))
        for key, value in [('schema', True), ('profile', 'invented'), ('inputBitcodeSha256', 'c' * 64),
                           ('outputBitcodeSha256', 'c' * 64), ('entries', rows[::-1]), ('entries', rows[:1]),
                           ('entries', [rows[0], dict(rows[1], unresolved=['unrecorded'])]),
                           ('unresolved', ['unknown_external'])]:
            wrong = copy.deepcopy(module); wrong['packageNativeArchive']['entryResolution'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): core_package_manifest.package_scalar_link(wrong)
        for selected_wrong in ({k: v for k, v in selected.items() if k != 'availableEntries'},
                               dict(selected, availableEntries=[e['entry'] for e in entries]), dict(selected, bitcodeHex='4243')):
            wrong = dict(module, packageNativeLink=selected_wrong)
            with self.assertRaises(ValueError): core_package_manifest.package_scalar_link(wrong)
        for symbol in ('memcpy', 'erf', 'getentropy', 'wcwidth', '_ZNSt8ios_base4InitC1Ev'):
            candidate = copy.deepcopy(module)
            inputs = dict(unresolved=sorted([symbol, 'unknown_external']))
            candidate['packageNativeLink']['buildInputs'] = inputs
            candidate['packageNativeArchive']['artifact']['buildInputs'] = inputs
            receipt = candidate['packageNativeArchive']['entryResolution']
            receipt['unresolved'] = [symbol]; receipt['entries'][1]['unresolved'] = [symbol]
            if symbol == 'memcpy': self.assertEqual(1, len(core_package_manifest.package_scalar_link(candidate)[0]['abi']))
            else:
                with self.subTest(symbol=symbol), self.assertRaises(ValueError): core_package_manifest.package_scalar_link(candidate)

    def test_archive_preserves_mixed_imports_and_checks_unresolved_artifact_bytes(self):
        import copy
        module = self.module(['WordRep'])
        proof = module['staticForeignImports']
        blocked = copy.deepcopy(proof['imports'][0])
        blocked['binder']['occurrence'] = 'blocked'
        blocked['symbol'] = blocked['emitted']['symbol'] = 'blocked'
        blocked['safety'] = blocked['emitted']['safety'] = 'interruptible'
        blocked['emitted']['arguments'] = ['AddrRep', 'void']
        proof['imports'].append(blocked)
        module['packageNativeArchive'] = dict(schema=1, profile='thc-package-native-archive-v1', execution='not-linked',
            unit=module['unit'], module=module['module'], unsupportedImports=[blocked['emitted']],
            unclassifiedReason=None, unresolvedSymbols=[], artifact=None)
        link, proved = core_package_manifest.package_scalar_link(module)
        self.assertEqual({link['abi'][0]['entry']}, proved)
        archive = core_package_manifest.package_native_archive(module)
        self.assertFalse(core_package_manifest.native_archive_blocks(module, {}, archive))
        binding = dict(foreignCall=dict(target=dict(unit=module['unit'], symbol='blocked'), convention='ccall', safety='interruptible'))
        self.assertTrue(core_package_manifest.native_archive_blocks(module, binding, archive))
        for key, value in [('unsupportedImports', []), ('unclassifiedReason', 'invented'), ('schema', True)]:
            bad = copy.deepcopy(module); bad['packageNativeArchive'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): core_package_manifest.package_scalar_link(bad)
        archived = copy.deepcopy(module)
        archived['packageNativeArchive']['artifact'] = archived.pop('packageNativeLink')
        archived['packageNativeArchive']['unresolvedSymbols'] = ['unknown_external']
        self.assertIsNone(core_package_manifest.package_scalar_link(archived))
        self.assertTrue(core_package_manifest.native_archive_blocks(archived, {}, archived['packageNativeArchive']))
        archived['packageNativeArchive']['artifact']['bitcodeHex'] = '4342'
        with self.assertRaisesRegex(ValueError, 'bitcode digest'): core_package_manifest.package_native_archive(archived)

    def test_conflicts_order_duplicates_and_erased_mutability_still_reject(self):
        for reps in (['AddrRep', 'WordRep'], ['ByteArray#', 'MutableByteArray#'],
                     ['ByteArray#', 'AddrRep'], ['AddrRep', 'AddrRep']):
            with self.subTest(reps=reps), self.assertRaises(ValueError):
                core_package_manifest.package_scalar_link(self.module(reps))

    def test_conflicting_original_abis_are_archive_only_without_poisoning_other_symbols(self):
        import copy
        module = self.module(['WordRep'])
        proof = module['staticForeignImports']
        narrow = copy.deepcopy(proof['imports'][0])
        narrow['binder']['occurrence'] = 'narrow'
        narrow['symbol'] = narrow['emitted']['symbol'] = 'width'
        narrow['emitted']['result'] = ['void', 'Int32Rep']
        wide = dict(narrow['emitted'], result=['void', 'Int64Rep'])
        proof['imports'].append(narrow)
        module['packageNativeArchive'] = dict(schema=1, profile='thc-package-native-archive-v1', execution='not-linked',
            unit=module['unit'], module=module['module'], unsupportedImports=[narrow['emitted']],
            unclassifiedReason=None, unresolvedSymbols=[], artifact=None, conflictingImports=[narrow['emitted'], wide])
        link, proved = core_package_manifest.package_scalar_link(module)
        self.assertEqual({link['abi'][0]['entry']}, proved)
        archive = core_package_manifest.package_native_archive(module)
        self.assertFalse(core_package_manifest.native_archive_blocks(module, {}, archive))
        binding = dict(foreignCall=dict(target=dict(unit=module['unit'], symbol='width'), convention='ccall', safety='unsafe'))
        self.assertTrue(core_package_manifest.native_archive_blocks(module, binding, archive))
        for witnesses in ([], [narrow['emitted']], [wide], [narrow['emitted'], narrow['emitted']],
                          [narrow['emitted'], dict(wide, unit='other')],
                          [narrow['emitted'], dict(wide, result=['void', 'invented'])]):
            bad = copy.deepcopy(module); bad['packageNativeArchive']['conflictingImports'] = witnesses
            with self.subTest(witnesses=witnesses), self.assertRaises(ValueError): core_package_manifest.package_scalar_link(bad)
        proof['imports'][-1]['normalizedType'] = {}
        with self.assertRaises(ValueError): core_package_manifest.package_scalar_link(module)

    def test_safe_and_unsafe_declarations_share_c_abi_but_retain_both_adapters(self):
        module = self.module(['AddrRep', 'AddrRep'])
        module['packageNativeLink']['abi'][0]['safety'] = 'safe'
        imported = module['staticForeignImports']['imports'][0]
        imported['safety'] = imported['emitted']['safety'] = 'safe'
        link, proved = core_package_manifest.package_scalar_link(module)
        self.assertEqual(['safe', 'unsafe'], [entry['safety'] for entry in link['abi']])
        self.assertEqual(2, len(proved))

    def test_ccall_header_is_retained_without_changing_emitted_symbol(self):
        module = self.module(['AddrRep', 'ByteArray#'])
        for imported in module['staticForeignImports']['imports']:
            imported['header'] = 'original.h'
        link, proved = core_package_manifest.package_scalar_link(module)
        self.assertEqual({entry['entry'] for entry in link['abi']}, proved)
        for malformed in ('', 'bad\0header', 7):
            module['staticForeignImports']['imports'][0]['header'] = malformed
            with self.subTest(header=malformed), self.assertRaises(ValueError):
                core_package_manifest.package_scalar_link(module)

    def test_safe_imports_retain_metadata_with_temporary_unsafe_carriers_but_reject_interruptible(self):
        def make(rep, safety, result='WordRep'):
            module = self.module([rep])
            module['packageNativeLink']['abi'][0]['safety'] = safety
            imported = module['staticForeignImports']['imports'][0]
            imported['safety'] = imported['emitted']['safety'] = safety
            module['packageNativeLink']['abi'][0]['result'] = result
            imported['emitted']['result'] = ['void', result]
            return module
        accepted = make('WordRep', 'safe')
        link, proved = core_package_manifest.package_scalar_link(accepted)
        self.assertEqual('safe', link['abi'][0]['safety'])
        self.assertEqual({link['abi'][0]['entry']}, proved)
        link, _ = core_package_manifest.package_scalar_link(make('WordRep', 'safe', 'AddrRep'))
        self.assertEqual('AddrRep', link['abi'][0]['result'])
        for rep in ('AddrRep', 'ByteArray#', 'MutableByteArray#'):
            link, _ = core_package_manifest.package_scalar_link(make(rep, 'safe'))
            self.assertEqual([rep], link['abi'][0]['arguments'])
            self.assertEqual('safe', link['abi'][0]['safety'])
        for where in ('declaration', 'emitted'):
            altered = make('WordRep', 'safe')
            imported = altered['staticForeignImports']['imports'][0]
            (imported if where == 'declaration' else imported['emitted'])['safety'] = 'unsafe'
            with self.subTest(where=where), self.assertRaises(ValueError):
                core_package_manifest.package_scalar_link(altered)
        for rep in ('AddrRep', 'ByteArray#', 'MutableByteArray#', 'WordRep'):
            with self.subTest(rep=rep), self.assertRaises(ValueError):
                core_package_manifest.package_scalar_link(make(rep, 'interruptible'))

    def test_signedness_adapters_require_headers_and_keep_integer_widths(self):
        for width in ('', '8', '16', '32', '64'):
            module = self.module(['Int' + width + 'Rep', 'Word' + width + 'Rep'])
            with self.assertRaises(ValueError):
                core_package_manifest.package_scalar_link(module)
            for imported in module['staticForeignImports']['imports']:
                imported['header'] = 'primitive-memops.h'
            link, proved = core_package_manifest.package_scalar_link(module)
            self.assertEqual(2, len(proved))
            self.assertEqual([['Int' + width + 'Rep'], ['Word' + width + 'Rep']],
                             [entry['arguments'] for entry in link['abi']])
            for bad in ('', 'bad\nheader', 'bad"header', 'bad\\header', None):
                module['staticForeignImports']['imports'][0]['header'] = bad
                with self.subTest(width=width, header=bad), self.assertRaises(ValueError):
                    core_package_manifest.package_scalar_link(module)
        for reps in (['Int8Rep', 'Word16Rep'], ['IntRep', 'Word64Rep']):
            module = self.module(reps)
            for imported in module['staticForeignImports']['imports']:
                imported['header'] = 'primitive-memops.h'
            with self.subTest(reps=reps), self.assertRaises(ValueError):
                core_package_manifest.package_scalar_link(module)


class PackageManifestTest(unittest.TestCase):
    def setUp(self):
        self.scratch = TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.boundary = core_package_manifest.BOUNDARY

    def unit(self, unit_id, name='Shared'):
        source = dict(schema=1, ghc='9.14.1', unit=unit_id, module=name,
                      boundary=self.boundary, bindings=[], constructors=[])
        path = self.root / f'{unit_id}.json'
        path.write_text(json.dumps(source) + '\n')
        return dict(id=unit_id, depends=[], modules=[dict(name=name, boundary=self.boundary,
                    path=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest())])

    def manifest(self, units):
        path = self.root / 'packages.json'
        path.write_text(json.dumps(dict(format='thc-core-packages', schema=1,
                                        ghc='9.14.1', units=units)) + '\n')
        return path

    def bundled(self, unit, members=None, inner=None, build_inputs=None, input_hash=None):
        module = unit['modules'][0]
        data = (self.root / module['path']).read_bytes()
        module['path'] = 'core/' + module['name'] + '.json'
        contents = {module['path']: data} if members is None else members
        inner = inner or dict(format='thc-core-bundle', schema=1, unit=unit['id'],
                              buildKey='a' * 64, exportKey='b' * 64, modules=unit['modules'])
        if build_inputs is not None:
            input_bytes = json.dumps(build_inputs).encode()
            inner['buildInputs'] = dict(path='inplace-manifest.json',
                                        sha256=input_hash or hashlib.sha256(input_bytes).hexdigest())
        bundle = self.root / 'bundle.zip'
        with ZipFile(bundle, 'w') as archive:
            archive.writestr('manifest.json', json.dumps(inner))
            if build_inputs is not None:
                archive.writestr('inplace-manifest.json', input_bytes)
            for name, value in contents.items():
                archive.writestr(name, value)
        unit['bundle'] = dict(path=str(bundle), sha256=hashlib.sha256(bundle.read_bytes()).hexdigest())
        return self.manifest([unit])

    def two_module_bundle(self, second=None):
        unit = self.unit('first')
        if second is None:
            second = json.dumps(dict(schema=1, ghc='9.14.1', unit='first', module='Other',
                boundary=self.boundary, bindings=[dict(id='first:Other.value', expr=['lit', 'int', '7'])],
                constructors=[])).encode()
        unit['modules'].append(dict(name='Other', boundary=self.boundary, path='core/Other.json',
                                    sha256=hashlib.sha256(second).hexdigest()))
        return self.bundled(unit, members={'core/Shared.json': (self.root / 'first.json').read_bytes(),
                                          'core/Other.json': second})

    def direct_unit(self, binding_count=1, fixed=False):
        unit = dict(id='first', depends=[], modules=[])
        payload, rows = bytearray(), []
        # Reverse bytewise ID/module order, raw spaces and non-ASCII text.
        for name in ('Zulu', 'Alpha'):
            binding = dict(id=f'first:{name}.雪 space', name='value', arity=0, lifted=True,
                           expr=['lit', 'int', '7'] if name == 'Zulu' else ['lit', 'string', 'prompt#'])
            prefix = json.dumps(dict(schema=1, ghc='9.14.1', unit='first', module=name,
                                    boundary=self.boundary, note='雪'), ensure_ascii=False).encode()[:-1]
            prefix += b', "bindings": ['
            chunks = [json.dumps(binding | dict(id=binding['id'] + (f' {index:05}' if binding_count > 1 else '')),
                                 ensure_ascii=False).encode() for index in range(binding_count)]
            body = b', \t\r\n'.join(chunks)
            source = prefix + body + b'], "constructors": []}\r\n'
            start = len(payload)
            payload.extend(source + b'\n')
            offset = start + len(prefix)
            for chunk in chunks:
                rows.append((json.loads(chunk)['id'].encode(), offset))
                offset += len(chunk) + len(b', \t\r\n')
            metadata_start = len(payload)
            original = json.loads(source)
            payload.extend(json.dumps({key: value for key, value in original.items()
                if key in core_package_manifest.UNIT_METADATA_KEYS}).encode())
            metadata_end = len(payload)
            payload.extend(b'\n')
            unit['modules'].append(dict(name=name, boundary=self.boundary, path=f'core/{name}.json',
                sha256=hashlib.sha256(source).hexdigest(), start=start, end=start + len(source),
                bindingsStart=start + len(prefix) - 1, bindingsEnd=start + len(prefix) + len(body) + 1,
                metadataStart=metadata_start, metadataEnd=metadata_end,
                containsDelimitedControl=False, registrationObligations=False, mainAlias=False,
                packageScalarDeclarations=False))
        if fixed:
            records = sorted((hashlib.md5(key).digest(), offset) for key, offset in rows)
            symbols = b''.join(key + offset.to_bytes(8, 'little') for key, offset in records)
        else:
            symbols = b''.join(key + b' ' + str(offset).encode() + b'\n' for key, offset in sorted(rows))
        for key, filename, data in [('json', 'core.jsons', payload), ('symbols', 'core.symbols', symbols)]:
            target = self.root / filename
            target.write_bytes(data)
            unit[key] = dict(path=str(target), sha256=hashlib.sha256(data).hexdigest())
        if fixed:
            unit['symbols']['format'] = 'md5-utf8-u64le-v1'
        return unit

    def test_direct_unit_fixed_md5_records(self):
        self.assertEqual('201ac5924113112a846d82b090d8458a', hashlib.md5(b'main:Main.main').hexdigest())
        self.assertEqual('23415231b60de428eeaf32979e1cb8ce', hashlib.md5('main:M.é😀'.encode()).hexdigest())
        unit = self.direct_unit(binding_count=2048, fixed=True)
        directory = Path(unit['symbols']['path']).read_bytes()
        self.assertEqual(4096 * 24, len(directory))
        modules = core_package_manifest.load(self.manifest([unit]))
        self.assertEqual(4096, sum(len(module['bindings']) for _, module in modules))
        empty = self.direct_unit(binding_count=0, fixed=True)
        self.assertEqual(b'', Path(empty['symbols']['path']).read_bytes())
        self.assertEqual(2, len(core_package_manifest.load(self.manifest([empty]))))

    def test_direct_unit_fixed_records_require_exact_explicit_format_and_offsets(self):
        import copy
        original = self.direct_unit(fixed=True)
        directory = Path(original['symbols']['path'])
        data = directory.read_bytes()
        for replacement in (data[:-1], data + b'\0', data[24:] + data[:24], data[:24],
                            data + data[:24], data[:16] + bytes(8) + data[24:]):
            unit = copy.deepcopy(original)
            directory.write_bytes(replacement)
            unit['symbols']['sha256'] = hashlib.sha256(replacement).hexdigest()
            with self.subTest(size=len(replacement)), self.assertRaisesRegex(ValueError, 'symbol directory'):
                core_package_manifest.load(self.manifest([unit]))
        directory.write_bytes(data)
        for marker in (None, 'md5', 'md5-utf8-u64be-v1'):
            unit = copy.deepcopy(original); unit['symbols']['format'] = marker
            with self.subTest(format=marker), self.assertRaisesRegex(ValueError, 'symbols format'):
                core_package_manifest.load(self.manifest([unit]))
        legacy = copy.deepcopy(original); del legacy['symbols']['format']
        with self.assertRaisesRegex(ValueError, 'symbol directory'):
            core_package_manifest.load(self.manifest([legacy]))

    def test_direct_unit_preserves_bytes_identity_and_explicit_audit(self):
        unit = self.direct_unit()
        path = self.manifest([unit])
        modules = core_package_manifest.load(path)
        self.assertEqual(['Zulu', 'Alpha'], [module['module'] for _, module in modules])
        self.assertTrue(all('core.jsons@' in source for source, _ in modules))
        result = subprocess.run(['python3', '-B', str(Path(__file__).with_name('audit-core.py')),
            '--package-manifest', str(path), '--entry', 'first:Zulu.雪 space'], text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(json.loads(result.stdout)['accepted'])

    def test_direct_unit_many_bindings_and_bounded_whitespace_cursor(self):
        unit = self.direct_unit(binding_count=2048)
        loaded = core_package_manifest.load(self.manifest([unit]))
        self.assertEqual(4096, sum(len(module['bindings']) for _, module in loaded))
        class NoSuffix(str):
            def __getitem__(self, key):
                if isinstance(key, slice) and key.start is not None and key.stop is None:
                    raise AssertionError('scanner copied an unvisited suffix')
                return super().__getitem__(key)
        text = NoSuffix(' \r\n\t { "雪" : 7, \n "bindings" : [ {"id":"x y"} ] } \n')
        self.assertEqual(5, core_package_manifest._skip_json_space(text, 0))
        begin, end = core_package_manifest._bindings_span(text)
        self.assertEqual(b'[ {"id":"x y"} ]', text.encode()[begin:end])
        self.assertEqual(len(text), core_package_manifest._skip_json_space(text, len(text)))

    def test_direct_unit_rejects_bad_pair_spans_summaries_and_symbols(self):
        import copy
        original = self.direct_unit()
        malformed = []
        for missing in ('json', 'symbols'):
            value = copy.deepcopy(original); del value[missing]; malformed.append(value)
        malformed.append(original | dict(bundle=dict(path='/unused', sha256='a' * 64)))
        for key, value in [('start', -1), ('end', 999999), ('bindingsStart', True),
                           ('bindingsEnd', 1), ('containsDelimitedControl', True),
                           ('registrationObligations', True), ('mainAlias', True), ('mainAlias', None),
                           ('metadataStart', -1), ('metadataEnd', 999999), ('sourceMetadataStart', 0),
                           ('packageScalarDeclarations', True)]:
            unit = copy.deepcopy(original); unit['modules'][0][key] = value; malformed.append(unit)
        for unit in malformed:
            with self.subTest(unit=unit), self.assertRaises(ValueError):
                core_package_manifest.load(self.manifest([unit]))
        for key in ('json', 'symbols'):
            unit = copy.deepcopy(original); unit[key]['sha256'] = '0' * 64
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'artifact hash'):
                core_package_manifest.load(self.manifest([unit]))
        directory = self.root / 'core.symbols'
        rows = directory.read_bytes().splitlines(keepends=True)
        for data in (b''.join(reversed(rows)), rows[0], b''.join(rows) + rows[0],
                     rows[0].rsplit(b' ', 1)[0] + b' 0\n' + rows[1]):
            unit = copy.deepcopy(original)
            directory.write_bytes(data); unit['symbols']['sha256'] = hashlib.sha256(data).hexdigest()
            with self.assertRaisesRegex(ValueError, 'symbol directory'):
                core_package_manifest.load(self.manifest([unit]))

    def test_direct_unit_rejects_hash_consistent_metadata_substitution(self):
        unit = self.direct_unit()
        source = Path(unit['json']['path'])
        data = source.read_bytes()
        item = unit['modules'][0]
        start, end = item['metadataStart'], item['metadataEnd']
        metadata = data[start:end].replace(b'"schema": 1', b'"schema": 2')
        changed = data[:start] + metadata + data[end:]
        source.write_bytes(changed)
        unit['json']['sha256'] = hashlib.sha256(changed).hexdigest()
        with self.assertRaisesRegex(ValueError, 'metadata projection'):
            core_package_manifest.load(self.manifest([unit]))
        self.assertFalse(core_package_manifest._same_json_value({'schema': True}, {'schema': 1}))

    @staticmethod
    def index_envelope(source, events=0):
        # Transport-only controls. The audit never uses these placeholder
        # navigation sections; the Kotlin reader independently checks them.
        header = b'THCJSIX1' + struct.pack('<IIQQ', 2, 0, len(source), events) + hashlib.sha256(source).digest()
        size = sum(((count + per - 1) // per) * 8 for count, per in
                   ((len(source), 1 << 32), (len(source), 2048), (len(source), 16384), (events, 32)))
        body = header + bytes(size)
        return body + hashlib.sha256(body).digest()

    def indexed_unit(self, bundled=False, transform=lambda value: value):
        unit = self.unit('first')
        source = (self.root / 'first.json').read_bytes()
        index = transform(self.index_envelope(source))
        location = 'core/Shared.json.idx' if bundled else 'first.json.idx'
        unit['modules'][0]['index'] = dict(path=location, sha256=hashlib.sha256(index).hexdigest())
        if bundled:
            path = self.bundled(unit, members={'core/Shared.json': source, location: index})
        else:
            (self.root / location).write_bytes(index)
            path = self.manifest([unit])
        return unit, path

    def test_optional_indexes_preserve_original_modules_for_loose_and_bundled_audits(self):
        for bundled in (False, True):
            with self.subTest(bundled=bundled):
                expected = core_package_manifest.load(self.manifest([self.unit('first')]))[0][1]
                _, path = self.indexed_unit(bundled)
                self.assertEqual(expected, core_package_manifest.load(path)[0][1])
                self.assertEqual(expected, core_package_manifest.load_for_audit(path)[0][1])

    def test_index_envelopes_check_exact_source_and_transport(self):
        def changed(offset, value, resign=True):
            def mutate(original):
                data = bytearray(original)
                data[offset:offset + len(value)] = value
                if resign:
                    data[-32:] = hashlib.sha256(data[:-32]).digest()
                return bytes(data)
            return mutate

        mutations = {
            'magic': changed(0, b'NOTINDEX'),
            'version': changed(8, struct.pack('<I', 1)),
            'flags': changed(12, struct.pack('<I', 1)),
            'source size': changed(16, struct.pack('<Q', 1)),
            'count exceeds source': changed(24, struct.pack('<Q', (1 << 64) - 1)),
            'missing topology section': changed(24, struct.pack('<Q', 1)),
            'wrong source identity': changed(32, bytes(32)),
            'wrong integrity': changed(-32, b'x', resign=False),
            'short header': lambda value: value[:63],
            'short trailer': lambda value: value[:-1],
            'extra bytes': lambda value: value + b'x',
        }
        for bundled in (False, True):
            for label, mutate in mutations.items():
                with self.subTest(bundled=bundled, mutation=label), self.assertRaisesRegex(ValueError, 'JSON index'):
                    _, path = self.indexed_unit(bundled, mutate)
                    core_package_manifest.load(path)
            unit, path = self.indexed_unit(bundled)
            unit['modules'][0]['index']['sha256'] = '0' * 64
            if bundled:
                # Rebuild both manifests to isolate the sidecar member hash.
                with ZipFile(self.root / 'bundle.zip') as archive:
                    members = {name: archive.read(name) for name in archive.namelist() if name != 'manifest.json'}
                unit['modules'][0]['path'] = 'first.json'
                path = self.bundled(unit, members=members)
            else:
                path = self.manifest([unit])
            with self.subTest(bundled=bundled, mutation='declared digest'), self.assertRaisesRegex(ValueError, 'index hash mismatch'):
                core_package_manifest.load(path)

    def test_index_derived_section_extents_and_odd_event_counts(self):
        for size in (0, 1, 511, 512, 513, 2047, 2048, 2049, 16383, 16384, 16385):
            source = b' ' * size
            for events in {0, min(size, 1), min(size, 31), min(size, 32), min(size, 33)}:
                with self.subTest(size=size, events=events):
                    index = self.index_envelope(source, events)
                    reference = dict(path='index.idx', sha256=hashlib.sha256(index).hexdigest())
                    core_package_manifest._validate_index_envelope('control', reference, source, index)

    def test_index_references_are_exact_safe_records(self):
        for reference in (None, {}, {'path': 'index.idx'}, {'path': 'index.idx', 'sha256': 'bad'},
                          {'path': 'index.idx', 'sha256': 'a' * 64, 'extra': 1},
                          *({'path': unsafe, 'sha256': 'a' * 64} for unsafe in
                            ('', '../index.idx', '/index.idx', 'C:/index.idx', 'a\\index.idx', 'a/./index.idx', 'a\x00idx'))):
            with self.subTest(reference=reference):
                unit = self.unit('first')
                unit['modules'][0]['index'] = reference
                with self.assertRaisesRegex(ValueError, 'invalid JSON index reference'):
                    core_package_manifest.load(self.manifest([unit]))

    def test_index_paths_cannot_collide_with_modules_indexes_or_reserved_members(self):
        for bundled in (False, True):
            for collision in ('same module', 'later module', 'other index', 'manifest', 'inputs'):
                if not bundled and collision == 'inputs':
                    continue
                with self.subTest(bundled=bundled, collision=collision):
                    unit, path = self.indexed_unit(bundled)
                    first = unit['modules'][0]
                    other = dict(first, name='Other', path='core/Other.json' if bundled else 'other.json',
                                 index=dict(path='other.idx', sha256='a' * 64))
                    unit['modules'].append(other)
                    first['index']['path'] = {
                        'same module': first['path'], 'later module': other['path'],
                        'other index': other['index']['path'],
                        'manifest': 'manifest.json' if bundled else 'packages.json',
                        'inputs': 'inplace-manifest.json',
                    }[collision]
                    if bundled:
                        first['path'] = 'first.json'
                        path = self.bundled(unit)
                    else:
                        path = self.manifest([unit])
                    with self.assertRaisesRegex(ValueError, 'duplicate.*(JSON/index|ZIP member)'):
                        core_package_manifest.load(path)

    def test_index_inventory_rejects_missing_undeclared_duplicate_or_disagreeing_members(self):
        for mutation in ('missing', 'extra', 'duplicate', 'outer disagreement'):
            with self.subTest(mutation=mutation):
                unit, _ = self.indexed_unit(True)
                bundle = self.root / 'bundle.zip'
                if mutation == 'outer disagreement':
                    unit['modules'][0]['index']['path'] = 'other.idx'
                else:
                    with ZipFile(bundle) as archive:
                        members = [(entry.filename, archive.read(entry)) for entry in archive.infolist()]
                    if mutation == 'missing':
                        members = [(name, body) for name, body in members if not name.endswith('.idx')]
                    elif mutation == 'extra':
                        members.append(('extra.idx', b'extra'))
                    else:
                        members.append(members[-1])
                    with warnings.catch_warnings():
                        warnings.simplefilter('ignore', UserWarning)
                        with ZipFile(bundle, 'w') as archive:
                            for name, body in members:
                                archive.writestr(name, body)
                    unit['bundle']['sha256'] = hashlib.sha256(bundle.read_bytes()).hexdigest()
                with self.assertRaisesRegex(ValueError, 'ZIP entry|bundle manifest disagrees'):
                    core_package_manifest.load(self.manifest([unit]))

    def test_loose_index_symlink_must_remain_inside_manifest_root(self):
        unit, path = self.indexed_unit()
        index = self.root / unit['modules'][0]['index']['path']
        index.unlink()
        with TemporaryDirectory() as elsewhere:
            outside = Path(elsewhere) / 'index.idx'
            outside.write_bytes(b'outside')
            index.symlink_to(outside)
            with self.assertRaisesRegex(ValueError, 'escapes manifest root'):
                core_package_manifest.load(path)

    def test_index_transport_buffers_are_released_before_module_delivery(self):
        _, path = self.indexed_unit(True)
        references, reads = [], []
        read = ZipFile.read

        class WeakBytes(bytearray):
            pass

        def tracked_read(archive, name, *args, **kwargs):
            data = read(archive, name, *args, **kwargs)
            if name.startswith('core/'):
                reads.append(name)
                data = WeakBytes(data)
                references.append(weakref.ref(data))
            return data

        with patch.object(ZipFile, 'read', tracked_read):
            with core_package_manifest.open_modules(path) as modules:
                next(modules)
                self.assertEqual(['core/Shared.json', 'core/Shared.json.idx'], reads)
                self.assertTrue(all(reference() is None for reference in references))
                self.assertEqual([], list(modules))
                self.assertTrue(modules.complete)

    def test_stream_matches_list_apis_and_completes_only_after_exhaustion(self):
        for bundled in (False, True):
            with self.subTest(bundled=bundled):
                path = self.two_module_bundle() if bundled else self.manifest([self.unit('first'), self.unit('second')])
                expected = core_package_manifest.load(path)
                self.assertEqual(expected, core_package_manifest.load_for_audit(path))
                for diagnostic in (False, True):
                    with core_package_manifest.open_modules(path, audit_archives=diagnostic) as modules:
                        self.assertFalse(modules.complete)
                        self.assertIs(modules, iter(modules))
                        self.assertEqual(expected[0], next(modules))
                        self.assertFalse(modules.complete)
                        self.assertEqual(expected[1], next(modules))
                        self.assertFalse(modules.complete)
                        self.assertEqual([], list(modules))
                        self.assertTrue(modules.complete)
                    self.assertEqual([], list(modules))
                    self.assertTrue(modules.complete)
                    modules.close()
                    with self.assertRaisesRegex(ValueError, 'stream is closed'):
                        with modules:
                            pass

    def test_stream_reads_one_member_at_a_time_and_drops_previous_ast(self):
        path = self.two_module_bundle()
        reads, references, buffers = [], [], []
        parse = core_package_manifest.strict_json
        read = ZipFile.read

        class WeakModule(dict):
            pass

        class WeakBytes(bytearray):
            pass

        def tracked_json(data):
            value = parse(data)
            if isinstance(value, dict) and 'bindings' in value:
                value = WeakModule(value)
                references.append(weakref.ref(value))
            return value

        def tracked_read(archive, name, *args, **kwargs):
            if name == 'core/Other.json':
                gc.collect()
                self.assertIsNone(references[0](), 'previous parsed module survived the next member read')
            reads.append(name)
            value = read(archive, name, *args, **kwargs)
            if name.startswith('core/'):
                value = WeakBytes(value)
                buffers.append(weakref.ref(value))
            return value

        with patch.object(core_package_manifest, 'strict_json', side_effect=tracked_json), \
                patch.object(ZipFile, 'read', tracked_read):
            with core_package_manifest.open_modules(path) as modules:
                first = next(modules)
                self.assertEqual(['manifest.json', 'core/Shared.json'], reads)
                self.assertIsNone(buffers[0](), 'raw JSON survived delivery of its parsed module')
                del first
                second = next(modules)
                self.assertEqual(['manifest.json', 'core/Shared.json', 'core/Other.json'], reads)
                del second
                self.assertEqual([], list(modules))
            gc.collect()
            self.assertTrue(all(reference() is None for reference in references))
            self.assertTrue(all(reference() is None for reference in buffers))

    def test_loose_stream_reads_only_the_current_module(self):
        path = self.manifest([self.unit('first'), self.unit('second')])
        reads = []
        read = Path.read_bytes

        def tracked_read(source):
            reads.append(source.name)
            return read(source)

        with patch.object(Path, 'read_bytes', tracked_read):
            with core_package_manifest.open_modules(path) as modules:
                next(modules)
                self.assertEqual(['packages.json', 'first.json'], reads)
                next(modules)
                self.assertEqual(['packages.json', 'first.json', 'second.json'], reads)
                self.assertEqual([], list(modules))

    def test_stream_identity_hashes_exact_manifest_bytes_and_does_not_claim_completion(self):
        path = self.manifest([self.unit('first'), self.unit('second')])
        data = path.read_bytes().replace(b'\n', b'\r\n')
        path.write_bytes(data)
        stream = core_package_manifest.open_modules(path)
        self.assertIsNone(stream.manifest_identity)
        next(stream)
        self.assertEqual(dict(path=str(path.resolve()), sha256=hashlib.sha256(data).hexdigest(),
                              manifest=json.loads(data)), stream.manifest_identity)
        self.assertFalse(stream.complete)
        stream.close()
        self.assertFalse(stream.complete)

    def test_stream_closes_verified_archive_on_completion_early_exit_and_consumer_error(self):
        path = self.two_module_bundle()
        for mode in ('complete', 'break', 'close', 'consumer-error'):
            with self.subTest(mode=mode):
                opened = []

                def tracked_open(stream):
                    archive = ZipFile(stream)
                    opened.append((archive, stream))
                    return archive

                with patch.object(core_package_manifest, 'ZipFile', side_effect=tracked_open):
                    modules = core_package_manifest.open_modules(path)
                    if mode == 'complete':
                        with modules:
                            list(modules)
                    elif mode == 'close':
                        next(modules)
                        modules.close()
                    elif mode == 'break':
                        with self.assertRaisesRegex(ValueError, 'not fully consumed'):
                            with modules:
                                for _ in modules:
                                    break
                    else:
                        with self.assertRaisesRegex(RuntimeError, 'consumer failed'):
                            with modules:
                                next(modules)
                                raise RuntimeError('consumer failed')
                    self.assertEqual(mode == 'complete', modules.complete)
                    self.assertEqual([], list(modules))
                    self.assertEqual(mode == 'complete', modules.complete)
                    self.assertEqual(1, len(opened), 'archive should be verified/opened once')
                    self.assertIsNone(opened[0][0].fp)
                    self.assertTrue(opened[0][1].closed)

    def test_late_invalid_module_closes_stream_and_never_completes(self):
        for bad, message in ((b'{"schema":1,"schema":1}', 'Duplicate JSON key'),
                             (b'{"value":NaN}', 'Invalid JSON constant'),
                             (b'{"value":Infinity}', 'Invalid JSON constant'),
                             (b'{"value":-Infinity}', 'Invalid JSON constant'),
                             (b'{}', 'unit/module/boundary mismatch'),
                             (b'{"value":', 'Expecting value')):
            with self.subTest(bad=bad):
                path = self.two_module_bundle(bad)
                opened = []

                def tracked_open(stream):
                    opened.append(stream)
                    return ZipFile(stream)

                with patch.object(core_package_manifest, 'ZipFile', side_effect=tracked_open):
                    modules = core_package_manifest.open_modules(path, audit_archives=True)
                    with self.assertRaisesRegex(ValueError, message):
                        with modules:
                            self.assertEqual('Shared', next(modules)[1]['module'])
                            next(modules)
                    self.assertFalse(modules.complete)
                    self.assertEqual([], list(modules))
                    self.assertFalse(modules.complete)
                    self.assertTrue(opened[0].closed)
                for loader in (core_package_manifest.load, core_package_manifest.load_for_audit):
                    with self.assertRaisesRegex(ValueError, message):
                        loader(path)

    def test_stream_rejects_late_unit_and_module_hash_errors(self):
        unit = self.unit('first')
        path = self.manifest([unit, unit])
        with self.assertRaisesRegex(ValueError, 'duplicate unit'):
            with core_package_manifest.open_modules(path) as modules:
                next(modules)
                self.assertFalse(modules.complete)
                next(modules)
        self.assertFalse(modules.complete)
        path = self.two_module_bundle()
        manifest = json.loads(path.read_text())
        unit = manifest['units'][0]
        with ZipFile(unit['bundle']['path']) as archive:
            contents = {item.filename: archive.read(item.filename) for item in archive.infolist()}
        inner = json.loads(contents['manifest.json'])
        inner['modules'][1]['sha256'] = unit['modules'][1]['sha256'] = '0' * 64
        contents['manifest.json'] = json.dumps(inner).encode()
        with ZipFile(unit['bundle']['path'], 'w') as archive:
            for name, data in contents.items():
                archive.writestr(name, data)
        unit['bundle']['sha256'] = hashlib.sha256(Path(unit['bundle']['path']).read_bytes()).hexdigest()
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'content hash mismatch'):
            with core_package_manifest.open_modules(path) as modules:
                next(modules)
                next(modules)
        self.assertFalse(modules.complete)

    def test_empty_or_unused_stream_cannot_complete(self):
        path = self.manifest([])
        modules = core_package_manifest.open_modules(path)
        with self.assertRaisesRegex(ValueError, 'no Core modules'):
            with modules:
                list(modules)
        self.assertFalse(modules.complete)
        with self.assertRaisesRegex(ValueError, 'not fully consumed'):
            with core_package_manifest.open_modules(path) as unused:
                pass
        self.assertFalse(unused.complete)

    def test_stream_archive_diagnostic_mode_keeps_execution_and_proof_boundaries(self):
        unit = self.unit('first')
        source = self.root / 'first.json'
        module = json.loads(source.read_text())
        module.update(schema=2, foreign=dict(schema=1, execution='not-linked', files=[],
            stubs=dict(header='', source='original CAPI stub', initializers=[], finalizers=[])))
        source.write_text(json.dumps(module))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        path = self.bundled(unit)
        with self.assertRaisesRegex(ValueError, 'Unsupported foreign execution'):
            with core_package_manifest.open_modules(path) as modules:
                list(modules)
        self.assertFalse(modules.complete)
        with core_package_manifest.open_modules(path, audit_archives=True) as modules:
            self.assertEqual(core_package_manifest.load_for_audit(path), list(modules))
        self.assertTrue(modules.complete)

        module = PackageNativeVariantsTest().module(['WordRep'])
        module['boundary'] = self.boundary
        source = self.root / 'variants.json'
        for corrupt in (False, True):
            if corrupt:
                module['packageNativeLink']['bitcodeHex'] = '4342'
            unit = self.unit('variants', 'Variants')
            source.write_text(json.dumps(module))
            unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
            path = self.bundled(unit)
            for diagnostic in (False, True):
                if corrupt:
                    with self.assertRaisesRegex(ValueError, 'bitcode'):
                        with core_package_manifest.open_modules(path, audit_archives=diagnostic) as modules:
                            list(modules)
                    self.assertFalse(modules.complete)
                else:
                    with core_package_manifest.open_modules(path, audit_archives=diagnostic) as modules:
                        self.assertEqual(1, len(list(modules)))
                    self.assertTrue(modules.complete)

    def test_same_module_name_in_distinct_units_is_unambiguous(self):
        path = self.manifest([self.unit('first'), self.unit('second')])
        self.assertEqual(['first', 'second'], [source['unit'] for _, source in core_package_manifest.load(path)])

    def test_auditor_combines_verified_package_with_pre_tidy_consumer_without_overlay(self):
        unit = self.unit('first')
        original = self.root / 'first.json'
        source = json.loads(original.read_text())
        source['bindings'] = [dict(id='first:Shared.value', name='value', lifted=True,
                                   arity=0, expr=['lit', 'int', '7'])]
        original.write_text(json.dumps(source))
        unit['modules'][0]['sha256'] = hashlib.sha256(original.read_bytes()).hexdigest()
        package = self.bundled(unit)
        consumer = self.root / 'consumer.json'
        consumer.write_text(json.dumps(dict(schema=1, ghc='9.14.1', module='Consumer',
            bindings=[dict(id='root', name='root', arity=0, lifted=True,
                           expr=['var', 'first:Shared.value'])], constructors=[])))
        command = ['python3', '-B', str(Path(__file__).with_name('audit-core.py')),
                   '--package-manifest', str(package), '--entry', 'root', str(consumer)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual({'root', 'first:Shared.value'}, {b['id'] for b in report['reachableBindings']})
        source['bindings'][0]['expr'] = ['lit', 'int', '8']
        conflict = json.loads(consumer.read_text())
        conflict['bindings'].append(source['bindings'][0])
        consumer.write_text(json.dumps(conflict))
        duplicate = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, duplicate.returncode, 'A loose consumer must not replace package originals')
        self.assertIn('duplicate-binding', {issue['code'] for issue in json.loads(duplicate.stdout)['issues']})
        with (self.root / 'bundle.zip').open('ab') as archive:
            archive.write(b'changed')
        corrupted = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, corrupted.returncode, 'Package validation must run before loose composition')
        self.assertIn('bundle hash mismatch', corrupted.stderr)

    def test_linked_foreign_target_must_match_audit_host(self):
        machine = platform.machine().lower()
        arch = {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(machine, machine)
        target = arch + ('-apple-darwin' if platform.system() == 'Darwin' else '-unknown-linux-gnu')
        symbols = ['clock_id', 'clock_time', 'clock_resolution']
        source = 'original C source'
        bitcode = b'BC'
        def scalar(primitive, evaluated):
            return dict(kind='void' if primitive is None else 'address' if primitive == 'AddrRep' else 'long',
                        primReps=[] if primitive is None else [primitive], evaluated=evaluated)
        def call(index, symbol):
            zero = index == 0
            output = 'Word64Rep' if zero else 'Int32Rep'
            return dict(foreignCall=dict(schema=1,
                target=dict(kind='static', unit='base-fixture', isFunction=True, symbol=symbol),
                convention='capi', safety='unsafe', arity=1 if zero else 3,
                suppliedArity=1 if zero else 3,
                argumentReps=([scalar(None, False)] if zero else
                              [scalar('Word64Rep', False), scalar('AddrRep', False), scalar(None, False)]),
                resultRep=dict(kind='unknown', primReps=[output], aggregate='unboxed-tuple',
                               components=[scalar(None, True), scalar(output, True)], evaluated=False)))
        abi = [dict(symbol=symbol, kind='clock-id' if index == 0 else 'clock-buffer')
               for index, symbol in enumerate(symbols)]
        module = dict(unit='base-fixture', module='System.CPUTime.Posix.ClockGetTime',
                      bindings=[call(index, symbol) for index, symbol in enumerate(symbols)],
                      foreign=dict(stubs=dict(header='', source=source, initializers=[], finalizers=[]), files=[]),
                      foreignLink=dict(schema=2, format='llvm-bitcode', unit='base-fixture',
                          module='System.CPUTime.Posix.ClockGetTime', target=target, symbols=symbols, abi=abi,
                          sourceSha256=hashlib.sha256(source.encode()).hexdigest(),
                          bitcodeSha256=hashlib.sha256(bitcode).hexdigest(), bitcodeHex=bitcode.hex()))
        self.assertTrue(core_package_manifest.linked_foreign(module))
        swapped = module | dict(bindings=module['bindings'] +
            [dict(foreignCall=module['bindings'][1]['foreignCall'] |
                dict(target=module['bindings'][1]['foreignCall']['target'] | dict(symbol=symbols[0])))])
        with self.assertRaisesRegex(ValueError, 'symbol ABI'):
            core_package_manifest.linked_foreign(swapped)
        wrong_abi = [(entry | dict(kind='clock-buffer' if index == 0 else 'clock-id'))
                     if index < 2 else entry for index, entry in enumerate(abi)]
        with self.assertRaisesRegex(ValueError, 'symbol ABI'):
            core_package_manifest.linked_foreign(module | dict(foreignLink=module['foreignLink'] | dict(abi=wrong_abi)))
        for broken in (abi[0] | dict(extra='field'), abi[0] | dict(kind=1)):
            with self.assertRaisesRegex(ValueError, 'ABI inventory'):
                core_package_manifest.linked_foreign(module | dict(foreignLink=module['foreignLink'] |
                    dict(abi=[broken] + abi[1:])))
        for invalid in (17, 'riscv64-unknown-linux-gnu'):
            with self.subTest(target=invalid), self.assertRaisesRegex(ValueError, 'target differs'):
                core_package_manifest.linked_foreign(module | dict(foreignLink=module['foreignLink'] | dict(target=invalid)))

    def test_hash_unit_boundary_and_duplicate_unit_must_match(self):
        unit = self.unit('first')
        path = self.manifest([unit])
        self.assertEqual(1, len(core_package_manifest.load(path)))
        with self.subTest('hash'):
            (self.root / 'first.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                core_package_manifest.load(path)
        unit = self.unit('first')
        with self.subTest('unit'):
            source_path = self.root / 'first.json'
            source = json.loads(source_path.read_text())
            source['unit'] = 'spoofed'
            source_path.write_text(json.dumps(source))
            unit['modules'][0]['sha256'] = hashlib.sha256(source_path.read_bytes()).hexdigest()
            path = self.manifest([unit])
            with self.assertRaisesRegex(ValueError, 'unit/module/boundary mismatch'):
                core_package_manifest.load(path)
        unit = self.unit('first')
        with self.subTest('pre-Tidy'):
            unit['modules'][0]['boundary'] = 'optimized-Core-before-Tidy'
            path = self.manifest([unit])
            with self.assertRaisesRegex(ValueError, 'post-Tidy'):
                core_package_manifest.load(path)
        unit = self.unit('first')
        with self.subTest('foreign binding'):
            source_path = self.root / 'first.json'
            source = json.loads(source_path.read_text())
            source['bindings'] = [dict(id='second:Shared.spoofed')]
            source_path.write_text(json.dumps(source))
            unit['modules'][0]['sha256'] = hashlib.sha256(source_path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, 'foreign binding owner'):
                core_package_manifest.load(self.manifest([unit]))
        unit = self.unit('first')
        with self.subTest('duplicate'):
            path = self.manifest([unit, unit])
            with self.assertRaisesRegex(ValueError, 'duplicate unit'):
                core_package_manifest.load(path)

    def test_module_path_cannot_escape_manifest_directory(self):
        unit = self.unit('first')
        unit['modules'][0]['path'] = '../first.json'
        with self.assertRaisesRegex(ValueError, 'stay inside'):
            core_package_manifest.load(self.manifest([unit]))

    def test_bundle_modules_are_read_directly_and_audited_by_exact_unit(self):
        unit = self.unit('first')
        source = self.root / 'first.json'
        document = json.loads(source.read_text())
        document['bindings'] = [dict(id='first:Shared.entry', name='entry', lifted=True,
                                     arity=0, expr=['lit', 'int', '42'])]
        source.write_text(json.dumps(document))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        path = self.bundled(unit)
        source.unlink()  # A loose-file fallback would fail here.
        loaded = core_package_manifest.load(path)
        self.assertEqual(['first:Shared.entry'], [binding['id'] for _, module in loaded
                          for binding in module['bindings']])
        self.assertIn('bundle.zip!/core/Shared.json', loaded[0][0])
        command = ['python3', str(Path(__file__).with_name('audit-core.py')),
                   '--package-manifest', str(path), '--entry', 'first:Shared.entry']
        result = subprocess.run(command, text=True, capture_output=True, check=True)
        self.assertTrue(json.loads(result.stdout)['accepted'])

    def test_bundle_validates_optional_build_inputs_without_rebuilding_them(self):
        unit = self.unit('first')
        inputs = dict(format='thc-core-build-inputs', schema=1, unit='first',
                      buildKey='a' * 64, exportKey='b' * 64,
                      nativeArtifacts=[dict(path='native/lib.a', sha256='c' * 64)])
        path = self.bundled(unit, build_inputs=inputs)
        self.assertEqual(1, len(core_package_manifest.load(path)))
        unit = self.unit('first')
        with self.assertRaisesRegex(ValueError, 'build-inputs hash mismatch'):
            core_package_manifest.load(self.bundled(unit, build_inputs=inputs, input_hash='0' * 64))

    def test_bundle_hash_inventory_members_and_core_identity_fail_closed(self):
        unit = self.unit('first')
        path = self.bundled(unit)
        bundle = self.root / 'bundle.zip'
        bundle.write_bytes(bundle.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'bundle hash mismatch'):
            core_package_manifest.load(path)

        unit = self.unit('first')
        with self.assertRaisesRegex(ValueError, 'missing, or extra ZIP entry'):
            core_package_manifest.load(self.bundled(unit, members={}))
        unit = self.unit('first')
        with self.assertRaisesRegex(ValueError, 'missing, or extra ZIP entry'):
            core_package_manifest.load(self.bundled(unit, members={
                'core/Shared.json': (self.root / 'first.json').read_bytes(),
                '../escape': b'no extraction'}))

        unit = self.unit('first')
        inner = dict(format='thc-core-bundle', schema=1, unit='wrong',
                     buildKey='a' * 64, exportKey='b' * 64, modules=[dict(unit['modules'][0])])
        inner['modules'][0]['path'] = 'core/Shared.json'
        with self.assertRaisesRegex(ValueError, 'bundle manifest disagrees'):
            core_package_manifest.load(self.bundled(unit, inner=inner))

        unit = self.unit('first')
        self.bundled(unit)
        unit['modules'][0]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'bundle manifest disagrees'):
            core_package_manifest.load(self.manifest([unit]))
        unit = self.unit('first')
        unit['modules'][0]['sha256'] = '0' * 64
        path = self.bundled(unit)
        with self.assertRaisesRegex(ValueError, 'content hash mismatch'):
            core_package_manifest.load(path)

    def test_bundle_rejects_duplicate_and_unsafe_member_names(self):
        unit = self.unit('first')
        path = self.bundled(unit)
        bundle = self.root / 'bundle.zip'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with ZipFile(bundle, 'a') as archive:
                archive.writestr('core/Shared.json', b'duplicate')
        unit['bundle']['sha256'] = hashlib.sha256(bundle.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'duplicate, unsafe'):
            core_package_manifest.load(self.manifest([unit]))
        for unsafe in ('../Shared.json', '/absolute.json', 'core/../Shared.json', 'core\\Shared.json'):
            with self.subTest(unsafe=unsafe):
                unit = self.unit('first')
                path = self.bundled(unit)
                unit['modules'][0]['path'] = unsafe
                with self.assertRaisesRegex(ValueError, 'unsafe ZIP member path'):
                    core_package_manifest.load(self.manifest([unit]))

    def test_bundle_rejects_corrupt_zip_and_wrong_core_unit(self):
        unit = self.unit('first')
        path = self.bundled(unit)
        bundle = self.root / 'bundle.zip'
        bundle.write_bytes(b'not a ZIP')
        unit['bundle']['sha256'] = hashlib.sha256(bundle.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'invalid ZIP bundle'):
            core_package_manifest.load(self.manifest([unit]))

        unit = self.unit('first')
        source = self.root / 'first.json'
        document = json.loads(source.read_text())
        document['unit'] = 'another-unit'
        source.write_text(json.dumps(document))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'unit/module/boundary mismatch'):
            core_package_manifest.load(self.bundled(unit))

    def test_archive_only_foreign_core_reports_execution_gap_not_identity_mismatch(self):
        unit = self.unit('first')
        source = self.root / 'first.json'
        module = json.loads(source.read_text())
        module['schema'] = 2
        module['foreign'] = dict(schema=1, execution='not-linked',
                                 stubs=dict(header='', source='foreign stub', initializers=[], finalizers=[]),
                                 files=[])
        module['bindings'] = [dict(id='first:Shared.entry', name='entry', lifted=True,
                                   arity=0, expr=['lit', 'int', '7'])]
        source.write_text(json.dumps(module))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'Unsupported foreign execution for first:Shared.*typed foreign registration.*callback'):
            core_package_manifest.load(self.bundled(unit))

        result = subprocess.run(['python3', str(Path(__file__).with_name('audit-core.py')),
                                 '--entry', 'first:Shared.entry', str(source)], text=True, capture_output=True)
        self.assertEqual(1, result.returncode)
        issues = json.loads(result.stdout)['issues']
        self.assertTrue(any(issue['code'] == 'module-format' and
                            'Unsupported foreign execution for first:Shared' in issue['detail']
                            for issue in issues), issues)

        module['unit'] = 'wrong'
        unit = self.unit('first')
        source.write_text(json.dumps(module))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'unit/module/boundary mismatch'):
            core_package_manifest.load(self.bundled(unit))

        module['unit'] = 'first'
        module['schema'] = 1  # Renumbering must not silently discard registration obligations.
        unit = self.unit('first')
        source.write_text(json.dumps(module))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'unsupported Core module schema/foreign metadata'):
            core_package_manifest.load(self.bundled(unit))

    def test_audit_only_foreign_bundle_never_accepts_and_still_checks_reachable_core(self):
        def archive(expression, foreign=None):
            unit = self.unit('first')
            source = self.root / 'first.json'
            module = json.loads(source.read_text())
            module['schema'] = 2
            module['foreign'] = foreign if foreign is not None else dict(
                schema=1, execution='not-linked',
                stubs=dict(header='', source='foreign stub', initializers=[], finalizers=[]), files=[])
            module['bindings'] = [dict(id='first:Shared.entry', name='entry', lifted=True,
                                       arity=0, expr=expression)]
            source.write_text(json.dumps(module))
            unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
            return self.bundled(unit)

        def audit(path, entry='first:Shared.entry', status=1):
            output = self.root / 'audit.json'
            result = subprocess.run(['python3', str(Path(__file__).with_name('audit-core.py')),
                                     '--package-manifest', str(path), '--entry', entry,
                                     '--output', str(output)], text=True, capture_output=True)
            self.assertEqual(status, result.returncode, result.stderr)
            return json.loads(output.read_text())

        safe = archive(['lit', 'int', '42'])
        with self.assertRaisesRegex(ValueError, 'Unsupported foreign execution'):
            core_package_manifest.load(safe)  # The normal loader remains strict.
        report = audit(safe)
        self.assertFalse(report['accepted'])
        self.assertEqual(['first:Shared.entry'], [item['id'] for item in report['reachableBindings']])
        self.assertEqual({'module-format'}, {issue['code'] for issue in report['issues']})
        self.assertIn('execution=not-linked', report['issues'][0]['detail'])

        archived_unit = json.loads(safe.read_text())['units'][0]
        other = self.unit('second')
        other_source = self.root / 'second.json'
        document = json.loads(other_source.read_text())
        document['bindings'] = [dict(id='second:Shared.main', name='main', lifted=True,
                                     arity=0, expr=['lit', 'int', '7'])]
        other_source.write_text(json.dumps(document))
        other['modules'][0]['sha256'] = hashlib.sha256(other_source.read_bytes()).hexdigest()
        unused = audit(self.manifest([archived_unit, other]), 'second:Shared.main', status=0)
        self.assertTrue(unused['accepted'], unused['issues'])
        self.assertEqual(['second:Shared.main'], [item['id'] for item in unused['reachableBindings']])

        unsupported = archive(['app', ['prim', 'unsupported#'], [['lit', 'int', '1']], [False]])
        report = audit(unsupported)
        self.assertFalse(report['accepted'])
        self.assertLessEqual({'module-format', 'unsupported-primitive'},
                             {issue['code'] for issue in report['issues']})
        self.assertEqual(['first:Shared.entry'], [item['id'] for item in report['reachableBindings']])

        malformed = dict(schema=1, execution='not-linked',
                         stubs=dict(header='', source='', initializers=[], finalizers=[]), files=[])
        with self.assertRaisesRegex(ValueError, 'malformed foreign archive'):
            core_package_manifest.load_for_audit(archive(['lit', 'int', '42'], malformed))

        for bad in (
                dict(schema=1, execution='not-linked',
                     stubs=dict(header='valid header', source=17, initializers=[], finalizers=[]),
                     files=[]),
                dict(schema=True, execution='not-linked', stubs=None,
                     files=[dict(language='c', source='int f;', extension='c')]),
                dict(schema=1, execution='not-linked', stubs=None,
                     files=[dict(language='', source='int f;', extension='c')]),
                dict(schema=1, execution='not-linked',
                     stubs=dict(header='', source='', initializers=[dict(
                         isInitializer=False, unit='first', module='Shared', name='init')],
                         finalizers=[]), files=[])):
            with self.subTest(foreign=bad), self.assertRaises(ValueError):
                core_package_manifest.load_for_audit(archive(['lit', 'int', '42'], bad))

        valid = archive(['lit', 'int', '42'])
        unit = json.loads(valid.read_text())['units'][0]
        unit['bundle']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'bundle hash mismatch'):
            core_package_manifest.load_for_audit(self.manifest([unit]))

    def test_audit_keeps_strict_missing_global_closure_for_bundle(self):
        unit = self.unit('first')
        source = self.root / 'first.json'
        document = json.loads(source.read_text())
        document['bindings'] = [dict(id='first:Shared.entry', name='entry', lifted=True,
                                     arity=0, expr=['var', 'second:Other.absent'])]
        source.write_text(json.dumps(document))
        unit['modules'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        path = self.bundled(unit)
        command = ['python3', str(Path(__file__).with_name('audit-core.py')),
                   '--package-manifest', str(path), '--entry', 'first:Shared.entry']
        result = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(1, result.returncode)
        report = json.loads(result.stdout)
        self.assertFalse(report['accepted'])
        self.assertEqual(1, report['summary']['missingGlobals'])
        self.assertEqual('second:Other.absent', report['missingGlobals'][0]['id'])


class RetainedModuleProbeTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('THC_LOADER_PROBE_MANIFEST'), 'retained package probe not requested')
    def test_largest_retained_module_validation_only(self):
        """Opt-in loader component probe, never a manifest or reachability audit.

        Run under an external memory limit/resource lease. Preserve the entire
        source manifest and catalogue; do not manufacture a one-module manifest.
        The output explicitly records that all other modules remain unaudited.
        """
        manifest_path = Path(os.environ['THC_LOADER_PROBE_MANIFEST'])
        output = Path(os.environ['THC_LOADER_PROBE_OUTPUT'])
        self.assertFalse(output.exists(), 'preserve previous probe evidence')
        manifest_bytes = manifest_path.read_bytes()
        manifest = core_package_manifest.strict_json(manifest_bytes.decode('utf-8'))
        self.assertEqual(core_package_manifest.FORMAT, manifest['format'])
        self.assertEqual('9.14.1', manifest['ghc'])
        inventory, candidates = [], []
        root = manifest_path.resolve().parent
        for unit in manifest['units']:
            bundle = unit.get('bundle')
            if bundle is not None:
                with ZipFile(bundle['path']) as archive:
                    sizes = {record['path']: archive.getinfo(record['path']).file_size for record in unit['modules']}
            else:
                sizes = {}
                for record in unit['modules']:
                    relative = Path(record['path'])
                    self.assertFalse(relative.is_absolute() or '..' in relative.parts)
                    artifact = (root / relative).resolve()
                    self.assertTrue(artifact.is_relative_to(root))
                    sizes[record['path']] = artifact.stat().st_size
            inventory.append(dict(unit=unit['id'], bundle=bundle,
                modules=[dict(record, bytes=sizes[record['path']]) for record in unit['modules']]))
            candidates.extend((sizes[record['path']], unit, record) for record in unit['modules'])
        size, unit, selected = max(candidates, key=lambda entry: entry[0])
        receipt = dict(schema=1, phase='largest-module-validation-only', accepted=False,
            manifestComplete=False, reachabilityAudited=False,
            manifest=dict(path=str(manifest_path), sha256=hashlib.sha256(manifest_bytes).hexdigest()),
            inventory=inventory, selected=dict(unit=unit['id'], module=selected, bytes=size),
            selectedModuleValidated=False)
        store_path = os.environ.get('THC_LOADER_PROBE_STORE')
        if store_path:
            receipt.update(selectedModuleIndexed=False, storeComplete=False, storePath=store_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(receipt, indent=2) + '\n')
        # This component verifies the selected bundle's *whole* hash, inventory
        # and build-input proof. It is deliberately not a partially accepted
        # open_modules stream. No normal loader/auditor completion is claimed.
        selected_members = (core_package_manifest._iter_bundle_modules(manifest_path, unit, unit['modules'])
                            if 'bundle' in unit else core_package_manifest._iter_loose_modules(root, unit['modules']))
        with closing(selected_members) as members:
            for record, source, data in members:
                self.assertEqual(record['sha256'], hashlib.sha256(data).hexdigest())
                if record != selected:
                    del data
                    continue
                self.assertEqual(size, len(data))
                module = core_package_manifest._validated_module(manifest_path, unit['id'], record, data, True)
                receipt.update(selectedModuleValidated=True, source=source,
                    bindingCount=len(module['bindings']), constructorCount=len(module.get('constructors', [])))
                del data
                if store_path:
                    # Component-only indexing, not a sealed catalogue. The full
                    # manifest has NOT been consumed or cross-module audited.
                    import importlib.util
                    import resource
                    import sqlite3
                    audit_path = Path(__file__).with_name('audit-core.py')
                    spec = importlib.util.spec_from_file_location('index_probe_audit', audit_path)
                    audit_core = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(audit_core)
                    receipt.update(phase='largest-module-indexing',
                        python=platform.python_version(), sqlite=sqlite3.sqlite_version,
                        validationMaxRssKiB=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                        storeToolSha256=hashlib.sha256(audit_path.read_bytes()).hexdigest())
                    output.write_text(json.dumps(receipt, indent=2) + '\n')
                    store = audit_core.AuditStore(store_path,
                        dict(manifest=receipt['manifest'], source=source, unit=unit['id'], module=record,
                             phase='incomplete-selected-module-probe'))
                    try:
                        store.put_record('module', source,
                            {key: value for key, value in module.items() if key not in ('bindings', 'constructors')})
                        for binding in module['bindings']:
                            self.assertIsNone(store.put_binding(source, binding))
                        for constructor in module.get('constructors', []):
                            store.put_record('constructors', constructor['id'], constructor)
                        receipt.update(indexedBindings=len(store.bindings),
                            indexedConstructors=len(store.records('constructors')),
                            largestExpressionBytes=store._one('SELECT max(length(expression)) FROM bindings')[0],
                            expressionLoads=store.expression_loads, cachedExpressionBytes=store.cached_expression_bytes)
                        self.assertEqual(receipt['bindingCount'], receipt['indexedBindings'])
                        self.assertFalse(store.complete)
                    finally:
                        store.close()
                    receipt.update(selectedModuleIndexed=True, phase='largest-module-indexed-incomplete',
                        storeBytes=Path(store_path).stat().st_size,
                        indexMaxRssKiB=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
                del module
                break
        self.assertTrue(receipt['selectedModuleValidated'])
        output.write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps({key: value for key, value in receipt.items() if key != 'inventory'}))


if __name__ == '__main__':
    unittest.main()
