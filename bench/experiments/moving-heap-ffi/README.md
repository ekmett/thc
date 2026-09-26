# Movable heap arrays at a native boundary

This is a Linux JNI/FFM experiment, not a production THC foreign-call adapter.
Java is used here for the actual JNI class/native-method ABI and FFM call-site
probe; it adds no Java runtime forwarding helper or Python harness.

## Required runtime contract

- Ordinary `newByteArray#` storage remains on the movable JVM heap.
- Only explicitly pinned allocation primops receive permanently stable native
  backing from allocation. `withForeignPtr`, contents operations and unsafe
  freeze do not copy or promote that storage.
- Sulong bitcode accesses an ordinary array through a managed reference plus
  offset: no physical pin is needed, including while GC moves its backing array.
- A real native call receiving an unpinned array must borrow the original bytes
  only for its native extent. No extra payload copies relative to GHC, no hidden
  permanent pinning and no copy-capable fallback.
- Explicit copying operations keep their ordinary GHC copying semantics.

## What this experiment checks

`HeapPinProbe` passes an ordinary Java `byte[]` and an overlapping slice into
a constant-time native leaf through `Linker.Option.critical(true)`. The C
function checks pointer aliasing and changes a byte observed through both
arguments and then from Java. An ordinary FFM downcall rejects the heap segment.

A separate JNI function acquires two critical references to the same array,
checks `isCopy == false`, checks pointer identity, writes through one alias and
reads through the other. Both acquisitions are released before returning.
The only JNI calls while held are the permitted nested critical acquisitions
and releases. There are no callbacks, blocking calls or retained pointers.

GC is requested *between* calls. Brief separate critical acquisitions sample
address bits to observe whether the array moves; those bits are never
dereferenced outside their acquisition. Relocation is reported, not required
by the assertion: a collector is free not to move an object in a given run.

## Run on Linux

Use the pinned Graal/JDK 25 installation as `JAVA_HOME`, from the repo root:

```sh
probe_build=$(mktemp -d /tmp/thc-moving-heap-ffi-XXXXXX)
clang -O2 -fPIC -shared -Wall -Wextra -Werror \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  bench/experiments/moving-heap-ffi/heap_pin.c \
  -o "$probe_build/libheap_pin.so"
"$JAVA_HOME/bin/javac" -d "$probe_build" \
  bench/experiments/moving-heap-ffi/HeapPinProbe.java
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED \
  -XX:-UsePerfData -Xms32m -Xmx96m -XX:+UseG1GC \
  -cp "$probe_build" HeapPinProbe "$probe_build/libheap_pin.so"
```

Observed on 2026-09-26, Linux x86_64:

```text
runtime=25.0.4.1+1-LTS-jvmci-25.3-b22
collectors=[G1 Young Generation, G1 Concurrent GC, G1 Old Generation]
ordinary_heap_rejected=true
ffm_heap_alias_mutation_checks=80
jni_no_copy_alias_mutation_checks=80
observed_array_relocations_between_calls=1
PASS (bounded leaf calls only; no general Sulong/native integration claim)
```

## Integration limits

The [FFM critical-call contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html#critical(boolean))
requires extremely short native execution without Java callbacks. A GHC
`unsafe` import is not automatically an FFM-critical function.

[JNI critical access](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/functions.html#getprimitivearraycritical-releaseprimitivearraycritical)
also restricts execution while held, and its portable specification permits
copying. This experiment establishes direct access only on the tested runtime
and collector; checking `isCopy` after acquisition is a diagnostic, not a
portable guarantee that no copy was attempted.

Inspection of pinned Sulong/NFI 25.3.4.1 found:

- Panama NFI array conversion allocates native storage and copies, with a
  separate copy-back path.
- libffi NFI array conversion uses `GetByteArrayElements` and
  `ReleaseByteArrayElements`, not critical access.
- Sulong lowers native data-pointer arguments to raw addresses before NFI;
  adding a buffer `toNative` method does not bracket a native borrow.

Therefore neither existing NFI array path meets the no-extra-copy contract.
A THC-owned JNI/native transition must enclose the entire native call,
deduplicate allocation aliases before acquisition, and release every borrowed
array before returning to Java/Truffle. It cannot expose a temporary pointer
and return to Java while the array remains critically acquired. Such a
production transition is not implemented by this experiment.

Existing native provider staging is not made zero-copy by this result.
Explicitly pinned native backing, managed buffer-only views and remaining
provider transitions must be tested separately. No managed-only Sulong run
is claimed here.

