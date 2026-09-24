#!/usr/bin/env python3
"""Synthetic reader mutations only: these tests are not guest/compiler evidence."""
from collections import Counter
import copy
import gzip
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('doublex2_bytearray_audit', Path(__file__).with_name('runtime-audit.py'))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
F64X2 = '<' + ','.join(['f64']*2) + '>'
I8X16 = '<' + ','.join(['i8']*16) + '>'


def node(number, name, **properties):
    return dict(id=number, nodeClass='test.'+name, properties=properties)


def edge(source, target, label='value', typ='Value', index=-1):
    return {'from': source, 'to': target, 'label': label, 'type': typ, 'listIndex': index}


def summarize(value):
    value['nodeClassCounts'] = dict(Counter(n['nodeClass'] for n in value['nodes']))
    return value


def by_id(value, number):
    return next(n for n in value['nodes'] if n['id'] == number)


def graph(entry='vectorIndexGraph'):
    operation, scale, arity = audit.ENTRIES[entry]
    store = operation == 'store'
    nodes = [node(0, 'StartNode'), node(1, 'ParameterNode', stamp='a java.lang.Object[]', index=1),
             node(2, 'PiNode', stamp='a!# java.lang.Object[]'),
             node(31, 'PiNode', stamp='a!# byte[]'), node(60, 'OffsetAddressNode'),
             node(61, 'MulNode', stamp='i64'), node(62, 'ConstantNode', stamp='i64', rawvalue=str(scale)),
             node(70, 'WriteNode' if store else 'ReadNode', stamp='void' if store else I8X16,
                  locationIdentity='Array: byte', memoryOrder='PLAIN', barrierType='NONE'),
             node(80, 'ReturnNode', stamp='void')]
    edges = [edge(0,70,'next','Successor'), edge(1,2,'object'), edge(21,31,'object'), edge(31,60,'base'), edge(61,60,'offset'),
             edge(42,61,'x'), edge(62,61,'y'), edge(60,70,'address','Association'), edge(70,80,'next','Successor')]
    for slot in range(arity+1):
        nodes += [node(10+slot, 'ConstantNode', stamp='i32', rawvalue=str(slot)),
                  node(20+slot, 'LoadIndexedNode', elementKind='JavaKind.Object', stamp='a java.lang.Object')]
        edges += [edge(2,20+slot,'array'), edge(10+slot,20+slot,'index')]
    for slot in ([2] if not store else range(2,5)):
        nodes.append(node(40+slot, 'UnboxNode', boxingKind='JavaKind.Long', stamp='i64'))
        edges.append(edge(20+slot,40+slot))
    if store:
        for lane in range(2):
            nodes.append(node(110+lane, 'FloatConvertNode', stamp='f64!', op='FloatConvert.L2D'))
            edges.append(edge(43+lane,110+lane))
        nodes += [node(130, 'SimdBroadcastNode', stamp=F64X2, length=2),
                  node(134, 'ReinterpretNode', stamp=I8X16)]
        edges += [edge(110,130), edge(131,134), edge(134,70), edge(31,80,'result')]
        for lane in (1,):
            nodes.append(node(130+lane, 'SimdInsertNode', stamp=F64X2, offset=lane))
            edges += [edge(129+lane,130+lane,'x'), edge(110+lane,130+lane,'y')]
    else:
        nodes += [node(71, 'ReinterpretNode', stamp=F64X2),
                  node(81, 'BoxNode$AllocatingBoxNode', stamp='a!# java.lang.Long')]
        edges += [edge(70,71), edge(81,80,'result')]
        for lane in range(2):
            nodes += [node(110+lane, 'SimdCutNode', stamp='f64', offset=lane, length=1),
                      node(120+lane, 'MulNode', stamp='f64'),
                      node(140+lane, 'ConstantNode', stamp='f64!', rawvalue=str(float((3,5)[lane])))]
            edges += [edge(71,110+lane), edge(110+lane,120+lane,'x'), edge(140+lane,120+lane,'y')]
        nodes += [node(150,'AddNode',stamp='f64'),
                  node(153,'FloatConvertNode',stamp='i64',op='FloatConvert.D2L')]
        edges += [edge(120,150,'x'),edge(121,150,'y'),edge(150,153),edge(153,81)]
    return summarize(dict(nodes=nodes, edges=edges))


def frame_tags(entry='vectorIndexGraph'):
    value = graph(entry)
    owner = 'com.oracle.truffle.api.impl.FrameWithoutBoxing'
    value['nodes'] += [
        node(90, 'VirtualArrayNode', stamp='a!# byte[]', componentType='byte', length=3),
        node(91, 'VirtualInstanceNode', type=owner, fields=[owner+'.'+name for name in (
            'descriptor', 'arguments', 'indexedLocals', 'indexedPrimitiveLocals', 'indexedTags', 'auxiliarySlots')]),
        node(92, 'VirtualObjectState'), node(93, 'VirtualObjectState'), node(94, 'FrameState'),
        node(95, 'VirtualArrayNode', componentType='java.lang.Object', length=3),
        node(96, 'VirtualArrayNode', componentType='long', length=3),
        node(97, 'ConstantNode', stamp='i32 [7]', rawvalue='7'), node(98, 'ConstantNode'),
        node(99, 'VirtualObjectState'), node(100, 'VirtualObjectState')]
    value['edges'] += [edge(91,92,'object'), edge(90,93,'object'), edge(91,94,'values',index=0)]
    for state in (92,93): value['edges'].append(edge(state,94,'virtualObjectMappings','State'))
    for ident,state in ((95,99),(96,100)):
        value['edges'] += [edge(ident,state,'object'),edge(state,94,'virtualObjectMappings','State')]
    for index, source in enumerate((98,98,95,96,90,98)): value['edges'].append(edge(source,92,'values',index=index))
    for index in range(3): value['edges'].append(edge(97,93,'values',index=index))
    return summarize(value)


