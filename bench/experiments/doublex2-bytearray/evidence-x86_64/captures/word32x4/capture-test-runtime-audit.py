#!/usr/bin/env python3
"""Synthetic reader mutations only: these tests are not guest/compiler evidence."""
from collections import Counter
import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('word32x4_bytearray_audit', Path(__file__).with_name('runtime-audit.py'))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
I32X4 = '<' + ','.join(['i32']*4) + '>'
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


def graph(entry='vectorIndexWorker'):
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
    for slot in ([2] if not store else range(2,7)):
        nodes.append(node(40+slot, 'UnboxNode', boxingKind='JavaKind.Long', stamp='i64'))
        edges.append(edge(20+slot,40+slot))
    if store:
        for lane in range(4):
            nodes.append(node(110+lane, 'NarrowNode', stamp='i32', inputBits=64, resultBits=32))
            edges.append(edge(43+lane,110+lane))
        nodes += [node(130, 'SimdBroadcastNode', stamp=I32X4, length=4),
                  node(134, 'ReinterpretNode', stamp=I8X16)]
        edges += [edge(110,130), edge(133,134), edge(134,70), edge(31,80,'result')]
        for lane in (1,2,3):
            nodes.append(node(130+lane, 'SimdInsertNode', stamp=I32X4, offset=lane))
            edges += [edge(129+lane,130+lane,'x'), edge(110+lane,130+lane,'y')]
    else:
        nodes += [node(71, 'ReinterpretNode', stamp=I32X4),
                  node(81, 'BoxNode$AllocatingBoxNode', stamp='a!# java.lang.Long')]
        edges += [edge(70,71), edge(81,80,'result')]
        for lane in range(4):
            nodes += [node(110+lane, 'SimdCutNode', stamp='i32', offset=lane, length=1),
                      node(120+lane, 'ZeroExtendNode', stamp='i64', inputBits=32, resultBits=64)]
            edges += [edge(71,110+lane), edge(110+lane,120+lane), edge(120+lane,81)]
    return summarize(dict(nodes=nodes, edges=edges))


def frame_tags(entry='vectorIndexWorker'):
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


