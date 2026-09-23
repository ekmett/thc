#!/usr/bin/env python3
"""Mutation tests for the gate, using synthetic parser data, never runtime evidence."""
from collections import Counter
import importlib.util
import hashlib
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('int16x8_runtime_audit', Path(__file__).with_name('runtime-audit.py'))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
STAMP = '<' + ','.join(['i16'] * 8) + '>'


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
    for lane in range(8):
        nodes.append(node(10 + lane, 'SimdCutNode', stamp='i16', offset=lane, length=1))
        edges += [edge(3, 10 + lane), edge(10 + lane, 4)]
    for item in edges:
        item['from'] = item.pop('from_')
    return dict(nodes=nodes, edges=edges, nodeClassCounts=dict(Counter(n['nodeClass'] for n in nodes)))


def resummarize(value):
    value['nodeClassCounts'] = dict(Counter(n['nodeClass'] for n in value['nodes']))
    return value


def cfg(entry='plusCase', instruction=None):
    line = instruction or ('nr 42 <|@ instruction xmm1|V128_WORD = ' + audit.OPCODES[entry]
                           + ' (x: xmm2|V128_WORD, y: xmm0|V128_WORD) size: XMM <|@ <|@')
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
        for stamp in ('i16', '<i16,i16,i16,i16>', '<' + ','.join(['i32'] * 8) + '>'):
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

    def test_live_carrier_and_short_vector_references_fail(self):
        for stamp in ('a!# thc.runtime.Int16X8', 'a jdk.incubator.vector.Short128Vector', 'a short[]'):
            value = graph()
            value['nodes'].append(node(90, 'PiNode', stamp=stamp))
            self.rejected(value)

    def test_dead_or_partially_observed_vector_fails(self):
        value = graph()
        value['edges'] = [e for e in value['edges'] if e['to'] != 4]
        self.rejected(value)
        value = graph()
        value['edges'] = [e for e in value['edges'] if e['from'] != 17]
        self.rejected(value)

    def test_result_box_must_be_returned(self):
        value = graph()
        value['edges'] = [e for e in value['edges'] if e['to'] != 5]
        self.rejected(value)

    def test_explicit_zero_subtraction_negation(self):
        value = graph('negateCase')
        value['nodes'][2]['nodeClass'] = 'test.SubNode'
        value['nodes'].append(node(90, 'ConstantNode', stamp=STAMP, rawvalue='<0,0,0,0,0,0,0,0>'))
        for item in value['edges']:
            if item['to'] == 3 and item['label'] == 'x':
                item['from'] = 90
        audit.inspect_graph(resummarize(value), 'negateCase')
        value['nodes'][-1]['properties']['rawvalue'] = '<1,0,0,0,0,0,0,0>'
        self.rejected(value, 'negateCase')

    def test_histogram_mismatch_fails(self):
        value = graph()
        value['nodeClassCounts']['test.NewArrayNode'] = 1
        with self.assertRaises(AssertionError):
            audit.inspect_graph(value, 'plusCase')


class LirGateTest(unittest.TestCase):
    def test_all_exact_xmm_word_instructions(self):
        for entry in audit.ENTRIES:
            with self.subTest(entry=entry):
                lir, instruction = audit.inspect_lir(cfg(entry), 'lambda a, b', entry, 'x86_64')
                self.assertIn(audit.OPCODES[entry], instruction)
                self.assertIn('end_cfg', lir)

    def test_wrong_width_opcode_register_or_comment_fails(self):
        correct = 'nr 42 <|@ instruction xmm1|V128_WORD = VPADDW (x: xmm2|V128_WORD, y: xmm0|V128_WORD) size: XMM <|@'
        for wrong in (correct.replace('VPADDW', 'VPADDD'), correct.replace('VPADDW', 'PADDW'),
                      correct.replace('xmm1', 'v123'), correct.replace('V128_WORD', 'V256_WORD'),
                      correct.replace('size: XMM', 'size: YMM'), correct.replace('instruction', 'comment')):
            with self.subTest(line=wrong), self.assertRaises(AssertionError):
                audit.inspect_lir(cfg(instruction=wrong), 'lambda a, b', 'plusCase', 'x86_64')

    def test_repeated_compilation_or_final_phase_fails(self):
        for value in (cfg() + cfg(), cfg() + 'begin_cfg\n  name "After FinalCodeAnalysisStage"\nend_cfg\n'):
            with self.assertRaises(AssertionError):
                audit.inspect_lir(value, 'lambda a, b', 'plusCase', 'x86_64')

    def test_different_root_or_architecture_fails(self):
        for target, arch in (('lambda c, d', 'x86_64'), ('lambda a, b', 'aarch64')):
            with self.assertRaises(AssertionError):
                audit.inspect_lir(cfg(), target, 'plusCase', arch)


class OracleGateTest(unittest.TestCase):
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
