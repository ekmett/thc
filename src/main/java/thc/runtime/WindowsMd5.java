// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.IdentityHashMap;
import thc.Language;

import static java.lang.foreign.ValueLayout.*;

/** Native transport for the unchanged GHC MD5 C algorithm on Windows.
 * ManagedMd5 validates every range and memcpy overlap before entering here.
 * One native image per backing array preserves even the defined input/output
 * aliases. Only the context and output ranges are copied back, including when
 * invocation unwinds; unrelated managed bytes are never overwritten.
 *
 * C retains no pointers and has no callbacks or global state. Invocation memory
 * is confined to one call; the stateless library code has process lifetime.
 * This requires native authority, never additional guest filesystem authority.
 */
final class WindowsMd5 {
    private WindowsMd5() {}

    private static final class Api {
        private static final SymbolLookup lookup;
        private static final Linker linker;
        private static final MethodHandle init, update, finish;
        static {
            try {
                if (!System.getProperty("os.name").startsWith("Windows")) throw new IllegalStateException("Check failed.");
                var library = Files.createTempFile("thc-md5-", ".dll");
                library.toFile().deleteOnExit();
                try (var source = WindowsMd5.class.getResourceAsStream("/thc/cbits/md5.dll")) {
                    if (source == null) ProgramKt.fault("Missing native Windows MD5 bridge");
                    Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING);
                }
                lookup = SymbolLookup.libraryLookup(library, Arena.global());
                linker = Linker.nativeLinker();
                init = function("thc_md5_init", ADDRESS, JAVA_LONG);
                update = function("thc_md5_update", ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT);
                finish = function("thc_md5_final", ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
            } catch (Throwable failure) { throw propagate(failure); }
        }
        private static MethodHandle function(String name, MemoryLayout... parameters) {
            return linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.ofVoid(parameters));
        }
    }

    private static final class Call implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final IdentityHashMap<byte[], MemorySegment> images = new IdentityHashMap<>();
        private Call() {
            if (!Language.currentState(null).getEnv().isNativeAccessAllowed())
                ProgramKt.fault("Native Windows MD5 requires native access");
        }
        private MemorySegment image(ManagedAddress address) {
            var owner = address.cbitsOwner$org_intelligence_thc();
            if (owner != null && owner.isPinned()) return address.cbitsSegment$org_intelligence_thc();
            var bytes = address.cbitsBacking$org_intelligence_thc();
            var image = images.get(bytes);
            if (image == null) {
                image = arena.allocate(Math.max(1, (long) bytes.length), 8);
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, image, 0, bytes.length);
                images.put(bytes, image);
            }
            return image;
        }
        private void copyBack(ManagedAddress address, MemorySegment image, long size) {
            var owner = address.cbitsOwner$org_intelligence_thc();
            if (owner != null && owner.isPinned()) { Reference.reachabilityFence(address); return; }
            long offset = address.cbitsOffset$org_intelligence_thc();
            MemorySegment.copy(image, offset, MemorySegment.ofArray(address.cbitsBacking$org_intelligence_thc()), offset, size);
            Reference.reachabilityFence(address);
        }
        @Override public void close() { arena.close(); }
    }

    public static void init(ManagedAddress context) {
        try (var call = new Call()) {
            var image = call.image(context);
            try { Api.init.invokeExact(image, context.cbitsOffset$org_intelligence_thc()); }
            finally { call.copyBack(context, image, 88); }
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static void update(ManagedAddress context, ManagedAddress input, int length) {
        try (var call = new Call()) {
            var contextImage = call.image(context);
            var inputImage = call.image(input);
            try { Api.update.invokeExact(contextImage, context.cbitsOffset$org_intelligence_thc(), inputImage,
                input.cbitsOffset$org_intelligence_thc(), length); }
            finally {
                call.copyBack(context, contextImage, 88);
                Reference.reachabilityFence(input);
            }
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static void finish(ManagedAddress output, ManagedAddress context) {
        try (var call = new Call()) {
            var outputImage = call.image(output);
            var contextImage = call.image(context);
            try { Api.finish.invokeExact(outputImage, output.cbitsOffset$org_intelligence_thc(), contextImage,
                context.cbitsOffset$org_intelligence_thc()); }
            finally {
                call.copyBack(output, outputImage, 16);
                call.copyBack(context, contextImage, 88);
            }
        } catch (Throwable failure) { throw propagate(failure); }
    }

    // FFM and resource operations keep the original Throwable and suppressed closes.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
