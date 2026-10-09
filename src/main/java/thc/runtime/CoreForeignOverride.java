// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import thc.Json;

/** Identities whose native implementation cannot operate on THC-owned state.
 * Identity selection is nonthrowing; the selected existing validator checks the ABI. */
public enum CoreForeignOverride {
    STACK, STACK_INFO, STDIO, PROCESS, STABLE_FREE, SHARED_CAF, SHUTDOWN, MAIN_THREAD,
    BOUND_THREAD, THREAD_ID, GC, RTS_EVENT, WINDOWS_IO, ALLOCATION_COUNTER, STRING_RTS, ENVIRONMENT,
    RTS_DIAGNOSTIC, RTS_ARGUMENTS, MANAGED_FILE, SIGNAL, ALLOCATION, MEMMOVE, MEMCPY;

    /** A checked Core capability, never permission to ignore a raw native reference. */
    public static boolean nativeCall(Object descriptor) {
        if (!(descriptor instanceof Map<?, ?> call) || !(call.get("target") instanceof Map<?, ?> target)) return false;
        for (var expected : NativeProfile.calls) {
            var owner = (Map<?, ?>) expected.get("target");
            if (!Objects.equals(owner.get("unit"), target.get("unit")) ||
                    !Objects.equals(owner.get("symbol"), target.get("symbol"))) continue;
            var canonical = new LinkedHashMap<Object, Object>(call);
            for (String key : List.of("schema", "arity", "suppliedArity"))
                if (canonical.get(key) instanceof Integer value) canonical.put(key, value.longValue());
            if (!expected.equals(canonical))
                throw RuntimeFault.fault("Core native override has the wrong exact foreign-call ABI: " + owner);
            validateNativeCapability(call);
            return true;
        }
        return false;
    }

    /** Typed source proof and its complete call inventory remain checked by the caller. */
    public static boolean nativeImport(Map<?, ?> entry, List<?> calls) {
        if (!(entry.get("emitted") instanceof Map<?, ?> emitted)) return false;
        for (var expected : NativeProfile.calls) {
            var target = (Map<?, ?>) expected.get("target");
            if (!Objects.equals(target.get("unit"), emitted.get("unit")) ||
                    !Objects.equals(target.get("symbol"), emitted.get("symbol"))) continue;
            var arguments = ((List<?>) expected.get("argumentReps")).stream().map(CoreForeignOverride::carrier).toList();
            var result = ((List<?>) ((Map<?, ?>) expected.get("resultRep")).get("components")).stream()
                .map(CoreForeignOverride::carrier).toList();
            // A retained ccall header is source provenance, not a different emitted ABI.
            Object header = entry.get("header");
            boolean validHeader = header == null || header instanceof String text && !text.isEmpty() &&
                text.chars().noneMatch(value -> "\u0000\n\r\"\\".indexOf(value) >= 0);
            if (!Boolean.TRUE.equals(entry.get("isFunction")) || !validHeader ||
                    !Objects.equals(entry.get("symbol"), target.get("symbol")) ||
                    !Objects.equals(entry.get("convention"), expected.get("convention")) ||
                    !Objects.equals(entry.get("safety"), expected.get("safety")) ||
                    !emitted.equals(Map.of("unit", target.get("unit"), "symbol", target.get("symbol"),
                        "convention", expected.get("convention"), "safety", expected.get("safety"),
                        "arguments", arguments, "result", result)))
                throw RuntimeFault.fault("Core native override import has the wrong exact emitted ABI: " + target);
            for (var descriptor : calls) {
                if (descriptor instanceof Map<?, ?> call && call.get("target") instanceof Map<?, ?> owner &&
                        Objects.equals(target.get("unit"), owner.get("unit")) &&
                        Objects.equals(target.get("symbol"), owner.get("symbol"))) nativeCall(call);
            }
            return true;
        }
        return false;
    }

    private static String carrier(Object representation) {
        var reps = (List<?>) ((Map<?, ?>) representation).get("primReps");
        return reps.isEmpty() ? "void" : (String) reps.getFirst();
    }

