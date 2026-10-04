# Original RTS event prerequisites and capabilities

Both backends recognize these exact pinned `ghc-internal` FCallIds. Their
unit, calling convention, safety, scalar ABI and State/result tuple are checked
at the foreign boundary; an unrelated symbol with the same spelling is not an
alternate declaration.

`getNumberOfProcessors` returns the context's immutable eligible CPU capacity:
the initial JVM available-processor count capped by discovered native eligibility,
at least one. This snapshot respects JVM/container limits and does not change
when a guest child is pinned.

`setNumCapabilities` accepts a positive `Word32` count and updates a separate,
context-local logical count. The original public Haskell wrapper retains its
nonpositive-`Int` rejection. Direct zero or noncanonical foreign carriers also
reject without changing the count. The `enabled_capabilities` read-only Word32
data label observes the logical count; its ownership and address restrictions
are unchanged. Updates and registry assignment are synchronized. Platform hosting
normalizes retained live and finished capability indices on shrink. Loom normalizes
parked and queued routes immediately and mounted routes when they unmount; HEC
workers are created on demand. Queries are snapshots, not a transaction with a
later query.

Ordinary platform threads receive logical capabilities round-robin; unmounted,
unlocked Loom threads can migrate between HECs. `forkOn#` reduces
its request modulo the logical count, then maps that capability modulo the
immutable eligible CPU count for its best-effort native affinity request.
Platform count changes do not repin existing carriers or change their initial
affinity-acceptance report. Neither mode creates CPUs, resizes JVM/compiler/GC
pools, or supplies GHC `-N` scheduling. Safe completion publishes the new count
before an enabled async exception poll; resumption does not replay the setter.
Ordinary async polling is explicitly opt-in on both backends.

The following first-writer shared CAF stores use the existing context-owned
stable-pointer protocol, each with an independent slot:

- `getOrSetSystemEventThreadIOManagerThreadStore`
- `getOrSetSystemTimerThreadEventManagerStore`
- `getOrSetSystemTimerThreadIOManagerThreadStore`

A null argument queries without installing. The winning valid stable pointer
is retained until context disposal; losers remain caller-owned. Foreign-context
pointers, invalid candidates, freeing a winner and use after disposal reject.
These slots do not themselves start a timer or event-manager thread.

`__hscore_f_setfd` (`Int32#`), `__hscore_fd_cloexec` (`Int64#`) and
`__hscore_sizeof_siginfo_t` (`Word64#`, safe) use ordinary package-declared
native linkage through Sulong, with the embedding context's native-access
authority. They have no THC-owned override; their package supplies the native
ABI values. The size grants no access to a host signal record. The original
`fcntl` write wrapper executes `F_SETFD` on an owned native resource, including
`FD_CLOEXEC`. See [foreign-code setup](interface-foreign.md).

## Native anonymous descriptors

In the explicit Linux x86_64 `NativeIO` context, original `pipe` and `eventfd`
create actual kernel resources behind the existing context-owned descriptor
registry. The returned integers are guest descriptor numbers, never arbitrary
host fds. Both pipe results are reserved before acquisition and published only
after validating the complete writable output image. Failed acquisition or
publication releases the acquired leases and reservations. Original read, write,
close, dup, status flags and readiness use these same owners; eventfd writes
retain their unsigned 64-bit counter value. Native failures retain actual errno,
including eventfd overflow/invalid increments, and success preserves sticky errno.
Ordinary contexts do not acquire this authority merely by allowing IO/native access.

`F_SETFD` updates descriptor flags on the owned native lease. Duplicates have
independent descriptor flags and share the open-file description.
[Process creation](process-lifecycle.md) preserves guest descriptor numbers and
`FD_CLOEXEC` when selecting inherited resources.

Original `epoll_create`, `epoll_ctl`, `epoll_wait` and `poll` use real Linux
kernel sets/readiness. Both safe and unsafe installed wait declarations lower
through typed AST and bytecode paths. Poll snapshots translate context descriptors
to private native duplicates and copy back only `revents`; negative descriptors
are ignored and unknown nonnegative descriptors report `POLLNVAL`. Zero-count
poll accepts a null image and retains its timeout/cancellation behavior.

Epoll preserves the opaque 64-bit event data and real ADD/MOD/DEL, level/edge and
one-shot semantics. Distinct logical dup aliases can have separate registrations;
closing one alias preserves existing registrations until the final guest owner
closes. Reusing its number cannot modify the old registration. Waits preserve a
single timeout deadline and wake on logical close or context cancellation.
Native errno is retained, including EEXIST, ENOENT and invalid arguments.

`setIOManagerWakeupFd`, `setIOManagerControlFd` and
`setTimerManagerControlFd` retain context-owned nonblocking eventfd/pipe writers.
Replacement and `-1` unregister are supported. Closing a registered descriptor
unregisters its logical identity before its number can be reused. Context shutdown
writes GHC's eventfd wake value `0xff` and control-pipe die byte `0xfe`, then clears
the registrations. Ordinary embedding streams cannot acquire this authority.
The existing original process-signal dispatcher remains the only signal delivery
path, in either hosting mode. Capability count changes update Loom HEC
routing but do not reconfigure event-manager threads automatically.

## Validation scope

The fixture-free [EnabledCapabilitiesTest](../src/test/java/thc/runtime/EnabledCapabilitiesTest.java)
checks typed Core lowering, logical count publication, owned label reads and
AST safe-completion masking/resumption. Existing [NativeEventDescriptorsTest](../src/test/java/thc/runtime/NativeEventDescriptorsTest.java),
[NativeEventWaitTest](../src/test/java/thc/runtime/NativeEventWaitTest.java) and
[NativeEpollTest](../src/test/java/thc/runtime/NativeEpollTest.java) check the runtime
owners' descriptor lifetimes, kernel readiness, cancellation and control-fd
protocol where their platform requirements are met. These component checks do
not qualify complete installed `ghc-internal` event-manager execution or its
package linkage.
