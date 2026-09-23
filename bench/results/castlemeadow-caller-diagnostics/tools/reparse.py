#!/usr/bin/env python3
"""Reparse the archived BGVs with pinned Graal; never executes the guest program."""
import argparse, datetime, hashlib, json, subprocess, sys
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java-home',type=Path,required=True);p.add_argument('output',type=Path);a=p.parse_args()
base=Path(__file__).resolve().parents[1];out=a.output.resolve();java=a.java_home.resolve()
exports=['--add-modules','jdk.graal.compiler','--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED','--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED']
source=base/'tools/capture/work/perf-sprint-v2/GraphInspect.java'
assert 'GRAALVM_VERSION="25.3.4.1"' in (java/'release').read_text()
state={'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Archive reparse only; no guest execution.','javaReleaseSha256':hashlib.sha256((java/'release').read_bytes()).hexdigest(),'graphReaderSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'commands':[]}
def run(args):
 row={'argv':list(map(str,args))};state['commands'].append(row)
 result=subprocess.run(row['argv'],capture_output=True,text=True,timeout=600);row.update(exitCode=result.returncode,stdout=result.stdout,stderr=result.stderr)
 if out.exists():(out/'reparse-execution.json').write_text(json.dumps(state,indent=2)+'\n')
 if result.returncode:raise RuntimeError(row)
run([sys.executable,base/'tools/verify.py','extract',out])
classes=out/'classes';classes.mkdir()
run([java/'bin/javac',*exports,'-d',classes,source])
for entry in json.loads((base/'selected-worker-bgv-manifest.json').read_text())['selected']:
 raw=out/entry['archiveMember'];dest=raw.parent/('parsed-'+raw.stem)
 run([java/'bin/java','-XX:-UseJVMCICompiler','-Xmx4g',*exports,'-cp',classes,'GraphInspect',raw,dest,'(?i)After PE Tier|After Inline|Before phase HighTierLowering|After mid tier'])
run([sys.executable,base/'tools/verify.py','verify-reparse',out])
state.update(finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),passed=True)
(out/'reparse-execution.json').write_text(json.dumps(state,indent=2)+'\n')
print('Verified all eight archived phase topologies.')
