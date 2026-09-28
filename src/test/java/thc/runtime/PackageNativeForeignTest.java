// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

/** Actual C execution controls, separate from the original-package Hashable oracle. */
public class PackageNativeForeignTest {
    @TempDir Path directory;
    private PackageScalarLink library() throws Exception { return library(false, "unsafe"); }
    private PackageScalarLink library(boolean pointerVariants, String safety) throws Exception {
        var source = directory.resolve("native.c"); var bitcode = directory.resolve("native.bc");
        Files.writeString(source, """
            #include <stdint.h>
            #include <stddef.h>
            uint64_t sum_bytes(const unsigned char *p, uint64_t n) {
              uint64_t result = 0;
              for (uint64_t i = 0; i < n; ++i) result += p[i];
              return result;
            }
            uint64_t sum_bytes_address(const unsigned char *p, uint64_t n) {
              return sum_bytes(p, n);
            }
            void update(unsigned char *state, const unsigned char *p, uint64_t n) {
              for (uint64_t i = 0; i < n; ++i) state[0] += p[i];
            }
            uint32_t alias(unsigned char *a, unsigned char *b) {
              a[1] = 197;
              return b[0];
            }
            uint32_t complement32(uint32_t value) { return ~value; }
            uint8_t complement8(uint8_t value) { return (uint8_t) ~value; }
            uint32_t equal(const unsigned char *a, const unsigned char *b) { return a == b; }
            uint32_t next_equal(const unsigned char *a, const unsigned char *b) { return a + 1 == b; }
            int64_t distance(const unsigned char *a, const unsigned char *b) { return b - a; }
            uint32_t read_before(const unsigned char *p) { return p[-1]; }
            uint64_t native_bits(const unsigned char *p) {
              /* XOR forces numerical projection, unlike a lazy ptrtoint. */
              return ((uintptr_t) p) ^ UINT64_C(0x5a5a123456787654);
            }
            uint32_t mixed_alias(unsigned char *a, const unsigned char *b) {
              if (a != b) return 0;
              a[0] = 91;
              return b[0];
            }
            """.stripTrailing());
        var command = new ArrayList<String>(); command.add(System.getenv("THC_CLANG") == null ? "clang" : System.getenv("THC_CLANG"));
        if (System.getProperty("os.name").equals("Linux")) command.add("--target=" + (System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch")) + "-unknown-linux-gnu");
        command.addAll(List.of("-O1", "-emit-llvm", "-c", source.toString(), "-o", bitcode.toString()));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start(); var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); var bytes = Files.readAllBytes(bitcode); var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var abi = List.of(new PackageScalarSignature("sum_bytes", "sum_bytes", List.of("ByteArray#", "Word64Rep"), "Word64Rep", "capi"),
            new PackageScalarSignature("update", "update", List.of("MutableByteArray#", "ByteArray#", "Word64Rep"), "void", "capi"),
            new PackageScalarSignature("alias", "alias", List.of("AddrRep", "AddrRep"), "Word32Rep"),
            new PackageScalarSignature("complement32", "complement32", List.of("Word32Rep"), "Word32Rep"),
            new PackageScalarSignature("complement8", "complement8", List.of("Word8Rep"), "Word8Rep"),
            new PackageScalarSignature("equal", "equal", List.of("AddrRep", "AddrRep"), "Word32Rep"),
            new PackageScalarSignature("next_equal", "next_equal", List.of("AddrRep", "AddrRep"), "Word32Rep"),
            new PackageScalarSignature("distance", "distance", List.of("AddrRep", "AddrRep"), "Int64Rep"),
            new PackageScalarSignature("read_before", "read_before", List.of("AddrRep"), "Word32Rep"),
            new PackageScalarSignature("native_bits", "native_bits", List.of("AddrRep"), "Word64Rep"),
            new PackageScalarSignature("mixed_alias", "mixed_alias", List.of("MutableByteArray#", "ByteArray#"), "Word32Rep"));
        var selected = pointerVariants ? List.of(abi.getFirst(), new PackageScalarSignature(abi.getFirst().symbol(), "sum_bytes_address", List.of("AddrRep", "Word64Rep"), abi.getFirst().result(), abi.getFirst().convention(), abi.getFirst().safety())) : abi;
        var signatures = new ArrayList<PackageScalarSignature>(); for (var signature : selected) signatures.add(new PackageScalarSignature(signature.symbol(), signature.entry(), signature.arguments(), signature.result(), signature.convention(), safety));
        return new PackageScalarLink("native-ffi-control", "test-host", sha, sha, bytes, signatures);
    }
    private static final class Entry extends RootNode {
        private final PackageScalarCall operation; private final boolean forceIntegerResult;
        @Child private PackageScalarAccess access;
        Entry(Language language, PackageScalarCall operation) { this(language, operation, false); }
        Entry(Language language, PackageScalarCall operation, boolean forceIntegerResult) { super(language); this.operation = operation; this.forceIntegerResult = forceIntegerResult; access = new PackageScalarAccess(operation); }
        @Override public Object execute(VirtualFrame frame) {
            var arguments = frame.getArguments();
            if (operation.getResult().equals("void") && !forceIntegerResult) { access.executeVoid(arguments, kotlin.Unit.INSTANCE); return kotlin.Unit.INSTANCE; }
            if (NarrowInteger.fromRep(operation.getResult()) != null) return access.executeInt(arguments, kotlin.Unit.INSTANCE);
            return access.executeLong(arguments, kotlin.Unit.INSTANCE);
        }
    }
    private PackageScalarSignature signature(PackageScalarLink link, String symbol) {
        PackageScalarSignature result = null;
        for (var value : link.getAbi()) if (value.symbol().equals(symbol)) { if (result != null) throw new IllegalArgumentException("Multiple signatures"); result = value; }
        if (result == null) throw new java.util.NoSuchElementException(symbol); return result;
    }
    @Test public void integerCallSitesRetainFirstInstalledCodeAndRejectWrongResultsBeforeEffects() throws Exception {
        var link = library();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); Language.currentState().getPackageCbits().link(link);
                var target = new Entry(language, new PackageScalarCall(link, signature(link, "sum_bytes"))).getCallTarget();
                assertEquals(385L, target.call(new byte[]{1, 2, 127, -1}, 4L)); target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(260L, target.call(new byte[]{5, -1}, 2L), "first installed call observes new bytes"); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var invalid = new Entry(language, new PackageScalarCall(link, signature(link, "update")), true).getCallTarget(); var state = ManagedAllocation.mutable(8, 8, true);
                assertThrows(RuntimeFault.class, () -> invalid.call(state, new byte[]{1}, 1L)); assertEquals(0L, state.readByte(0), "wrong result path must reject before C mutates its argument");
            } finally { context.leave(); }
        }
    }
    private Object argument(PackageScalarSignature signature, byte[] bytes) { return signature.arguments().getFirst().equals("AddrRep") ? ManagedAddress.fromByteArray(bytes).plus(1) : bytes; }
    @Test public void sameCNameRetainsEachNativeAdapterAndItsFirstCompiledCall() throws Exception {
        var link = library(true, "unsafe");
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var registry = Language.currentState().getPackageCbits(); registry.link(link);
                for (var signature : link.getAbi()) {
                    assertSame(signature, registry.resolve(link, signature).getSignature(), "same C symbol must not overwrite a sibling adapter");
                    var target = new Entry(language, new PackageScalarCall(link, signature)).getCallTarget(); var bytes = new byte[]{1, 2, 127, -1};
                    long expected = signature.arguments().getFirst().equals("AddrRep") ? 384L : 130L; assertEquals(expected, target.call(argument(signature, bytes), 3L));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var next = new byte[]{3, 5, 7, 11}; assertEquals(signature.arguments().getFirst().equals("AddrRep") ? 23L : 15L, target.call(argument(signature, next), 3L), "first installed call preserves the selected carrier");
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"unsafe", "safe"})
    public void realCReadsAndMutatesAliasedPersistentByteStorage(String safety) throws Exception {
        var link = library(false, safety);
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); Language.currentState().getPackageCbits().link(link);
                var functions = new LinkedHashMap<String, RootCallTarget>(); for (var signature : link.getAbi()) functions.put(signature.symbol(), new Entry(language, new PackageScalarCall(link, signature)).getCallTarget());
                var source = new byte[]{1, 2, 127, -1}; assertEquals(385L, functions.get("sum_bytes").call(source, 4L));
                var state = ManagedAllocation.mutable(8, 8); functions.get("update").call(state, source, 4L); assertEquals(129L, state.readByte(0));
                functions.get("update").call(state, new byte[]{3, 5}, 2L); assertEquals(137L, state.readByte(0), "foreign mutations survive separate calls");
                var address = ManagedAddress.fromAllocation(state); assertEquals(197, functions.get("alias").call(address, address.plus(1)));
                assertEquals(197L, state.readByte(1), "overlapping arguments share the original allocation");
                assertEquals(1, functions.get("equal").call(address, address)); assertEquals(1, functions.get("equal").call(address, ManagedAddress.fromAllocation(state)));
                assertEquals(1, functions.get("next_equal").call(address, address.plus(1))); assertEquals(0, functions.get("equal").call(address, address.plus(1)));
                assertEquals(7L, functions.get("distance").call(address, address.plus(7))); assertEquals(-7L, functions.get("distance").call(address.plus(7), address));
                assertEquals(8L, functions.get("distance").call(address, address.plus(8)), "one-past pointer"); assertEquals(137, functions.get("read_before").call(address.plus(1)));
                assertEquals(91, functions.get("mixed_alias").call(state, state), "const and writable views share identity"); assertEquals(91L, state.readByte(0));
                var rawAlias = ManagedAddress.fromByteArray(state.rawBytesIfPointerFree()); assertEquals(1, functions.get("equal").call(address, rawAlias), "raw and owned views share identity");
                assertEquals(1, functions.get("equal").call(rawAlias, address), "raw-first views use the same owned transport");
                var pinned = PinnedMemory.allocate(8, 64); var pinnedAddress = ManagedAddress.fromAllocation(pinned); long pinnedBits = pinned.nativeSegment().address();
                functions.get("update").call(pinned, source, 4L); assertEquals(129L, pinned.readByte(0)); assertEquals(197, functions.get("alias").call(pinnedAddress, pinnedAddress.plus(1)));
                assertEquals(197L, pinned.readByte(1)); assertEquals(1, functions.get("next_equal").call(pinnedAddress, pinnedAddress.plus(1)));
                assertEquals(pinnedBits + 1, (Long) functions.get("native_bits").call(pinnedAddress.plus(1)) ^ 0x5a5a123456787654L);
                assertEquals(pinnedBits, pinned.nativeSegment().address(), "C uses the original pinned allocation");
                var literal = ManagedAddress.fromHex("6162"); assertEquals(1, functions.get("next_equal").call(literal, literal.plus(1))); assertEquals(97, functions.get("read_before").call(literal.plus(1)));
                // First/only argument is interior; there is no existing
                // native image or base argument to hide an offset error.
                var nativeLiteral = ManagedAddress.fromHex("1020304050"); var nativeImages = Language.currentState().getNativeAddresses(); assertNull(nativeImages.transport(nativeLiteral));
                long projected = (Long) functions.get("native_bits").call(nativeLiteral.plus(3)); var image = nativeImages.transport(nativeLiteral);
                assertNotNull(image, "C numerical use actually materialized an immutable native image"); assertEquals(image.asPointer() + 3, projected ^ 0x5a5a123456787654L);
                assertEquals(image.asPointer() + 1, (Long) functions.get("native_bits").call(nativeLiteral.plus(1)) ^ 0x5a5a123456787654L);
                assertEquals(48, functions.get("read_before").call(nativeLiteral.plus(3))); var separate = ManagedAddress.fromByteArray(new byte[]{91});
                assertEquals(0, functions.get("equal").call(address, separate)); assertEquals(-1, functions.get("complement32").call(0), "Word32 result retains all raw bits");
                assertEquals(0, functions.get("complement32").call(-1)); assertEquals(255, functions.get("complement8").call((byte) 0));
                var frozen = ManagedAllocation.immutable(new byte[]{0}, 8); assertThrows(Exception.class, () -> functions.get("update").call(frozen, source, 4L)); assertArrayEquals(new byte[]{1, 2, 127, -1}, source);
            } finally { context.leave(); }
        }
    }
    private record Conversion(String rep, long value) {}
    @Test public void integerConversionsRetainUnsignedBitsAndRejectOutOfRangeInputs() {
        assertEquals((byte) -1, PackageScalarAccessKt.packageCInteger("Word8Rep", 255)); assertEquals((short) -1, PackageScalarAccessKt.packageCInteger("Word16Rep", 65535));
        assertEquals(-1, PackageScalarAccessKt.packageCInteger("Word32Rep", -1));
        for (var row : List.of(new Conversion("Word8Rep", -1L), new Conversion("Word8Rep", 256L), new Conversion("Word16Rep", 65536L),
                new Conversion("Word32Rep", 0x1_0000_0000L), new Conversion("Int8Rep", 128L), new Conversion("Int16Rep", 32768L)))
            assertThrows(RuntimeFault.class, () -> NarrowInteger.fromRep(row.rep()).fromHost(row.value()));
    }
}
