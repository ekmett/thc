#!/usr/bin/env python3
"""Mutation tests for the gate, using synthetic parser data, never runtime evidence."""
from collections import Counter
import copy
import importlib.util
import hashlib
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('word8x16_runtime_audit', Path(__file__).with_name('runtime-audit.py'))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
STAMP = '<' + ','.join(['i8'] * 16) + '>'
WORD_STAMP = '<' + ','.join(['i16'] * 8) + '>'


def node(number, name, **properties):
    return dict(id=number, nodeClass='test.' + name, properties=properties)


def edge(source, destination, label='value'):
    return dict(from_=source, to=destination, label=label, type='Value')


def graph(entry='plusCase'):
    nodes = [node(1, 'UnboxNode', boxingKind='JavaKind.Long', stamp='i64'),
             node(2, 'UnboxNode', boxingKind='JavaKind.Long', stamp='i64'),
             node(3, audit.ENTRIES[entry], stamp=STAMP),
             node(4, 'BoxNode$AllocatingBoxNode', stamp='a!# java.lang.Long'),
             node(5, 'ReturnNode', stamp='void')]
    edges = [edge(1, 3, 'x'), edge(2, 3, 'y'), edge(4, 5, 'result')]
    if entry == 'timesCase':
        nodes[2]['nodeClass'] = 'test.ReinterpretNode'
        nodes += [node(201, 'SimdInsertNode', stamp=STAMP), node(202, 'SimdInsertNode', stamp=STAMP),
                  node(203, 'ReinterpretNode', stamp=WORD_STAMP), node(204, 'ReinterpretNode', stamp=WORD_STAMP),
                  node(205, 'AndNode', stamp=WORD_STAMP), node(206, 'AndNode', stamp=WORD_STAMP),
                  node(207, 'UnsignedRightShiftNode', stamp=WORD_STAMP), node(208, 'UnsignedRightShiftNode', stamp=WORD_STAMP),
                  node(209, 'MulNode', stamp=WORD_STAMP), node(210, 'MulNode', stamp=WORD_STAMP),
                  node(211, 'AndNode', stamp=WORD_STAMP), node(212, 'LeftShiftNode', stamp=WORD_STAMP),
                  node(213, 'OrNode', stamp=WORD_STAMP),
                  node(230, 'ConstantNode', stamp=WORD_STAMP, rawvalue='<'+','.join(['255']*8)+'>'),
                  node(231, 'ConstantNode', stamp='i32 [8]', rawvalue='8')]
        edges = [edge(1, 201), edge(2, 202), edge(201, 203), edge(202, 204),
                 edge(203, 205, 'x'), edge(230, 205, 'y'), edge(204, 206, 'x'), edge(230, 206, 'y'),
                 edge(203, 207, 'x'), edge(231, 207, 'y'), edge(204, 208, 'x'), edge(231, 208, 'y'),
                 edge(205, 209, 'x'), edge(206, 209, 'y'), edge(207, 210, 'x'), edge(208, 210, 'y'),
                 edge(209, 211, 'x'), edge(230, 211, 'y'), edge(210, 212, 'x'), edge(231, 212, 'y'),
                 edge(211, 213, 'x'), edge(212, 213, 'y'), edge(213, 3), edge(4, 5, 'result')]
    for lane in range(16):
        nodes.append(node(10 + lane, 'SimdCutNode', stamp='i8', offset=lane, length=1))
        nodes.append(node(100 + lane, 'ZeroExtendNode', stamp='i64 [0 - 255]', inputBits=8, resultBits=64))
        edges += [edge(3, 10 + lane), edge(10 + lane, 100 + lane), edge(100 + lane, 4)]
    for item in edges:
        item['from'] = item.pop('from_')
    return dict(nodes=nodes, edges=edges, nodeClassCounts=dict(Counter(n['nodeClass'] for n in nodes)))


def resummarize(value):
    value['nodeClassCounts'] = dict(Counter(n['nodeClass'] for n in value['nodes']))
    return value


