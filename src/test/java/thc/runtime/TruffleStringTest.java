// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleString.Encoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.TruffleStringOp.*;

class TruffleStringTest {
    private static final Path DATA = Path.of(System.getProperty("thc.projectRoot"), "build/truffle-strings");
    private static final String PREFIX = "main:StringPrimitives.";
    private static final byte[] UTF8 = "Aé😀".getBytes(StandardCharsets.UTF_8);
    private static Object op(TruffleStringOp operation, Object... args) {
        return new TruffleStringOp.Site(operation).execute(args);
    }
    private static TruffleString utf8(byte[] bytes) {
        return (TruffleString) op(FROM_BYTES, Encoding.UTF_8, bytes, 0L, (long) bytes.length);
    }
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> module() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(DATA.resolve("manifest.json")));
        for (String key : List.of("inputHashes", "artifactHashes")) {
            var hashes = (Map<String, String>) manifest.get(key);
            assertTrue(hashes.size() >= (key.equals("inputHashes") ? 10 : 4));
            for (var entry : hashes.entrySet()) {
                byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("thc.projectRoot"), entry.getKey()));
                assertEquals(entry.getValue(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "Stale string fixture: " + entry.getKey());
            }
        }
        var modules = new ArrayList<Map<String, Object>>();
        for (String file : List.of("THC.Prim.cbd", "StringPrimitives.cbd"))
            modules.add(thc.CoreCbdFixtures.read(DATA.resolve("core").resolve(file)));
        return CoreModules.merge(modules);
    }
    private static ExecutableProgram program(String backend, String entry) throws Exception {
        var source = CoreModules.reachable(module(), PREFIX + entry, true);
        source.put("instrument", true);
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
    }
    private static Object call(ExecutableProgram program, String entry, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length),
            new Object[]{program.entryValue(PREFIX + entry), arguments});
    }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void genuineHaskellUnicodeAndEncodingRoundTrips(String backend) throws Exception {
        var nativePoints = (List<?>) Json.parse(Files.readString(DATA.resolve("oracle.json")));
        assertEquals(List.of(65L, 233L, 128512L), nativePoints);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var stats = program(backend, "unicodeStats");
                var roundTrip = program(backend, "roundTrip");
                var slice = program(backend, "sliceRepeat");
                var raw = program(backend, "rawString");
                var original = utf8(UTF8);
                assertSame(original, call(raw, "rawString", original));
                for (long code : new long[]{0, 1, 2, 6, 7, 8, 9}) {
                    Encoding encoding = (Encoding) op(ENCODING, code);
                    Object result = call(stats, "unicodeStats", code);
                    var tuple = ((GuestRoot) stats.entryTarget(PREFIX + "unicodeStats").getRootNode()).getTupleResult();
                    var fields = TupleResults.ownedTupleResult(result, tuple);
                    long byteLength = code == 0 ? 7 : code == 2 || code >= 8 ? 12 : 8;
                    long supplementaryOffset = code == 0 ? 3 : code == 2 || code >= 8 ? 8 : 4;
                    long[] want = {byteLength, 3, 128512, supplementaryOffset, 1};
                    for (int i = 0; i < want.length; i++) assertEquals(want[i], tuple.getLayout().getLong(fields, i));
                    assertArrayEquals(UTF8, (byte[]) call(roundTrip, "roundTrip", 0L, code));
                    var expectedSlice = TruffleString.fromJavaStringUncached("é😀é😀", encoding);
                    assertArrayEquals(expectedSlice.copyToByteArrayUncached(encoding), (byte[]) call(slice, "sliceRepeat", code));
                }
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void genuineLoopAndEncodingCallsExecuteCompiledCode(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var loop = program(backend, "codePointSum");
                var roundTrip = program(backend, "roundTrip");
                TruffleString s = utf8(UTF8);
                // Ordinary profile establishment is explicit; not a zero-training claim.
                for (int i = 0; i < 4; i++) {
                    assertEquals(128810L, call(loop, "codePointSum", 0L, s));
                    assertArrayEquals(UTF8, (byte[]) call(roundTrip, "roundTrip", 0L, 7L));
                }
                var originalLoop = loop.entryTarget(PREFIX + "codePointSum");
                compile(originalLoop);
                compile(roundTrip.entryTarget(PREFIX + "roundTrip"));
                long before = ((Number) loop.diagnostics().get("compiledEntries")).longValue();
                assertEquals(128810L, call(loop, "codePointSum", 0L, s));
                assertArrayEquals(UTF8, (byte[]) call(roundTrip, "roundTrip", 0L, 7L));
                assertTrue(((Number) loop.diagnostics().get("compiledEntries")).longValue() > before);
                assertTrue(((Number) roundTrip.diagnostics().get("compiledEntries")).longValue() > 0);
                assertSame(originalLoop, loop.entryTarget(PREFIX + "codePointSum"));
                var separate = program(backend, "codePointSum");
                assertEquals(128810L, call(separate, "codePointSum", 0L, s));
                var firstSites = sites(originalLoop.getRootNode());
                var separateSites = sites(separate.entryTarget(PREFIX + "codePointSum").getRootNode());
                assertFalse(firstSites.isEmpty(), "Real lowered sites must own native cached nodes");
                assertFalse(separateSites.isEmpty());
                for (var site : firstSites) for (var other : separateSites) assertNotSame(site, other);
            } finally { context.leave(); }
        }
    }

    private static List<TruffleStringOp.Site> sites(RootNode root) {
        var result = new ArrayList<>(com.oracle.truffle.api.nodes.NodeUtil.findAllNodeInstances(root, TruffleStringOp.Site.class));
        // Generated instruction caches are not @Children; use the public bytecode
        // instruction metadata, just as the existing SIMD/foreign node controls do.
        if (root instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments())
                if (argument.getDescriptor().getKind() == com.oracle.truffle.api.bytecode.Instruction.Argument.Kind.NODE_PROFILE) {
                    var node = argument.asCachedNode();
                    if (node != null) result.addAll(com.oracle.truffle.api.nodes.NodeUtil.findAllNodeInstances(node, TruffleStringOp.Site.class));
                }
        for (var site : result) assertSame(root, site.getRootNode());
        return result;
    }

    @Test void nativeOperationsKeepByteAndCodePointUnitsDistinct() {
        var e = Encoding.UTF_8;
        var s = utf8(UTF8);
        var face = op(FROM_CODE_POINT, e, 128512L);
        assertEquals(7L, op(BYTE_LENGTH, e, s));
        assertEquals(3L, op(CODE_POINT_LENGTH, e, s));
        assertEquals(195L, op(READ_BYTE, e, s, 1L));
        assertEquals(128512L, op(CODE_POINT_AT, e, s, 2L));
        assertEquals(128512L, op(CODE_POINT_AT_BYTE, e, s, 3L));
        assertEquals(4L, op(CODE_POINT_BYTE_LENGTH, e, s, 3L));
        assertEquals(2L, op(BYTE_TO_CODE_POINT, e, s, 0L, 3L));
        assertEquals(3L, op(CODE_POINT_TO_BYTE, e, s, 0L, 2L));
        assertEquals(2L, op(CODE_POINT_TO_BYTE, e, s, 1L, 1L));
        assertEquals(1L, op(BYTE_TO_CODE_POINT, e, s, 1L, 2L));
        assertEquals(2L, op(INDEX_OF_CODE_POINT, e, s, 128512L, 0L, 3L));
        assertEquals(3L, op(BYTE_INDEX_OF_CODE_POINT, e, s, 128512L, 0L, 7L));
        assertEquals(2L, op(INDEX_OF_STRING, e, s, face, 0L, 3L));
        assertEquals(3L, op(BYTE_INDEX_OF_STRING, e, s, face, 0L, 7L));
        assertEquals(-1L, op(INDEX_OF_STRING, e, s, face, 0L, 2L));
        assertEquals(1L, op(EQUAL, e, face, op(SUBSTRING, e, s, 2L, 1L)));
        assertEquals(1L, op(EQUAL, e, face, op(SUBSTRING_BYTES, e, s, 3L, 4L)));
        assertEquals(0L, op(COMPARE_BYTES, e, s, utf8(UTF8)));
        assertTrue((Long) op(COMPARE_BYTES, e, utf8(new byte[]{65}), utf8(new byte[]{66})) < 0);
        assertEquals((long) s.hashCodeUncached(e), op(HASH, e, s));
        assertEquals(1L, op(EQUAL, e, s, op(CONCAT, e, utf8(new byte[]{65}), op(SUBSTRING, e, s, 1L, 2L))));
        assertArrayEquals("😀😀".getBytes(StandardCharsets.UTF_8), (byte[]) op(TO_BYTES, e, op(REPEAT, e, face, 2L)));
    }

    private static void stringCalls(Object value, Map<String, List<?>> found) {
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst())) {
                var meta = CoreRepresentations.metadata(list);
                if (meta != null && meta.get("foreignCall") instanceof Map<?, ?> call &&
                    call.get("target") instanceof Map<?, ?> target &&
                    target.get("symbol") instanceof String symbol && symbol.startsWith("thc_string_v1_"))
                    found.putIfAbsent(symbol, list);
            }
            for (var item : list) stringCalls(item, found);
        } else if (value instanceof Map<?, ?> map) {
            for (var item : map.values()) stringCalls(item, found);
        }
    }

    @Test @SuppressWarnings("unchecked") void genuineDeclarationsHaveExactAbiAndRejectForgedShapes() throws Exception {
        var found = new HashMap<String, List<?>>();
        stringCalls(module(), found);
        assertEquals(TruffleStringOp.values().length, found.size());
        for (var operation : TruffleStringOp.values()) {
            var expression = found.get(operation.symbol);
            assertNotNull(expression, operation.symbol);
            assertSame(operation, TruffleStringOp.validate(expression, false));
            assertThrows(RuntimeFault.class, () -> TruffleStringOp.validate(expression, true));
            var copy = (List<?>) Json.parse(Json.stringify(expression));
            var foreign = (Map<String, Object>) CoreRepresentations.metadata(copy).get("foreignCall");
            foreign.put("suppliedArity", 0L);
            assertThrows(RuntimeFault.class, () -> TruffleStringOp.validate(copy, false));
        }
        var copy = (List<?>) Json.parse(Json.stringify(found.get(FROM_BYTES.symbol)));
        var foreign = (Map<String, Object>) CoreRepresentations.metadata(copy).get("foreignCall");
        foreign.put("argumentTypes", Arrays.asList(null, "MutableByteArray#", null, null));
        assertThrows(RuntimeFault.class, () -> TruffleStringOp.validate(copy, false));
    }

    @Test void copiedBackingAndManagedOwnershipArePreserved() {
        byte[] input = UTF8.clone();
        var s = utf8(input);
        input[0] = 90;
        byte[] output = (byte[]) op(TO_BYTES, Encoding.UTF_8, s);
        assertArrayEquals(UTF8, output);
        output[0] = 88;
        assertEquals(65L, op(READ_BYTE, Encoding.UTF_8, s, 0L));
        var owner = ManagedAllocation.mutable(16, 8);
        owner.copyBytesIn(UTF8, 0, 2, UTF8.length);
        var managed = op(FROM_BYTES, Encoding.UTF_8, owner, 2L, 7L);
        owner.copyBytesIn(new byte[]{90}, 0, 2, 1);
        assertArrayEquals(UTF8, (byte[]) op(TO_BYTES, Encoding.UTF_8, managed));
        owner.writeAddressByteOffset(0, ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8)));
        assertThrows(RuntimeFault.class, () -> op(FROM_BYTES, Encoding.UTF_8, owner, 0L, 8L));
        assertThrows(RuntimeException.class, () -> op(FROM_BYTES, Encoding.UTF_8, input, 0L, 99L));
        assertThrows(ArithmeticException.class, () -> op(READ_BYTE, Encoding.UTF_8, s, 1L << 40));
    }

    @Test void malformedEncodingAndNumericFailuresAreExplicit() {
        var invalid = utf8(new byte[]{(byte) 0xff});
        assertEquals(0L, op(IS_VALID, Encoding.UTF_8, invalid));
        assertEquals(-1L, op(CODE_POINT_AT, Encoding.UTF_8, invalid, 0L));
        assertEquals(-1L, op(CODE_POINT_BYTE_LENGTH, Encoding.UTF_8, invalid, 0L));
        var truncated = utf8(new byte[]{(byte) 0xf0, (byte) 0x9f});
        assertEquals(0L, op(IS_VALID, Encoding.UTF_8, truncated));
        assertEquals(-1L, op(CODE_POINT_AT, Encoding.UTF_8, truncated, 0L));
        assertEquals(-3L, op(CODE_POINT_BYTE_LENGTH, Encoding.UTF_8, truncated, 0L));
        var replaced = op(SWITCH_ENCODING, Encoding.UTF_16, invalid);
        assertEquals(0xfffdL, op(CODE_POINT_AT, Encoding.UTF_16, replaced, 0L));
        var lossy = op(SWITCH_ENCODING, Encoding.US_ASCII, utf8(UTF8));
        assertArrayEquals(new byte[]{65, 63, 63}, (byte[]) op(TO_BYTES, Encoding.US_ASCII, lossy));
        assertThrows(RuntimeException.class, () -> op(FROM_BYTES, Encoding.UTF_16, new byte[]{1}, 0L, 1L));
        assertThrows(RuntimeFault.class, () -> op(FROM_CODE_POINT, Encoding.US_ASCII, 128512L));
        assertThrows(RuntimeFault.class, () -> op(ENCODING, 10L));
        assertEquals(255L, op(PARSE_INT64, utf8(new byte[]{102, 102}), 16L));
        assertEquals(1.25, op(PARSE_DOUBLE, utf8(new byte[]{49, 46, 50, 53})));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var parser = new RootNode(language) {
                    @Child private Site site = new Site(PARSE_INT64);
                    @Override public Object execute(VirtualFrame frame) { return site.execute(frame, frame.getArguments()); }
                }.getCallTarget();
                for (String text : List.of("x", "9223372036854775808")) {
                    var failure = assertThrows(com.oracle.truffle.api.exception.AbstractTruffleException.class,
                        () -> parser.call(utf8(text.getBytes(StandardCharsets.US_ASCII)), 10L));
                    var env = Language.currentState().getEnv();
                    assertTrue(env.isHostException(failure));
                    assertInstanceOf(TruffleString.NumberFormatException.class, env.asHostException(failure));
                }
            } finally { context.leave(); }
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class NativeString implements TruffleObject {
        final TruffleString value = utf8(UTF8);
        int reads, javaReads;
        @ExportMessage boolean isString() { return true; }
        @ExportMessage TruffleString asTruffleString() { reads++; return value; }
        @ExportMessage String asString() { javaReads++; return "Aé😀"; }
    }
    @Test void interopUsesNativeMessageWithoutJavaStringMediation() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState();
            owner.getThreads().enterCurrent(null, false, false, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var acquire = new RootNode(language) {
                    @Child private InteropLibraryAcquisition access = new InteropLibraryAcquisition();
                    @Override public Object execute(VirtualFrame frame) { return access.execute(frame.getArguments()[0]); }
                }.getCallTarget();
                var read = new RootNode(language) {
                    @Child private InteropAccess access = new InteropAccess();
                    @Override public Object execute(VirtualFrame frame) {
                        return access.execute(PolyglotOp.AS_TRUFFLE_STRING, frame.getArguments());
                    }
                }.getCallTarget();
                var receiver = new NativeString();
                var library = (InteropLibrary) acquire.call(receiver);
                assertSame(receiver.value, read.call(receiver, library, Unit.INSTANCE));
                compile(read);
                assertSame(receiver.value, read.call(receiver, library, Unit.INSTANCE));
                assertEquals(2, receiver.reads);
                assertEquals(0, receiver.javaReads, "Native conversion must not mediate through Java String");
                var predicate = new RootNode(language) {
                    @Child private InteropAccess access = new InteropAccess();
                    @Override public Object execute(VirtualFrame frame) { return access.execute(PolyglotOp.IS_STRING, frame.getArguments()); }
                }.getCallTarget();
                // Truffle's assertion wrapper checks isString by querying asString.
                // Do not confuse that contract check with the asTruffleString route.
                assertEquals(1L, predicate.call(receiver, library, Unit.INSTANCE));
                assertEquals(0L, predicate.call(42L, acquire.call(42L), Unit.INSTANCE));
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
}
