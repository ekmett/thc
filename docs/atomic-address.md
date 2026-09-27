# Atomic address operations

All 16 GHC 9.14.1 address atomics have typed AST and bytecode lowering:

| Operation | Result |
| --- | --- |
| `atomicReadWordAddr#` | Current machine word |
| `atomicWriteWordAddr#` | State only |
| `atomicExchangeWordAddr#`, `atomicExchangeAddrAddr#` | Previous word or address |
| `atomicCasWordAddr#`, `atomicCasWord8/16/32/64Addr#`, `atomicCasAddrAddr#` | Previous value, whether comparison succeeds or fails |
| `fetchAdd/Sub/And/Nand/Or/XorWordAddr#` | Previous machine word |

CAS compares the narrowed expected value, stores the narrowed replacement on
success, and zero extends narrow results. Arithmetic wraps at 64 bits. Every
operation supplies a full barrier; this is stronger than the exchange primops'
specified read barrier. Locations must have the operation's natural alignment
and contain the entire element. Narrow CAS touches no adjacent sentinel bytes.

Managed allocations and their derived addresses share the allocation monitor.
Their atomics additionally lock the backing byte array, so separately constructed
raw-array address aliases synchronize with the original allocation. Managed
pointer cells retain address references, including null; CAS compares location
identity and offset. Numeric atomics reject pointer cells rather than exposing
synthetic pointer bits. Immutable byte storage admits atomic reads only.

Pinned numeric storage uses real atomics on Linux x86_64 and macOS x86_64/arm64.
Word and Word32 operations use volatile memory-segment handles. JDK 25 excludes
byte/short atomic updates, so the packaged C provider implements those two CAS
widths using sequentially consistent compiler atomics; it never reads a wider
element to emulate them. This follows the
[JDK access-mode restrictions](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/MemoryLayout.html#access-mode-restrictions).

Native-enabled Linux x86_64 contexts also admit owned malloc storage under a
lifetime borrow. Native pointer cells contain actual process pointer bits.
Results recover live
allocations in the current context or registered immutable images; unknown bits
remain non-dereferenceable. Managed mutable pointers cannot be stored in native
cells because they have no real native address. Freed owners, foreign contexts,
unowned numeric locations and opaque labels reject before memory access.

The native oracle and independent Kotlin byte-buffer model cover all operations,
successful and failed CAS, narrowing, wraparound, aliasing, null pointers and
neighboring sentinels. Pre/post-tidy Core is audited. Both backends exercise the
first installed call with exact compiled-entry increments and no settling calls;
storage checks cover raw/owned aliases, contention, publication and native
lifetime/context failures. Run the focused group with the pinned toolchain:

```sh
cabal run exe:thc-fixtures --offline -- atomic-address
./gradlew --continue testDefault --tests thc.runtime.AtomicAddressTest \
  testDense --tests thc.runtime.AtomicAddressTest
```

[AtomicTickets.hs](../examples/AtomicTickets.hs) shows a ticket dispenser whose
fetch-add returns the first reserved ticket. Export it from the repository root:

```sh
compiler/export.sh examples/AtomicTickets.hs
```

`reserveTickets :: Int# -> Int# -> Int#` needs two arguments. The low-level
command-line kernel launcher accepts only one integer, so call this entry from
the existing [JVM embedding API](site/embedding.md#load-an-integer-kernel):

```kotlin
import thc.executionContext
import thc.loadEntry

fun main() {
    for (backend in listOf("bytecode", "ast")) {
        executionContext().use { context ->
            val reserve = loadEntry(context,
                listOf("build/core/AtomicTickets.json"),
                "reserveTickets", backend = backend)
            check(reserve.execute(40L, 3L).asLong() == 40L)
        }
    }
}
```

Use the pinned JVM dependencies and run with the repository root as the working
directory. The arguments initialize the local counter to `40` and advance it to
`43`; the returned ticket is `40`. This example does not request compilation.
After preparing `atomic-address`, its native fixture driver can also be run
directly:

```sh
printf 'numeric 8 40 3 0\n' | build/atomic-address/native/oracle
```
