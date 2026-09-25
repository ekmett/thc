# Original terminal-image and signal ABI helpers

This Linux x86-64 slice implements the thirteen unchanged GHC 9.14.1 declarations
`__hscore_sizeof_termios`, `__hscore_lflag`, `__hscore_poke_lflag`,
`__hscore_ptr_c_cc`, `__hscore_echo`, `__hscore_icanon`, `__hscore_vmin`,
`__hscore_vtime`, `__hscore_tcsanow`, `__hscore_sizeof_sigset_t`,
`__hscore_sigttou`, `__hscore_sig_block`, and `__hscore_sig_setmask`.
These are image accessors and header
constants, not terminal syscalls. The closed ABI probe uses the build host's
actual `<termios.h>`/`<signal.h>` and the existing checked native compiler target.
Signal constants preserve `HsBase.h`'s zero fallback when a macro is absent;
`sizeof(sigset_t)` is measured. The size helper returns an `Int#`; the three
signal constants return `Int32#`. All four retain the original unsafe `ccall`,
State# operand and State/result tuple. The three signal constants retain their
original-call CAF bodies; GHC inlines the size helper's original FCallId directly
into the consumer. These calls do not read or change the host signal mask.

Every image access validates the complete struct byte region. A setter also
requires writable storage and a canonical unsigned 32-bit value before any
write. Managed pointer cells within the image are rejected, and allocation-owned
images remain locked through validation and access. `c_cc` returns an alias
retaining the same allocation, not a host pointer. Original zero-width State
operands are checked before access; the setter preserves the exact singleton
`(# State# #)` result, without inventing a returned scalar.

`thc-fixtures original-termios` prepares genuine public installed-import
pre/post Core and native observations using the existing Haskell framework.
The native oracle touches allocated images only: six full-width patterns,
offset canaries, member reads/writes, and two writes through the returned
`c_cc` pointer. Kotlin checks the raw descriptors, strict closures, observed
images and constants, both runtime backends, and first installed compiled
entries. CAF constants separately exercise each unchanged original-call body before
its shared thunk is forced: one compiled entry per call, followed by the warmed
consumer's one entry with both retained targets still valid. The signal-size
consumer instead requires exactly one original-call binding and one retained,
first-installed compiled target, executing exactly one compiled entry per call.
Memory consumers
execute two compiled entries (the consumer and its immediate State lambda).
Other hosts are explicitly excluded, not assigned guessed layouts.

This does not admit `tcgetattr`, `tcsetattr`, signal masks, saved terminal state,
event-manager stores or bound-thread support. It does not mutate a host terminal
or establish full original Handle/`putStrLn` execution. Those reachable calls
remain separate strict-audit obligations.
