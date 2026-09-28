// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import thc.Json;
import thc.Language;

import static java.lang.foreign.ValueLayout.*;

/** Original ghc-internal Windows encoding/error imports. No filesystem authority
 * is granted here. Windows owns code-page tables, conversion flags and localized
 * messages; the context owns captured last-error and every returned allocation. */
public final class WindowsCodePages {
    private final Language.State context;
    public final CarrierLocal<Long> lastError = new CarrierLocal<>(0L);
    public WindowsCodePages(Language.State context) { this.context = context; }

    private void current() {
        if (Language.currentState(null) != context) throw RuntimeFault.fault("Windows encoding service belongs to another context");
        if (!context.getEnv().isNativeAccessAllowed()) throw new SecurityException("Windows encoding requires native access");
        Abi.requireLayout();
    }
    @FunctionalInterface private interface ForeignAction<T> { T run() throws Throwable; }
    private <T> T foreign(ForeignAction<T> action) {
        var previous = context.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try { return action.run(); }
        catch (Throwable failure) { throw propagate(failure); }
        finally { context.getThreads().leaveForeign(previous); }
    }
    // Preserve the original checked FFM/resource exception across Java callbacks.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    @TruffleBoundary public long error() { current(); return lastError.get(); }
    @TruffleBoundary public long codePage(boolean console) {
        current();
        if (!console) return foreign(() -> Integer.toUnsignedLong((int) Api.ansi.invokeExact()));
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(Api.capture);
            int result = foreign(() -> (int) Api.console.invokeExact(error));
            lastError.set(Api.error(error));
            return Integer.toUnsignedLong(result);
        }
    }
    @TruffleBoundary public long leadByte(long codePage, long value) {
        current();
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(Api.capture);
            int result = foreign(() -> (int) Api.lead.invokeExact(error, (int) codePage, (byte) value));
            lastError.set(Api.error(error));
            return result;
        }
    }

    private record Region(ManagedAddress address, long bytes, boolean writable) {}
    private static final class Image {
        final ManagedAddress base;
        long first, end;
        MemorySegment segment;
        Image(ManagedAddress base, long first, long end) { this.base = base; this.first = first; this.end = end; }
    }
    private static final Object LOCK_ORDER = new Object();
    private static <T> T locked(List<ManagedAllocation> owners, int index, Supplier<T> action) {
        if (index == owners.size()) return action.get();
        synchronized (owners.get(index)) { return locked(owners, index + 1, action); }
    }

    /** A synchronous snapshot per allocation preserves all pointer aliases,
     * including identical input/output pointers rejected by Win32. Validate and
     * lock every region before the OS call; publish even partial failure writes.
     * Native owners retain their existing ordered lifetime borrows. */
    private <T> T buffers(List<ManagedAddress> addresses, Supplier<List<Region>> describe,
            BiFunction<Arena, List<MemorySegment>, T> action) {
        return ManagedAddress.withNativeBorrows(addresses, () -> {
            var owners = new ArrayList<ManagedAllocation>();
            for (var address : addresses) {
                var owner = address.cbitsOwner();
                if (owner != null && !owners.contains(owner)) owners.add(owner);
            }
            owners.sort((a, b) -> Integer.compareUnsigned(System.identityHashCode(a), System.identityHashCode(b)));
            Supplier<T> execute = () -> {
                var regions = describe.get();
                var images = new ArrayList<Image>();
                var selected = new ArrayList<Image>();
                for (var region : regions) {
                    if (region.address == ManagedAddress.nullAddress()) { selected.add(null); continue; }
                    region.address.requireByteRegion(region.bytes, region.writable);
                    long offset = region.address.cbitsOffset();
                    var base = region.address.plus(-offset);
                    Image image = null;
                    for (var candidate : images) if (candidate.base.sameLocation(base)) { image = candidate; break; }
                    if (image == null) { image = new Image(base, offset, offset + region.bytes); images.add(image); }
                    image.first = Math.min(image.first, offset);
                    image.end = Math.max(image.end, offset + region.bytes);
                    selected.add(image);
                }
                try (var arena = Arena.ofConfined()) {
                    for (var image : images) image.segment = arena.allocate(Math.max(1, image.end - image.first), 8);
                    var pointers = new ArrayList<MemorySegment>();
                    for (int index = 0; index < regions.size(); index++) {
                        var region = regions.get(index);
                        var image = selected.get(index);
                        if (image == null) { pointers.add(MemorySegment.NULL); continue; }
                        var pointer = image.segment.asSlice(region.address.cbitsOffset() - image.first);
                        bytes(region.address, region.bytes, storage -> pointer.asSlice(0, region.bytes).copyFrom(storage));
                        pointers.add(pointer);
                    }
                    T result = action.apply(arena, pointers);
                    for (int index = 0; index < regions.size(); index++) {
                        var region = regions.get(index);
                        if (region.writable && selected.get(index) != null) {
                            var pointer = pointers.get(index);
                            bytes(region.address, region.bytes, storage -> storage.copyFrom(pointer.asSlice(0, region.bytes)));
                        }
                    }
                    return result;
                }
            };
            boolean collision = false;
            for (int index = 1; index < owners.size(); index++)
                if (System.identityHashCode(owners.get(index - 1)) == System.identityHashCode(owners.get(index))) { collision = true; break; }
            if (collision) synchronized (LOCK_ORDER) { return locked(owners, 0, execute); }
            return locked(owners, 0, execute);
        });
    }
    private static void bytes(ManagedAddress address, long count, Consumer<MemorySegment> action) {
        if (address.hasNativeStorage()) {
            address.withNativeSegment(segment -> { action.accept(segment.asSlice(0, count)); return null; });
        } else action.accept(address.cbitsSegment().asSlice(address.cbitsOffset(), count));
    }
    private long inputBytes(ManagedAddress address, int count, int width) {
        if (address == ManagedAddress.nullAddress()) throw RuntimeFault.fault("Windows conversion requires an input buffer");
        if (count < -1) throw RuntimeFault.fault("Windows conversion input length must be nonnegative or -1");
        if (count != -1) return Math.max(0L, count) * width;
        // A -1 count includes the terminator. Never scan beyond the guest allocation.
        long available = address.availableBytes();
        long position = 0;
        while (position <= available - width) {
            boolean zero = true;
            for (int index = 0; index < width; index++) if (address.readWord8(position + index) != 0) zero = false;
            position += width;
            if (zero) return position;
        }
        throw RuntimeFault.fault("Unterminated Windows conversion input");
    }
    @TruffleBoundary public long info(long codePage, ManagedAddress output) {
        current();
        if (output == ManagedAddress.nullAddress()) throw RuntimeFault.fault("GetCPInfo requires a writable CPINFO buffer");
        return buffers(List.of(output), () -> List.of(new Region(output, Abi.getFieldBytes(), true)), (arena, pointers) -> {
            // GHC allocates18 field bytes; native CPINFO is20 with tail padding.
            var data = arena.allocate(Abi.getInfoBytes(), Abi.getInfoAlignment());
            data.asSlice(0, Abi.getFieldBytes()).copyFrom(pointers.get(0));
            var error = arena.allocate(Api.capture);
            int result = foreign(() -> (int) Api.info.invokeExact(error, (int) codePage, data));
            lastError.set(Api.error(error));
            if (result != 0) pointers.get(0).asSlice(0, Abi.getFieldBytes()).copyFrom(data.asSlice(0, Abi.getFieldBytes()));
            return (long) result;
        });
    }
    @TruffleBoundary public long multiByte(long codePage, long flags, ManagedAddress input, long count,
            ManagedAddress output, long capacity) {
        current();
        return buffers(List.of(input, output), () -> {
            if ((int) capacity < 0) throw RuntimeFault.fault("Windows conversion output capacity must be nonnegative");
            long sourceBytes = inputBytes(input, (int) count, 1);
            return List.of(new Region(input, sourceBytes, false), new Region(output, Math.max(0L, (int) capacity) * 2, true));
        }, (arena, pointers) -> {
            var error = arena.allocate(Api.capture);
            int result = foreign(() -> (int) Api.multi.invokeExact(error, (int) codePage, (int) flags, pointers.get(0),
                (int) count, pointers.get(1), (int) capacity));
            lastError.set(Api.error(error));
            return (long) result;
        });
    }
    @TruffleBoundary public long wideChar(long codePage, long flags, ManagedAddress input, long count,
            ManagedAddress output, long capacity, ManagedAddress defaultChar, ManagedAddress usedDefault) {
        current();
        return buffers(List.of(input, output, defaultChar, usedDefault), () -> {
            if ((int) capacity < 0) throw RuntimeFault.fault("Windows conversion output capacity must be nonnegative");
            long sourceBytes = inputBytes(input, (int) count, 2);
            long defaultBytes = 0;
            if (defaultChar != ManagedAddress.nullAddress()) {
                defaultChar.requireByteRegion(1, false);
                defaultBytes = leadByte(codePage, defaultChar.readWord8(0)) != 0 ? 2 : 1;
            }
            return List.of(new Region(input, sourceBytes, false), new Region(output, Math.max(0L, (int) capacity), true),
                new Region(defaultChar, defaultBytes, false), new Region(usedDefault, 4, true));
        }, (arena, pointers) -> {
            var error = arena.allocate(Api.capture);
            int result = foreign(() -> (int) Api.wide.invokeExact(error, (int) codePage, (int) flags, pointers.get(0),
                (int) count, pointers.get(1), (int) capacity, pointers.get(2), pointers.get(3)));
            lastError.set(Api.error(error));
            return (long) result;
        });
    }
    /** Exact ordered mapping in GHC9.14.1 cbits/Win32Utils.c. The first
     * ERROR_INVALID_HANDLE row (EBADF) wins over its duplicate. */
    @TruffleBoundary public long mapErrno(long error) {
        current();
        long value = error & 0xffff_ffffL;
        String name = switch ((int) value) {
            case 2, 3, 15, 18, 53, 67, 161, 206 -> "ENOENT";
            case 4 -> "EMFILE";
            case 5, 16, 33, 65, 82, 83, 108, 132, 158, 167 -> "EACCES";
            case 6, 114, 130 -> "EBADF";
            case 7, 8, 9, 1816 -> "ENOMEM";
            case 10 -> "E2BIG";
            case 11 -> "ENOEXEC";
            case 17 -> "EXDEV";
            case 80, 183 -> "EEXIST";
            case 89, 164, 215 -> "EAGAIN";
            case 109, 232 -> "EPIPE";
            case 112 -> "ENOSPC";
            case 128, 129 -> "ECHILD";
            case 145 -> "ENOTEMPTY";
            default -> value >= 19 && value <= 36 ? "EACCES" : value >= 188 && value <= 202 ? "ENOEXEC" : "EINVAL";
        };
        return Abi.getErrno().get(name);
    }
    @TruffleBoundary public void setErrno() {
        current();
        context.getStdio().captureForeignErrno(mapErrno(lastError.get()));
    }
    @TruffleBoundary public ManagedAddress message(long errorCode) {
        current();
        try (var arena = Arena.ofConfined()) {
            var resultPointer = arena.allocate(ADDRESS);
            var error = arena.allocate(Api.capture);
            int count = foreign(() -> (int) Api.message.invokeExact(error, (int) Abi.getMessageFlags(), MemorySegment.NULL,
                (int) errorCode, (int) Abi.getLanguage(), resultPointer, 0, MemorySegment.NULL));
            lastError.set(Api.error(error));
            if (count == 0) return ManagedAddress.nullAddress();
            var pointer = resultPointer.get(ADDRESS, 0);
            // Adoption consumes the allocation, including cleanup on publication failure.
            return context.getNativeAllocations().adoptWindowsLocal(pointer,
                (Integer.toUnsignedLong(count) + 1) * 2);
        }
    }
    @TruffleBoundary public ManagedAddress localFree(ManagedAddress address) {
        current();
        context.getNativeAllocations().free(address, ManagedNativeAllocations.Allocator.WINDOWS_LOCAL);
        return ManagedAddress.nullAddress();
    }

    public static final AbiValues Abi = new AbiValues();
    public static final class AbiValues {
        private volatile Map<?, ?> fields;
        private Map<?, ?> fields() {
            var result = fields;
            if (result != null) return result;
            synchronized (this) {
                if (fields == null) {
                    // A failed receipt load must remain retryable, as Kotlin lazy was.
                    try { fields = load(); } catch (IOException failure) { throw propagate(failure); }
                }
                return fields;
            }
        }
        private Map<?, ?> load() throws IOException {
            if (!WindowsDirectoryStreams.supportedHost()) throw new IllegalStateException("Windows encoding ABI requires Windows x86_64");
            Map<?, ?> document;
            try (var stream = WindowsCodePages.class.getResourceAsStream("/thc/native/windows-directory-abi.json")) {
                if (stream == null) throw RuntimeFault.fault("Missing native Windows ABI");
                document = (Map<?, ?>) Json.INSTANCE.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (!(document.get("layout") instanceof Map<?, ?>)) throw RuntimeFault.fault("Malformed native Windows ABI");
            var layout = (Map<?, ?>) document.get("layout");
            if (!(Long.valueOf(1).equals(document.get("schema")) && "x86_64".equals(document.get("architecture")) &&
                    Long.valueOf(8).equals(layout.get("pointerBytes")) && Long.valueOf(2).equals(layout.get("wcharBytes")) &&
                    Long.valueOf(4).equals(layout.get("boolBytes")) && Long.valueOf(4).equals(layout.get("dwordBytes")) &&
                    Long.valueOf(18).equals(layout.get("cpInfoFieldBytes")) && Long.valueOf(0).equals(layout.get("cpInfoMaxOffset")) &&
                    Long.valueOf(4).equals(layout.get("cpInfoDefaultOffset")) && Long.valueOf(6).equals(layout.get("cpInfoLeadOffset"))))
                throw new IllegalStateException("Check failed.");
            return layout;
        }
        public void requireLayout() { fields(); }
        public long getInfoBytes() { return (Long) fields().get("cpInfoBytes"); }
        public long getInfoAlignment() { return (Long) fields().get("cpInfoAlignment"); }
        public long getFieldBytes() { return (Long) fields().get("cpInfoFieldBytes"); }
        public long getMessageFlags() { return (Long) fields().get("formatMessageFlags"); }
        public long getLanguage() { return (Long) fields().get("defaultLanguage"); }
        public Map<String, Long> getErrno() {
            var result = new LinkedHashMap<String, Long>();
            for (var entry : ((Map<?, ?>) fields().get("errno")).entrySet())
                result.put(Objects.requireNonNull((String) entry.getKey()), Objects.requireNonNull((Long) entry.getValue()));
            return result;
        }
    }
    private static final class Api {
        private static final Linker linker = Linker.nativeLinker();
        private static final SymbolLookup lookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        static final MemoryLayout capture = Linker.Option.captureStateLayout();
        private static final long offset = capture.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
        private static MethodHandle call(String name, MemoryLayout result, MemoryLayout... arguments) {
            return linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, arguments),
                Linker.Option.captureCallState("GetLastError"));
        }
        static final MethodHandle ansi = linker.downcallHandle(lookup.find("GetACP").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle console = call("GetConsoleCP", JAVA_INT);
        static final MethodHandle lead = call("IsDBCSLeadByteEx", JAVA_INT, JAVA_INT, JAVA_BYTE);
        static final MethodHandle info = call("GetCPInfo", JAVA_INT, JAVA_INT, ADDRESS);
        static final MethodHandle multi = call("MultiByteToWideChar", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);
        static final MethodHandle wide = call("WideCharToMultiByte", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
        static final MethodHandle message = call("FormatMessageW", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
        static final MethodHandle localFree = call("LocalFree", ADDRESS, ADDRESS);
        static long error(MemorySegment storage) { return Integer.toUnsignedLong(storage.get(JAVA_INT, offset)); }
    }
    public static WindowsCodePages current(Node node) { return Language.currentState(node).getWindowsCodePages(); }
    public static void releaseLocal(MemorySegment pointer) {
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(Api.capture);
            var result = (MemorySegment) Api.localFree.invokeExact(error, pointer);
            if (result.address() != 0) throw RuntimeFault.fault("LocalFree failed: " + Api.error(error));
        } catch (Throwable failure) { throw propagate(failure); }
    }
}