def cfg(entry='vectorIndexWorker', line=None):
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
            node(203,'FrameState',code=cached+'.handleIndexVectorWord32Array$Index_(Lcom/oracle/truffle/api/impl/FrameWithoutBoxing;, [B, J, J)'),
            node(204,'FrameState',code=cached+'.continueAt(Lthc/runtime/BytecodeRootGen;, Lcom/oracle/truffle/api/impl/FrameWithoutBoxing;, J)')]
        for state,slot,frame_slot in ((203,2,1),(204,7,6)):
            value['edges'] += [edge(201,state,'values',index=slot),edge(91,state,'values',index=frame_slot),
                              edge(202,state,'values',index=0)]
        value['edges'].append(edge(203,204,'outerFrameState','State'))
    else:
        value['nodes'] += [node(203,'FrameState',code='thc.runtime.VectorWord32Unpack.executeTuple(Lcom/oracle/truffle/api/frame/VirtualFrame;, [I, I)'),
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


class GraphTest(unittest.TestCase):
    def reject(self, value, entry='vectorIndexWorker'):
        with self.assertRaises(AssertionError): audit.inspect_graph(summarize(value), entry)

    def test_all_four_memory_entries(self):
        for entry in audit.ENTRIES:
            result = audit.inspect_graph(graph(entry), entry)
            self.assertEqual(len(result['unsignedLaneExtensions'])+len(result['packedStoreLanes']),4)

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

    def test_all_unsigned_output_lanes_live(self):
        for mutation in ('missing','duplicate','signed','wrong_bits','dead','rewired','extra_use'):
            value=graph()
            if mutation=='missing': value['edges']=[e for e in value['edges'] if e['from']!=123]
            elif mutation=='duplicate': by_id(value,113)['properties']['offset']=2
            elif mutation=='signed': by_id(value,120)['nodeClass']='test.SignExtendNode'
            elif mutation=='wrong_bits': by_id(value,120)['properties']['inputBits']=16
            elif mutation=='dead': value['edges']=[e for e in value['edges'] if e['from']!=70 or e['type']!='Value']
            elif mutation=='rewired': next(e for e in value['edges'] if e['to']==120)['from']=111
            else: value['edges'].append(edge(110,81))
            self.reject(value)

    def test_all_store_lanes_from_correct_host_arguments(self):
        entry='vectorStoreGraph'
        for mutation in ('wrong_lane','wrong_slot','wrong_width','unsigned','wrong_insert','wrong_broadcast','constant_payload','wrong_return'):
            value=graph(entry)
            if mutation=='wrong_lane': next(e for e in value['edges'] if e['to']==112)['from']=43
            elif mutation=='wrong_slot': by_id(value,13)['properties']['rawvalue']='4'
            elif mutation=='wrong_width': by_id(value,110)['properties']['resultBits']=16
            elif mutation=='unsigned': by_id(value,110)['nodeClass']='test.ZeroExtendNode'
            elif mutation=='wrong_insert': by_id(value,133)['properties']['offset']=2
            elif mutation=='wrong_broadcast': by_id(value,130)['properties']['length']=8
            elif mutation=='constant_payload': by_id(value,134)['nodeClass']='test.ConstantNode'
            else: next(e for e in value['edges'] if e['to']==80 and e['type']=='Value')['from']=22
            self.reject(value,entry)

    def test_no_private_payload_allocations_calls_fields(self):
        for name in ('NewArrayNode','NewInstanceNode','CommitAllocationNode','InvokeNode','ForeignCallNode',
                     'LoadFieldNode','StoreFieldNode','StoreIndexedNode','UnsafeLoadNode','AtomicReadNode'):
            value=graph(); value['nodes'].append(node(200,name)); self.reject(value)
        for stamp in ('a!# byte[]','a int[]','a short[]','a jdk.incubator.vector.Byte128Vector',
                      'a thc.runtime.Word32X4','a thc.runtime.Int32X4'):
            value=graph();value['nodes'].append(node(200,'ConstantNode',stamp=stamp));self.reject(value)

    def test_scalar_memory_and_lane_boxes_rejected(self):
        for extra in (node(200,'LoadIndexedNode',elementKind='JavaKind.Byte'),
                      node(200,'BoxNode$AllocatingBoxNode',stamp='a!# java.lang.Integer'),
                      node(200,'UnboxNode',boxingKind='JavaKind.Int'),
                      node(200,'BoxNode$AllocatingBoxNode',stamp='a!# java.lang.Long')):
            value=graph(); value['nodes'].append(extra); self.reject(value)

    def test_exact_frame_metadata_exception(self):
        self.assertEqual(audit.inspect_graph(frame_tags(),'vectorIndexWorker')['frameTagMetadata'],[90])
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

    def test_duplicate_or_dangling_nodes_fail(self):
        value=graph();value['nodes'].append(copy.deepcopy(value['nodes'][0]));self.reject(value)
        value=graph();value['edges'].append(edge(999,70));self.reject(value)

    def test_fractional_and_boolean_graph_counts_fail(self):
        for ident,key in ((1,'index'),(110,'offset'),(110,'length'),(120,'inputBits'),(120,'resultBits')):
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
        self.assertEqual(audit.inspect_graph(summarize(value),'vectorIndexWorker')['frameTagMetadata'],[90])
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
            self.assertEqual(audit.inspect_graph(value,'vectorIndexWorker')['interpreterArrayMetadata'],[201])

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
                audit.inspect_graph(summarize(value),'vectorIndexWorker')

    def test_exact_host_bloom_bookkeeping_is_not_a_guest_lane(self):
        result=audit.inspect_graph(bloom_metadata(),'vectorStoreGraph')
        self.assertEqual(result['bloomBookkeepingUnbox'],201)
        self.assertEqual(len(result['packedStoreLanes']),4)

    def test_bloom_mask_slot_owner_and_escape_negatives(self):
        for mutation in ('mask','mask_width','dynamic_mask','operator','frame_slot','header_slot','wrong_owner',
                         'duplicate','address','payload','returned','state_escape','extra_argument'):
            value=bloom_metadata()
            if mutation=='mask':by_id(value,203)['properties']['rawvalue']='0'
            elif mutation=='mask_width':by_id(value,203)['properties']['stamp']='i32'
            elif mutation=='dynamic_mask':by_id(value,203)['nodeClass']='test.AddNode'
            elif mutation=='operator':by_id(value,202)['nodeClass']='test.AddNode'
            elif mutation=='frame_slot':next(e for e in value['edges'] if e['from']==202)['listIndex']=1
            elif mutation=='header_slot':next(e for e in value['edges'] if e['to']==201)['from']=27
            elif mutation=='wrong_owner':by_id(value,91)['properties']['fields'][3]='payload'
            elif mutation=='duplicate':
                value['nodes'].append(node(205,'UnboxNode',boxingKind='JavaKind.Long',stamp='i64'));value['edges'].append(edge(20,205))
            elif mutation=='address':value['edges'].append(edge(201,61,'x'))
            elif mutation=='payload':next(e for e in value['edges'] if e['to']==110)['from']=201
            elif mutation=='returned':next(e for e in value['edges'] if e['to']==80 and e['type']=='Value')['from']=202
            elif mutation=='state_escape':value['edges'].append(edge(202,94,'values',index=2))
            else:
                value['nodes'].append(node(205,'UnboxNode',boxingKind='JavaKind.Long',stamp='i64'));value['edges'].append(edge(27,205))
            with self.subTest(mutation=mutation):self.reject(value,'vectorStoreGraph')


class LirTest(unittest.TestCase):
    def inspect(self,text,entry='vectorIndexWorker'):
        return audit.inspect_lir(text,'lambda bytes, offset',entry,'x86_64')

    def test_all_selected_memory_instructions(self):
        for entry in audit.ENTRIES:self.assertEqual(len(self.inspect(cfg(entry),entry)[1]),1)

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
        with self.assertRaises(AssertionError):audit.inspect_lir(cfg(),'lambda bytes, offset','vectorIndexWorker','aarch64')

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
    boundary=(0,1,65535,65536,0x7fffffff,0x80000000,0x80000001,0xfffffffe,0xffffffff)
    native={};rows=[]
    for lane in range(4):
        for value in boundary:
            lanes=[0x81234567,0x92345678,0xa3456789,0xb456789a];lanes[lane]=value
            # 16-byte units have exactly four safe starts, 4-byte units thirteen.
            offset=len(rows)%(4 if scale==16 else 13)
            initial=list(b''.join((0x89abcdef+i*0x01030507).to_bytes(4,'little') for i in range(16)))
            expected=list(initial)
            for i,v in enumerate(lanes):expected[offset*scale+4*i:offset*scale+4*i+4]=(v&0xffffffff).to_bytes(4,'little')
            score=sum(v*w for v,w in zip(lanes,(3,5,7,11)))
            name=('vector' if scale==16 else 'scalar')+('IndexCase' if operation=='index' else 'StoreCase')
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
            else:row['lanes'][0]=-1
            with self.subTest(mutation=mutation),self.assertRaises(AssertionError):audit.validate_graph_cases(definition,native)

    def test_truncated_native_selector_mapping_fails(self):
        definition,native=cases('scalarStoreGraph');native.pop(next(iter(native)))
        with self.assertRaises(KeyError):audit.validate_graph_cases(definition,native)

    def test_fractional_and_boolean_fixture_metadata_fail(self):
        for key in ('arity','offsetUnitBytes'):
            for replacement in (float(audit.ENTRIES['vectorIndexWorker'][2 if key=='arity' else 1]),True):
                definition,native=cases('vectorIndexWorker');definition[key]=replacement
                with self.assertRaises(AssertionError):audit.validate_graph_cases(definition,native)
        for key in ('offset','expectedScalar','lanes','nativeArguments'):
            definition,native=cases('vectorIndexWorker');row=definition['cases'][0]
            if key in ('lanes','nativeArguments'):row[key][0]=float(row[key][0])
            else:row[key]=float(row[key])
            with self.assertRaises(AssertionError):audit.validate_graph_cases(definition,native)

    def test_all_unsigned_high_bits_affect_checksum(self):
        for entry in audit.ENTRIES:
            definition,native=cases(entry)
            for row in definition['cases']:
                signed=sum((v if v < 1<<31 else v-(1<<32))*w for v,w in zip(row['lanes'],(3,5,7,11)))
                self.assertNotEqual(row['expectedScalar'],signed)

    def test_correlated_replacement_corpus_cannot_change_sentinels(self):
        definition,native=cases('vectorIndexWorker');row=definition['cases'][0]
        row['lanes'][1]+=1
        row['nativeArguments']=[row['offset'],*row['lanes']]
        row['expectedScalar']=sum(v*w for v,w in zip(row['lanes'],(3,5,7,11)))
        start=row['offset']*16+4
        replacement=list(row['lanes'][1].to_bytes(4,'little'))
        row['initialBytes'][start:start+4]=replacement
        row['expectedBytes'][start:start+4]=replacement
        native[(row['nativeEntry'],*row['nativeArguments'])]=row['expectedScalar']
        with self.assertRaisesRegex(AssertionError,'exact rotated unsigned boundary/sentinel corpus'):
            audit.validate_graph_cases(definition,native)

    def test_oracle_missing_duplicate_wrong_arity_and_negative_zero(self):
        entries={'test':dict(arity=1,cases=[[0]])}
        self.assertEqual(audit.parse_oracle('test\t0\t1\n',entries),{('test',0):1})
        for text in ('','test\t0\t1\ntest\t0\t1\n','test\t0\t2\t1\n','test\t-0\t1\n','other\t0\t1\n'):
            with self.assertRaises(AssertionError):audit.parse_oracle(text,entries)

    def test_offline_correction_cannot_change_runtime_probe_runner_or_jdk(self):
        root=Path('/source')
        names=('runtime-audit.py','test-runtime-audit.py','run-runtime.sh','Word32X4ByteArrayRuntimeGraphProbe.java')
        sources=[dict(path=str(root/'bench/experiments/word32x4-bytearray'/name),sha256='a') for name in names]
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
                    audit.inspect_graph(value,'vectorIndexWorker')
        self.assertEqual(len(set(hashes)),4)

    def test_real_signed_memory_loads_fail_exact_unsigned_extension(self):
        # These same-arity historical loads have real packed memory but the
        # opposite extension. No transformed graph or new guest execution.
        root=Path(__file__).resolve().parents[3]
        evidence=root/'bench/experiments/int32x4-bytearray/evidence-x86_64'
        hashes=[]
        for stage in ('pre','post'):
            for backend in ('ast','bytecode'):
                for entry in ('vectorIndexWorker','scalarIndexWorker'):
                    path=evidence/f'{stage}-{backend}-{entry}/graph.json.gz'
                    raw=path.read_bytes();value=json.loads(gzip.decompress(raw));hashes.append(hashlib.sha256(raw).hexdigest())
                    with self.assertRaisesRegex(AssertionError,'Loaded Word32 lane must zero-extend 32 to 64 bits'):
                        audit.inspect_graph(value,entry)
        self.assertEqual(len(set(hashes)),8)


if __name__=='__main__':unittest.main()
