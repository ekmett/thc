#!/usr/bin/env python3
"""Compare advertised primitives with the pinned GHC API, not a parsed name list."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
# allThePrimOps includes GHC's generated vector families and target-specific table.
QUERY = r'''mapM_ (\op -> let (_,_,_,arity,_) = GHC.Builtin.PrimOps.primOpSig op in putStrLn (GHC.Types.Name.Occurrence.occNameString (GHC.Builtin.PrimOps.primOpOcc op) ++ "\t" ++ show arity ++ "\t" ++ unwords (words (GHC.Utils.Outputable.showSDocUnsafe (GHC.Utils.Outputable.ppr (GHC.Builtin.PrimOps.primOpType op)))))) GHC.Builtin.PrimOps.allThePrimOps'''


def report(rows, advertised):
    inventory = {}
    for name, arity, signature in rows:
        if name in inventory:
            raise ValueError(f'Duplicate GHC primop: {name}')
        inventory[name] = dict(name=name, valueArity=int(arity), signature=signature,
                               advertised=name in advertised)
    for name, arity in advertised.items():
        if name not in inventory:
            raise ValueError(f'Advertised primitive absent from pinned GHC: {name}')
        expected = inventory[name]['valueArity']
        if type(arity) is not int or arity != expected:
            raise ValueError(f'Primitive arity mismatch: {name}: THC {arity!r}, GHC {expected}')
    return dict(schema=1, ghc='9.14.1', counts=dict(total=len(inventory),
                advertised=len(advertised), unadvertised=len(inventory)-len(advertised)),
                claim='Advertised names and value arities only; not proof of runtime semantics, lowering, or tested input coverage.',
                primitives=[inventory[name] for name in sorted(inventory)])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/primop-coverage.json')
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    version = subprocess.check_output([ghc, '--numeric-version'], text=True).strip()
    if version != '9.14.1':
        raise SystemExit(f'THC requires GHC 9.14.1; found {version}')
    command = [ghc, '-ignore-dot-ghci', '-package', 'ghc', '-e', QUERY]
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    rows = [line.split('\t', 2) for line in result.stdout.splitlines()]
    capability = ROOT / 'scripts/core-capabilities.json'
    data = report(rows, json.loads(capability.read_text())['primitives'])
    data['provenance'] = dict(command=command, compilerInfo=subprocess.check_output([ghc, '--info'], text=True),
        inputs={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in (capability, Path(__file__).resolve())})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2) + '\n')
    print(f"GHC primop inventory: {data['counts']['advertised']} advertised / {len(rows)} total; names and arities agree")


if __name__ == '__main__':
    main()