def cfg(entry='vectorIndexGraph', line=None):
    store = audit.ENTRIES[entry][0] == 'store'
    instruction = ('VECTORSTORE (input: xmm2|V128_BYTE, address: [rsi|QWORD + rax|QWORD * 1 + 16])'
                   if store else 'xmm2|V128_BYTE = VECTORLOAD [rsi|QWORD + rax|QWORD * 1 + 16]')
    line = line or 'nr 42 <|@ instruction '+instruction+' size: XMM op: VMOVDQU32 <|@ <|@'
    return 'begin_compilation\n  method "TruffleHotSpotCompilation-123[lambda bytes, offset]"\nend_compilation\nbegin_cfg\n  name "After FinalCodeAnalysisStage"\n'+line+'\nend_cfg\n'


def metadata_array(bytecode=True):
    value=frame_tags()
    cached='thc.runtime.BytecodeRootGen$CachedBytecodeNode'
    value['nodes'].append(node(201,'ConstantNode',stamp='a!# byte[]' if bytecode else 'a!# int[]'))
    if bytecode:
        value['nodes'] += [node(202,'ConstantNode',stamp='a!# '+cached),
            node(203,'FrameState',code=cached+'.handleIndexVectorDoubleArray$Index_(Lcom/oracle/truffle/api/impl/FrameWithoutBoxing;, [B, J, J)'),
            node(204,'FrameState',code=cached+'.continueAt(Lthc/runtime/BytecodeRootGen;, Lcom/oracle/truffle/api/impl/FrameWithoutBoxing;, J)')]
        for state,slot,frame_slot in ((203,2,1),(204,7,6)):
            value['edges'] += [edge(201,state,'values',index=slot),edge(91,state,'values',index=frame_slot),
                              edge(202,state,'values',index=0)]
        value['edges'].append(edge(203,204,'outerFrameState','State'))
    else:
        value['nodes'] += [node(203,'FrameState',code='thc.runtime.VectorDoubleUnpack.executeTuple(Lcom/oracle/truffle/api/frame/VirtualFrame;, [I, I)'),
                           node(205,'ConstantNode',stamp='i32 [0]',rawvalue='0')]
        value['edges'] += [edge(201,203,'values',index=2),edge(91,203,'values',index=1),edge(205,203,'values',index=3)]
    return summarize(value)


def bloom_metadata():
    value=frame_tags('vectorStoreGraph')
    value['nodes'] += [node(201,'UnboxNode',boxingKind='JavaKind.Long',stamp='i64'),
                      node(202,'OrNode',stamp='i64'),node(203,'ConstantNode',stamp='i64',rawvalue='141888540639232'),
                      node(204,'ConstantNode',stamp='i32 [1]',rawvalue='1')]
    value['edges'] += [edge(20,201),edge(201,202,'x'),edge(203,202,'y'),edge(202,100,'values',index=0)]
    next(e for e in value['edges'] if e['to']==93 and e['label']=='values' and e['listIndex']==0)['from']=204
    return summarize(value)


def double_frame_metadata():
    value=frame_tags('vectorStoreGraph')
    by_id(value,96)['properties']['stamp']='a!# long[]'
    value['nodes'].append(node(201,'ConstantNode',stamp='i32 [3]',rawvalue='3'))
    next(e for e in value['edges'] if e['to']==93 and e['label']=='values' and e['listIndex']==1)['from']=201
    value['edges'].append(edge(110,100,'values',index=1))
    return summarize(value)