def frame_tags():
    value = graph()
    owner = 'com.oracle.truffle.api.impl.FrameWithoutBoxing'
    value['nodes'] += [
        node(90, 'VirtualArrayNode', stamp='a!# byte[]', componentType='byte', length=3),
        node(91, 'VirtualInstanceNode', type=owner, fields=[owner+'.'+name for name in (
            'descriptor', 'arguments', 'indexedLocals', 'indexedPrimitiveLocals', 'indexedTags', 'auxiliarySlots')]),
        node(92, 'VirtualObjectState'), node(93, 'VirtualObjectState'), node(94, 'FrameState'),
        node(95, 'VirtualArrayNode', componentType='java.lang.Object', length=3),
        node(96, 'VirtualArrayNode', componentType='long', length=3),
        node(97, 'ConstantNode', stamp='i32 [7]', rawvalue='7'), node(98, 'ConstantNode')]
    def add(source, target, label, index=-1, kind='Value'):
        value['edges'].append(dict(**{'from': source}, to=target, label=label, type=kind, listIndex=index))
    add(91, 92, 'object'); add(90, 93, 'object'); add(91, 94, 'values', 0)
    for state in (92, 93): add(state, 94, 'virtualObjectMappings', kind='State')
    for index, source in enumerate((98, 98, 95, 96, 90, 98)): add(source, 92, 'values', index)
    for index in range(3): add(97, 93, 'values', index)
    return resummarize(value)


def cfg(entry='plusCase', instruction=None):
    register = 'WORD' if entry == 'timesCase' else 'BYTE'
    line = instruction or ('nr 42 <|@ instruction xmm1|V128_' + register + ' = ' + audit.OPCODES[entry]
                           + ' (x: xmm2|V128_' + register + ', y: xmm0|V128_' + register + ') size: XMM <|@ <|@')
    if entry == 'timesCase' and instruction is None:
        line += '\n' + line.replace('nr 42', 'nr 43').replace('xmm1', 'xmm3')
    return ('begin_compilation\n  method "TruffleHotSpotCompilation-123[lambda a, b]"\n'
            'end_compilation\nbegin_cfg\n  name "After FinalCodeAnalysisStage"\n'
            'begin_block\n' + line + '\nend_block\nend_cfg\n')


