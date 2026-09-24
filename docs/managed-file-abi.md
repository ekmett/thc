# Managed JVM file ABI version 1

`foreign import prim safe` declarations with the exact `thc_io_v1_*` symbols
select THC's context-owned JVM file service. These are explicit THC intrinsics;
names such as `open`, `read`, `write`, `close`, and the platform's `__hsbase_*`
functions are not redirected. Descriptors are context-local integers, never raw
POSIX or Windows handles. The service supports regular files and the embedding's
standard byte streams; it does not implement a scheduler, readiness polling,
interruptible calls, or arbitrary device files.

Every argument is unlifted. In the table, `S` is the erased `State# RealWorld`
carrier, `I` is exact `IntRep`, and `A` is exact `AddrRep` backed by managed memory.

| Symbol suffix after `thc_io_v1_` | Arguments | Result |
| --- | --- | --- |
| `open` | A path, I mode, S | (# S, I descriptor #) |
| `read` | I descriptor, A destination, I length, S | (# S, I count #) |
| `write` | I descriptor, A source, I length, S | (# S, I count #) |
| `close` | I descriptor, S | (# S, I status #) |
| `error_kind` | S | (# S, I category #) |
| `error_message` | S | (# S, A message #) |
| `seek` | I descriptor, I offset, I mode, S | (# S, I position #) |
| `size` | I descriptor, S | (# S, I length #) |
| `set_size` | I descriptor, I length, S | (# S, I status #) |
| `is_terminal` | I descriptor, S | (# S, I flag #) |
| `device_type` | I descriptor, S | (# S, I type #) |

Open modes are Read 0, Write 1, Append 2, and ReadWrite 3. Seek modes are
absolute 0, relative 1, and end 2. Device types are regular file 0 and stream 1.
The embedding exposes no terminal identity, so valid descriptors currently
report `is_terminal = 0`. Paths and error messages are NUL-terminated UTF-8.
Error messages are immutable managed addresses. Read returns zero at EOF;
successful close and set_size return zero. Operational failures return -1.
The sticky error category/message belongs to the current context and host
thread; getters and successful operations do not erase it.

Error categories are None 0, NotFound 1, Permission 2, AlreadyExists 3,
BadDescriptor 4, InvalidArgument 5, Other 6, Unsupported 7, ResourceBusy 8, and
IsDirectory 9. These values are portable THC categories, not host errno values.
Malformed managed memory and invalid State carriers remain runtime faults.

The loader and auditor require schema 1, an exact static function target, exact
prim/safe convention and safety, saturated declared and supplied arity, and raw
argument/result representations without extra fields. The target's unit field
must exist and be null or a nonempty string: main and library declarations share
the same explicit ABI. A lexical/global/join-bound head cannot acquire intrinsic
behavior from an attached descriptor. Unknown symbols under `thc_io_v1_` fail
closed. Both lowerings evaluate operands and validate State before service
effects, erase only State, and store the payload in a typed long/address slot.

`compiler/THC/Plugin.hs` must recognize these exact symbols in its prim/safe
foreign-import closure check; this does not authorize recognition of arbitrary
symbols under the prefix. The auditor additionally requires each supported
symbol in `scripts/core-capabilities.json`'s `managedForeignCalls` list.

The focused controls are `CoreManagedFilesTest`, `ManagedFileCallTest`, and
`python3 scripts/test-core-managed-files.py`. They cover all eleven contracts,
mutated descriptors and bound heads, normal and explicitly installed compiled
AST/bytecode calls, binary buffers, EOF, seeking, resizing, append, typed error
messages, and invalid State before effects. These synthetic ABI controls are
separate from native comparisons and exported public `System.IO` Handle tests;
they do not by themselves establish those higher-level APIs' support.
Replacing a higher-level source adapter alone does not cover old GHC unfoldings
already inlined into precompiled consumers: those require a future bridge at the
original foreign/C-bitcode boundary, or rebuilding all consumers against the
backend's interfaces.

The AST dispatch uses direct comparisons against its constant operation. A
Kotlin enum `when` compiled through its synthetic mutable mapping array retained
all operation branches in the captured Graal graph and triggered a frame-accessor
guard on the first compiled `size` call. Direct comparisons remove the unrelated
paths. The regression requires the first installed targets to stay valid after
every operation, without recompilation or relaxed assertions.