class GraphTest(unittest.TestCase):
    def reject(self, value, entry='vectorIndexGraph'):
        with self.assertRaises(AssertionError): audit.inspect_graph(summarize(value), entry)

    def test_all_four_memory_entries(self):
        for entry in audit.ENTRIES:
            result = audit.inspect_graph(graph(entry), entry)
            self.assertEqual(len(result['doubleLaneProducts'])+len(result['packedStoreLanes']),2)

    def test_reject_wrong_memory_kind_count_width_and_order(self):
        for mutation in ('kind','duplicate','width','location','order','barrier','address_kind'):
            value = graph(); n = by_id(value,70)
            if mutation == 'kind': n['nodeClass'] = 'test.FloatingReadNode'
            elif mutation == 'duplicate': value['nodes'].append(node(200,'ReadNode'))
            elif mutation == 'width': n['properties']['stamp'] = '<i32,i32>'
            elif mutation == 'location': n['properties']['locationIdentity'] = 'OFF_HEAP'
            elif mutation == 'order': n['properties']['memoryOrder'] = 'VOLATILE'
            elif mutation == 'barrier': n['properties']['barrierType'] = 'READ'
            else: by_id(value,60)['nodeClass'] = 'test.RawAddressNode'
            self.reject(value)

    def test_exact_caller_backing_lineage(self):
        for mutation in ('array_slot','parameter','constant','private_array','dynamic_slot','wrong_edge'):
            value = graph()
            if mutation == 'array_slot': by_id(value,11)['properties']['rawvalue'] = '2'
            elif mutation == 'parameter': by_id(value,1)['properties']['index'] = 0
            elif mutation == 'constant': by_id(value,1)['nodeClass'] = 'test.ConstantNode'
            elif mutation == 'private_array': by_id(value,31)['nodeClass'] = 'test.NewArrayNode'
            elif mutation == 'dynamic_slot': by_id(value,11)['nodeClass'] = 'test.AddNode'
            else: next(e for e in value['edges'] if e['to']==70)['type'] = 'Value'
            self.reject(value)

    def test_dynamic_offset_and_fixed_successor_required(self):
        for source,target in ((42,61),(70,80),(0,70)):
            value=graph(); value['edges']=[e for e in value['edges'] if (e['from'],e['to'])!=(source,target)]
            self.reject(value)
        value=graph(); value['edges'].append(edge(0,80,'next','Successor'));self.reject(value)

    def test_all_double_output_lanes_live(self):
        for mutation in ('missing','duplicate','integer','float','dead','rewired','weight','sum_bypass','conversion'):
            value=graph()
            if mutation=='missing': value['edges']=[e for e in value['edges'] if e['from']!=121]
            elif mutation=='duplicate': by_id(value,111)['properties']['offset']=0
            elif mutation=='integer': by_id(value,120)['properties']['stamp']='i32'
            elif mutation=='float': by_id(value,110)['properties']['stamp']='f32'
            elif mutation=='dead': value['edges']=[e for e in value['edges'] if e['from']!=70 or e['type']!='Value']
            elif mutation=='rewired': next(e for e in value['edges'] if e['to']==120 and e['label']=='x')['from']=111
            elif mutation=='weight': by_id(value,140)['properties']['rawvalue']='5.0'
            elif mutation=='conversion': by_id(value,153)['properties']['op']='FloatConvert.F2L'
            else: next(e for e in value['edges'] if e['to']==153)['from']=120
            self.reject(value)

    def test_all_store_lanes_from_correct_host_arguments(self):
        entry='vectorStoreGraph'
        for mutation in ('wrong_lane','wrong_slot','wrong_width','unsigned','wrong_insert','wrong_broadcast','constant_payload','wrong_return'):
            value=graph(entry)
            if mutation=='wrong_lane': next(e for e in value['edges'] if e['to']==111)['from']=43
            elif mutation=='wrong_slot': by_id(value,13)['properties']['rawvalue']='4'
            elif mutation=='wrong_width': by_id(value,110)['properties']['stamp']='f32'
            elif mutation=='unsigned': by_id(value,110)['properties']['op']='FloatConvert.UL2D'
            elif mutation=='wrong_insert': by_id(value,131)['properties']['offset']=0
            elif mutation=='wrong_broadcast': by_id(value,130)['properties']['length']=8
            elif mutation=='constant_payload': by_id(value,134)['nodeClass']='test.ConstantNode'
            else: next(e for e in value['edges'] if e['to']==80 and e['type']=='Value')['from']=22
            self.reject(value,entry)

    def test_double_checksum_commutation_preserves_grouping(self):
        value=graph()
        for ident in (120,121,150):
            incoming=[e for e in value['edges'] if e['to']==ident and e['type']=='Value']
            incoming[0]['from'],incoming[1]['from']=incoming[1]['from'],incoming[0]['from']
        self.assertEqual(len(audit.inspect_graph(value,'vectorIndexGraph')['doubleLaneProducts']),2)

    def test_double_proof_rejects_integer_views_float_rounding_and_fma(self):
        for ident,field,replacement in ((71,'stamp','<i32,i32,i32,i32>'),(110,'stamp','i32'),
                                       (120,'stamp','f32'),(150,'stamp','f32'),(153,'op','FloatConvert.F2L')):
            value=graph();by_id(value,ident)['properties'][field]=replacement;self.reject(value)
        value=graph();by_id(value,150)['nodeClass']='test.FusedMultiplyAddNode';self.reject(value)
        for replacement in ('FloatConvert.I2D','FloatConvert.L2F','FloatConvert.UL2D'):
            value=graph('vectorStoreGraph');by_id(value,110)['properties']['op']=replacement
            self.reject(value,'vectorStoreGraph')

    def test_exact_long_bits_reinterpret_chain_and_width_negatives(self):
        for entry in audit.ENTRIES:
            value=graph(entry)
            value['nodes'].append(node(200,'ReinterpretNode',stamp='<i64,i64>'))
            if audit.ENTRIES[entry][0]=='index':
                next(e for e in value['edges'] if e['to']==71)['from']=200
                value['edges'].append(edge(70,200))
            else:
                next(e for e in value['edges'] if e['to']==134)['from']=200
                value['edges'].append(edge(131,200))
            audit.inspect_graph(summarize(value),entry)
            for wrong in ('<i32,i32,i32,i32>','<i64>','<i64,i64,i64,i64>',
                          '<f32,f32,f32,f32>','<f64>','<f64,f64,f64,f64>'):
                bad=copy.deepcopy(value);by_id(bad,200)['properties']['stamp']=wrong
                with self.subTest(entry=entry,stamp=wrong):self.reject(bad,entry)

    def test_no_private_payload_allocations_calls_fields(self):
        for name in ('NewArrayNode','NewInstanceNode','CommitAllocationNode','InvokeNode','ForeignCallNode',
                     'LoadFieldNode','StoreFieldNode','StoreIndexedNode','UnsafeLoadNode','AtomicReadNode'):
            value=graph(); value['nodes'].append(node(200,name)); self.reject(value)
        for stamp in ('a!# byte[]','a int[]','a short[]','a jdk.incubator.vector.Byte128Vector',
                      'a thc.runtime.DoubleX2','a thc.runtime.Int32X4','a!# float[]','a!# double[]','a jdk.incubator.vector.Float128Vector',
                      'a!# java.lang.Float','a!# java.lang.Double','a!# long[]'):
            value=graph();value['nodes'].append(node(200,'ConstantNode',stamp=stamp));self.reject(value)

    def test_scalar_memory_and_lane_boxes_rejected(self):
        for extra in (node(200,'LoadIndexedNode',elementKind='JavaKind.Byte'),
                      node(200,'BoxNode$AllocatingBoxNode',stamp='a!# java.lang.Integer'),
                      node(200,'UnboxNode',boxingKind='JavaKind.Int'),
                      node(200,'BoxNode$AllocatingBoxNode',stamp='a!# java.lang.Long')):
            value=graph(); value['nodes'].append(extra); self.reject(value)

    def test_exact_frame_metadata_exception(self):
        self.assertEqual(audit.inspect_graph(frame_tags(),'vectorIndexGraph')['frameTagMetadata'],[90])
        for mutation in ('owner','slot','fields','length','tag','escape','materialize','dynamic'):
            value=frame_tags()
            if mutation=='owner':by_id(value,91)['properties']['type']='jdk.incubator.vector.Byte128Vector'
            elif mutation=='slot':next(e for e in value['edges'] if e['from']==90 and e['label']=='values')['listIndex']=5
            elif mutation=='fields':by_id(value,91)['properties']['fields'][4]='payload'
            elif mutation=='length':by_id(value,96)['properties']['length']=2
            elif mutation=='tag':by_id(value,97)['properties']['rawvalue']='2'
            elif mutation=='escape':value['edges'].append(edge(90,80,'result'))
            elif mutation=='materialize':value['nodes'].append(node(200,'CommitAllocationNode'))
            else:by_id(value,97)['nodeClass']='test.AddNode'
            self.reject(value)

    def test_long_frame_backing_is_only_exact_metadata(self):
        value=frame_tags();by_id(value,96)['properties']['stamp']='a!# long[]'
        audit.inspect_graph(value,'vectorIndexGraph')
        for mutation in ('escape','owner','slot'):
            bad=copy.deepcopy(value)
            if mutation=='escape':bad['edges'].append(edge(96,80,'result'))
            elif mutation=='owner':by_id(bad,91)['properties']['type']='jdk.incubator.vector.Float128Vector'
            else:next(e for e in bad['edges'] if e['from']==96 and e['label']=='values')['listIndex']=2
            self.reject(bad)

    def test_double_frame_slot_has_exact_float_tag_and_primitive_storage(self):
        for spelling in ('f64','f64!','f64! [-65536.0 - 65535.0]'):
            value=double_frame_metadata();by_id(value,110)['properties']['stamp']=spelling
            result=audit.inspect_graph(value,'vectorStoreGraph')
            self.assertEqual(result['frameTagMetadata'],[90])
            self.assertEqual(len(result['packedStoreLanes']),2)
        # Omitted/default entry and explicit Double +0 are reconstruction-only.
        value=double_frame_metadata()
        value['edges']=[e for e in value['edges'] if not (e['to']==100 and e['label']=='values')]
        audit.inspect_graph(summarize(value),'vectorStoreGraph')
        value['nodes'].append(node(202,'ConstantNode',stamp='f64! [0.0]',rawvalue='0.0'))
        value['edges'].append(edge(202,100,'values',index=1))
        audit.inspect_graph(summarize(value),'vectorStoreGraph')

    def test_double_frame_wrong_width_tag_object_and_escape_fail(self):
        for mutation in ('float','long','integer','object','missing_stamp','tag_long','tag_float','tag_illegal',
                         'tag_bool','tag_static','tag_fraction','tag_kind','object_slot','escape','state_escape',
                         'wrong_owner','wrong_slot','wrong_length','materialized'):
            value=double_frame_metadata()
            if mutation in ('float','long','integer','object','missing_stamp'):
                by_id(value,110)['properties']['stamp']={'float':'f32','long':'i64','integer':'i32',
                    'object':'a!# java.lang.Double','missing_stamp':''}[mutation]
            elif mutation.startswith('tag_'):
                if mutation=='tag_kind':by_id(value,201)['properties']['stamp']='i64'
                else:by_id(value,201)['properties']['rawvalue']={'tag_long':'1','tag_float':'4','tag_illegal':'7',
                    'tag_bool':'5','tag_static':'8','tag_fraction':'3.0'}[mutation]
            elif mutation=='object_slot':
                value['nodes'].append(node(202,'ConstantNode',stamp='a java.lang.Object'))
                value['edges'].append(edge(202,99,'values',index=1))
            elif mutation=='escape':value['edges'].append(edge(96,80,'result'))
            elif mutation=='state_escape':value['edges'].append(edge(100,80,'result'))
            elif mutation=='wrong_owner':by_id(value,91)['properties']['type']='jdk.incubator.vector.Float128Vector'
            elif mutation=='wrong_slot':next(e for e in value['edges'] if e['to']==100 and e['label']=='values')['listIndex']=0
            elif mutation=='wrong_length':by_id(value,96)['properties']['length']=4
            else:value['nodes'].append(node(202,'CommitAllocationNode'))
            with self.subTest(mutation=mutation):
                if mutation!='materialized':
                    nodes={n['id']:n for n in value['nodes']}
                    self.assertFalse(audit.virtual_frame_tags(nodes[90],nodes,value['edges']))
                self.reject(value,'vectorStoreGraph')

    def test_double_frame_snapshot_kinds_cannot_be_mismatched(self):
        value=double_frame_metadata()
        value['nodes'] += [node(210,'VirtualObjectState'),node(211,'FrameState'),
                           node(212,'ConstantNode',stamp='i32 [1]',rawvalue='1')]
        value['edges'] += [edge(90,210,'object'),edge(210,211,'virtualObjectMappings','State')]
        for e in list(value['edges']):
            if e['to']==93 and e['label']=='values':
                value['edges'].append(edge(e['from'],210,'values',index=e['listIndex']))
        for state in (92,99,100):value['edges'].append(edge(state,211,'virtualObjectMappings','State'))
        audit.inspect_graph(summarize(value),'vectorStoreGraph')
        for mutation in ('long_tag_float_value','missing_primitive_snapshot','duplicate_tag_snapshot'):
            bad=copy.deepcopy(value)
            if mutation=='long_tag_float_value':
                next(e for e in bad['edges'] if e['to']==210 and e['label']=='values' and e['listIndex']==1)['from']=212
            elif mutation=='missing_primitive_snapshot':
                bad['edges']=[e for e in bad['edges'] if not (e['from']==100 and e['to']==211)]
            else:bad['edges'].append(edge(210,94,'virtualObjectMappings','State'))
            with self.subTest(mutation=mutation):self.reject(bad,'vectorStoreGraph')

    def test_duplicate_or_dangling_nodes_fail(self):
        value=graph();value['nodes'].append(copy.deepcopy(value['nodes'][0]));self.reject(value)
        value=graph();value['edges'].append(edge(999,70));self.reject(value)

    def test_fractional_and_boolean_graph_counts_fail(self):
        for ident,key in ((1,'index'),(110,'offset'),(110,'length')):
            for replacement in (float(by_id(graph(),ident)['properties'][key]),True):
                value=graph();by_id(value,ident)['properties'][key]=replacement;self.reject(value)
        for key in ('from','to','listIndex'):
            value=graph();value['edges'][0][key]=float(value['edges'][0][key]);self.reject(value)
        value=frame_tags();by_id(value,90)['properties']['length']=3.0;self.reject(value)

    def test_multiple_frame_tag_snapshots_and_default_zero(self):
        value=frame_tags()
        value['nodes'] += [node(201,'VirtualObjectState'),node(202,'FrameState'),
                           node(203,'ConstantNode',stamp='i32 [1]',rawvalue='1')]
        value['edges'] += [edge(90,201,'object'),edge(201,202,'virtualObjectMappings','State'),
                           edge(203,201,'values',index=0),edge(97,201,'values',index=2)]
        for state in (92,99,100):value['edges'].append(edge(state,202,'virtualObjectMappings','State'))
        # Missing tag slot1 is default0; Long slot0 has default primitive0.
        self.assertEqual(audit.inspect_graph(summarize(value),'vectorIndexGraph')['frameTagMetadata'],[90])
        for mutation in ('overlap','extra_index','dynamic_tag','wrong_kind','object_in_long','primitive_in_object'):
            bad=copy.deepcopy(value)
            if mutation=='overlap':bad['edges'].append(edge(201,94,'virtualObjectMappings','State'))
            elif mutation=='extra_index':bad['edges'].append(edge(97,201,'values',index=3))
            elif mutation=='dynamic_tag':by_id(bad,203)['nodeClass']='test.AddNode'
            elif mutation=='wrong_kind':by_id(bad,203)['properties']['stamp']='i64'
            elif mutation=='object_in_long':
                bad['nodes'].append(node(204,'ConstantNode',stamp='a java.lang.Object'));bad['edges'].append(edge(204,99,'values',index=0))
            else:
                bad['nodes'].append(node(204,'ConstantNode',stamp='i64'));bad['edges'].append(edge(204,100,'values',index=1))
            self.reject(bad)

    def test_exact_interpreter_arrays_are_metadata_only(self):
        for bc in (False,True):
            value=metadata_array(bc)
            self.assertEqual(audit.inspect_graph(value,'vectorIndexGraph')['interpreterArrayMetadata'],[201])

    def test_interpreter_array_slot_receiver_code_owner_escape_negatives(self):
        for mutation in ('slot','receiver','code','owner','value_escape','state_escape','missing_method','memory_base','dynamic_array'):
            value=metadata_array()
            if mutation=='slot':next(e for e in value['edges'] if e['from']==201)['listIndex']=3
            elif mutation=='receiver':by_id(value,202)['properties']['stamp']='a!# jdk.incubator.vector.Byte128Vector'
            elif mutation=='code':by_id(value,203)['properties']['code']='arbitrary([B)'
            elif mutation=='owner':by_id(value,91)['properties']['fields'][4]='payload'
            elif mutation=='value_escape':value['edges'].append(edge(201,81))
            elif mutation=='state_escape':value['edges'].append(edge(203,81))
            elif mutation=='missing_method':value['edges']=[e for e in value['edges'] if e['from']!=201 or e['to']!=204]
            elif mutation=='memory_base':next(e for e in value['edges'] if e['to']==60 and e['label']=='base')['from']=201
            else:by_id(value,201)['nodeClass']='test.PiNode'
            self.reject(value)

    def test_tuple_destination_slots_are_not_vector_payloads(self):
        for mutation in ('wrong_method','wrong_slot','nonzero_base','escaping'):
            value=metadata_array(False)
            if mutation=='wrong_method':by_id(value,203)['properties']['code']='jdk.incubator.vector.IntVector.lanes([I)'
            elif mutation=='wrong_slot':next(e for e in value['edges'] if e['from']==201)['listIndex']=1
            elif mutation=='nonzero_base':by_id(value,205)['properties']['rawvalue']='1'
            else:value['edges'].append(edge(201,80,'result'))
            self.reject(value)

    def test_real_calls_allocations_rejected_before_metadata(self):
        for name in ('InvokeWithExceptionNode','CommitAllocationNode','AllocatedObjectNode'):
            value=graph();value['nodes'] += [node(200,'ConstantNode',stamp='a!# byte[]'),node(201,name)]
            with self.assertRaisesRegex(AssertionError,'Residual allocation/payload/call: '+name):
                audit.inspect_graph(summarize(value),'vectorIndexGraph')

    def test_exact_host_bloom_bookkeeping_is_not_a_guest_lane(self):
        result=audit.inspect_graph(bloom_metadata(),'vectorStoreGraph')
        self.assertEqual(result['bloomBookkeepingUnbox'],201)
        self.assertEqual(len(result['packedStoreLanes']),2)

    def test_bloom_mask_slot_owner_and_escape_negatives(self):
        for mutation in ('mask','mask_width','dynamic_mask','operator','frame_slot','header_slot','wrong_owner',
                         'duplicate','address','payload','returned','state_escape','extra_argument'):
            value=bloom_metadata()
            if mutation=='mask':by_id(value,203)['properties']['rawvalue']='0'
            elif mutation=='mask_width':by_id(value,203)['properties']['stamp']='i32'
            elif mutation=='dynamic_mask':by_id(value,203)['nodeClass']='test.AddNode'
            elif mutation=='operator':by_id(value,202)['nodeClass']='test.AddNode'
            elif mutation=='frame_slot':next(e for e in value['edges'] if e['from']==202)['listIndex']=1
            elif mutation=='header_slot':next(e for e in value['edges'] if e['to']==201)['from']=25
            elif mutation=='wrong_owner':by_id(value,91)['properties']['fields'][3]='payload'
            elif mutation=='duplicate':
                value['nodes'].append(node(205,'UnboxNode',boxingKind='JavaKind.Long',stamp='i64'));value['edges'].append(edge(20,205))
            elif mutation=='address':value['edges'].append(edge(201,61,'x'))
            elif mutation=='payload':next(e for e in value['edges'] if e['to']==110)['from']=201
            elif mutation=='returned':next(e for e in value['edges'] if e['to']==80 and e['type']=='Value')['from']=202
            elif mutation=='state_escape':value['edges'].append(edge(202,94,'values',index=2))
            else:
                value['nodes'].append(node(205,'UnboxNode',boxingKind='JavaKind.Long',stamp='i64'));value['edges'].append(edge(25,205))
            with self.subTest(mutation=mutation):self.reject(value,'vectorStoreGraph')