class GraphGateTest(unittest.TestCase):
    def rejected(self, value, entry='plusCase'):
        with self.assertRaises(AssertionError):
            audit.inspect_graph(resummarize(value), entry)

    def test_each_packed_operation_and_all_lanes(self):
        for entry in audit.ENTRIES:
            with self.subTest(entry=entry):
                self.assertEqual(audit.inspect_graph(graph(entry), entry)['stamp'], STAMP)

    def test_scalar_or_wrong_width_cannot_substitute(self):
        for stamp in ('i8', '<i8,i8,i8,i8,i8,i8,i8,i8>', '<' + ','.join(['i16'] * 16) + '>'):
            value = graph()
            value['nodes'][2]['properties']['stamp'] = stamp
            self.rejected(value)

    def test_allocations_memory_and_calls_fail(self):
        for name in ('NewArrayNode', 'NewInstanceNode', 'DynamicNewInstanceNode', 'CommitAllocationNode',
                     'InvokeNode', 'InvokeWithExceptionNode', 'ForeignCallNode', 'LoadFieldNode',
                     'StoreFieldNode', 'StoreIndexedNode', 'ReadNode', 'WriteNode', 'UnsafeLoadNode'):
            with self.subTest(name=name):
                value = graph()
                value['nodes'].append(node(90, name))
                self.rejected(value)

    def test_payload_array_load_fails_but_host_object_array_is_allowed(self):
        for element in ('JavaKind.Short', 'JavaKind.Byte', 'JavaKind.Long'):
            value = graph()
            value['nodes'].append(node(90, 'LoadIndexedNode', elementKind=element))
            self.rejected(value)
        value = graph()
        value['nodes'].append(node(90, 'LoadIndexedNode', elementKind='JavaKind.Object'))
        audit.inspect_graph(resummarize(value), 'plusCase')

    def test_lane_boxes_unboxes_and_extra_long_boxes_fail(self):
        for extra in (node(90, 'BoxNode$AllocatingBoxNode', stamp='a!# java.lang.Short'),
                      node(90, 'BoxNode$AllocatingBoxNode', stamp='a!# java.lang.Long'),
                      node(90, 'UnboxNode', boxingKind='JavaKind.Short'),
                      node(90, 'UnboxNode', boxingKind='JavaKind.Long')):
            value = graph()
            value['nodes'].append(extra)
            self.rejected(value)

    def test_live_carrier_vector_and_payload_references_fail(self):
        for stamp in ('a!# thc.runtime.Word8X16', 'a jdk.incubator.vector.Byte128Vector',
                      'a jdk.incubator.vector.Short128Vector', 'a byte[]', 'a short[]'):
            value = graph()
            value['nodes'].append(node(90, 'PiNode', stamp=stamp))
            self.rejected(value)

    def test_only_exact_virtual_frame_tag_metadata_is_allowed(self):
        audit.inspect_graph(frame_tags(), 'plusCase')
        for name in ('VirtualArrayNode', 'ConstantNode', 'NewArrayNode'):
            value = graph()
            value['nodes'].append(node(90, name, stamp='a!# byte[]', componentType='byte', length=16))
            self.rejected(value)

    def test_virtual_tag_owner_and_slot_identity_are_required(self):
        for mutation in ('owner_type', 'owner_fields', 'field_index', 'sibling_length', 'sibling_kind', 'not_virtual'):
            with self.subTest(mutation=mutation):
                value = frame_tags(); by_id = {n['id']: n for n in value['nodes']}
                if mutation == 'owner_type': by_id[91]['properties']['type'] = 'jdk.incubator.vector.Byte128Vector'
                elif mutation == 'owner_fields': by_id[91]['properties']['fields'][4] = 'payload'
                elif mutation == 'field_index': next(e for e in value['edges'] if e['from'] == 90 and e['label'] == 'values')['listIndex'] = 5
                elif mutation == 'sibling_length': by_id[96]['properties']['length'] = 2
                elif mutation == 'sibling_kind': by_id[95]['properties']['componentType'] = 'byte'
                else: by_id[90]['nodeClass'] = 'test.NewArrayNode'
                self.rejected(value)

    def test_virtual_tag_materialization_or_escape_is_rejected(self):
        for source in (90, 91, 92, 93, 94):
            for target in (4, 5):
                value = frame_tags()
                value['edges'].append(dict(**{'from': source}, to=target, label='value', type='Value'))
                self.rejected(value)
        value = frame_tags()
        value['edges'].append(dict(**{'from': 1}, to=91, label='value', type='Value'))
        self.rejected(value)
        for name in ('CommitAllocationNode', 'MaterializedObjectState', 'AllocatedObjectNode'):
            value = frame_tags(); value['nodes'].append(node(99, name))
            value['edges'].append(dict(**{'from': 90}, to=99, label='object', type='Value'))
            self.rejected(value)

    def test_virtual_tag_values_must_be_complete_initial_constants(self):
        for mutation in ('value', 'dynamic', 'missing', 'duplicate', 'wrong_edge_kind'):
            with self.subTest(mutation=mutation):
                value = frame_tags(); constant = next(n for n in value['nodes'] if n['id'] == 97)
                if mutation == 'value': constant['properties']['rawvalue'] = '6'
                elif mutation == 'dynamic': constant['nodeClass'] = 'test.PhiNode'
                else:
                    e = next(e for e in value['edges'] if e['to'] == 93 and e['label'] == 'values')
                    if mutation == 'missing': value['edges'].remove(e)
                    elif mutation == 'duplicate': e['listIndex'] = 1
                    else: e['type'] = 'State'
                self.rejected(value)

    def test_virtual_tag_states_must_remain_same_frame_metadata(self):
        for mutation in ('state_type', 'edge_type', 'label', 'different_frame'):
            value = frame_tags()
            edge = next(e for e in value['edges'] if e['from'] == 93)
            if mutation == 'state_type': next(n for n in value['nodes'] if n['id'] == 94)['nodeClass'] = 'test.PiNode'
            elif mutation == 'edge_type': edge['type'] = 'Value'
            elif mutation == 'label': edge['label'] = 'value'
            else:
                value['nodes'].append(node(99, 'FrameState')); edge['to'] = 99
            self.rejected(value)

    def test_dead_or_partially_observed_vector_fails(self):
        value = graph()
        value['edges'] = [e for e in value['edges'] if e['to'] != 4]
        self.rejected(value)
        value = graph()
        value['edges'] = [e for e in value['edges'] if e['from'] != 25]
        self.rejected(value)

    def test_result_box_must_be_returned(self):
        value = graph()
        value['edges'] = [e for e in value['edges'] if e['to'] != 5]
        self.rejected(value)

    def test_unsigned_widening_is_recorded_for_every_lane(self):
        for entry in audit.ENTRIES:
            lanes = audit.inspect_graph(graph(entry), entry)['unsignedLaneExtensions']
            self.assertEqual([lane['lane'] for lane in lanes], list(range(16)))
            self.assertTrue(all(lane['inputBits'] == 8 and lane['resultBits'] == 64 for lane in lanes))

    def test_signed_or_wrong_width_output_extension_fails_for_every_lane(self):
        for lane in range(16):
            for mutation in ('signed', 'input_width', 'result_width', 'cut_kind'):
                value = graph(); target = next(n for n in value['nodes'] if n['id'] == 100+lane)
                if mutation == 'signed': target['nodeClass'] = 'test.SignExtendNode'
                elif mutation == 'input_width': target['properties']['inputBits'] = 16
                elif mutation == 'result_width': target['properties']['resultBits'] = 32
                else: next(n for n in value['nodes'] if n['id'] == 10+lane)['properties']['stamp'] = 'i16'
                self.rejected(value)

    def test_output_cut_cannot_bypass_unsigned_widening(self):
        for lane in range(16):
            value = graph()
            value['edges'].append(dict(**{'from': 10+lane}, to=4, label='value', type='Value'))
            self.rejected(value)
        value = graph()
        next(e for e in value['edges'] if e['to'] == 100)['label'] = 'wrong'
        self.rejected(value)

    def test_histogram_mismatch_fails(self):
        value = graph()
        value['nodeClassCounts']['test.NewArrayNode'] = 1
        with self.assertRaises(AssertionError):
            audit.inspect_graph(value, 'plusCase')

    def test_times_requires_two_word_products_not_one_byte_product(self):
        for mutation in ('one', 'third', 'byte'):
            value = graph('timesCase')
            if mutation == 'one': value['nodes'] = [n for n in value['nodes'] if n['id'] != 210]
            elif mutation == 'third': value['nodes'].append(node(240, 'MulNode', stamp=WORD_STAMP))
            else: next(n for n in value['nodes'] if n['id'] == 209)['properties']['stamp'] = STAMP
            self.rejected(value, 'timesCase')

    def test_times_reconstruction_constants_are_exact(self):
        for number, bad in ((230, '<'+','.join(['127']*8)+'>'), (231, '7'), (231, '16')):
            value = graph('timesCase')
            next(n for n in value['nodes'] if n['id'] == number)['properties']['rawvalue'] = bad
            self.rejected(value, 'timesCase')

    def test_times_requires_logical_high_byte_extract_and_low_byte_mask(self):
        for number in (205, 206, 207, 208, 211, 212, 213):
            value = graph('timesCase')
            next(n for n in value['nodes'] if n['id'] == number)['nodeClass'] = 'test.AddNode'
            self.rejected(value, 'timesCase')

    def test_times_products_must_have_the_same_input_vectors(self):
        value = graph('timesCase')
        value['nodes'].append(node(240, 'SimdInsertNode', stamp=STAMP))
        value['nodes'].append(node(241, 'ReinterpretNode', stamp=WORD_STAMP))
        value['edges'].append(dict(**{'from':240}, to=241, label='value', type='Value'))
        next(e for e in value['edges'] if e['to'] == 208 and e['label'] == 'x')['from'] = 241
        self.rejected(value, 'timesCase')

    def test_times_products_and_all_sixteen_outputs_must_be_live(self):
        value = graph('timesCase')
        value['edges'] = [e for e in value['edges'] if e['from'] != 25]
        self.rejected(value, 'timesCase')
        value = graph('timesCase')
        next(e for e in value['edges'] if e['to'] == 212 and e['label'] == 'x')['from'] = 209
        self.rejected(value, 'timesCase')

    def test_times_commutative_operand_order_is_semantic_not_serialization(self):
        value = graph('timesCase')
        for item in value['edges']:
            if item['to'] in (205, 206, 209, 210, 211, 213) and item['label'] in ('x', 'y'):
                item['label'] = 'y' if item['label'] == 'x' else 'x'
        result = audit.inspect_graph(resummarize(value), 'timesCase')
        self.assertEqual(result['expansion']['maskPerShort'], 255)


