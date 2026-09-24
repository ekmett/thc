#!/usr/bin/env bash
# Run only after root authorizes JVM work; serialize against throughput timing.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
make --no-print-directory -s check-java
if (( $# < 3 )); then echo 'Usage: capture.sh ast|bytecode IMMUTABLE_LIBDIR OUTPUT_DIR [cycle3|cycleInner|cycleNonTail|cycleArity] [INPUT]' >&2; exit 2; fi
BACKEND="$1"; LIBDIR="$(cd "$2" && pwd)"; OUT="$3"; ENTRY="${4:-cycleInner}"; BASE="${5:-10000}"
case "$BACKEND" in ast|bytecode) ;; *) exit 2;; esac
if [[ -d "$OUT" && -n "$(ls -A "$OUT")" ]]; then echo "Refusing nonempty output: $OUT" >&2; exit 2; fi
mkdir -p "$OUT" work/tail-reentry-review/tools
OUT="$(cd "$OUT" && pwd)"
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xss2m \
  -Dthc.traceCompilation=true -Dthc.minimumWarmCalls=256 "-Dthc.backend=$BACKEND" \
  -Djdk.graal.Dump=Truffle:1 -Djdk.graal.PrintGraph=File \
  -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=false \
  "-Djdk.graal.DumpPath=$OUT" -cp "$LIBDIR/*" thc.ProbeKt \
  "$ROOT/work/tail-reentry-review/cycles.json" "$ENTRY" --steady 10 0.01 1 "$BASE" \
  > "$OUT/run.tsv" 2> "$OUT/run.log"
EXPORTS=(--add-modules jdk.graal.compiler \
 --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED \
 --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d work/tail-reentry-review/tools tools/GraphInspect.java
for graph in "$OUT"/TruffleHotSpotCompilation-*.bgv; do
 [[ -f "$graph" ]] || continue
 name="$(basename "$graph" .bgv)"
 "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp work/tail-reentry-review/tools GraphInspect \
  "$graph" "$OUT/parsed-$name" '(?i)After PE Tier|After Inline|Before phase HighTierLowering|After mid tier'
done
python3 tools/audit-call-packets.py "$OUT" --output "$OUT/packet-audit.json"
python3 work/tail-reentry-review/audit-loops.py "$OUT"
python3 - "$BACKEND" "$LIBDIR" "$OUT" "$ENTRY" "$BASE" <<'PY'
from pathlib import Path
import sys,json,hashlib
backend,libdir,out,entry,base=sys.argv[1:];out=Path(out)
def h(p):return {'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
row=(out/'run.tsv').read_text().strip().split('\t');calls=int(row[2]);base=int(base)
values=[42 if entry=='cycleArity' else max(base+i,0)+(17 if entry=='cycleNonTail' else 0) for i in range(16)]
expected=(sum(values)*(calls//16)+(1<<63))%(1<<64)-(1<<63)
assert calls%16==0 and int(row[4])==expected,(row,expected)
log=(out/'run.log').read_text();assert 'guestLastTierInstalled=true'in log
manifest=Path(libdir).parent/'manifest.json'
provenance={'backend':backend,'entry':entry,'inputBase':base,'instrument':False,'checksumVerified':True,
 'runtimeJars':[h(p)for p in sorted(Path(libdir).glob('*.jar'))],
 'fixture':h(Path('work/tail-reentry-review/cycles.json')),'harness':h(Path('work/tail-reentry-review/capture.sh')),
 'tools':[h(Path(p))for p in ['tools/GraphInspect.java','tools/audit-call-packets.py','work/tail-reentry-review/audit-loops.py']],
 'rawGraphs':[h(p)for p in sorted(out.glob('*.bgv'))],
 'frozenManifest':None if not manifest.exists()else {'file':h(manifest),'contents':json.loads(manifest.read_text())}}
(out/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
PY
