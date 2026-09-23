# v6 object sizes

Measured with `Instrumentation.getObjectSize` on the frozen v6 bytecode runtime.
These are shallow sizes, including object headers and alignment. They exclude
referenced objects and do not measure allocation frequency or retained size.

| Object | Shallow bytes |
| --- | ---: |
| Map `Bin` | 40 |
| `I#` | 24 |
| Map `Tip` singleton | 16 |
| Capture with one primitive Long and two DataValue references | 32 |
| Other ten discovered capture layouts | 24 each |
| JVM boxed `Long` | 24 |
| `Object[3]` | 32 |
| `Object[4]` | 32 |
| `Object[5]` | 40 |

The JVM reports version `25.0.4.1+1-LTS-jvmci-25.3-b22`, compressed object and
class pointers enabled, 8-byte object alignment, and compact object headers
disabled. [sizes.out](sizes.out) contains every measured constructor and capture,
its actual generated class, and its instance-field types. Generated class numbers
are identities within this probe process, not stable names across runs.

The helper links the `mapAggregate` Core dependency closure and creates samples
through the runtime's owned constructor and capture factories. It never reads
allocation keys. All reachable layouts are inspected, including cold paths.
It does **not** execute the Map workload, collect throughput, or request guest
compilation; the context sets `engine.Compilation=false`. Packet array lengths
are measured directly. No GHC object-size comparison is claimed here.

The probe uses `thc.staticShapeUnchecked=true` and
`thc.constructorClassIdentity=true`. [config.json](config.json) retains the exact
commands, flags, source-helper and JDK-release hashes, and frozen runtime manifest.
All 112 frozen files and the recorded probe inputs were hash-checked before and
after execution. The captured stderr contains routine Truffle Unsafe deprecation
warnings; all three compile/package/probe commands exited successfully.

Only source helpers, configuration, manifest metadata, and text outputs are
archived here. No compiled agent/classes or runtime/JDK libraries are included.
To repeat against an available frozen runtime, using a new output directory:

```sh
python3 bench/results/constructor-class/object-sizes/object-sizes.py \
  /path/to/frozen-v6 /path/to/new-output \
  --java-home /path/to/graalvm-25.3.4.1/Contents/Home \
  --backend bytecode \
  --jvm-option=-Dthc.staticShapeUnchecked=true \
  --jvm-option=-Dthc.constructorClassIdentity=true
```
