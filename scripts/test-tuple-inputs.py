#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Typed-ingress contracts: exact logical proofs, never width guesses."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('tuple_input_audit', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
from core_tuple_inputs import LONG_REPS, proof_error
from core_vectors import VECTOR_REP, LANE_REP

CAP = json.loads((ROOT / 'core-capabilities.json').read_text())
ENABLED = dict(CAP, aggregateInputs=['empty-unboxed-tuple', 'unboxed-tuple'])
DISABLED = dict(CAP, aggregateInputs=['empty-unboxed-tuple'])
LONG = dict(kind='long', primReps=['IntRep'], evaluated=True)
STATE = dict(kind='void', primReps=[], evaluated=True)
REF = dict(kind='object', primReps=['BoxedRep (Just Lifted)'], evaluated=False)
CLOSURE = dict(REF, kind='closure', evaluated=True)

def tup(*components):
    return dict(kind='unknown', aggregate='unboxed-tuple', components=list(components),
                primReps=[r for c in components for r in c['primReps']], evaluated=True)

def var(name, proof=CLOSURE):
    return ['var', name] + ([] if proof is None else [dict(rep=copy.deepcopy(proof))])

def lit(n=7): return ['lit', 'int', str(n), dict(rep=copy.deepcopy(LONG))]
def bind(name, expr, proof=CLOSURE, lifted=True):
    return dict(id=name, name=name, expr=expr, rep=copy.deepcopy(proof), lifted=lifted)

def fixture(formals, actuals=None):
    formals=copy.deepcopy(formals);actuals=copy.deepcopy(formals if actuals is None else actuals)
    cons={}
    def value(rep):
        if audit.Audit.is_tuple(rep):
            parts=rep['components'];key=f'Tuple{len(parts)}'
            cons[key]=dict(id=key,kind='unboxed-tuple',arity=len(parts),fieldReps=[None]*len(parts),
                           fieldLifted=[None]*len(parts),strictFields=[False]*len(parts))
            if not parts:return ['con',key,0,dict(rep=rep)]
            return ['app',['con',key,len(parts)],[value(p) for p in parts],
                    [lifted(p) for p in parts],True,True,dict(rep=rep)]
        if rep['kind']=='void':return ['void',dict(rep=rep)]
        if rep['kind'] in ('data','object','closure'):
            key='Box'+('Lifted' if lifted(rep) else 'Unlifted')
            cons[key]=dict(id=key,kind='boxed',arity=0,fieldReps=[],fieldLifted=[],strictFields=[])
            return ['con',key,0,dict(rep=rep)]
        kind=rep['kind'] if rep['kind'] in ('float','double') else 'int'
        return ['lit',kind,'1' if kind=='int' else '1.0',dict(rep=rep)]
    parameters=[dict(id=f'p{i}',lifted=lifted(p),rep=p) for i,p in enumerate(formals)]
    worker=bind('worker',['lam',parameters,lit(),dict(rep=CLOSURE,resultRep=LONG)])
    worker['arity']=len(formals)
    call=['app',var('worker'),[value(p) for p in actuals],[lifted(p) for p in actuals],False,False,dict(rep=LONG)]
    return dict(schema=1,ghc='9.14.1',constructors=list(cons.values()),bindings=[bind('root',call,LONG),worker])

def lifted(proof):
    return 'aggregate' not in proof and proof.get('primReps')==['BoxedRep (Just Lifted)']

def report(module, entry='root', cap=ENABLED):
    return audit.Audit([('tuple-input.json',module)],cap).run([entry])

