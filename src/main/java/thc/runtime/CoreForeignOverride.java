// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Map;
import java.util.List;

/** Identities whose native implementation cannot operate on THC-owned state.
 * Identity selection is nonthrowing; the selected existing validator checks the ABI. */
public enum CoreForeignOverride {
    STACK, STACK_INFO, STDIO, PROCESS, STABLE_FREE, SHARED_CAF, SHUTDOWN, MAIN_THREAD,
    BOUND_THREAD, THREAD_ID, GC, RTS_EVENT, ALLOCATION_COUNTER, STRING_RTS, ENVIRONMENT,
    RTS_DIAGNOSTIC, RTS_ARGUMENTS, MANAGED_FILE, SIGNAL, ALLOCATION, MEMMOVE, MEMCPY;

    static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst())) {
                var selected = select(CoreRepresentations.metadata(list));
                if (selected != null) {
                    if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                        throw RuntimeFault.fault("Foreign override lacks a declared variable head");
                    switch (selected) {
                        case STACK -> CoreStackForeign.validateHead(head, false);
                        case STACK_INFO -> CoreStackInfoForeign.validateHead(head, false);
                        case STDIO -> CoreOriginalStdio.validateHead(head, false);
                        case PROCESS -> CoreProcessForeign.validateHead(head, false);
                        case STABLE_FREE -> CoreStablePointers.validateHead(head, false);
                        case SHARED_CAF -> CoreSharedCAFStores.validateHead(head, false);
                        case SHUTDOWN -> CoreRtsShutdown.validateHead(head, false);
                        case MAIN_THREAD -> CoreMainThreadForeign.validateHead(head, false);
                        case BOUND_THREAD, ALLOCATION_COUNTER -> CoreBoundThreadForeign.validateHead(head, false);
                        case THREAD_ID -> CoreThreadIdForeign.validateHead(head, false);
                        case GC -> CoreGcForeign.validateHead(head, false);
                        case RTS_EVENT -> CoreRtsEventForeign.validateHead(head, false);
                        case STRING_RTS -> CoreStringRtsForeign.validateHead(head, false);
                        case ENVIRONMENT -> CoreEnvironmentForeign.validateHead(head, false);
                        case RTS_DIAGNOSTIC -> CoreRtsDiagnosticForeign.validateHead(head, false);
                        case RTS_ARGUMENTS -> CoreRtsArgumentsForeign.validateHead(head, false);
                        case MANAGED_FILE -> CoreManagedFiles.validateHead(head, false);
                        case SIGNAL -> CoreSignalForeign.validateHead(head, false);
                        case ALLOCATION -> CoreNativeAllocationForeign.validateHead(head, false);
                        case MEMMOVE -> CoreMemoryCopyForeign.MEMMOVE.validateHead(head, false);
                        case MEMCPY -> CoreMemoryCopyForeign.MEMCPY.validateHead(head, false);
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    public static CoreForeignOverride select(Object metadata) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call) ||
                !(call.get("target") instanceof Map<?, ?> target) || !(target.get("symbol") instanceof String symbol)) return null;
        Object unit = target.get("unit");
        boolean internal = "ghc-internal".equals(unit);
        boolean unix = CoreOriginalStdio.isOriginalUnixUnit(unit);
        // These names identify the public THC ABI, not arbitrary libc spellings.
        for (var operation : ManagedFileOp.values())
            if (operation.getSymbol().equals(symbol)) return MANAGED_FILE;
        if (internal) {
            if (CoreStackForeign.CLONE.equals(symbol)) return STACK;
            if ("hs_free_stable_ptr".equals(symbol)) return STABLE_FREE;
            if ("rts_setMainThread".equals(symbol)) return MAIN_THREAD;
            if ("rtsSupportsBoundThreads".equals(symbol)) return BOUND_THREAD;
            if ("stg_getThreadAllocationCounterzh".equals(symbol)) return ALLOCATION_COUNTER;
            if (ThreadIdForeignOp.named(symbol) != null) return THREAD_ID;
            for (var operation : OriginalStackInfoOp.values()) if (operation.getSymbol().equals(symbol)) return STACK_INFO;
            for (var operation : RtsShutdownOp.values()) if (operation.getSymbol().equals(symbol)) return SHUTDOWN;
            for (var operation : RtsDiagnosticOp.values()) if (operation.getSymbol().equals(symbol)) return RTS_DIAGNOSTIC;
            for (var operation : NativeAllocationOp.values()) if (operation.getSymbol().equals(symbol)) return ALLOCATION;
            for (var operation : RtsEventForeignOp.values())
                if (operation != RtsEventForeignOp.SIGINFO_SIZE && operation.getSymbol().equals(symbol)) return RTS_EVENT;
        }
        var store = SharedCAFStore.named(symbol);
        if (store != null && store.getUnit().equals(unit)) return SHARED_CAF;
        for (var operation : GcForeignOp.values())
            if (operation != GcForeignOp.MONOTONIC && operation.getUnit().equals(unit) && operation.getSymbol().equals(symbol)) return GC;
        // A native GHC RTS cannot report THC's compiler compatibility and RTS modes.
        for (var operation : StringRtsOp.values())
            if ((operation == StringRtsOp.THREADED || operation == StringRtsOp.KEEP_CAFS || operation.isHostWay()) &&
                    operation.acceptsUnit(unit) && operation.getSymbol().equals(symbol)) return STRING_RTS;
        for (var operation : RtsArgumentsOp.values())
            if ((internal || unix && operation == RtsArgumentsOp.GET) && operation.getSymbol().equals(symbol)) return RTS_ARGUMENTS;
        for (var operation : EnvironmentOp.values()) {
            if (!(internal || unix) || !operation.matchesSymbol(symbol)) continue;
            if ((operation == EnvironmentOp.SET || operation == EnvironmentOp.CLEAR || symbol.equals("__hsunix_get_environ")) && !unix) continue;
            if (symbol.startsWith("ghczuwrapper") && (!unix || !wrapperOwner(symbol, unit))) continue;
            return ENVIRONMENT;
        }
        if (internal || "unix-2.8.8.0-inplace".equals(unit))
            for (var operation : ProcessSignalOp.values()) if (operation.getSymbol().equals(symbol)) return SIGNAL;
        if (unit instanceof String name && name.matches("process-1\\.6\\.26\\.1-(?:inplace|[0-9a-f]+)"))
            for (var operation : ProcessOp.values()) if (operation.getSymbol().equals(symbol)) return PROCESS;
        if (symbol.equals("memmove") && internal) return MEMMOVE;
        if (symbol.equals("memcpy") && (internal || "array-0.5.8.0-inplace".equals(unit) ||
                unit instanceof String name && name.matches("ram-0\\.22\\.1(?:-[A-Za-z0-9]+)?"))) return MEMCPY;
        for (var operation : OriginalStdioOp.values()) {
            if (!ownedStdio(operation) || !operation.acceptsUnit(unit) || !operation.matchesSymbol(symbol)) continue;
            if (symbol.startsWith("ghczuwrapper") && !wrapperOwner(symbol, unit)) continue;
            return STDIO;
        }
        return null;
    }

    private static boolean wrapperOwner(String symbol, Object unit) {
        return unit instanceof String name && symbol.contains("ZC" + name.replace("-", "zm").replace(".", "zi") + "ZC");
    }

    private static boolean ownedStdio(OriginalStdioOp operation) {
        if (operation.getUnixNative()) return NativeUnix.pathname(operation) || NativeUnix.descriptor(operation);
        return operation.getTransfer() || operation.getOpening() || operation.getDuplication() ||
            operation.getReadiness() || operation.getFcntl() || operation.getEventDescriptor() ||
            operation.getDirectoryStream() || operation.getCurrentDirectory() || operation.getPathRemoval() ||
            operation.getPathLink() || operation.getPathMode() || operation.getPathStat() ||
            operation.getReadImage() || operation.getSavedTermios() || operation.getIconv() || operation.getLocking() ||
            switch (operation) {
                case ERRNO, SET_ERRNO, CLOSE, SEEK, TRUNCATE, ISATTY, ACCESS, UNLINKAT, FSTATAT,
                    TCSETATTR, LAST_ERROR, MAP_ERRNO, WINDOWS_ERROR_MESSAGE, LOCAL_FREE,
                    CONSOLE_CODE_PAGE, CODE_PAGE_INFO, DBCS_LEAD_BYTE, MULTI_BYTE_TO_WIDE, WIDE_TO_MULTI_BYTE -> true;
                default -> false;
            };
    }
}
