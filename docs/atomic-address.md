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

Native-enabled Linux x86_64 contexts also admit owned malloc storage under a
lifetime borrow. Word and Word32 operations use volatile memory-segment handles.
JDK 25 excludes byte/short atomic updates, so a small C provider implements those
two CAS widths using sequentially consistent compiler atomics; it never reads a
wider element to emulate them. This follows the
[JDK access-mode restrictions](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/MemoryLayout.html#access-mode-restrictions).
Native pointer cells contain actual process pointer bits. Results recover live
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
./gradlew --no-daemon test --tests thc.runtime.AtomicAddressTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --tests thc.runtime.AtomicAddressTest
```

[AtomicTickets.hs](../examples/AtomicTickets.hs) shows a ticket dispenser whose
fetch-add returns the first reserved ticket:

```sh
compiler/export.sh examples/AtomicTickets.hs
build/install/thc/bin/thc build/core/AtomicTickets.json reserveTickets 40 3 --compile
THC_BACKEND=ast build/install/thc/bin/thc build/core/AtomicTickets.json reserveTickets 40 3 --compile
```

Both calls return `40`; the counter in the local allocation advances to `43`.
This example requires an existing `installDist` build. The native fixture driver
can also be run directly, for example
`printf 'numeric 8 40 3 0\\n' | build/atomic-address/native/oracle`.
