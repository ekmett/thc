#!/usr/bin/env python3
"""Check structured caller demand on genuine Core and GHC API edge cases."""
import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def walk(value):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def calls(value, name=None):
    return [node for node in walk(value)
            if isinstance(node, list) and node and node[0] == 'app'
            and (name is None or (node[1][0] == 'var'
                 and node[1][1].rsplit('.', 1)[-1] == name))]


def audit(module):
    count = strict = 0
    for node in calls(module['bindings']):
        certificate = node[-1].get('callDemand')
        if certificate is None:
            continue
        arity, marks = certificate['arity'], certificate['strictArgs']
        assert type(arity) is int and arity >= 0, certificate
        assert isinstance(marks, list) and len(marks) == len(node[2]), node
        assert all(type(mark) is bool for mark in marks), certificate
        assert not any(marks[arity:]), certificate
        if len(marks) < arity:
            assert not any(marks), certificate
        count += 1
        strict += sum(marks)
    assert count > 0, 'Fixture must exercise structured demand export'
    print(f"PASS {module['module']}: {count} call certificates, {strict} strict argument positions")


def fixture_checks(module, coercions):
    bindings = {binding['name']: binding for binding in module['bindings']}

    def demand(binding, callee, arity, marks):
        found = calls(bindings[binding]['expr'], callee)
        assert len(found) == 1, (binding, callee, found)
        assert found[0][-1].get('callDemand') == {'arity': arity, 'strictArgs': marks}, found[0]
        return found[0]

    direct = demand('ordinaryEntry', 'strictTree', 1, [True])
    produced = calls(direct[2][0], 'makeTree')
    assert len(produced) == 1 and produced[0][5] is False, produced
    for name in ('strictTree', 'strictPair', 'strictPoly'):
        definition = bindings[name]
        assert not any(definition['entryStrict']), definition
        assert definition['info']['cbvEligible'] is False, definition
        for parameter in definition['expr'][1]:
            if parameter['lifted']:
                assert parameter['rep']['evaluated'] is False, parameter
    demand('polyDataEntry', 'strictPoly', 1, [True])
    demand('polyFunctionEntry', 'strictPoly', 1, [True])
    demand('polyFunctionEntry', 'strictPair', 2, [False])
    demand('bottomPAPEntry', 'strictPair', 2, [False])
    demand('bottomPAPEntry', 'keepPAP', 1, [True])
    demand('absentEntry', 'ignore', 1, [False])
    demand('deadEndEntry', 'deadEnd', 1, [False])
    barrier = demand('lazyBarrier', 'strictTree', 1, [False])
    # The original lazyId remains effective even though the exported argument
    # now has exactly the same shape as the ordinary makeTree call.
    rewritten = calls(barrier[2][0], 'makeTree')
    assert len(rewritten) == 1 and 'callDemand' not in rewritten[0][-1], rewritten

    # This real GADT worker contains a retained coercion before its strict tree.
    workers = [binding for binding in walk(coercions['bindings'])
               if isinstance(binding, dict) and binding.get('name') == '$wwitnessed']
    assert len(workers) == 1, workers
    worker = workers[0]
    assert worker['expr'][1][0]['coercion'] is True, worker
    applications = [node for node in calls(coercions['bindings'])
                    if node[1][:2] == ['var', worker['id']]]
    assert applications, 'Real fixture must call its coercion-taking worker'
    for node in applications:
        assert node[2][0][0] == 'void', node
        assert node[-1]['callDemand'] == {'arity': 3, 'strictArgs': [False, False, True]}, node
    print('PASS: ordinary strict calls, unchanged entry/WHNF facts, polymorphic values, PAP saturation, absent/bottom exclusion, lazy barrier, retained coercion alignment')


