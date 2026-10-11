# jam-vm

![C++26](https://img.shields.io/badge/C%2B%2B-26-00599C?style=flat&logo=c%2B%2B&logoColor=white)
![Java25](https://img.shields.io/badge/Java-25-ED8B00?style=flat&logo=openjdk&logoColor=white)
[![CMake](https://img.shields.io/github/actions/workflow/status/ekmett/jam/vm.yml?branch=main&style=flat&label=CMake&logo=cmake&logoColor=white)](https://github.com/ekmett/jam/actions/workflows/vm.yml?query=branch%3Amain)

This directory adapts [jam](../README.md) to collect Java objects
on HotSpot and Native Image, with both Java reference processing and GHC-style
generalized weak pointers.
The intended consumer is [thc](https://github.com/ekmett/thc): Haskell running
through Truffle/Graal, with its closures in the host heap.

## Using the collector

First, [download a runtime](../docs/vm/distribution.md) or
[build the patched JDK](../docs/vm/build.md). Then select Jam with that JVM:

```sh
java -Xshare:off -Xms256m -Xmx256m \
  -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC -Xlog:gc \
  -jar application.jar
```

Keep `-Xms` and `-Xmx` equal. `JamYoungSize` sets the nursery capacity;
`JamPromoteEvery` sets the interval between whole-nursery promotion attempts.
Java code continues to use its ordinary reference classes.

This requires the Jam-enabled JDK. HotSpot's collector registration is compiled
into the VM; a stock JVM cannot load Jam as a collector plugin. See the
[build guide](../docs/vm/build.md) for dependencies and library paths.

## Weak associations

A generalized weak pointer associates a key with a value and an optional
finalizer. The value may refer back to the key without keeping the association
alive. Java's stock reference types don't provide that operation.

Build the [Java/JNI API](../docs/vm/thc-integration.md), then register an association:

```java
import jam.vm.Weak;

Weak.checkAvailable();
long token = Weak.create(key, value, () -> releaseResource());
```

Now you can read its value while the association is live:

```java
Object result = Weak.deref(token);
```

Any caller can run pending finalizers:

```java
int completed = Weak.pump();
```

Finalizers are JVM `Runnable`s. A runnable can enter whatever guest context it
needs; context identity is invisible to jam-vm. The pump runs callbacks on its
calling thread and completes each claim even when execution throws. The
[integration guide](../docs/vm/thc-integration.md) covers explicit finalization,
low-level claims, native loading and the thc handoff.

## [Documentation](https://ekmett.github.io/jam/)

* [Prebuilt runtimes](../docs/vm/distribution.md)
* [Build and run](../docs/vm/build.md)
* [Native Image](../docs/vm/native-image.md)
* [Integrating thc](../docs/vm/thc-integration.md)
* [Weak-pointer semantics](../docs/vm/weak-pointers.md)
* [Lifted weak references](https://github.com/ekmett/jam/issues/7)
* [Heap architecture](../docs/vm/architecture.md)
* [HotSpot integration](../docs/vm/hotspot-integration.md)
* [Supported configurations](../docs/vm/status.md)

The current targets are macOS arm64, Linux x86_64 and Windows x86_64, using
the patched JDK 25 or [GraalVM 25.3.4.1](../docs/vm/build.md#graalvm), with fixed capacities,
stop-the-world collection and compressed oops. Native executables use the
SubstrateVM adapter, selected with `native-image --gc=jam`.
See the [package platform requirements](../docs/vm/distribution.md) for OS floors.

## Source and license

The [source manifest](https://github.com/ekmett/jam/blob/main/vm/config/source-pins.json) pins OpenJDK, Graal and the
reference implementations. The adapter links the Jam library from this checkout;
the root CMake build owns the native dependency. Jam is used directly through its hosted collection
API. The [HotSpot patch](https://github.com/ekmett/jam/blob/main/vm/patches/hotspot-jam.patch) adds the registered collector.
Java objects live in jam's heap, using its SIMD compactor and work scheduler.

Original code is **BSD-2-Clause OR Apache-2.0**. Imported code and patches
retain their own terms. See [LICENSE.md](LICENSE.md) and [NOTICE.md](NOTICE.md).

## Contact Information

Contributions and bug reports are welcome!

Please feel free to contact me through [GitHub](https://github.com/ekmett/jam).

-Edward Kmett