class LirTest(unittest.TestCase):
    def inspect(self,text,entry='vectorIndexGraph'):
        return audit.inspect_lir(text,'lambda bytes, offset',entry,'x86_64')

    def test_all_selected_memory_instructions(self):
        for entry in audit.ENTRIES:self.assertEqual(len(self.inspect(cfg(entry),entry)[1]),1)

    def test_double_register_and_move_are_still_exactly_128_bits(self):
        for entry in audit.ENTRIES:
            text=cfg(entry).replace('V128_BYTE','V128_DOUBLE').replace('VMOVDQU32','VMOVUPD')
            self.inspect(text,entry)
            for before,after in (('V128_DOUBLE','V256_DOUBLE'),('V128_DOUBLE','V128_SINGLE'),
                                 ('VMOVUPD','VMOVSD'),('VMOVUPD','VMOVUPS'),('xmm2','ymm2'),('xmm2','zmm2')):
                with self.assertRaises(AssertionError):self.inspect(text.replace(before,after),entry)
            self.inspect(cfg(entry).replace('V128_BYTE','V128_QWORD'),entry)

    def test_wrong_width_opcode_location_or_register_fails(self):
        good=cfg()
        for before,after in (('size: XMM','size: YMM'),('V128_BYTE','V256_BYTE'),('xmm2','ymm2'),
                             ('VMOVDQU32','VMOVQ'),('VECTORLOAD','VECTORSTORE'),('rsi|','rsp|'),
                             ('rsi|QWORD + rax|QWORD * 1 + 16','data[0]'),('xmm2','v42')):
            with self.subTest(after=after),self.assertRaises(AssertionError):self.inspect(good.replace(before,after))

    def test_missing_duplicate_or_other_compilation_fails(self):
        text=cfg();line=next(s for s in text.splitlines() if 'instruction' in s)
        for bad in (text.replace(line,''),text.replace(line,line+'\n'+line),
                    text.replace('end_compilation','  method "TruffleHotSpotCompilation-124[lambda bytes, offset]"\nend_compilation'),
                    text.replace('After FinalCodeAnalysisStage','Before FinalCodeAnalysisStage')):
            with self.assertRaises(AssertionError):self.inspect(bad)

    def test_comments_and_trailing_opcode_substrings_fail(self):
        text=cfg();line=next(s for s in text.splitlines() if 'instruction' in s)
        for bad in (text.replace(line,'# '+line), text.replace('VMOVDQU32','VMOVDQU32_FAKE'),
                    text.replace(' <|@ <|@',' comment size: XMM op: VMOVDQU32 <|@ <|@'),
                    text.replace('instruction xmm2','instruction fake xmm2')):
            with self.assertRaises(AssertionError):self.inspect(bad)

    def test_identical_header_is_not_a_retry(self):
        text=cfg().replace('end_compilation','  method "TruffleHotSpotCompilation-123[lambda bytes, offset]"\nend_compilation')
        self.inspect(text)

    def test_non_x86_fails(self):
        with self.assertRaises(AssertionError):audit.inspect_lir(cfg(),'lambda bytes, offset','vectorIndexGraph','aarch64')

    def test_exact_compressed_oop_index_is_still_physical_heap_address(self):
        for entry in audit.ENTRIES:
            text=cfg(entry).replace('rax|QWORD * 1','rax|DWORD[_] * 8')
            self.inspect(text,entry)
            for before,after in (('DWORD[_]','DWORD'),('DWORD[_]','DWORD[*]'),('DWORD[_]','DWORD[.]'),
                                 ('* 8','* 4'),('rax|DWORD','rsp|DWORD'),('xmm2','ymm2')):
                with self.subTest(before=before,after=after),self.assertRaises(AssertionError):
                    self.inspect(text.replace(before,after),entry)