# Exercise cases source optimization would otherwise remove or normalize.
# The function under test reads GHC objects directly; no demand string is parsed.
API_CHECK = r'''
module Main where
import GHC.Plugins
import GHC.Builtin.Types (intTy)
import GHC.Types.Demand
import GHC.Types.Id.Make (lazyId)
import GHC.Types.Tickish (GenTickish(HpcTick))
import GHC.Unit.Types (mainUnit)
import qualified Thc.Demands as D

check :: (Eq a, Show a) => String -> a -> a -> IO ()
check label actual expected
  | actual == expected = pure ()
  | otherwise = error (label ++ ": " ++ show actual ++ " /= " ++ show expected)

main :: IO ()
main = do
  let x = Var (mkTemplateLocal 101 intTy)
      ty = mkVisFunTyMany intTy (mkVisFunTyMany intTy intTy)
      fun ar ds div = setIdArity
        (setIdDmdSig (mkTemplateLocal 102 ty) (mkClosedDmdSig ds div)) ar
      f = fun 1 [evalDmd, evalDmd] topDiv
      co = Coercion (mkReflCo Nominal intTy)
      cast e = Cast e (mkReflCo Representational (exprType e))
      tick = HpcTick (mkModule mainUnit (mkModuleName "DemandApi")) 0
      lazy = mkApps (Var lazyId) [Type intTy, x]
      call = D.callDemand . Var
  check "signature threshold exceeds idArity" (call f [x]) (Just (2,[False]))
  check "saturated threshold" (call f [x,x]) (Just (2,[True,True]))
  check "signature threshold below idArity" (call (fun 3 [evalDmd] topDiv) [x]) (Just (1,[True]))
  check "type erasure" (call f [Type intTy,x,Type intTy,x]) (Just (2,[True,True]))
  check "coercion consumes signature slot" (call f [co,x]) (Just (2,[False,True]))
  check "coercion does not erase threshold" (call f [co]) (Just (2,[False]))
  check "overapplication suffix" (call f [x,x,x]) (Just (2,[True,True,False]))
  check "absent and bottom demands" (call (fun 2 [absDmd,botDmd] botDiv) [x,x]) (Just (2,[False,False]))
  check "dead end retains strict used demand" (call (fun 1 [evalDmd] exnDiv) [x]) (Just (1,[True]))
  check "zero arity divergence gives no strict suffix" (call (fun 0 [] botDiv) [x]) (Just (0,[False]))
  check "lazy argument" (call f [lazy,x]) (Just (2,[False,True]))
  check "lazy under cast/tick" (call f [cast (Tick tick lazy),x]) (Just (2,[False,True]))
  check "cast head" (D.callDemand (cast (Var f)) [x,x]) Nothing
  check "nonfloating tick head" (D.callDemand (Tick tick (Var f)) [x,x]) Nothing
  check "unknown lambda head" (D.callDemand (Lam (mkTemplateLocal 103 intTy) x) [x]) Nothing
  putStrLn "PASS: GHC API signature/id arity mismatch, type/coercion slots, overapplication, absence/bottom/dead ends, lazy wrappers, cast/tick/unknown heads"
'''


def api_checks():
    ghc = os.environ.get('GHC', 'ghc')
    version = subprocess.check_output([ghc, '--numeric-version'], text=True).strip()
    assert version == '9.14.1', f'GHC API checks require 9.14.1, got {version}'
    with tempfile.TemporaryDirectory(prefix='thc-demand-api-') as directory:
        work = Path(directory)
        source, executable = work / 'Main.hs', work / 'check-demand'
        source.write_text(API_CHECK)
        subprocess.run([ghc, '--make', '-v0', '-O0', '-dynamic', '-package', 'ghc',
                        '-i' + str(ROOT / 'compiler'), '-odir', directory, '-hidir', directory,
                        str(source), '-o', str(executable)], check=True, cwd=ROOT)
        subprocess.run([str(executable)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ghc-api', action='store_true', help='Also check synthetic GHC API edge cases')
    parser.add_argument('modules', nargs='*', type=Path)
    args = parser.parse_args()
    paths = args.modules or [ROOT / 'build/core/DemandAudit.json', ROOT / 'build/core/CbvCoercionAudit.json']
    modules = [json.loads(path.read_text()) for path in paths]
    for module in modules:
        audit(module)
    if not args.modules:
        fixture_checks(*modules)
    if args.ghc_api:
        api_checks()


if __name__ == '__main__':
    main()
