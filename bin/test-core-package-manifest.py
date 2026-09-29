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
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import warnings
import weakref
from zipfile import ZipFile

import core_package_manifest


class DeepJsonTest(unittest.TestCase):
    def setUp(self):
        # New Python releases can decode deeper containers in C. Force the
        # same fallback exercised by supported older interpreters.
        raw_decode = json.JSONDecoder.raw_decode
        dumps = json.dumps
        def bounded_decode(decoder, text, idx=0):
            if text[idx:idx + 1] in ('[', '{'):
                raise RecursionError()
            return raw_decode(decoder, text, idx)
        def bounded_dumps(value, **kwargs):
            if isinstance(value, (dict, list, tuple)):
                raise RecursionError()
            return dumps(value, **kwargs)
        self.stdlib_dumps = dumps
        self.raw_decode = raw_decode
        for target, name, replacement in ((json.JSONDecoder, 'raw_decode', bounded_decode),
                                           (json, 'dumps', bounded_dumps)):
            patcher = patch.object(target, name, replacement)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_deep_mixed_containers_keep_strict_admission_and_exact_bytes(self):
        text = '{"child":[' * 1200 + '"\\u20ac"' + ']}' * 1200
        value = core_package_manifest.strict_json(text)
        self.assertEqual(text, core_package_manifest.json_dumps(value))
        self.assertTrue(core_package_manifest._same_json_value(value, core_package_manifest.strict_json(text)))
        wrapped = '{"label":"€","bindings":' + text + ',"tail":false}'
        start, end = core_package_manifest._bindings_span(wrapped)
        self.assertEqual(text.encode(), wrapped.encode()[start:end])
        for leaf in ('{"x":1,"x":2}', 'NaN', 'Infinity', '{"x":}', '[0,]', '{"x":0,}'):
            with self.subTest(leaf=leaf), self.assertRaises(ValueError):
                core_package_manifest.strict_json('[' * 1200 + leaf + ']' * 1200)

    def test_compact_codec_matches_stdlib_and_retains_raw_cursor(self):
        values = [None, True, False, 0, -0.0, 1e30, float('inf'), '€' + chr(0xd800),
                  [1, {'escaped': chr(34) + chr(92) + chr(10), 'nul': chr(0)}],
                  {1: 'integer', False: 'boolean', None: 'null', 'é': [2, 3]}]
        for value in values:
            with self.subTest(value=value):
                expected = self.stdlib_dumps(value, ensure_ascii=True, separators=(',', ':'))
                self.assertEqual(expected, core_package_manifest.json_dumps(value))
                self.assertEqual(self.raw_decode(json.JSONDecoder(), expected)[0], core_package_manifest.json_loads(expected))
        text = ' [1,{"x":true}] rest'
        self.assertEqual(self.raw_decode(json.JSONDecoder(), text, 1), core_package_manifest.json_raw_decode(text, 1))
        for text in ('', '[] x', '[,]', '{1:2}', '{"a" 2}', '[1 2]'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                core_package_manifest.strict_json(text)

    def test_cyclic_storage_and_typed_projection_equality_remain_checked(self):
        cycle = []
        cycle.append(cycle)
        with self.assertRaisesRegex(ValueError, 'Circular reference'):
            core_package_manifest.json_dumps(cycle)
        self.assertFalse(core_package_manifest._same_json_value([True], [1]))
        self.assertFalse(core_package_manifest._same_json_value({'x': []}, {'x': [0]}))


class ManagedImportTypeTest(unittest.TestCase):
    """Scoped metadata controls, without registering or executing native code."""
    @staticmethod
    def tycon(name, *arguments):
        return dict(kind='tycon', name=dict(unit='ghc-internal', module='GHC.Internal.Types',
                    occurrence=name, namespace='type'), arguments=list(arguments))

    def module(self, declared=None):
        variable = dict(kind='bound-variable', index=0)
        if declared is None:
            declared = dict(kind='forall', binderKind=self.tycon('Type'), body=dict(kind='function',
                multiplicity=self.tycon('Many'), argument=self.tycon('Ptr', variable), result=self.tycon('IO', self.tycon('Unit'))))
        foreign = dict(schema=1, execution='not-linked', files=[], stubs=dict(header='',
            source='void capi_wrapper(void *p) { free(p); }', initializers=[], finalizers=[]))
        declaration = dict(binder=dict(unit='fixture', module='Imports', occurrence='capi_free', namespace='value'),
            header='stdlib.h', symbol='free', unit=None, isFunction=True, convention='capi', safety='unsafe',
            declaredType=declared, normalizedType=declared, normalizationRole='representational',
            emitted=dict(symbol='capi_wrapper', unit=None, convention='capi', safety='unsafe',
                         arguments=['AddrRep', 'void'], result=['void']))
        return dict(schema=2, ghc='9.14.1', unit='fixture', module='Imports', foreign=foreign, bindings=[],
            staticForeignImportStubs=dict(schema=1, scope='retained-static-import-products', execution='not-linked',
                profile='ghc-9.14.1-thc-only-static-c-imports-v1', unit='fixture', module='Imports', status='verified',
                wordBits=64, expectedForeign=foreign, imports=[declaration], expectedCalls=[]))

    def test_scoped_forall_keeps_types_and_existing_scalar_provenance_checks(self):
        original = self.module()
        self.assertTrue(core_package_manifest.managed_import_stubs(original))
        self.assertTrue(core_package_manifest.managed_import_stubs(self.module(dict(kind='forall',
            binderKind=self.tycon('Type'), body=dict(kind='forall', binderKind=dict(kind='bound-variable', index=0),
                body=self.tycon('Ptr', dict(kind='bound-variable', index=1)))))))
        for change in ('role', 'owner', 'carrier', 'state', 'inventory'):
            with self.subTest(change=change):
                changed = json.loads(json.dumps(original))
                proof = changed['staticForeignImportStubs']
                item = proof['imports'][0]
                if change == 'role': item['normalizationRole'] = 'phantom'
                elif change == 'owner': item['binder']['unit'] = 'other'
                elif change == 'carrier': item['emitted']['arguments'][0] = 'BoxedRep (Just Lifted)'
                elif change == 'state': item['emitted']['arguments'].pop()
                else: proof['expectedCalls'] = [{}]
                with self.assertRaises(ValueError): core_package_manifest.managed_import_stubs(changed)

    def test_malformed_kind_scopes_and_variable_records_are_not_erased(self):
        typ = self.tycon('Type')
        body = self.tycon('Ptr', dict(kind='bound-variable', index=0))
        invalid = [dict(kind='bound-variable', index=0),
            dict(kind='forall', body=body), dict(kind='forall', binderKind=typ, body=body, representation='AddrRep'),
            dict(kind='forall', binderKind=dict(kind='bound-variable', index=0), body=body),
            dict(kind='forall', binderKind=dict(kind='unknown'), body=body),
            dict(kind='forall', binderKind='Type', body=body),
            dict(kind='forall', binderKind=typ, body=dict(kind='cast', type=body))]
        invalid += [dict(kind='forall', binderKind=typ, body=self.tycon('Ptr', dict(kind='bound-variable', index=index)))
                    for index in (-1, 1, 2**63, True, 0.0, '0', None)]
        invalid += [dict(kind='forall', binderKind=typ, body=dict(kind='bound-variable', index=0, representation='AddrRep'))]
        for bad in invalid:
            for field in ('declaredType', 'normalizedType'):
                with self.subTest(bad=bad, field=field):
                    changed = self.module()
                    changed['staticForeignImportStubs']['imports'][0][field] = bad
                    with self.assertRaises(ValueError): core_package_manifest.managed_import_stubs(changed)


class PackageFinalizerProvenanceTest(ManagedImportTypeTest):
    @staticmethod
    def named(module, name, *arguments):
        return dict(kind='tycon', name=dict(unit='ghc-internal', module=module, occurrence=name,
                                          namespace='type'), arguments=list(arguments))

    def finalizer(self):
        unit = self.named('GHC.Internal.Tuple', 'Unit')
        pointer = self.named('GHC.Internal.Ptr', 'Ptr', unit)
        function = dict(kind='function', multiplicity=self.tycon('Many'), argument=pointer,
                        result=self.named('GHC.Internal.Types', 'IO', unit))
        signature = self.named('GHC.Internal.Ptr', 'FunPtr', function)
        original = self.module()
        proof = original['staticForeignImportStubs']
        proof.update(schema=2, addresses=[dict(binder=dict(unit='fixture', module='Imports',
            occurrence='finalizer', namespace='value'), header=None, symbol='original_finalizer', isFunction=True,
            convention='capi', declaredType=signature, normalizedType=signature,
            normalizationRole='representational', callback=dict(arguments=['AddrRep'], result='void'))])
        return original

    def test_typed_pointer_callback_is_inert_until_native_link_proves_it(self):
        self.assertTrue(core_package_manifest.managed_import_stubs(self.finalizer()))

    def test_wrapper_abi_projects_its_exact_normalized_callback_type(self):
        scalar = self.named('GHC.Internal.Int', 'Int32')
        callback = dict(kind='function', multiplicity=self.tycon('Many'), argument=scalar,
            result=self.named('GHC.Internal.Types', 'IO', scalar))
        wrapper = dict(kind='function', multiplicity=self.tycon('Many'), argument=callback,
            result=self.named('GHC.Internal.Types', 'IO', self.named('GHC.Internal.Ptr', 'FunPtr', callback)))
        original = self.module()
        proof = original['staticForeignImportStubs']
        proof.update(schema=3, addresses=[], wrappers=[dict(
            binder=dict(unit='fixture', module='Imports', occurrence='wrap', namespace='value'),
            helper='actual_helper', convention='ccall', declaredType=wrapper, normalizedType=wrapper,
            normalizationRole='representational', arguments=[scalar], result=scalar, effect='io', typeString='W')])
        self.assertTrue(core_package_manifest.managed_import_stubs(original))
        for field, value in [('arguments', []), ('result', self.named('GHC.Internal.Types', 'Int')),
                             ('effect', 'pure'), ('helper', 'bad label'), ('typeString', ''),
                             ('binder', dict(unit='other', module='Imports', occurrence='wrap', namespace='value'))]:
            changed = json.loads(json.dumps(original))
            changed['staticForeignImportStubs']['wrappers'][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                core_package_manifest.managed_import_stubs(changed)

    def test_parallel_import_proof_cannot_hide_invalid_selected_stub_addresses(self):
        for parallel in (self.module()['staticForeignImportStubs'], self.finalizer()['staticForeignImportStubs']):
            valid = self.finalizer()
            valid['staticForeignImports'] = parallel
            self.assertTrue(core_package_manifest.managed_import_stubs(valid))
            address = valid['staticForeignImportStubs']['addresses'][0]
            for addresses in (None, [], ['not an address'],
                    [dict(address, binder=dict(address['binder'], unit='other'))],
                    [dict(address, callback=dict(arguments=['AddrRep', 'AddrRep'], result='void'))]):
                with self.subTest(parallel_schema=parallel['schema'], addresses=addresses):
                    changed = json.loads(json.dumps(valid))
                    changed['staticForeignImportStubs']['addresses'] = addresses
                    with self.assertRaises(ValueError): core_package_manifest.managed_import_stubs(changed)

    def test_callback_profile_cannot_replace_the_actual_normalized_signature(self):
        for change in ('integer-parameter', 'integer-result', 'two-parameters', 'pure-result', 'fake-unit',
                       'fake-pointer', 'fake-funptr', 'data-address', 'environment', 'duplicate-binder'):
            with self.subTest(change=change):
                module = json.loads(json.dumps(self.finalizer()))
                address = module['staticForeignImportStubs']['addresses'][0]
                normalized = address['normalizedType']
                function = normalized['arguments'][0]
                integer = self.named('GHC.Internal.Int', 'Int32')
                if change == 'integer-parameter': function['argument'] = integer
                elif change == 'integer-result': function['result']['arguments'] = [integer]
                elif change == 'two-parameters': function['result'] = dict(kind='function',
                    multiplicity=self.tycon('Many'), argument=function['argument'], result=function['result'])
                elif change == 'pure-result': function['result'] = function['result']['arguments'][0]
                elif change == 'fake-unit': function['result']['arguments'][0]['name']['unit'] = 'impostor'
                elif change == 'fake-pointer': function['argument']['name']['module'] = 'Other'
                elif change == 'fake-funptr': normalized['name']['unit'] = 'impostor'
                elif change == 'data-address': address['isFunction'] = False
                elif change == 'environment': address['callback']['arguments'].append('AddrRep')
                else: module['staticForeignImportStubs']['addresses'].append(dict(address, symbol='other'))
                with self.assertRaises(ValueError): core_package_manifest.managed_import_stubs(module)


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

    def test_ordinary_call_proof_cannot_authorize_a_marked_finalizer(self):
        import importlib.util
        root = Path(__file__).resolve().parent
        spec = importlib.util.spec_from_file_location('finalizer_authority_audit', root / 'audit-core.py')
        audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(audit)
        capabilities = json.loads((root / 'core-capabilities.json').read_text())
        ordinary = self.module(['AddrRep'])
        link, proof = ordinary['packageNativeLink'], ordinary['staticForeignImports']
        entry = link['abi'][0]['entry']
        link.update(schema=2, finalizers=[entry]); link['abi'][0]['result'] = 'void'
        declaration = PackageFinalizerProvenanceTest().finalizer()['staticForeignImportStubs']['addresses'][0]
        declaration.update(symbol='read_bytes', binder=dict(unit='variants', module='Callbacks', occurrence='cleanup', namespace='value'))
        call = proof['imports'][0]
        call['emitted']['result'] = ['void']
        call['declaredType'] = call['normalizedType'] = declaration['normalizedType']['arguments'][0]
        self.assertEqual(set(), core_package_manifest.package_scalar_link(ordinary)[1])
        self.assertFalse(audit.Audit([('ordinary', ordinary)], capabilities).run([])['accepted'])
        retained = json.loads(json.dumps(ordinary))
        retained['module'] = 'Internal'
        del retained['staticForeignImports']
        self.assertEqual(set(), core_package_manifest.package_scalar_link(retained)[1],
                         'unit-wide link attachment cannot prove a finalizer')

        # Removing the finalizer role preserves ordinary call admission.
        unmarked = json.loads(json.dumps(ordinary))
        unmarked['packageNativeLink']['schema'] = 1
        del unmarked['packageNativeLink']['finalizers']
        self.assertEqual({entry}, core_package_manifest.package_scalar_link(unmarked)[1])

        typed = json.loads(json.dumps(ordinary))
        typed['module'] = 'Callbacks'
        typed['staticForeignImports'].update(module='Callbacks', schema=2, imports=[], addresses=[declaration])
        self.assertEqual({entry}, core_package_manifest.package_scalar_link(typed)[1])
        mismatch = json.loads(json.dumps(typed))
        mismatch['staticForeignImports']['addresses'][0]['symbol'] = 'different_finalizer'
        with TemporaryDirectory() as directory:
            for index, (modules, accepted) in enumerate((([ordinary], False), ([ordinary, mismatch], False),
                    ([ordinary, typed], True), ([typed, ordinary], True), ([retained], False),
                    ([retained, mismatch], False), ([retained, typed], True), ([typed, retained], True))):
                inputs = [(str(position), module) for position, module in enumerate(modules)]
                self.assertEqual(accepted, audit.Audit(inputs, capabilities).run([])['accepted'])
                with audit.AuditStore(Path(directory) / (str(index) + '.sqlite'), {}) as store:
                    self.assertEqual(accepted, audit.Audit(iter(inputs), capabilities, store=store).run([])['accepted'])

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

    def test_retired_partial_native_protocol_is_rejected(self):
        module = self.module(['AddrRep', 'ByteArray#'])
        original = module['packageNativeLink']
        with self.assertRaises(ValueError):
            core_package_manifest.package_scalar_link(dict(module,
                packageNativeLink=dict(original, availableEntries=[original['abi'][0]['entry']])))
        with self.assertRaises(ValueError):
            core_package_manifest.package_native_archive(dict(module, packageNativeArchive=dict(entryResolution={})))
        call = dict(original['abi'][0], arguments=[], result='AddrRep')
        address = dict(call, entry=original['abi'][1]['entry'])
        linked = dict(module, packageNativeLink=dict(original, abi=[call, address], dataSymbols=[address['entry']]))
        del linked['staticForeignImports']
        self.assertEqual({call['entry'], address['entry']}, core_package_manifest.package_scalar_link(linked)[1])

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

    def test_conflicts_order_and_duplicates_still_reject(self):
        for reps in (['AddrRep', 'WordRep'],
                     ['ByteArray#', 'AddrRep'], ['AddrRep', 'AddrRep']):
            with self.subTest(reps=reps), self.assertRaises(ValueError):
                core_package_manifest.package_scalar_link(self.module(reps))

    def test_mutability_variants_are_distinct_adapters_not_a_component_rejection(self):
        link, proved = core_package_manifest.package_scalar_link(self.module(['ByteArray#', 'MutableByteArray#']))
        self.assertEqual(2, len(proved))
        self.assertEqual([['ByteArray#'], ['MutableByteArray#']], [entry['arguments'] for entry in link['abi']])

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

    def test_deep_core_control_summary_preserves_exact_primitive_detection(self):
        for leaf, expected in ((['prim', 'prompt#'], True), (['prim', 'control0#'], True),
                               (['lit', 'string', 'prompt#'], False), (['var', 'control0#'], False)):
            with self.subTest(leaf=leaf):
                expression = leaf
                for _ in range(2000):
                    expression = ['let', 'nonrec', [dict(expr=expression)], ['void']]
                module = dict(module='Deep', bindings=[dict(id='unit:Deep.f', expr=expression)])
                summary = dict(containsDelimitedControl=expected, registrationObligations=False,
                               mainAlias=False, packageScalarDeclarations=False)
                core_package_manifest._check_unit_summaries('deep-core', summary, module)
                with self.assertRaisesRegex(ValueError, 'containsDelimitedControl summary'):
                    core_package_manifest._check_unit_summaries('deep-core',
                        summary | dict(containsDelimitedControl=not expected), module)

    def test_direct_unit_delivers_the_same_once_decoded_original_module(self):
        unit = self.direct_unit()
        original_bytes = Path(unit['json']['path']).read_bytes()
        originals = {original_bytes[item['start']:item['end']].decode('utf-8'): item['name']
                     for item in unit['modules']}
        parse, decoded = core_package_manifest.strict_json, {}
        def tracked(data):
            value = parse(data)
            if data in originals:
                name = originals[data]
                self.assertNotIn(name, decoded, 'original module decoded more than once')
                decoded[name] = value
            return value
        with patch.object(core_package_manifest, 'strict_json', side_effect=tracked):
            modules = core_package_manifest.load_for_audit(self.manifest([unit]))
        self.assertEqual({'Zulu', 'Alpha'}, decoded.keys())
        for _, module in modules:
            self.assertIs(decoded[module['module']], module)
        self.assertEqual(original_bytes, Path(unit['json']['path']).read_bytes())

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

    def test_retired_indexes_are_rejected_before_artifact_access(self):
        for bundled in (False, True):
            for reference in (None, {}, {'path': 'missing.idx', 'sha256': '0' * 64}):
                with self.subTest(bundled=bundled, reference=reference):
                    unit = self.unit('first')
                    unit['modules'][0]['index'] = reference
                    path = self.bundled(unit) if bundled else self.manifest([unit])
                    with self.assertRaisesRegex(ValueError, 'sidecars are no longer supported'):
                        core_package_manifest.load(path)

    def test_transport_buffers_are_released_before_module_delivery(self):
        path = self.bundled(self.unit('first'))
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
                self.assertEqual(['core/Shared.json'], reads)
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
