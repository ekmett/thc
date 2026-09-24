#!/usr/bin/env bash
set -euo pipefail
cd /Users/ekmett/thc
make --no-print-directory -s check-java
ENTRY="$1"
LIBDIR="$2"
OUT="$3"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xss2m -Dthc.backend=ast -Dthc.traceCompilation=true -Dthc.minimumWarmCalls=256 -Djdk.graal.Dump=Truffle:1 -Djdk.graal.PrintGraph=File -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=false "-Djdk.graal.DumpPath=$OUT" -cp "$LIBDIR/*" thc.ProbeKt /Users/ekmett/thc/work/typed-application-review/Synthetic.TypedApplications.json "$ENTRY" --steady 5 0.01 1 0 > "$OUT/run.tsv" 2> "$OUT/run.log"
EXPORTS=(--add-modules jdk.graal.compiler --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
for graph in "$OUT"/TruffleHotSpotCompilation-*.bgv; do
  [[ -f "$graph" ]] || continue
  name="$(basename "$graph" .bgv)"
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp build/graph-tools GraphInspect "$graph" "$OUT/parsed-$name" '(?i)After PE Tier|After Inline|Before phase HighTierLowering|After mid tier'
done
python3 tools/audit-call-packets.py "$OUT" --output "$OUT/packet-audit.json"
python3 - "$ENTRY" "$LIBDIR" "$OUT" <<'PY'
from pathlib import Path
import hashlib,json,sys
entry,libdir,out=sys.argv[1:]; out=Path(out)
def h(p):return dict(path=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
row=(out/'run.tsv').read_text().strip().split('\t'); calls=int(row[2])
values=[(-(1<<63)+17 if x==0 else x*3) if entry=='typedDirect' else (8 if x==0 else x*2+6) for x in range(16)]
expected=(sum(values)*(calls//16)+(1<<63))%(1<<64)-(1<<63)
assert calls%16==0 and int(row[4])==expected,(row,expected)
assert 'guestLastTierInstalled=true' in (out/'run.log').read_text()
manifest=Path(libdir).parent/'manifest.json'
(out/'provenance.json').write_text(json.dumps(dict(entry=entry,backend='ast',instrument=False,checksumVerified=True,runtimeJars=[h(p) for p in sorted(Path(libdir).glob('*.jar'))],runtimeManifest=json.loads(manifest.read_text()),fixture=h(Path('work/typed-application-review/Synthetic.TypedApplications.json')),harness=h(Path('work/typed-application-review/capture.sh'))),indent=2)+'\n')
PY
