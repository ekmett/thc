# Weak pointers

A generalized weak pointer associates a key `K` with a value `V` and an optional
finalizer `F`. Keeping the weak pointer itself alive does not keep its key alive.
If the key is live, the value and finalizer are retained. If the key is dead,
the association dies and its finalizer becomes eligible to run.

The interesting part is deciding what *live* means. Both `V` and `F` may refer
to `K`, and other weak associations may lead to it as well.

The proposed language-layer strategy for lifted guest keys is tracked in
[issue #7](https://github.com/ekmett/jam/issues/7).

## Start with a cycle

Suppose the only path to a key goes through its own weak value:

```text
association A: K ⇒ V
              ▲   │
              └───┘
```

Here `⇒` is conditional retention and the return edge is an ordinary strong
reference. With no independent path to `K`, the association must die. Its own
value cannot supply the evidence needed to make that value live.

Now add a strong root to `K`. The key is live, so the collector follows `V`.
Everything strongly reachable from `V` is then live as usual. The difference
is entirely in when that first conditional edge is followed.

This is why a Java `WeakReference<K>` paired with a strongly held `V` does
not suffice. The strong value would retain the key through the return edge.
Making `V` weak too would allow it to disappear while `K` remains live.

## Close over live keys

One association's value can contain another association's key:

```text
root → K₁ ⇒ V₁ → K₂ ⇒ V₂
```

Following `V₁` makes `K₂` live, which permits following `V₂`. Registration order
must not determine whether that second step happens.

Let `S` be the objects retained before generalized weak processing, including
ordinary roots, pending/running guest finalizers and Java soft references
retained during discovery. Write `strong(X)` for closure under ordinary strong
edges, with Java reference fields handled by the VM's reference policy. Then:

```text
L₀     = strong(S)
Lₙ₊₁   = strong(Lₙ ∪ {V, F | an active (K, V, F) has K ∈ Lₙ})
L      = the least fixed point of this sequence
```

Starting at `L₀` gives us the least fixed point. In particular, a cycle of
conditional edges with no live entry point does not keep itself alive.
The implementation repeatedly scans unactivated registrations and drains
jam's actual tracing frontier until no association activates.

During a minor collection, old keys are conservatively live. The collector
does not prove old-generation death while collecting only young. A major
uses the marks for both generations.

## Freeze death before following finalizers

After closure, every remaining association dies as one batch. The registry
clears its key and value before any finalizer from that batch is traced.

Consider two associations with the same dead key. The first finalizer might
refer back to that key. If we traced it before deciding the second association,
the second one would appear live. Reversing registration order could reverse
the outcome.

Freezing the complete dead batch removes that dependency. A finalizer may keep
the key's object around, or resurrect it later by storing it in a root, but
the old weak registration stays dead:

```text
retired(A) ⇒ deref(A) = null
claims_of_finalizer(A) ≤ 1
```

Retirement is irreversible. Registering another association after resurrection
creates a new registration; it does not repair the old one. A dead key is kept
only when an actual strong edge from retained data reaches it. The registry
does not retain dead keys merely because they had finalizers.

Jam's original weak policy processes sorted registrations once and retains
newly dead finalizers immediately. That is a different policy. thc-vm keeps
jam's collection machinery and supplies the fixed-point and batch rules at
the hosted phase boundary.

## Java references in the same heap

HotSpot uses OpenJDK's `ReferenceProcessor` for Java soft, weak, final and
phantom references. Generalized weak processing shares its tracing epoch:

1. Trace ordinary VM roots and queued/running guest finalizers. Java discovery
   and soft-reference retention use the same jam frontier.
2. Close the generalized live-key fixed point and freeze the remaining dead
   registrations. Tracing a live value can discover further Java references.
3. Make Java soft/weak clearing and final-reference decisions.
4. Trace the frozen guest finalizers, then perform Java final keepalive and
   phantom processing.
5. Clear weak VM roots, prepare forwarding, repair roots and move objects.

An optional hook just before Java final keepalive supplies step four. Other
collectors retain their existing call behavior. This order lets a Java weak
reference be cleared before a guest finalizer retains or resurrects its
referent. It also gives phantom processing the final retained graph.

Running two weak processors once in sequence would not establish this closure
or ordering: following one association can expose another policy's edges.

## Java and JNI access

Use `java.lang.ref.WeakReference<T>` for an ordinary Java weak pointer. Its
`get()` returns a strong Java reference when the referent is still available.
Use `thc.vm.Weak` when a live key must retain a separate value or finalizer,
including values and finalizers that refer back to the key.

Native code can keep a JNI weak global created by `NewWeakGlobalRef`. Acquire
a strong local with `NewLocalRef` before using its referent, and release that
local with `DeleteLocalRef` when done. A separate null check does not keep the
referent alive between JNI calls. `NewLocalRef` can return null after collection
or an allocation failure. Check for a pending exception before treating null as
a collected referent.
Release the weak handle itself with `DeleteWeakGlobalRef`.

JNI weak globals have [phantom-reference clearing semantics](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/functions.html#weak-global-references).
A newly queued generalized finalizer can therefore keep a JNI weak global's
referent available after a Java `WeakReference` to the same object has cleared.
After the finalizer completes and its acquired strong references are released,
a later collection can clear the JNI weak global too. This ordering applies on
both HotSpot and Native Image.

## Claiming a finalizer

Registration tokens are stable native IDs. They are not object addresses and
do not serve as strong JNI handles to `K`, `V` or `F`. Dropping the token does
not cancel finalization. Retired native records are recycled, while a generation
in each token prevents an old token from naming a later registration. A slot
whose generation would wrap is never reused. Registry storage tracks its
concurrent high-water mark rather than the total number of registrations.

Registration acquires record, live-index and tracing-buffer capacity before
publishing a token. Failure leaves existing associations and their cleanup
ownership unchanged. The C entry returns zero; the matching HotSpot and Native
Image Java boundaries throw `OutOfMemoryError` after releasing the heap lock.
There is no implicit retry, retirement or finalizer execution on failure.
A language-side replacement must keep its existing cleanup ownership until the
new registration succeeds.

Registry traversal and retirement allocate no further metadata: fixed-point
tracing borrows the buffer reserved at registration. This does not make the
collector's tracing workers or other VM allocations immune to exhaustion.

The public [Java API](https://github.com/ekmett/thc/blob/main/src/vm/src/bridge/java/thc/vm/Weak.java) exposes the protocol:

| Operation | Effect |
| --- | --- |
| `create(K, V, F)` | Register a conditional association with a JVM `Runnable` finalizer |
| `deref(token)` | Return its active value, or null after retirement |
| `take(tokenOut)` | Claim one queued finalizer and return its token |
| `finalizeNow(token)` | Retire and claim explicitly, using the same at-most-once state |
| `complete(token)` | Release the running-finalizer root |
| `pump()` | Claim, run and complete pending callbacks on the calling thread |

For example, a caller can claim a finalizer, run it outside the GC
safepoint, and release its root even if execution throws:

```java
long[] token = new long[1];
Runnable finalizer = thc.vm.Weak.take(token);
if (finalizer != null) {
    try {
        finalizer.run();
    } finally {
        thc.vm.Weak.complete(token[0]);
    }
}
```

`pump()` performs this loop for the caller. A Truffle runtime installs a runnable
that enters and executes its guest closure. Context identity is invisible to
thc-vm; any caller can pump the shared queue. Pending and running finalizers
remain roots through subsequent collections, including a collection triggered
by the finalizer itself. `complete` ends that retention. See
[integrating thc](thc-integration.md) for the artifact and scheduling contract.

## Remaining work

The registry still scans current associations repeatedly. A key-indexed work
queue could reduce that cost without changing the laws above.

THC's weak primitive integration and language-owned handoffs shipped in
[THC #1200](https://github.com/ekmett/thc/pull/1200), with bounded end-to-end
weak/ForeignPtr and weak-thread evidence recorded in
[Jam #7](https://github.com/ekmett/jam/issues/7). This does not establish every
GHC behavior on every backend, or recovery from arbitrary VM exhaustion.
Finalizer scheduling and shutdown cleanup remain language-owned policies.

The pinned GHC sources include
[`System.Mem.Weak`](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/base/src/System/Mem/Weak.hs),
[`MarkWeak.c`](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/sm/MarkWeak.c)
and [`Weak.c`](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Weak.c).
See [supported configurations](status.md) for the remaining runtime work.

## Handoff costs

The [lifted weak measurement](lifted-weak-cost.md) compares ordinary weak
registrations, one handoff, and a short chain, including delayed pumping.
It records retention and finalizer latency on a packaged runtime; the observed
collection counts are not API guarantees.
