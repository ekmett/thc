#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('audit',ROOT/'audit-core.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
CAP=json.loads((ROOT/'core-capabilities.json').read_text())
LONG=dict(kind='long',primReps=['IntRep'],evaluated=True)
DATA=dict(kind='data',primReps=['BoxedRep (Just Lifted)'],evaluated=False)
CLOSURE=dict(DATA,kind='closure',evaluated=True)

def fixture(size=3):
    ids=[f'C{i}' for i in range(size)]
    family=dict(typeConstructor='test:T',constructors=ids,smallFamilyLimit=7,smallFamily=size<=7)
    cons=[dict(id=k,name=k,kind='boxed',arity=0,tag=i+1,fieldReps=[],fieldTypes=[],fieldLifted=[],strictFields=[],
               dataToTagFamily=copy.deepcopy(family)) for i,k in enumerate(ids)]
    app=['app',['prim','dataToTagSmall#' if size<=7 else 'dataToTagLarge#',dict(rep=CLOSURE)],
         [['var','x',dict(rep=DATA)]],[True],False,False,dict(rep=LONG,dataToTagFamily=family)]
    root=dict(id='root',name='root',lifted=True,rep=CLOSURE,
              expr=['lam',[dict(id='x',name='x',lifted=True,rep=DATA)],app,dict(rep=CLOSURE,resultRep=LONG)])
    return dict(schema=1,ghc='9.14.1',bindings=[root],constructors=cons)

def run(module,cap=CAP):return audit.Audit([('data-tags.json',module)],cap).run(['root'])

class DataTagTests(unittest.TestCase):
    def test_known_families_and_pinned_pointer_tag_boundary(self):
        for size in [1,3,7,8,9]:self.assertTrue(run(fixture(size))['accepted'])
        self.assertNotIn('dataToTagSmall#',CAP['primitives'])
        self.assertNotIn('dataToTagLarge#',CAP['primitives'])
        cap=copy.deepcopy(CAP);cap.pop('dataToTag');self.assertFalse(run(fixture(),cap)['accepted'])

    def test_variant_and_complete_family_checks(self):
        for variant in ['missing','empty','duplicate','reverse','con','tag','float-tag','arity','newtype',
                        'truncated','extra','limit','float-limit','small','wrong-variant','field']:
            with self.subTest(variant=variant):
                m=fixture();app=m['bindings'][0]['expr'][2];family=app[6]['dataToTagFamily'];con=m['constructors'][0]
                if variant=='missing':app[6].pop('dataToTagFamily')
                elif variant=='empty':family['constructors']=[]
                elif variant=='duplicate':family['constructors'][1]=family['constructors'][0]
                elif variant=='reverse':family['constructors'].reverse()
                elif variant=='con':m['constructors'].pop()
                elif variant=='tag':con['tag']=2
                elif variant=='float-tag':con['tag']=1.0
                elif variant=='arity':con['arity']=0.0
                elif variant=='newtype':con['kind']='newtype'
                elif variant=='truncated':family['constructors']=['C0'];con['dataToTagFamily']=copy.deepcopy(family)
                elif variant=='extra':m['constructors'].append(dict(con,id='Extra'))
                elif variant=='limit':family['smallFamilyLimit']=3
                elif variant=='float-limit':family['smallFamilyLimit']=7.0
                elif variant=='small':family['smallFamily']=False
                elif variant=='wrong-variant':app[1][1]='dataToTagLarge#'
                elif variant=='field':con['fieldReps']=[['IntRep']]
                self.assertFalse(run(m)['accepted'])
        m=fixture(9);m['bindings'][0]['expr'][2][1][1]='dataToTagSmall#';self.assertFalse(run(m)['accepted'])

    def test_exact_operand_and_result_proofs_and_saturation(self):
        for variant in ['closure','object','unknown','long','levity','generic','result','word','aggregate','function','bare','zero','two']:
            with self.subTest(variant=variant):
                m=fixture();app=m['bindings'][0]['expr'][2];operand=app[2][0][2]['rep']=dict(DATA)
                if variant in ['closure','object','unknown']:operand['kind']=variant
                elif variant=='long':app[2][0][2]['rep']=LONG
                elif variant=='levity':app[3]=[False]
                elif variant=='generic':operand['primReps']=['BoxedRep Nothing']
                elif variant=='result':app[6]['rep']=DATA
                elif variant=='word':app[6]['rep']=dict(LONG,primReps=['WordRep'])
                elif variant=='aggregate':app[6]['rep']=dict(kind='unknown',primReps=[],aggregate='unboxed-tuple',components=[],evaluated=True)
                elif variant=='function':app[1][2]['rep']=[]
                elif variant=='bare':m['bindings'][0]['expr'][2]=app[1]
                elif variant=='zero':app[2]=[];app[3]=[]
                elif variant=='two':app[2]*=2;app[3]*=2
                # Legacy/general reference occurrences refine from the exact lexical binder.
                if variant in ('unknown','object','generic'):self.assertTrue(run(m)['accepted'])
                else:self.assertFalse(run(m)['accepted'])

    def test_genuine_exports_and_negative_frontiers(self):
        path=ROOT.parent/'build/data-to-tag/manifest.json'
        if not path.exists():self.skipTest('Run prepare-data-to-tag.py')
        manifest=json.loads(path.read_text())
        for stage, paths in manifest['stages'].items():
            modules=[(p,json.loads((ROOT.parent/p).read_text())) for p in paths]
            for entry in manifest['entries']+manifest['frontiers']:
                self.assertEqual(entry in manifest['entries'],audit.Audit(modules,CAP).run([entry])['accepted'],(stage,entry))

if __name__=='__main__':unittest.main()