    private static final class NativeProfile {
        static final List<Map<?, ?>> calls = read();
        private static List<Map<?, ?>> read() {
            Object raw;
            try (var input = CoreForeignOverride.class.getResourceAsStream("/thc/core-native-overrides.json")) {
                if (input == null) throw new IllegalStateException("Missing Core native override capability profile");
                raw = Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException failure) { throw new IllegalStateException("Cannot read Core native override capability profile", failure); }
            if (!(raw instanceof Map<?, ?> profile) || !profile.keySet().equals(Set.of("schema", "profile", "ghc", "calls")) ||
                    !Objects.equals(profile.get("schema"), 1L) || !Objects.equals(profile.get("ghc"), "9.14.1") ||
                    !Objects.equals(profile.get("profile"), "ghc-9.14.1-thc-core-native-overrides-v1") ||
                    !(profile.get("calls") instanceof List<?> entries) || entries.isEmpty())
                throw new IllegalStateException("Invalid Core native override capability profile");
            var identities = new HashSet<Object>();
            return entries.stream().<Map<?, ?>>map(entry -> {
                if (!(entry instanceof Map<?, ?> call) || !identities.add(call.get("target")))
                    throw new IllegalStateException("Ambiguous Core native override capability profile");
                validateNativeCapability(call);
                return call;
            }).toList();
        }
    }

    private static void validateNativeCapability(Map<?, ?> call) {
        var arguments = (List<?>) call.get("argumentReps");
        var result = call.get("resultRep");
        var metadata = Map.of("foreignCall", call, "rep", result);
        var flags = Collections.nCopies(arguments.size(), false);
        var target = (Map<?, ?>) call.get("target");
        var callback = CoreDynamicForeign.validate(metadata, call, target, arguments, flags, result);
        if (callback != null) {
            if (callback.getKind() != PackageScalarCall.Kind.FREE_CALLBACK)
                throw new IllegalStateException("Core native profile requires an existing static owned operation");
            return;
        }
        var selected = select(metadata);
        Object operation = selected == null ? null : switch (selected) {
            case STACK_INFO -> CoreStackInfoForeign.validate(metadata, arguments, flags, result);
            case STDIO -> CoreOriginalStdio.validate(metadata, arguments, flags, result);
            case SHARED_CAF -> CoreSharedCAFStores.validate(metadata, arguments, flags, result);
            case SHUTDOWN -> CoreRtsShutdown.validate(metadata, arguments, flags, result);
            case GC -> CoreGcForeign.validate(metadata, arguments, flags, result);
            case RTS_EVENT -> CoreRtsEventForeign.validate(metadata, arguments, flags, result);
            case WINDOWS_IO -> CoreWindowsIoForeign.validate(metadata, arguments, flags, result);
            case STRING_RTS -> CoreStringRtsForeign.validate(metadata, arguments, flags, result);
            case RTS_DIAGNOSTIC -> CoreRtsDiagnosticForeign.validate(metadata, arguments, flags, result);
            case RTS_ARGUMENTS -> CoreRtsArgumentsForeign.validate(metadata, arguments, flags, result);
            default -> null;
        };
        if (operation == null) throw new IllegalStateException("Core native profile has no actual owning validator: " + target);
    }

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
                        case WINDOWS_IO -> CoreWindowsIoForeign.validateHead(head, false);
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

    /** Canonical ownership for archive routing and cold audits, never ABI admission. */
    public static String owner(Object metadata) {
        var operation = select(metadata);
        if (operation != null) return operation.name();
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> call) ||
                !(call.get("target") instanceof Map<?, ?> target)) return null;
        var dynamic = CoreDynamicForeign.select(target);
        return dynamic == null ? null : dynamic.name();
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
            if (WindowsIoOp.named(symbol) != null) return WINDOWS_IO;
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
                    CONSOLE_CODE_PAGE, CODE_PAGE_INFO, DBCS_LEAD_BYTE, MULTI_BYTE_TO_WIDE, WIDE_TO_MULTI_BYTE, WIDE_TO_MULTI_BYTE_SAFE -> true;
                default -> false;
            };
    }
}
