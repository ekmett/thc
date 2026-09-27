#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Package manifests reject stale, mismatched and pre-Tidy Core artifacts."""

import hashlib
import json
from pathlib import Path
import platform
import subprocess
from tempfile import TemporaryDirectory
import unittest
import warnings
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


if __name__ == '__main__':
    unittest.main()
