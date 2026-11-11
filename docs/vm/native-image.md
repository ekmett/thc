# Native Image

The [Jam-enabled GraalVM](build.md#graalvm) can also build native executables
whose Java objects live in jam's heap. Select the collector when building the
image:

```sh
export JAVA_HOME=/absolute/path/to/jam-graalvm
"$JAVA_HOME/bin/native-image" --gc=jam -jar application.jar application
./application -Xmx128m -Xmn32m
```

`-Xmx` sets the combined capacity of the two generations. `-Xmn` sets the
nursery capacity; the remainder belongs to old. The defaults are 128 MiB total
and a nursery one quarter that size. These capacities remain fixed for the
lifetime of an isolate.

Jam links its collector into the executable. On macOS and Linux this includes
its matching C++/ABI/unwind libraries; ordinary platform libraries remain
dynamic. The executable does not need a JVM installation or Jam runtime
sidecars on those platforms. On Windows, use `native-image.cmd`: Jam is linked
into the executable, while the builder still places the matching Microsoft CRT
DLLs beside it. Keep those DLLs with the executable; this does not switch
Graal's Windows toolchain from `/MD` to `/MT`.

The builder writes `application.thc/linkage.txt` containing `static` and copies
license notices into `application.thc/legal/`. Those files describe the build
and carry redistribution notices; the executable does not read them. Preserve
the notices in your distribution, wherever it keeps third-party licenses.

The supplier ships `lib/thc/native-image-libraries.txt`, listing archive names
in dependency order, and the corresponding archives under `lib/thc/static/`.
Native Image requires every listed archive and uses Graal's static-library
linker path. JVM execution continues to use the separately packaged shared
bridge and `runtime-libraries.txt`. Consumers of older packages without the
static manifest must still retain their generated runtime sidecars.

## Weak associations

Put the same `thc.vm.Weak` API used on HotSpot on the image class path:

```sh
"$JAVA_HOME/bin/native-image" --gc=jam \
  -cp "$JAVA_HOME/lib/thc/thc-vm.jar:application.jar" \
  your.application.Main application
```

Now, register an association and pump finalizers as usual:

```java
long token = Weak.create(key, value, finalizer);
Object result = Weak.deref(token);
int completed = Weak.pump();
```

The image builder connects these methods directly to the collector. There is
no JNI library to load in the executable. Ordinary Java reference queues and
generalized weak associations share the collection's reachability decisions;
a value or finalizer's return edge to its key does not keep the association
alive. See [weak-pointer semantics](weak-pointers.md).

Registrations belong to the current isolate. Any thread in that isolate can
pump them, including callbacks supplied by different Truffle contexts. A
token cannot be passed to another isolate. The installed runnable still owns
entering its guest context and executing the finalizer.

## Native pointers and isolates

Use Native Image's `PinnedObject` when native code must hold an object address.
Jam exposes a stable side mapping of the physical pages containing the object.
The managed reference can move during collection while the native alias stays
fixed. Both minor and major collection continue with pins open. Pinned page
runs constrain packing and can retain padding, so close pins when the native
operation finishes to release the alias and its retention costs.

The normal isolate entry, attachment and teardown APIs select the corresponding
Jam heap. A thread enters a `jam::heap_scope` before executing Java or VM work
and leaves it before returning to native code. Nested native callbacks can
therefore enter another isolate and return to the original one. No heap handle
needs to be passed through guest code.

## Truffle

Use this GraalVM as the image builder for the Truffle application. Keep its
language and Truffle dependencies on the application's normal image class path
and add `--gc=jam` to the build. The runtime compiler uses Jam's allocation and
exact-slot barriers; collection repairs stack, continuation and installed-code
references as well as the image heap's writable roots.

THC's weak primitive lowering and finalizer handoffs are integrated through
`thc.vm.Weak`; see [the shipped scope](status.md#shipped-scope-and-limits).
Other languages still supply their own primitive lowering and pump policy.

## Limits

The adapter uses compressed references with a three-bit shift, eight-byte
object alignment and ordinary object headers. The permanent image heap begins
1 GiB above the compressed base, so old capacity plus its protected prefix must
fit below that boundary. The nursery occupies jam's second 16 GiB address
domain. These are virtual address ranges, not eager physical allocations.

Collection stops Java mutators. Open side-alias pins do not defer major collection.
Layered images and dynamic class loading are not supported. The shared weak
registry's [registration contract](weak-pointers.md#claiming-a-finalizer)
also applies to native images.
The experimental AMD64 `MemoryMaskingAndFencing` option is not supported.
Native Image does not run legacy `Object.finalize` methods. Use the runnable
finalizers registered through `Weak` for guest finalization.
