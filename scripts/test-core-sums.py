#!/usr/bin/env python3
"""Binary sum proof and auditor boundaries, without changing the capability file."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import core_sums as sums

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('sum_audit', ROOT/'scripts/audit-core.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
CAP = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
ENABLED = dict(CAP, aggregateResults=['unboxed-tuple','unboxed-sum'])
INT = dict(kind='long', primReps=['IntRep'], evaluated=True)
WORD = dict(kind='long', primReps=['WordRep'], evaluated=True)
FLOAT = dict(kind='float', primReps=['FloatRep'], evaluated=True)
DOUBLE = dict(kind='double', primReps=['DoubleRep'], evaluated=True)
VOID = dict(kind='void', primReps=[], evaluated=True)
BOX = dict(kind='data', primReps=[sums.LIFTED], evaluated=False)
CLOSURE = dict(BOX, kind='closure', evaluated=True)
UNLIFTED = dict(kind='object', primReps=[sums.UNLIFTED], evaluated=True)


def tup(*fields):
    return dict(kind='unknown', aggregate='unboxed-tuple', components=list(fields),
                primReps=[r for f in fields for r in f['primReps']], evaluated=True)


def summ(first=INT, second=WORD):
    reps, slots=sums.layout([first,second])
    return dict(kind='unknown', aggregate='unboxed-sum', alternatives=[first,second],
                primReps=reps, tagSlot=0, alternativeSlots=slots, evaluated=True)


def binder(name, proof):
    return dict(id=name, lifted=sums.lifted_payload(proof), rep=copy.deepcopy(proof))


def var(name, proof=None):
    return ['var',name,*([dict(rep=copy.deepcopy(proof))] if proof is not None else [])]


def lit(value=1, proof=INT):
    return ['lit','int',str(value),dict(rep=copy.deepcopy(proof))]


def lam(parameters,body,result):
    return ['lam',parameters,body,dict(rep=CLOSURE,resultRep=copy.deepcopy(result))]


def binding(name,expr,proof=CLOSURE):
    return dict(id=name,name=name,lifted=True,rep=copy.deepcopy(proof),expr=expr)


def fixture(proof=None):
    proof=copy.deepcopy(proof or summ())
    constructors=[dict(id=f'Sum{i}',kind='unboxed-sum',arity=1,sumArity=2,tag=i,
                       fieldReps=[None],strictFields=[False],fieldLifted=[None]) for i in (1,2)]
    def payload(rep):
        if rep.get('aggregate')=='unboxed-tuple':
            key='Tuple'+str(len(rep['components']))
            if not any(c['id']==key for c in constructors):
                constructors.append(dict(id=key,kind='unboxed-tuple',arity=len(rep['components']),fieldReps=[None]*len(rep['components'])))
            if not rep['components']: return ['con',key,0,dict(rep=copy.deepcopy(rep))]
            return ['app',['con',key,len(rep['components']),dict(rep=CLOSURE)],
                    [payload(p) for p in rep['components']],[sums.lifted_payload(p) for p in rep['components']],
                    True,True,dict(rep=copy.deepcopy(rep))]
        if rep['kind']=='void': return ['void',dict(rep=copy.deepcopy(rep))]
        if rep['kind'] in ('data','closure','object'):
            key='Box'
            if not any(c['id']==key for c in constructors):
                constructors.append(dict(id=key,kind='boxed',arity=0,fieldReps=[],strictFields=[],fieldLifted=[]))
            return ['con',key,0,dict(rep=copy.deepcopy(rep))]
        if rep['kind'] in ('float','double'):
            return ['lit',rep['kind'],'0',dict(rep=copy.deepcopy(rep))]
        return ['lit','word' if rep['primReps']==['WordRep'] else 'int','1',dict(rep=copy.deepcopy(rep))]
    selected=proof['alternatives'][0]
    value=['app',['con','Sum1',1,dict(rep=CLOSURE)],[payload(selected)],[sums.lifted_payload(selected)],True,True,dict(rep=copy.deepcopy(proof))]
    producer=binding('producer',lam([binder('x',INT)],value,proof))
    call=['app',var('producer',CLOSURE),[var('x',INT)],[False],False,False,dict(rep=copy.deepcopy(proof))]
    arms=[['data',f'Sum{i}',[f'field{i}'],lit(i),dict(binders=[binder(f'field{i}',p)])]
          for i,p in enumerate(proof['alternatives'],1)]
    case=['case',call,'case',arms,dict(rep=INT,binder=binder('case',proof))]
    return dict(schema=1,ghc='9.14.1',constructors=constructors,
                bindings=[binding('root',lam([binder('x',INT)],case,INT)),producer])


def run(module,entry='root',cap=ENABLED):
    return audit.Audit([('sum.json',module)],cap).run([entry])


class SumProofTest(unittest.TestCase):
    def test_exact_storage_and_ordered_projection(self):
        proof=summ(tup(DOUBLE,INT,INT,BOX),tup(UNLIFTED,FLOAT,WORD))
        self.assertIsNone(sums.proof_error(proof))
        self.assertEqual(['WordRep',sums.LIFTED,sums.UNLIFTED,'WordRep','WordRep','FloatRep','DoubleRep'],proof['primReps'])
        self.assertEqual([[6,3,4,1],[2,5,3]],proof['alternativeSlots'])

    def test_zero_and_singleton_tuple_payloads_stay_logical(self):
        for proof in (summ(VOID,tup()),summ(tup(VOID,tup()),tup(tup(),VOID)),summ(tup(BOX),BOX)):
            self.assertIsNone(sums.proof_error(proof))
        self.assertFalse(sums.lifted_payload(tup(BOX)))
        self.assertTrue(sums.lifted_payload(BOX))
        self.assertNotEqual(audit.Audit.shape(VOID),audit.Audit.shape(tup()))

    def test_unknown_nested_sum_and_unsupported_leaf_families_reject(self):
        invalid=[dict(INT,primReps=['Int32Rep']),dict(WORD,primReps=['Word64Rep']),
                 dict(BOX,primReps=['BoxedRep Nothing']),dict(INT,kind='unknown'),
                 dict(INT,kind='address',primReps=['AddrRep']),dict(kind='vector',primReps=['VecRep 2 Int64ElemRep'],evaluated=True),
                 dict(kind='unknown',primReps=None,evaluated=False),dict(tup(),components=None),summ(),tup(summ())]
        for field in invalid:
            with self.subTest(field=field):
                proof=dict(summ(),alternatives=[field,INT])
                self.assertIsNotNone(sums.proof_error(proof))

    def test_exact_tags_maps_and_storage_reject_forgery(self):
        for key,value in [('tagSlot',False),('tagSlot',0.0),('tagSlot',1),('alternatives',None),('alternatives',3),
            ('components',None),('components',[]),('vector',{}),
            ('alternatives',[INT]),('alternativeSlots',None),('alternativeSlots',[[True],[1]]),
            ('alternativeSlots',[[1.0],[1]]),('alternativeSlots',[[0],[1]]),
            ('primReps',['WordRep','WordRep','WordRep'])]:
            with self.subTest(key=key,value=value): self.assertIsNotNone(sums.proof_error(dict(summ(),**{key:value})))
        proof=summ(tup(INT,WORD),INT)
        self.assertIsNotNone(sums.proof_error(dict(proof,alternativeSlots=[[1,1],[1]])))
        self.assertIsNotNone(sums.proof_error(dict(proof,alternativeSlots=[[2,1],[1]])))
        self.assertIsNotNone(sums.proof_error(dict(summ(FLOAT,DOUBLE),primReps=['WordRep','DoubleRep','FloatRep'])))


class SumAuditTest(unittest.TestCase):
    def accepted(self,module,entry='root'):
        report=run(module,entry);self.assertTrue(report['accepted'],report['issues'])

    def rejected(self,module,entry='root'):
        report=run(module,entry);self.assertFalse(report['accepted']);return report

    def test_capability_and_constructor_saturation(self):
        module=fixture();self.accepted(module)
        disabled=copy.deepcopy(CAP)
        disabled['aggregateResults']=[kind for kind in disabled.get('aggregateResults',[]) if kind!='unboxed-sum']
        self.assertFalse(run(module,cap=disabled)['accepted'])
        for args in ([],[lit(),lit()]):
            forged=fixture();value=forged['bindings'][1]['expr'][2];value[2]=args;value[3]=[False]*len(args)
            self.rejected(forged)
        forged=fixture();value=forged['bindings'][1]['expr'][2]
        forged['bindings'][1]['expr'][2]=['con','Sum1',1,dict(rep=value[6]['rep'])]
        self.rejected(forged)

    def test_exact_family_tag_and_arity(self):
        for key,value in [('arity',True),('arity',1.0),('sumArity',2.0),('sumArity',3),('tag',True),('tag',1.0),('tag',0),('tag',3)]:
            with self.subTest(key=key,value=value):
                module=fixture();module['constructors'][0][key]=value;self.rejected(module)

    def test_tuple_state_empty_and_lazy_reference_payloads(self):
        for proof in (summ(VOID,tup()),summ(tup(INT,VOID,tup(INT)),FLOAT),summ(tup(BOX),DOUBLE),summ(BOX,UNLIFTED)):
            with self.subTest(proof=proof): self.accepted(fixture(proof))
        module=fixture(summ(tup(BOX),INT));module['bindings'][1]['expr'][2][3]=[True];self.rejected(module)
        module=fixture(summ(BOX,INT));module['bindings'][1]['expr'][2][3]=[False];self.rejected(module)

    def test_payload_mismatch_and_lexical_fallback(self):
        module=fixture();value=module['bindings'][1]['expr'][2]
        value[2]=[var('x')];self.accepted(module)
        value[2]=[var('x',WORD)];self.rejected(module)
        module=fixture();module['bindings'][1]['expr'][2][2]=[lit(1,WORD)];self.rejected(module)
        module=fixture();module['bindings'][1]['expr'][2][2]=[['lit','int','1']];self.rejected(module)

    def test_tuple_payload_uses_lexical_proof_without_erasing_logical_nesting(self):
        proof=summ(tup(tup(),INT),WORD)
        module=fixture(proof);value=module['bindings'][1]['expr'][2];payload=value[2][0]
        # The default returns the case-bound tuple without duplicating its proof
        # on the occurrence. Compilation retains the lexical exact layout.
        value[2][0]=['case',payload,'p',[['default',None,[],var('p')]],
                     dict(rep=proof['alternatives'][0],binder=binder('p',proof['alternatives'][0]))]
        self.accepted(module)
        # Equal physical width does not make a different empty-component position legal.
        value[2][0][3][0][3]=var('p',tup(INT,tup()))
        self.rejected(module)

    def test_state_empty_and_sum_case_binder_shapes_remain_distinct(self):
        for replacement in (tup(),tup(VOID)):
            module=fixture(summ(VOID,INT));arm=module['bindings'][0]['expr'][2][3][0]
            arm[4]['binders'][0]['rep']=replacement
            self.rejected(module)
        module=fixture();module['bindings'][0]['expr'][2][4]['binder']['rep']=summ(WORD,INT)
        self.rejected(module)
        module=fixture();module['bindings'][0]['expr'][2][4]['binder']['rep']=tup(WORD,WORD)
        self.rejected(module)

    def test_all_case_arms_ids_and_levities_are_checked(self):
        for mode in ('empty','duplicate','wrong-family','missing-record','malformed-record','wrong-id','wrong-shape','wrong-levity','default-binders','duplicate-default','lit','lifted-case'):
            with self.subTest(mode=mode):
                module=fixture();case=module['bindings'][0]['expr'][2];arm=case[3][1]
                if mode=='empty':case[3]=[]
                if mode=='duplicate':arm[1]='Sum1'
                if mode=='wrong-family':module['constructors'][1]['sumArity']=3
                if mode=='missing-record':del arm[4]
                if mode=='malformed-record':arm[4]['binders']=[None]
                if mode=='wrong-id':arm[4]['binders'][0]['id']='different'
                if mode=='wrong-shape':arm[4]['binders'][0]['rep']=INT
                if mode=='wrong-levity':arm[4]['binders'][0]['lifted']=True
                if mode=='default-binders':arm[0]='default'
                if mode=='duplicate-default':case[3]=[['default',None,[],lit()],['default',None,[],lit()]]
                if mode=='lit':case[3]=[['lit',['int','1'],[],lit()]]
                if mode=='lifted-case':case[4]['binder']['lifted']=True
                self.rejected(module)
        module=fixture();module['bindings'][0]['expr'][2][3]=[['default',None,[],lit()]];self.accepted(module)
        module=fixture();module['bindings'][0]['expr'][2][3]=module['bindings'][0]['expr'][2][3][:1];self.accepted(module)

    def test_sum_host_producer_alias_pap_and_global_storage_reject(self):
        self.rejected(fixture(),'producer')
        module=fixture();module['bindings'].append(binding('alias',var('producer',CLOSURE)));self.rejected(module,'alias')
        module=fixture();producer=module['bindings'][1]['expr'];producer[1].append(binder('y',INT))
        module['bindings'].append(binding('pap',['app',var('producer',CLOSURE),[lit()],[False],True,True,dict(rep=CLOSURE)]))
        self.rejected(module,'pap')
        module=fixture();proof=summ();value=module['bindings'][1]['expr'][2]
        module['bindings']=[binding('root',value,proof)];self.rejected(module)
        # The exact RHS still exposes unsupported storage if the binder lies or
        # carries only an unconstrained legacy record.
        for declared in (CLOSURE,dict(kind='unknown',primReps=None,evaluated=False)):
            module=fixture();value=module['bindings'][1]['expr'][2]
            module['bindings'].append(binding('stored',value,declared))
            module['bindings'][0]['expr'][2][1]=var('stored')
            report=self.rejected(module)
            self.assertTrue(any(i['detail']=='unboxed-sum global binding' for i in report['issues']))

    def test_formals_arguments_captures_let_and_join_results_reject(self):
        proof=summ()
        for mode in ('formal','argument','capture','let','erased-let','heap-field','join','join-capture','join-formal'):
            with self.subTest(mode=mode):
                module=fixture();root=module['bindings'][0]['expr'];case=root[2]
                if mode=='formal':root[1].append(binder('unused',proof))
                if mode=='argument':case[1][2]=[module['bindings'][1]['expr'][2]]
                if mode=='capture':case[3][0][3]=lam([],var('case',proof),proof)
                if mode=='let':root[2]=['let',False,[binding('bad',case[1],proof)],lit(),dict(rep=INT)]
                if mode=='erased-let':root[2]=['let',False,[binding('bad',case[1])],lit(),dict(rep=INT)]
                if mode=='heap-field':
                    module['constructors'].append(dict(id='Heap',kind='boxed',arity=1,fieldReps=[[sums.LIFTED]],strictFields=[False],fieldLifted=[True]))
                    case[3][0][3]=['app',['con','Heap',1,dict(rep=CLOSURE)],[var('case',proof)],[True],True,True,dict(rep=BOX)]
                if mode=='join':
                    join=binding('j',lam([binder('n',INT)],module['bindings'][1]['expr'][2],proof))
                    join.update(joinValueArity=1,joinResultRep=proof,info=dict(joinArity=1))
                    root[2]=['let',False,[join],lit(),dict(rep=INT)]
                if mode in ('join-capture','join-formal'):
                    body=lit() if mode=='join-formal' else ['case',var('case',proof),'jcase',copy.deepcopy(case[3]),dict(rep=INT,binder=binder('jcase',proof))]
                    join=binding('j',lam([binder('n',proof if mode=='join-formal' else INT)],body,INT))
                    join.update(joinValueArity=1,joinResultRep=INT,info=dict(joinArity=1))
                    case[3][0][3]=['let',False,[join],lit(),dict(rep=INT)]
                report=self.rejected(module)
                expected={'formal':'formal argument','argument':'argument','capture':'capture','let':'let binding',
                          'erased-let':'let binding','heap-field':'argument','join':'join result',
                          'join-capture':'join capture','join-formal':'formal argument'}[mode]
                self.assertTrue(any(i['detail']=='unboxed-sum '+expected for i in report['issues']),report['issues'])

    def test_constructor_metadata_cannot_encode_a_sum_as_one_word_field(self):
        module=fixture();zero=summ(VOID,tup())
        module['constructors'].append(dict(id='Heap',kind='boxed',arity=1,fieldReps=[['WordRep']],
            fieldTypes=[zero],strictFields=[False],fieldLifted=[False]))
        module['bindings'][0]['expr'][2]=['case',['app',['con','Heap',1,dict(rep=CLOSURE)],
            [['lit','word','1',dict(rep=WORD)]],[False],True,True,dict(rep=BOX)],'heap',
            [['data','Heap',['word'],lit(),dict(binders=[binder('word',WORD)])]],
            dict(rep=INT,binder=binder('heap',dict(BOX,evaluated=True)))]
        report=self.rejected(module)
        self.assertTrue(any(i['detail']=='unboxed-sum heap field' for i in report['issues']))

    def test_scalar_primitive_unknown_guard_cannot_hide_sum_proof(self):
        module=fixture();module['bindings'][0]['expr'][2]=['app',['prim','+#'],[lit(),lit()],[False,False],False,False,dict(rep=summ())]
        report=self.rejected(module)
        self.assertIn('primitive-representation',{i['code'] for i in report['issues']})

    def test_literal_intrinsic_fallback_cannot_erase_sum_marker(self):
        for kind in ('int16','word16','int32','word32'):
            for reps in (None,['WordRep','WordRep']):
                expression=['lit',kind,'0',dict(rep=dict(summ(),primReps=reps))]
                self.assertTrue(sums.is_sum(audit.Audit.expression_rep(expression)))
                module=fixture();module['bindings'][0]['expr'][2]=expression
                self.rejected(module)

    def test_new_host_resolver_does_not_bypass_malformed_expression_reporting(self):
        for expression in (['var'],['app',['var'],[]]):
            module=fixture();module['bindings'][0]['expr']=expression
            self.rejected(module)

    @unittest.skipUnless((ROOT/'build/sum-layout/pre-core/SumLayoutAudit.json').exists(),'Prepare genuine sum metadata fixtures')
    def test_genuine_pre_and_post_core_accept_only_bounded_consumers(self):
        positive=['sumCase','directCase','lazyCase','zeroCase','unitCase','boxedKindsCase','floatDoubleCase']
        negative=['nestedCase','narrowWideCase','threeWayCase','returnedSum','lazySum','zeroSum','unitSum','boxedKindsSum',
                  'floatDoubleSum','aliasIdentity','runtimePolymorphic','levityPolymorphic','abstractSumIdentity','abstractRuntimeSum',
                  'abstractAlternative','addressResult','vectorResult']
        for stage in ('pre','post'):
            module=json.loads((ROOT/f'build/sum-layout/{stage}-core/SumLayoutAudit.json').read_text())
            for entry in positive:
                with self.subTest(stage=stage,entry=entry):self.accepted(module,entry)
            for entry in negative:
                with self.subTest(stage=stage,entry=entry):self.rejected(module,entry)


if __name__=='__main__':unittest.main()