def cases(entry):
    operation,scale,arity=audit.ENTRIES[entry]
    boundary=(-(1<<40),-(1<<32)-1,-1,0,1,(1<<32)-1,(1<<40)-1,1<<40)
    native={};rows=[]
    for lane in range(2):
        for value in boundary:
            lanes=[-31,127];lanes[lane]=value
            # 16-byte units have four safe starts, 8-byte units seven.
            offset=len(rows)%(4 if scale==16 else 7)
            initial=list(b''.join((0x89abcdef+i*0x01030507).to_bytes(4,'little') for i in range(16)))
            expected=list(initial)
            for i,v in enumerate(lanes):expected[offset*scale+8*i:offset*scale+8*i+8]=struct.pack('<d',v)
            score=sum(v*w for v,w in zip(lanes,(3,5)))
            name=('vector' if scale==16 else 'scalar')+('GraphIndexCase' if operation=='index' else 'GraphStoreCase')
            args=[offset,*lanes]
            if operation=='index':native[(name,*args)]=score
            else:
                for byte,v in enumerate(expected):native[(name,*args,byte)]=score*257+v
            rows.append(dict(offset=offset,lanes=lanes,initialBytes=expected if operation=='index' else initial,
                             expectedBytes=expected,expectedScalar=score,nativeEntry=name,nativeArguments=args))
    return dict(name=entry,operation=operation,offsetUnitBytes=scale,arity=arity,cases=rows),native


