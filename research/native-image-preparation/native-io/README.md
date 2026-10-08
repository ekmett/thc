# Linux native IO downcall metadata

The executable image's ordinary loader creates a native IO context before
entering guest code. These descriptor/option combinations cover the existing
Linux x86-64 native services; they are not guest symbol or representation
admission rules. Registering a stub does not open libraries, allocate handles,
initialize contexts or execute foreign code during image preparation.

Keep option variants distinct: `captureCallState` means the existing `errno`
capture, and only the readiness `fcntl` descriptor uses `firstVariadicArg: 2`.
No critical/heap-access option or upcall is registered here. Native callbacks
continue to use the existing NFI provider.

Source inventory (all under `src/main/java/thc/runtime`):

| Owner | Declared downcalls |
| --- | --- |
| NativeFileLease.NativeCalls / WaitDuplicate | close; variadic fcntl |
| NativeDirectoryApi | open, name, dup_command, range_error, stream_open, stream_fdopen, stream_read, stream_close |
| NativePollApi / NativeEpoll.Api | eventfd, poll, read, write, close; epoll_ctl, epoll_wait |
| NativeOpenOperation.Api | observe, start_at, done, cancel, wake, reset, wait, finish |
| NativeProcessApi | spawn, poll, terminate, dispose |
| NativeSignalTransport.Api | open, info_size, constant, number, usr2_available, install, take, wake, reset_wake, close, exit |
| ProcessIdentity.Libc | getpid, geteuid |
| NativeUnix.Bindings | errno address and every existing OriginalStdioOp.getUnixNative declaration |
| ManagedNativeAllocations.Libc | malloc with errno capture, free |
| LinuxCpuAffinity | sched_getaffinity, sched_setaffinity |
| NativeNarrowAtomic | thc_atomic_cas_narrow |

Identical shapes/options share one registration. `jint`/`jlong` match the Java
carriers already declared by those bindings; signedness remains the original
runtime's responsibility. Windows-specific bindings are not included in this
Linux-only experimental image profile. The launcher metadata regression checks
the startup close and open-request observation shapes, the variadic/capture distinction, uniqueness,
and all registry-derived Unix signatures without initializing a native provider.
