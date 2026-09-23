#!/usr/bin/env python3
"""Saturated nominal enum proofs must not broaden general primitive acceptance."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())
LONG = dict(kind='long', primReps=['IntRep'], evaluated=True)
DATA = dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=False)
CLOSURE = dict(DATA, kind='closure', evaluated=True)

def fixture():
    family = dict(typeConstructor='Enum', constructors=['A', 'B'])
    constructors = [dict(id=k, name=k, kind='boxed', arity=0, tag=i+1, fieldReps=[], fieldTypes=[],
        fieldLifted=[], strictFields=[], enumFamily=copy.deepcopy(family)) for i,k in enumerate(['A','B'])]
    app = ['app', ['prim','tagToEnum#',dict(rep=CLOSURE)], [['var','x',dict(rep=LONG)]], [False], False, False,
           dict(rep=DATA, enumFamily=family)]
    root = dict(id='root', name='root', lifted=True, arity=1, rep=CLOSURE,
        expr=['lam',[dict(id='x',lifted=False,rep=LONG)],app,dict(rep=CLOSURE,resultRep=DATA)])
    return dict(schema=1,ghc='9.14.1',bindings=[root],constructors=constructors)

def run(m, cap=None): return audit.Audit([('enum.json',m)], cap or CAP).run(['root'])

class EnumTests(unittest.TestCase):
    def test_exact_family_is_accepted_without_generic_primitive_permission(self):
        self.assertNotIn('tagToEnum#',CAP['primitives']);self.assertTrue(run(fixture())['accepted'])
    def test_disabled_capability(self):
        cap=copy.deepcopy(CAP);cap.pop('tagToEnum');self.assertFalse(run(fixture(),cap)['accepted'])
    def test_missing_incomplete_contradictory_family(self):
        for variant in ['missing','type','empty','duplicate','reverse','missing-con','wrong-family','arity','fields','tag','floating-tag','newtype','truncated-family','extra-family-member']:
            with self.subTest(variant=variant):
                m=fixture();app=m['bindings'][0]['expr'][2];family=app[6]['enumFamily'];con=m['constructors'][0]
                if variant=='missing':app[6].pop('enumFamily')
                elif variant=='type':family['typeConstructor']='Other'
                elif variant=='empty':family['constructors']=[]
                elif variant=='duplicate':family['constructors']=['A','A']
                elif variant=='reverse':family['constructors'].reverse()
                elif variant=='missing-con':m['constructors'].pop()
                elif variant=='wrong-family':con['enumFamily']['typeConstructor']='Other'
                elif variant=='arity':con['arity']=1
                elif variant=='fields':con['fieldTypes']=[LONG]
                elif variant=='tag':con['tag']=2
                elif variant=='floating-tag':con['tag']=1.0
                elif variant=='newtype':con['kind']='newtype'
                elif variant=='truncated-family':
                    family['constructors']=['A'];con['enumFamily']=copy.deepcopy(family)
                elif variant=='extra-family-member':
                    extra=copy.deepcopy(con);extra['id']='C';m['constructors'].append(extra)
                result=run(m);self.assertFalse(result['accepted']);self.assertTrue(any(x['code']=='enum-family' for x in result['issues']))
                if variant in ['truncated-family','extra-family-member']:
                    self.assertTrue(any('contradictory supplied family record' in x['detail'] for x in result['issues']))
    def test_bare_partial_overapplied_and_invalid_proofs(self):
        for variant in ['bare','zero','two','lifted','word','unknown','malformed','function-proof','result','aggregate']:
            with self.subTest(variant=variant):
                m=fixture();app=m['bindings'][0]['expr'][2]
                if variant=='bare':m['bindings'][0]['expr'][2]=app[1]
                elif variant=='zero':app[2]=[];app[3]=[]
                elif variant=='two':app[2]*=2;app[3]*=2
                elif variant=='lifted':app[3]=[True]
                elif variant=='word':app[2][0][2]['rep']=dict(LONG,primReps=['WordRep'])
                elif variant=='unknown':app[2][0]=['lit','int','0']
                elif variant=='malformed':app[2][0][2]['rep']=[]
                elif variant=='function-proof':app[1][2]['rep']=[]
                elif variant=='result':app[6]['rep']=LONG
                elif variant=='aggregate':app[6]['rep']=dict(kind='unknown',aggregate='unboxed-tuple',components=[DATA],primReps=DATA['primReps'],evaluated=True)
                self.assertFalse(run(m)['accepted'])
    def test_legacy_var_occurrence_uses_exact_lexical_proof(self):
        for proof in [None, dict(kind='unknown', primReps=None, evaluated=False),
                      dict(kind='unknown', primReps=['IntRep'], evaluated=False)]:
            m=fixture();m['bindings'][0]['expr'][2][2][0]=['var','x']+([] if proof is None else [dict(rep=proof)])
            self.assertTrue(run(m)['accepted'])
    def test_real_unsupported_families(self):
        for stage in ['pre','post']:
            path=ROOT.parent/'build/tag-to-enum'/f'{stage}-core/TagToEnumFrontier.json'
            module=json.loads(path.read_text())
            for entry in ['parameterized','family']:
                result=audit.Audit([(str(path),module)],CAP).run([entry])
                self.assertFalse(result['accepted'])
                self.assertTrue(any(x['code']=='enum-family' for x in result['issues']))
    def test_real_exports(self):
        for stage in ['pre','post']:
            folder=ROOT.parent/'build/tag-to-enum'/f'{stage}-core'
            self.assertTrue(folder.is_dir(),'Run prepare-tag-to-enum-audit.py')
            modules=[(str(p),json.loads(p.read_text())) for p in folder.glob('*.json')]
            result=audit.Audit(modules,CAP).run(['boolCase','orderingCase','colourCase','externalCase','papCase','lazyCase','lazyTagCase','onceCase'])
            self.assertTrue(result['accepted'],result['issues'])
if __name__=='__main__':unittest.main()
