#!/usr/bin/env python3
"""Integer-only bit models and pinned exact scalar representation contracts."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import scalar_bitcast_model as model

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('bitcast_auditor', ROOT/'scripts/audit-core.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
CAP = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
SIGNATURES = {'castFloatToWord32#': ('FloatRep','Word32Rep'), 'castWord32ToFloat#': ('Word32Rep','FloatRep'),
              'castDoubleToWord64#': ('DoubleRep','Word64Rep'), 'castWord64ToDouble#': ('Word64Rep','DoubleRep')}


def proof(rep):
    return dict(kind={'FloatRep':'float','DoubleRep':'double'}.get(rep,'long'), primReps=[rep], evaluated=True)


def fixture(name):
    arg, result = map(proof, SIGNATURES[name])
    body = ['app',['prim',name],[['var','x',dict(rep=copy.deepcopy(arg))]],[False],False,False,dict(rep=result)]
    closure = dict(kind='closure',primReps=['BoxedRep (Just Lifted)'],evaluated=True)
    lam = ['lam',[dict(id='x',lifted=False,rep=arg)],body,dict(rep=closure,resultRep=copy.deepcopy(result))]
    return dict(schema=1,ghc='9.14.1',constructors=[],bindings=[dict(id='root',name='root',lifted=True,rep=closure,expr=lam)]), body


def check(module): return audit.Audit([('bitcast.json',module)],CAP).run(['root'])


class ScalarBitCastTest(unittest.TestCase):
    def test_exact_pinned_signatures_and_no_numeric_conversion(self):
        table=json.loads((ROOT/'src/main/resources/thc/scalar-primop-signatures.json').read_text())['primitives']
        for name,(arg,result) in SIGNATURES.items():
            self.assertEqual(table[name],dict(arguments=[arg],result=result))
            self.assertEqual(CAP['primitives'][name],1)
            self.assertTrue(check(fixture(name)[0])['accepted'])

    def test_contradictory_argument_result_lexical_and_arity_proofs_reject(self):
        for name in SIGNATURES:
            for mutation in ('argument','result','lexical','partial','over','bare'):
                module,app=fixture(name)
                if mutation=='argument':app[2][0][2]['rep']=proof('IntRep')
                elif mutation=='result':app[6]['rep']=proof('WordRep')
                elif mutation=='lexical':module['bindings'][0]['expr'][1][0]['rep']=proof('WordRep')
                elif mutation=='partial':app[2].clear();app[3].clear()
                elif mutation=='over':app[2].append(copy.deepcopy(app[2][0]));app[3].append(False)
                else:module['bindings'][0]['expr'][2]=['prim',name]
                self.assertFalse(check(module)['accepted'],(name,mutation))

    def test_nan_classes_signs_and_every_payload_bit_are_retained(self):
        for width,mantissa in [(32,23),(64,52)]:
            values=model.patterns(width)
            self.assertEqual({model.classify(v,width) for v in values},
                {(s,k) for s in [0,1] for k in ['zero','subnormal','normal','infinity','quiet-nan','signalling-nan']})
            exponent=0x7f800000 if width==32 else 0x7ff0000000000000
            for sign in [0,1<<(width-1)]:
                for bit in range(mantissa):self.assertIn(sign|exponent|(1<<bit),values)
                self.assertIn(sign|exponent|(1<<(mantissa-1)),values)

    def test_models_use_raw_integer_bits_even_for_nan_and_signed_zero(self):
        for name in model.ENTRIES:
            width=32 if name.startswith('float') else 64
            for raw in model.inputs(width):
                expected=int.from_bytes((raw&((1<<width)-1)).to_bytes(width//8,'little'),'little',signed=width==64)
                self.assertEqual(model.expected(name,raw),expected)
        self.assertEqual(model.expected('floatDecode',-1),0xffffffff)
        self.assertEqual(model.expected('doubleDecode',-(1<<63)),-(1<<63))


if __name__=='__main__':unittest.main()
