# Descriptor readiness and waiting

The Linux x86_64 native file provider supplies readiness for its owned resources.
`fdReady` uses this service; [waitRead# and waitWrite#](file-wait.md) additionally
support resumable guest interruption. Opaque embedding streams have no readiness
contract and return ENOTSUP.

Each wait retains the exact logical descriptor. Close, replacement with `dup2`,
and context disposal invalidate it and wake its waiters. Reusing the descriptor
number cannot redirect a suspended wait to a new resource. Aliases have independent
logical wait sets while sharing their open description.

A physical wait owns a duplicated native lease and a private eventfd. Native
`poll` waits for readiness or wakeup without busy polling. Safepoints and host
cancellation wake it; resource cleanup closes both handles on every exit.
No registry or IO monitor is held during the poll.

Guest delivery is considered at a resumable blocking cut. Masked-interruptible
waits permit delivery; uninterruptible masking defers it. Existing safe/unsafe
`fdReady` calls remain foreign extents and do not become asynchronously
interruptible merely because their underlying host poll can be cancelled.

Regular files have immediate readiness, including EOF. Native pipes and other
supported descriptors use actual direction-sensitive events; errors and hangups
count as ready. Readiness preserves sticky errno and never consumes stream bytes.
Logical close can precede completion of an already-started transfer; physical
retirement waits for that transfer. This service does not make arbitrary native
byte transfers interruptible.

The implementation uses [TruffleSafepoint](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/TruffleSafepoint.html)
and its interrupter contract. Other platforms need their own native readiness
provider.
