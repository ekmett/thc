#!/usr/bin/env python3
from pathlib import Path
import json,hashlib,subprocess,sys
r=Path(__file__).resolve().parents[1];m=json.loads((r/'manifest.json').read_text())
actual={p.relative_to(r).as_posix() for p in r.rglob('*') if p.is_file() and p!=r/'manifest.json' and '__pycache__' not in p.parts}
assert actual=={x['path'] for x in m['files']}
for x in m['files']:
 p=r/x['path'];assert p.stat().st_size==x['bytes'] and hashlib.sha256(p.read_bytes()).hexdigest()==x['sha256'],x['path']
for name in ['agnostic','default','diagnostics']:subprocess.run([sys.executable,str(r/name/'tools/verify.py')],check=True)
print('PASS: sealed one-step Force evidence; 90 throughput windows and matched diagnostic archives verified.')