class TupleInputs(unittest.TestCase):
    def accepted(self,module,**kw):
        r=report(module,**kw);self.assertTrue(r['accepted'],r['issues']);return r
    def rejected(self,module,code=None,detail=None,**kw):
        r=report(module,**kw);self.assertFalse(r['accepted'])
        if code:self.assertIn(code,{i['code'] for i in r['issues']},r['issues'])
        if detail:self.assertIn(detail,[i['detail'] for i in r['issues']],r['issues'])
        return r

    def test_disabled_capability_rejects_nonempty_and_preserves_exact_empty(self):
        self.rejected(fixture([tup(LONG)]),cap=DISABLED,detail='unboxed-tuple formal argument')
        self.accepted(fixture([tup(),LONG]),cap=DISABLED)

    def test_recursive_mixed_and_zero_width_inputs_preserve_leaf_kinds(self):
        for shape in [tup(),tup(STATE),tup(tup()),tup(LONG),tup(STATE,tup(),LONG),
                      tup(tup(LONG,REF),dict(kind='float',primReps=['FloatRep'],evaluated=True),
                          dict(kind='double',primReps=['DoubleRep'],evaluated=True),
                          dict(REF,primReps=['BoxedRep (Just Unlifted)'],evaluated=True)),
                      tup(*[dict(LONG,primReps=[r]) for r in sorted(LONG_REPS)])]:
            self.assertIsNone(proof_error(shape));self.accepted(fixture([LONG,shape,LONG]))

    def test_lazy_lifted_payload_evaluatedness_and_boxed_kind_refine_independently(self):
        module=fixture([tup(dict(REF,kind='data'))],[tup(REF)])
        self.accepted(module)
        for evaluated in (False,True):
            module['bindings'][1]['expr'][1][0]['rep']['components'][0]['evaluated']=evaluated
            self.accepted(module)

    def test_unknown_and_unsupported_leaf_proofs_fail_even_in_unused_formals(self):
        # A valid SIMD leaf is supported with tuple-fields enabled. Its negative
        # specimen must instead contradict the exact physical VecRep annotation.
        bad=[dict(kind='unknown',primReps=None,evaluated=True),dict(REF,primReps=['BoxedRep Nothing']),
             dict(kind='address',primReps=['AddrRep'],evaluated=True),
             dict(kind='vector',primReps=['VecRep 2 Int64ElemRep'],vector=dict(lanes=4,element='Int64ElemRep'),evaluated=True),
             dict(kind='unknown',aggregate='unboxed-sum',alternatives=None,primReps=None,evaluated=True),
             dict(LONG,primReps=[]),dict(STATE,primReps=['IntRep'])]
        for leaf in bad:
            shape=tup(LONG);shape['components']=[leaf];shape['primReps']=leaf['primReps']
            self.assertIsNotNone(proof_error(shape, allow_vectors=True))
            module=fixture([tup(LONG)]);module['bindings'][1]['expr'][1][0]['rep']=shape
            self.rejected(module,detail='unboxed-tuple formal argument')

    def test_exact_vector_tuple_component_obeys_transport_capability(self):
        shape=tup(copy.deepcopy(VECTOR_REP))
        self.assertIsNone(proof_error(shape, allow_vectors=True))
        self.assertIsNotNone(proof_error(shape, allow_vectors=False))
        module=fixture([shape])
        # Supply a real vector producer to the guest worker's tuple argument;
        # the generic scalar fixture literal cannot establish this proof.
        module['bindings'][0]['expr'][2][0][2][0]=[
            'app',['prim','broadcastInt64X2#',dict(rep=copy.deepcopy(CLOSURE))],
            [['lit','int64','1',dict(rep=copy.deepcopy(LANE_REP))]],
            [False],False,False,dict(rep=copy.deepcopy(VECTOR_REP))]
        self.accepted(module)
        disabled=dict(ENABLED,vectorTransport=[name for name in ENABLED['vectorTransport'] if name!='tuple-fields'])
        self.rejected(module,cap=disabled,detail='unboxed-tuple formal argument')

    def test_null_missing_and_malformed_logical_layouts_are_not_flat_register_inference(self):
        for change in [dict(components=None),dict(components=[]),dict(kind='long'),dict(primReps=None),dict(evaluated=None)]:
            proof=dict(tup(LONG),**change);self.assertIsNotNone(proof_error(proof))
            module=fixture([tup(LONG)]);module['bindings'][1]['expr'][1][0]['rep']=proof
            self.rejected(module)
        proof=tup(LONG);proof.pop('components');self.assertIsNotNone(proof_error(proof))

    def test_same_storage_has_distinct_logical_boundaries_and_primitive_names(self):
        for left,right in [(tup(LONG),LONG),(tup(),STATE),(tup(),REF),
                           (tup(LONG,tup()),tup(tup(),LONG)),(tup(STATE),tup(tup())),
                           (tup(LONG),tup(dict(LONG,primReps=['WordRep']))),
                           (tup(REF),tup(dict(REF,primReps=['BoxedRep (Just Unlifted)'],evaluated=True)))]:
            self.rejected(fixture([left],[right]),code='aggregate-shape')

    def test_raw_unlifted_flags_survive_call_and_entry_demand(self):
        for where in ('formal','actual','missing'):
            module=fixture([tup(LONG)]);call=module['bindings'][0]['expr'];worker=module['bindings'][1]['expr']
            worker[3]['entryStrict']=[True];call[6]['callStrict']=[True]
            if where=='formal':worker[1][0]['lifted']=True
            elif where=='actual':call[3]=[True]
            else:call[3]=[]
            self.rejected(module,code='application-levity')

    def test_lexical_tuple_proof_can_supply_absent_or_unknown_occurrence(self):
        shape=tup(LONG,tup(STATE,REF))
        for proof in (None,dict(kind='unknown',primReps=None,evaluated=False),shape):
            module=fixture([shape]);worker=module['bindings'][1]
            callee=copy.deepcopy(worker);callee.update(id='callee',name='callee')
            worker['expr'][2]=['app',var('callee'),[var('p0',proof)],[False],False,False,dict(rep=LONG)]
            module['bindings'].append(callee);self.accepted(module)
            worker['expr'][2][2][0]=var('p0',tup(LONG,tup(REF,STATE)))
            self.rejected(module,code='aggregate-shape')

    def test_missing_actual_proof_without_lexical_source_cannot_satisfy_tuple_formal(self):
        for actual in [['lit','int','1'],['void'],['lit','int','1',dict(rep=dict(kind='unknown',primReps=None,evaluated=True))]]:
            module=fixture([tup(LONG)]);module['bindings'][0]['expr'][2]=[actual]
            self.rejected(module,code='aggregate-shape')

    def test_known_global_lambda_alias_and_pap_keep_logical_positions(self):
        for target in ('lambda','global','pap','local-pap'):
            shape=tup(LONG);module=fixture([LONG,shape,LONG]);call=module['bindings'][0]['expr']
            if target=='lambda':call[1]=module['bindings'][1]['expr']
            if target=='global':module['bindings'].append(bind('alias',var('worker')));call[1]=var('alias')
            if target in ('pap','local-pap'):
                prefix=['app',call[1],call[2][:1],call[3][:1],False,False,dict(rep=CLOSURE)]
                suffix=['app',prefix,call[2][1:],call[3][1:],False,False,dict(rep=LONG)]
                module['bindings'][0]['expr']=suffix;call=suffix
                if target=='local-pap':
                    suffix[1]=var('partial');module['bindings'][0]['expr']=['let',False,[bind('partial',prefix)],suffix,dict(rep=LONG)]
            self.accepted(module)
            position=0 if target in ('pap','local-pap') else 1
            call[2][position]=lit();self.rejected(module,code='aggregate-shape')

    def test_tuple_only_pap_prefix_and_remaining_scalar_position(self):
        for shape in (tup(tup(),STATE),tup(LONG,REF)):
            module=fixture([shape,LONG]);call=module['bindings'][0]['expr']
            partial=['app',call[1],call[2][:1],[False],False,False,dict(rep=CLOSURE)]
            call[1]=partial;call[2]=call[2][1:];call[3]=[False]
            self.accepted(module)
            call[2]=copy.deepcopy(partial[2]);self.rejected(module,code='aggregate-shape')

    def test_definition_site_alias_scope_and_reversed_recursive_group(self):
        for recursive in (False,True):
            module=fixture([tup(LONG)]);call=module['bindings'][0]['expr'];worker=module['bindings'][1]
            alias=bind('alias',var('worker'));second=bind('second',var('alias'));call[1]=var('second')
            if recursive:
                module['bindings']=[bind('root',['let',True,[second,alias,worker],call,dict(rep=LONG)],LONG)]
            else:
                shadow=fixture([STATE])['bindings'][1]
                module['bindings'].extend([alias,second]);module['bindings'][0]['expr']=['let',False,[shadow],call,dict(rep=LONG)]
            self.accepted(module)
            call[2]=[['void',dict(rep=STATE)]];self.rejected(module,code='aggregate-shape')

    def test_all_cold_arms_are_checked(self):
        module=fixture([tup(LONG)]);bad=module['bindings'][0]['expr']
        module['bindings'][0]['expr']=['case',lit(0),'choice',[
            ['lit',['int','0'],[],lit()],['default',None,[],bad]],
            dict(rep=LONG,binder=dict(id='choice',rep=LONG,lifted=False))]
        self.accepted(module)
        bad[2]=[lit()]
        self.rejected(module,code='aggregate-shape')

    def test_tuple_case_and_result_forwarding_use_exact_lexical_proofs(self):
        shape=tup(LONG)
        module=fixture([shape]);worker=module['bindings'][1]
        worker['expr'][2]=['case',var('p0',None),'caseTuple',[
            ['data','Tuple1',['field'],lit(),dict(binders=[dict(id='field',lifted=False,rep=LONG)])]],
            dict(rep=LONG,binder=dict(id='caseTuple',rep=shape,lifted=False))]
        self.accepted(module)
        worker['expr'][2][1]=var('p0',tup(dict(LONG,primReps=['WordRep'])))
        self.rejected(module,code='aggregate-shape')
        module=fixture([shape]);worker=module['bindings'][1]
        worker['expr'][2]=['case',lit(0),'choose',[['default',None,[],var('p0',None)]],
                             dict(rep=shape,binder=dict(id='choose',rep=LONG,lifted=False))]
        worker['expr'][3]['resultRep']=shape
        call=module['bindings'][0]['expr'];call[6]['rep']=shape
        module['bindings'][0]['expr']=['case',call,'result',[['default',None,[],lit()]],
                                       dict(rep=LONG,binder=dict(id='result',rep=shape,lifted=False))]
        self.accepted(module)
        worker['expr'][2]=var('p0',None);self.accepted(module)

    def test_dynamic_target_allows_exact_tuple_but_rejects_unknown_leaf(self):
        module=fixture([tup(LONG)]);call=module['bindings'][0]['expr'];call[1]=var('f')
        module['bindings'][0]['expr']=['lam',[dict(id='f',rep=CLOSURE,lifted=True)],call,dict(rep=CLOSURE,resultRep=LONG)]
        self.accepted(module)
        call[2][0][6]['rep']['components'][0]=dict(REF,primReps=['BoxedRep Nothing'])
        call[2][0][6]['rep']['primReps']=['BoxedRep Nothing']
        self.rejected(module,detail='unboxed-tuple argument')

    def test_join_formals_captures_local_storage_and_primitive_boundaries_remain_rejected(self):
        shape=tup(LONG)
        module=fixture([shape]);worker=module['bindings'].pop();worker.update(joinValueArity=1,joinResultRep=LONG,info=dict(joinArity=1))
        module['bindings'][0]['expr']=['let',False,[worker],module['bindings'][0]['expr'],dict(rep=LONG)]
        self.rejected(module,detail='unboxed-tuple formal argument')
        module=fixture([shape]);worker=module['bindings'][1]
        worker['expr'][2]=['lam',[],var('p0',shape),dict(rep=CLOSURE,resultRep=shape)];worker['expr'][3]['resultRep']=CLOSURE
        module['bindings'][0]['expr'][6]['rep']=CLOSURE
        self.rejected(module,detail='unboxed-tuple capture')
        for declared in (shape,CLOSURE):
            module=fixture([shape]);value=module['bindings'][0]['expr'][2][0]
            module['bindings'][0]['expr']=['let',False,[bind('stored',value,declared)],lit(),dict(rep=LONG)]
            self.rejected(module,detail='unboxed-tuple let binding')
        module=fixture([shape]);module['bindings'][0]['expr'][1]=['prim','negateInt#']
        self.rejected(module,detail='unboxed-tuple argument')

    def test_boxed_constructor_fields_cannot_hide_zero_or_single_slot_tuples(self):
        for shape in (tup(),tup(STATE),tup(LONG)):
            module=fixture([shape]);call=module['bindings'][0]['expr']
            module['constructors'].append(dict(id='Heap',kind='boxed',arity=1,fieldReps=[shape['primReps']],fieldTypes=[shape],strictFields=[False],fieldLifted=[False]))
            call[1]=['con','Heap',1];call[6]['rep']=REF
            self.rejected(module,detail='unboxed-tuple heap field')
            self.rejected(module,detail='unboxed-tuple argument')

    def test_constructor_alias_and_pap_are_not_ordinary_tuple_ingress(self):
        for precise in (False,True):
            for partial in (False,True):
                module=fixture([tup(LONG)]);call=module['bindings'][0]['expr']
                con=dict(id='Heap',kind='boxed',arity=2 if partial else 1,
                         fieldReps=[['IntRep']]*(2 if partial else 1),
                         strictFields=[False]*(2 if partial else 1),fieldLifted=[False]*(2 if partial else 1))
                if precise:con['fieldTypes']=[LONG]*(2 if partial else 1)
                module['constructors'].append(con)
                target=['con','Heap',con['arity']]
                if partial:target=['app',target,[lit()],[False],False,False,dict(rep=CLOSURE)]
                module['bindings'].append(bind('constructorAlias',target))
                call[1]=var('constructorAlias');call[6]['rep']=REF
                self.rejected(module,code='aggregate-shape')

    def test_host_lambda_alias_and_residual_pap_boundaries_are_rejected(self):
        for target in ('worker','alias','pap'):
            module=fixture([LONG,tup(LONG)]);module['bindings'].append(bind('alias',var('worker')))
            call=module['bindings'][0]['expr'];module['bindings'].append(bind('pap',['app',call[1],call[2][:1],[False],False,False,dict(rep=CLOSURE)]))
            self.rejected(module,entry=target,detail='unboxed-tuple host argument')
        module=fixture([LONG]);worker=module['bindings'][1];shape=tup(LONG)
        module['constructors'].extend(fixture([shape])['constructors'])
        worker['expr'][2]=fixture([shape])['bindings'][0]['expr'][2][0];worker['expr'][3]['resultRep']=shape
        module['bindings'].append(bind('alias',var('worker')))
        for target in ('worker','alias'):self.rejected(module,entry=target,detail='unboxed-tuple host result')

    def test_genuine_pre_post_exports_require_enabled_typed_ingress(self):
        directory=Path(os.environ.get('THC_TUPLE_INPUT_FIXTURE',ROOT.parent/'build/tuple-input'))
        paths=[directory/f'{stage}-core/TupleInputAudit.json' for stage in ('pre','post')]
        if not all(path.is_file() for path in paths):self.skipTest('Run prepare-tuple-input-audit.py or point THC_TUPLE_INPUT_FIXTURE at genuine exports')
        entries=list(dict.fromkeys(line.split('\t')[0] for line in (directory/'oracle.tsv').read_text().splitlines()))+['pairInputs']
        for path in paths:
            module=json.loads(path.read_text())
            r=audit.Audit([(str(path),module)],ENABLED).run(entries);self.assertTrue(r['accepted'],r['issues'])
            r=audit.Audit([(str(path),module)],DISABLED).run(entries);self.assertFalse(r['accepted'])
            self.assertIn('unboxed-tuple formal argument',[i['detail'] for i in r['issues']])

if __name__=='__main__':unittest.main()