class LirGateTest(unittest.TestCase):
    def test_all_exact_xmm_byte_operations_and_two_word_products(self):
        for entry in audit.ENTRIES:
            with self.subTest(entry=entry):
                lir, instructions = audit.inspect_lir(cfg(entry), 'lambda a, b', entry, 'x86_64')
                self.assertEqual(len(instructions), 2 if entry == 'timesCase' else 1)
                self.assertTrue(all(audit.OPCODES[entry] in line for line in instructions))
                self.assertIn('end_cfg', lir)

    def test_wrong_width_opcode_register_or_comment_fails(self):
        correct = 'nr 42 <|@ instruction xmm1|V128_BYTE = VPADDB (x: xmm2|V128_BYTE, y: xmm0|V128_BYTE) size: XMM <|@'
        for wrong in (correct.replace('VPADDB', 'VPADDD'), correct.replace('VPADDB', 'PADDB'),
                      correct.replace('xmm1', 'v123'), correct.replace('V128_BYTE', 'V256_BYTE'),
                      correct.replace('size: XMM', 'size: YMM'), correct.replace('instruction', 'comment')):
            with self.subTest(line=wrong), self.assertRaises(AssertionError):
                audit.inspect_lir(cfg(instruction=wrong), 'lambda a, b', 'plusCase', 'x86_64')

    def test_unsigned_arithmetic_does_not_inherit_negation_zero_cast_exception(self):
        for entry in audit.ENTRIES:
            good = cfg(entry)
            register = 'WORD' if entry == 'timesCase' else 'BYTE'
            for width in ('256', '512'):
                bad = good.replace('x: xmm2|V128_'+register, 'x: xmm2|V128_'+register+'(V'+width+'_'+register+')')
                with self.assertRaises(AssertionError):
                    audit.inspect_lir(bad, 'lambda a, b', entry, 'x86_64')

    def test_operand_width_and_register_are_checked_not_just_result(self):
        good = cfg()
        for old, new in (('x: xmm2', 'x: ymm2'), ('y: xmm0', 'y: zmm0'),
                         ('x: xmm2|V128_BYTE', 'x: xmm2|V256_BYTE'),
                         ('y: xmm0|V128_BYTE', 'y: xmm0|V512_BYTE')):
            with self.assertRaises(AssertionError):
                audit.inspect_lir(good.replace(old, new), 'lambda a, b', 'plusCase', 'x86_64')

    def test_times_rejects_missing_extra_or_fictional_byte_multiply(self):
        good = cfg('timesCase')
        first = next(line for line in good.splitlines() if 'nr 42' in line)
        for bad in (good.replace(first+'\n', ''), good.replace(first, first+'\n'+first),
                    good.replace('VPMULLW', 'VPMULLB'), good.replace('V128_WORD', 'V128_BYTE'),
                    good.replace('size: XMM', 'size: YMM')):
            with self.assertRaises(AssertionError):
                audit.inspect_lir(bad, 'lambda a, b', 'timesCase', 'x86_64')

    def test_repeated_compilation_or_final_phase_fails(self):
        for value in (cfg() + cfg(), cfg() + 'begin_cfg\n  name "After FinalCodeAnalysisStage"\nend_cfg\n'):
            with self.assertRaises(AssertionError):
                audit.inspect_lir(value, 'lambda a, b', 'plusCase', 'x86_64')

    def test_identical_metadata_headers_are_not_recompilations(self):
        header = 'begin_compilation\n  method "TruffleHotSpotCompilation-123[lambda a, b]"\nend_compilation\n'
        audit.inspect_lir(header + cfg(), 'lambda a, b', 'plusCase', 'x86_64')
        for bad in (header.replace('-123[', '-124['), header.replace('lambda a, b', 'lambda c, d')):
            with self.assertRaises(AssertionError):
                audit.inspect_lir(bad + cfg(), 'lambda a, b', 'plusCase', 'x86_64')

    def test_different_root_or_architecture_fails(self):
        for target, arch in (('lambda c, d', 'x86_64'), ('lambda a, b', 'aarch64')):
            with self.assertRaises(AssertionError):
                audit.inspect_lir(cfg(), target, 'plusCase', arch)


