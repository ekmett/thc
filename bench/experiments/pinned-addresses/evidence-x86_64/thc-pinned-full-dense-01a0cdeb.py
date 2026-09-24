import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET

root = Path('/home/ekmett/ai/thc-pinned-addresses-01a0cdeb')
out = root / 'bench/experiments/pinned-addresses/evidence-x86_64'
prior = Path('/home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/20260924-001353-34em6u44')
result = json.loads((prior / 'result.json').read_text())
if result['returncode'] != 0:
    raise SystemExit('Default full suite failed; no dense run or overwrite attempted.')
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / 'build/test-results/test').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(suite.attrib[key])
if totals != dict(tests=665, failures=0, errors=0, skipped=0):
    raise SystemExit('Unexpected full default summary: ' + repr(totals))
shutil.copytree(prior, out / 'runs/full-default-665-pass')
with tarfile.open(out / 'junit/full-default-665-pass.tar.gz', 'x:gz') as archive:
    archive.add(root / 'build/test-results/test', arcname='test-results')
print('Preserved full default suite before dense run: ' + json.dumps(totals), flush=True)
environment = dict(os.environ, JAVA_TOOL_OPTIONS='-Dthc.handoffSlabs=true')
command = ['scripts/gradle.sh', '--offline', '--no-daemon', '--max-workers=4', 'test', '--rerun-tasks']
print('Dense command: ' + json.dumps(command), flush=True)
print('JAVA_TOOL_OPTIONS=' + environment['JAVA_TOOL_OPTIONS'], flush=True)
raise SystemExit(subprocess.run(command, cwd=root, env=environment, check=False).returncode)