class InputTest(unittest.TestCase):
    def test_independent_byte_native_mapping_all_roots(self):
        for entry in audit.ENTRIES:
            definition,native=cases(entry);audit.validate_graph_cases(definition,native)

    def test_wrong_bytes_checksum_native_mapping_or_units(self):
        for mutation in ('before','after','checksum','native','arity','scale','mapping','duplicate','empty','bool_byte','signed_lane'):
            definition,native=cases('vectorStoreGraph');row=definition['cases'][0]
            if mutation=='before':row['initialBytes'][63]^=1
            elif mutation=='after':row['expectedBytes'][63]^=1
            elif mutation=='checksum':row['expectedScalar']+=1
            elif mutation=='native':native[next(iter(native))]+=1
            elif mutation=='arity':definition['arity']=6
            elif mutation=='scale':definition['offsetUnitBytes']=4
            elif mutation=='mapping':row['nativeArguments'][0]+=1
            elif mutation=='duplicate':definition['cases'][-1]=copy.deepcopy(row)
            elif mutation=='empty':definition['cases']=[]
            elif mutation=='bool_byte':row['expectedBytes'][0]=False
            else:row['lanes'][0]=(1<<40)+1
            with self.subTest(mutation=mutation),self.assertRaises(AssertionError):audit.validate_graph_cases(definition,native)

    def test_truncated_native_selector_mapping_fails(self):
        definition,native=cases('scalarStoreGraph');native.pop(next(iter(native)))
        with self.assertRaises(KeyError):audit.validate_graph_cases(definition,native)

    def test_fractional_and_boolean_fixture_metadata_fail(self):
        for key in ('arity','offsetUnitBytes'):
            for replacement in (float(audit.ENTRIES['vectorIndexGraph'][2 if key=='arity' else 1]),True):
                definition,native=cases('vectorIndexGraph');definition[key]=replacement
                with self.assertRaises(AssertionError):audit.validate_graph_cases(definition,native)
        for key in ('offset','expectedScalar','lanes','nativeArguments'):
            definition,native=cases('vectorIndexGraph');row=definition['cases'][0]
            if key in ('lanes','nativeArguments'):row[key][0]=float(row[key][0])
            else:row[key]=float(row[key])
            with self.assertRaises(AssertionError):audit.validate_graph_cases(definition,native)

    def test_graph_domain_has_exact_binary64_products_and_sums(self):
        for entry in audit.ENTRIES:
            definition,native=cases(entry)
            for row in definition['cases']:
                score=0.0
                for v,w in zip(row['lanes'],(3,5)):
                    term=struct.unpack('<d',struct.pack('<d',float(v)*w))[0]
                    score=struct.unpack('<d',struct.pack('<d',score+term))[0]
                self.assertEqual(row['expectedScalar'],int(score))

    def test_correlated_replacement_corpus_cannot_change_sentinels(self):
        definition,native=cases('vectorIndexGraph');row=definition['cases'][0]
        row['lanes'][1]+=1
        row['nativeArguments']=[row['offset'],*row['lanes']]
        row['expectedScalar']=sum(v*w for v,w in zip(row['lanes'],(3,5)))
        start=row['offset']*16+8
        replacement=list(struct.pack('<d',row['lanes'][1]))
        row['initialBytes'][start:start+8]=replacement
        row['expectedBytes'][start:start+8]=replacement
        native[(row['nativeEntry'],*row['nativeArguments'])]=row['expectedScalar']
        with self.assertRaisesRegex(AssertionError,'exact rotated finite boundary/sentinel corpus'):
            audit.validate_graph_cases(definition,native)

    def test_oracle_missing_duplicate_wrong_arity_and_negative_zero(self):
        entries={'test':dict(arity=1,cases=[[0]])}
        self.assertEqual(audit.parse_oracle('test\t0\t1\n',entries),{('test',0):1})
        for text in ('','test\t0\t1\ntest\t0\t1\n','test\t0\t2\t1\n','test\t-0\t1\n','other\t0\t1\n'):
            with self.assertRaises(AssertionError):audit.parse_oracle(text,entries)

    def test_offline_correction_cannot_change_runtime_probe_runner_or_jdk(self):
        root=Path('/source')
        names=('runtime-audit.py','test-runtime-audit.py','run-runtime.sh','DoubleX2ByteArrayRuntimeGraphProbe.java')
        sources=[dict(path=str(root/'bench/experiments/doublex2-bytearray'/name),sha256='a') for name in names]
        old=dict(sources=sources,runtimeJars=[dict(path='runtime.jar',sha256='a')],jdkFiles=[dict(path='jdk',sha256='a')],sourceRevision='old')
        current=copy.deepcopy(old);current['sources'][0]['sha256']='b';current['sourceRevision']='new'
        self.assertFalse(audit.verify_snapshot(old,current,root,True)['guestExecutionRepeated'])
        with self.assertRaises(AssertionError):audit.verify_snapshot(old,current,root)
        for where,index in (('sources',2),('sources',3),('runtimeJars',0),('jdkFiles',0)):
            current=copy.deepcopy(old);current[where][index]['sha256']='b'
            with self.assertRaises(AssertionError):audit.verify_snapshot(old,current,root,True)

    def test_real_arithmetic_only_controls_lack_packed_memory(self):
        # Committed historical captures, four signed lanes, are
        # negative MEMORY controls only; not new native or guest execution.
        root=Path(__file__).resolve().parents[3]
        evidence=root/'bench/experiments/int32x4-multiply/evidence-x86_64'
        hashes=[]
        for stage in ('pre','post'):
            for backend in ('ast','bytecode'):
                path=evidence/f'{stage}-{backend}-timesCase/graph.json.gz'
                raw=path.read_bytes();value=json.loads(gzip.decompress(raw));hashes.append(hashlib.sha256(raw).hexdigest())
                with self.assertRaisesRegex(AssertionError,'Exactly one fixed caller-array ReadNode required'):
                    audit.inspect_graph(value,'vectorIndexGraph')
        self.assertEqual(len(set(hashes)),4)

    def test_real_integer_memory_loads_are_not_double_observations(self):
        root=Path(__file__).resolve().parents[3]
        for family in ('int32x4','word32x4'):
            evidence=root/f'bench/experiments/{family}-bytearray/evidence-x86_64'
            hashes=[]
            for stage in ('pre','post'):
                for backend in ('ast','bytecode'):
                    for entry in ('vectorIndexWorker','scalarIndexWorker'):
                        path=evidence/f'{stage}-{backend}-{entry}/graph.json.gz'
                        raw=path.read_bytes();value=json.loads(gzip.decompress(raw));hashes.append(hashlib.sha256(raw).hexdigest())
                        with self.assertRaises(AssertionError):
                            audit.inspect_graph(value,entry.replace('Worker','Graph'))
            self.assertEqual(len(set(hashes)),8)

    def test_real_float_memory_loads_are_not_double_observations(self):
        root=Path(__file__).resolve().parents[3]
        evidence=root/'bench/experiments/floatx4-bytearray/evidence-x86_64'
        hashes=[]
        for stage in ('pre','post'):
            for backend in ('ast','bytecode'):
                for entry in ('vectorIndexGraph','scalarIndexGraph'):
                    path=evidence/f'{stage}-{backend}-{entry}/graph.json.gz'
                    raw=path.read_bytes();value=json.loads(gzip.decompress(raw));hashes.append(hashlib.sha256(raw).hexdigest())
                    # Same public arity and memory footprint. The mismatch is
                    # the actual f32/F2L load observation, not an arity shortcut.
                    with self.assertRaisesRegex(AssertionError,'Checksum must convert binary64 directly'):
                        audit.inspect_graph(value,entry)
        self.assertEqual(len(set(hashes)),8)

    def test_real_four_lane_stores_are_only_signature_controls(self):
        root=Path(__file__).resolve().parents[3]
        checked=0
        for family in ('int32x4','word32x4','floatx4'):
            evidence=root/f'bench/experiments/{family}-bytearray/evidence-x86_64'
            for stage in ('pre','post'):
                for backend in ('ast','bytecode'):
                    for entry in ('vectorStoreGraph','scalarStoreGraph'):
                        value=json.loads(gzip.decompress((evidence/f'{stage}-{backend}-{entry}/graph.json.gz').read_bytes()))
                        with self.assertRaises(AssertionError):audit.inspect_graph(value,entry)
                        checked+=1
        # These differ in host arity and can fail there or at exact metadata;
        # rejection does not prove signed/unsigned/floating machine-byte distinctions.
        self.assertEqual(checked,24)


if __name__=='__main__':unittest.main()