class OracleGateTest(unittest.TestCase):
    def test_offline_checker_correction_cannot_change_runtime_or_probe(self):
        root = Path('/test')
        sources = [dict(path=str(root / 'bench/experiments/word8x16-foundation' / name), sha256='old')
                   for name in ('runtime-audit.py', 'test-runtime-audit.py', 'Word8X16RuntimeGraphProbe.java', 'run-runtime.sh')]
        sources.append(dict(path='/test/src/main/java/thc/runtime/Word8X16.java', sha256='runtime'))
        initial = dict(sources=sources, runtimeJars=['jar'], jdkFiles=['jdk'], sourceRevision='old')
        current = copy.deepcopy(initial)
        current['sources'][0]['sha256'] = 'fixed'
        current['sourceRevision'] = 'fixed'
        with self.assertRaises(AssertionError): audit.verify_snapshot(initial, current, root)
        correction = audit.verify_snapshot(initial, current, root, True)
        self.assertFalse(correction['guestExecutionRepeated'])
        for index in (2, 3, 4):
            bad = copy.deepcopy(current); bad['sources'][index]['sha256'] = 'changed'
            with self.assertRaises(AssertionError): audit.verify_snapshot(initial, bad, root, True)
        for key in ('runtimeJars', 'jdkFiles'):
            bad = copy.deepcopy(current); bad[key] = ['changed']
            with self.assertRaises(AssertionError): audit.verify_snapshot(initial, bad, root, True)

    def test_digest_matches_sha256(self):
        source = Path(__file__)
        self.assertEqual(audit.digest(source), hashlib.sha256(source.read_bytes()).hexdigest())

    def test_exact_declared_native_rows(self):
        entries = {'plusCase': dict(arity=2, cases=[[1, -2], [-32768, 32767]])}
        rows = 'plusCase\t1\t-2\t123\nplusCase\t-32768\t32767\t-789\n'
        self.assertEqual(len(audit.parse_oracle(rows, entries)), 2)
        for wrong in (rows + rows, rows.splitlines()[0], rows.replace('123', str(1 << 63)),
                      rows.replace('123', '1.0'), rows.replace('plusCase', 'minusCase'), rows.replace('\t1\t', '\t01\t')):
            with self.subTest(rows=wrong), self.assertRaises(AssertionError):
                audit.parse_oracle(wrong, entries)


if __name__ == '__main__':
    unittest.main()
