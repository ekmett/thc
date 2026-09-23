# Class-owned constructor layout prototype

Isolated source copy of `e9c3db9f2c8710f65281ee10e1521a25de7a7566`, verified file by file in `snapshot.json`. This directory is ignored; the main checkout is unchanged. All four complete test configurations passed; actual factory shallow sizes are measured below. No throughput claim yet.

`-Dthc.classOwnedLayouts=true` opts into constructor values without an instance layout pointer. The default remains false. Other storage safety, constructor-class matching, boxed-value caching, and compact-header flags keep their previous meaning.

The private `ClassValue` registry permanently reserves one generated carrier for a private owner token, then publishes its completed `DataLayout` descriptor. A reservation is never reassigned, removed, or weakened. The descriptor is strongly retained by the class-scoped entry, and publication is a volatile write after all layout fields and cached values initialize. The descriptor has no language, context, environment, program, or arbitrary guest field values. It does retain its StaticShape/factory/properties and primitive boxed/nullary caches. A globally retained shared array carrier therefore retains its first owner descriptor; this design does not promise per-context reclamation of that owner.

A constructor operation knows the expected layout and checks its reserved exact carrier. It does not consult ClassValue. Only generic `DataValue.layout`/debug printing performs the cold boundary lookup. The fieldless DataValue base has no instance fields. The ordinary representation is a LayoutDataValue subclass with one layout field. For shared array classes, a failed permanent reservation causes the private sample to be discarded and the shape rebuilt with fresh StaticProperty instances using that layout-carrying superclass before any value escapes. Unexpected carrier changes on the pointer-free path fail before escape; the ordinary path keeps the prior assumption-based fallback behavior.

Both representations authenticate the private allocation key before ValidatedStorage/Object initialization. The key and owner token are distinct and are never stored in values. Reference assignment checking, explicit layout ownership/index guards, and the engine safety override are preserved. StaticShape uses only its supported builder API; factory return assignability permits the same DataValueFactory for both public superclasses.

Changed runtime files:

- `src/main/kotlin/thc/runtime/DataValues.kt`
- `src/main/kotlin/thc/runtime/ClassOwnedLayouts.kt` (new)

New tests cover compiled old values surviving array collisions, closed contexts, option mixing, permanent registration/publication, reference types, nulls, lazy thunks, and invalid keys. Existing tests that assumed all array-backed layouts use the same Java class now distinguish the permanent first owner from shared fallback carriers; their ownership and cold-branch checks remain active in both modes.

Reproduction commands (already executed under the granted serial CPU lane; separate logs/XML retained in `validation/`):

```sh
export JAVA_HOME=/Users/ekmett/cadenza/.toolchains/graalvm-25.3.4.1+1.1/Contents/Home
export THC_GRADLE_USER_HOME=/Users/ekmett/thc/.gradle-user-home
./scripts/gradle.sh test installDist --no-daemon
JAVA_TOOL_OPTIONS=-Dthc.classOwnedLayouts=true ./scripts/gradle.sh test --rerun --no-daemon
JAVA_TOOL_OPTIONS='-Dthc.classOwnedLayouts=true -Dthc.staticShapeUnchecked=true -XX:+UseCompactObjectHeaders' ./scripts/gradle.sh test --rerun --no-daemon
```

`validate-prototype.py` also ran compact checked with class matching and boxed caching enabled, then the same configuration with storage checks disabled. Every configuration passed **157 tests, zero failures/errors/skips**. Constructor bytecode confirms the key check precedes `ValidatedStorage.<init>`; DataValue has no instance field; LayoutDataValue stores exactly one layout field after authenticated superconstruction. The ClassValue field is private static final, with no public getter or reset method.

The first artifact freeze stopped because copied `modules.txt` retained the old frozen corpus's read-only permission. Only the newly copied manifest was made writable; `--resume-freeze` resumed publication and all four probes. No runtime source or test changed after these successful runs.

| Actual constructor | Normal, off | Normal, owned | Compact, off | Compact, owned |
| --- | ---: | ---: | ---: | ---: |
| Map Bin | 40 | 40 | 40 | 32 |
| I# | 24 | 24 | 24 | 16 |
| Tip | 16 | 16 | 16 | 8 |

These are shallow bytes from `Instrumentation.getObjectSize` through real linked Map factories. Each probe hash-checked the frozen inputs before and after, executed no Map workload and disabled guest compilation. The JVM used compressed oops/class pointers and 8-byte alignment. Exact classes, fields, flags and helper hashes are retained in `validation/sizes-*/`. The runtime JAR SHA256 is `1d51bca562a5f7848cd2f9b88cde6102e00509760bf2a70713f0afedc4fcb192`.

Remaining validation belongs to the parent's serialized lane: full 18-case Map checks for proposed configurations, then controlled throughput and allocation/graph comparison. Compare prototype-off against old v7 as well as prototype-on against prototype-off: the new fallback subtype check might affect the off path. No throughput improvement or retained-size claim is established yet.
