#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Generate/check the shared scalar proof contract using GHC 9.14.1 type APIs."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
TABLE = ROOT / 'src/main/resources/thc/scalar-primop-signatures.json'
# Structural primitive types only: no forall, tuple, sum, vector, state or boxed type.
QUERY = r'''let scalar t = case GHC.Core.Type.splitTyConApp_maybe t of { Just (tc,[]) | GHC.Core.TyCon.isPrimTyCon tc -> case GHC.Types.RepType.typePrimRep_maybe t of { Just [r] | r `elem` [GHC.Core.TyCon.IntRep,GHC.Core.TyCon.WordRep,GHC.Core.TyCon.Int8Rep,GHC.Core.TyCon.Word8Rep,GHC.Core.TyCon.Int16Rep,GHC.Core.TyCon.Word16Rep,GHC.Core.TyCon.Int32Rep,GHC.Core.TyCon.Word32Rep,GHC.Core.TyCon.Int64Rep,GHC.Core.TyCon.Word64Rep,GHC.Core.TyCon.FloatRep,GHC.Core.TyCon.DoubleRep,GHC.Core.TyCon.AddrRep] -> Just (show r); _ -> Nothing }; _ -> Nothing }; emit op = let (vs,args,res,_,_) = GHC.Builtin.PrimOps.primOpSig op in if not (null vs) then pure () else case traverse scalar (args++[res]) of {Just rs -> putStrLn (GHC.Types.Name.Occurrence.occNameString (GHC.Builtin.PrimOps.primOpOcc op) ++ "\t" ++ unwords rs); Nothing -> pure ()} in mapM_ emit GHC.Builtin.PrimOps.allThePrimOps'''


def derive(rows, advertised):
    signatures = {}
    for line in rows.splitlines():
        name, *reps = line.split()
        if name not in advertised:
            continue
        if name in signatures or len(reps) != advertised[name] + 1:
            raise ValueError(f'Invalid scalar signature for {name}')
        signatures[name] = dict(arguments=reps[:-1], result=reps[-1])
    return dict(schema=1, ghc='9.14.1', targetWordSize=64,
                signatureSource='GHC.Builtin.PrimOps.primOpSig / GHC.Types.RepType.typePrimRep_maybe',
                primitives=dict(sorted(signatures.items())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='update the checked-in table; default verifies it')
    args = parser.parse_args()
    ghc = os.environ.get('GHC', 'ghc')
    assert subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1'
    info = subprocess.check_output([ghc, '--info'], text=True)
    assert dict(ast.literal_eval(info))['target word size in bits'] == '64', 'Only 64-bit targets are supported'
    command = [ghc, '-ignore-dot-ghci', '-package', 'ghc', '-e', QUERY]
    rows = subprocess.check_output(command, text=True)
    caps = ROOT / 'scripts/core-capabilities.json'
    data = derive(rows, json.loads(caps.read_text())['primitives'])
    text = json.dumps(data, indent=2)+'\n'
    if args.write:
        TABLE.parent.mkdir(parents=True, exist_ok=True)
        TABLE.write_text(text)
    elif TABLE.read_text() != text:
        raise SystemExit('Scalar signature contract differs from pinned GHC; review then regenerate with --write')
    out = ROOT / 'build/scalar-signatures'
    out.mkdir(parents=True, exist_ok=True)
    provenance = dict(ghc='9.14.1', compilerInfo=info, command=command, entries=len(data['primitives']),
        inputs={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in (Path(__file__).resolve(), caps, TABLE)})
    (out/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    print(f"Verified {len(data['primitives'])} exact monomorphic scalar signatures against GHC 9.14.1")


if __name__ == '__main__': main()
