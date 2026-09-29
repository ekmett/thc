// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.lang.reflect.Array;
import java.nio.file.*;
import java.util.*;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.CoreModules;
import thc.CoreUnitProgram;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class JavaArrayTest {
    private static final String PREFIX = "main:JavaArrays.";
    private static Map<String, Object> source;
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true).allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(name -> name.startsWith("java.lang.")).allowNativeAccess(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private static synchronized Map<String, Object> source() throws Exception {
        if (source != null) return source;
        var root = Path.of(System.getProperty("thc.projectRoot"), "build/java-arrays/core");
        var paths = new ArrayList<String>(List.of("@" + root.getParent().resolve("packages.json")));
        for (String file : List.of("THC.Prim.json", "JavaArrays.json", "THC.Exception.json", "THC.Internal.Exception.json"))
            paths.add(root.resolve(file).toString());
        source = (Map<String, Object>) Json.parse(CoreModules.request(paths, PREFIX + "multiply", true, false, "ast", false));
        return source;
    }
    private static CoreUnitProgram program(String backend, String name) throws Exception {
        var input = new LinkedHashMap<>(source());
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        return new CoreUnitProgram(language, Objects.requireNonNull(CoreModules.unitDirectory(input)), input,
            PREFIX + name, backend, false, Language.currentState());
    }
    private static Object call(ExecutableProgram p, String name, Object... args) {
        var root = (GuestRoot) p.entryTarget(PREFIX + name).getRootNode();
        var shape = root.getTupleResult();
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        var entry = new EntryRoot(language, root.getInputProofs(),
            shape == null ? root.getScalarResultProof() : shape.getProof(), new Metrics(false), shape).getCallTarget();
        Object answer = Calls.target(entry, new Object[]{p.entryValue(PREFIX + name), args});
        if (shape == null) return answer;
        Object[] fields = (Object[]) answer;
        assertEquals(1, fields.length);
        return fields[0];
    }
    private static void compile(ExecutableProgram p, String name) throws Exception {
        var target = p.entryTarget(PREFIX + name);
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }

    @ParameterizedTest @Tag("foreign-exceptions-full-core") @ValueSource(strings = {"ast", "bytecode"})
    void haskellPrimitiveArraysPreserveEveryScalarCarrier(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                String[] kinds = {"Boolean", "Byte", "Short", "Char", "Int", "Long", "Float", "Double"};
                Object[] values = {1L, -113, -31000, 65535, 123456789, 0x123456789abcdefL, -3.25f, 7.125};
                for (int i = 0; i < kinds.length; i++) {
                    String name = "cycle" + kinds[i]; try (var p = program(backend, name)) {
                    assertEquals(values[i], call(p, name, values[i], Unit.INSTANCE));
                    compile(p, name);
                    assertEquals(values[i], call(p, name, values[i], Unit.INSTANCE));
                    }
                }
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @Tag("foreign-exceptions-full-core") @ValueSource(strings = {"ast", "bytecode"})
    void haskellUsesRawArraysWithRuntimeSpeciesMasksAndIndices(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                try (var p = program(backend, "multiply")) {
                for (long width : new long[]{128, 0}) {
                    int lanes = width == 0 ? FloatVector.SPECIES_PREFERRED.length() : 4;
                    int count = lanes * 2 + 1;
                    float[] a = new float[count], b = new float[count], out = new float[count];
                    for (int i = 0; i < count; i++) { a[i] = i - 3.25f; b[i] = i * .5f; }
                    for (int pass = 0; pass < 2; pass++) {
                        assertSame(Unit.INSTANCE, call(p, "multiply", width, (long) count, a, b, out, Unit.INSTANCE));
                        for (int i = 0; i < count; i++) assertEquals(a[i] * b[i], out[i]);
                        if (pass == 0) compile(p, "multiply");
                    }
                }
                }
                int[] input = {10, 20, 30, 40, 50, 60, 70}, out = new int[7], indices = {6, 4, 2, 0};
                boolean[] mask = {true, false, true, true};
                try (var gather = program(backend, "gatherScatter")) {
                for (int pass = 0; pass < 2; pass++) {
                    Arrays.fill(out, -1);
                    assertSame(Unit.INSTANCE, call(gather, "gatherScatter", 128L, input, out, indices, mask, Unit.INSTANCE));
                    assertArrayEquals(new int[]{10, -1, 30, -1, -1, -1, 70}, out);
                    if (pass == 0) compile(gather, "gatherScatter");
                }
                }
                try (var shuffle = program(backend, "shuffleCopy")) {
                assertSame(Unit.INSTANCE, call(shuffle, "shuffleCopy", 128L, input, out, new int[]{3, 2, 1, 0}, Unit.INSTANCE));
                assertArrayEquals(new int[]{40, 30, 20, 10}, Arrays.copyOf(out, 4));
                }
                try (var caught = program(backend, "caughtVectorBounds")) {
                    assertEquals(731L, call(caught, "caughtVectorBounds", new int[0], Unit.INSTANCE));
                    assertEquals(19L, call(caught, "caughtVectorBounds", new int[]{19, 2, 3, 4}, Unit.INSTANCE));
                }
            } finally { context.leave(); }
        }
    }

    @Test void allArrayOverloadsFollowThePinnedJdkSemantics() {
        String[] kinds = {"BOOLEAN", "BYTE", "SHORT", "CHAR", "INT", "LONG", "FLOAT", "DOUBLE"};
        Class<?>[] types = {boolean.class, byte.class, short.class, char.class, int.class, long.class, float.class, double.class};
        VectorSpecies[] species = {ByteVector.SPECIES_128, ByteVector.SPECIES_128, ShortVector.SPECIES_128,
            ShortVector.SPECIES_128, IntVector.SPECIES_128, LongVector.SPECIES_128, FloatVector.SPECIES_128, DoubleVector.SPECIES_128};
        for (int k = 0; k < kinds.length; k++) {
            int n = species[k].length();
            Object input = Array.newInstance(types[k], n * 2 + 1), output = Array.newInstance(types[k], n * 2 + 1);
            for (int i = 0; i < Array.getLength(input); i++) {
                Object value = switch (k) { case 0 -> (i & 1) != 0; case 1 -> (byte) (i - 17); case 2 -> (short) (i - 71);
                    case 3 -> (char) (65500 + i); case 4 -> i * 17; case 5 -> (long) i * 0x1234567;
                    case 6 -> i * .5f; default -> i * .25; };
                Array.set(input, i, value);
            }
            var mask = species[k].indexInRange(0, n - 1);
            int[] map = new int[n]; for (int i = 0; i < n; i++) map[i] = i * 2;
            for (boolean indexed : new boolean[]{false, true}) for (boolean masked : new boolean[]{false, true}) {
                String suffix = (indexed ? "_INDEXED" : "") + (masked ? "_MASKED" : "");
                var read = new ArrayList<Object>(List.of(species[k], input, 1L));
                if (indexed) { read.add(map); read.add(0L); } if (masked) read.add(mask); read.add(Unit.INSTANCE);
                Object vector = VectorApiOp.valueOf("READ_JAVA_" + kinds[k] + "_VECTOR" + suffix).execute(read.toArray());
                output = Array.newInstance(types[k], n * 2 + 1);
                var write = new ArrayList<Object>(List.of(output, 1L, vector));
                if (indexed) { write.add(map); write.add(0L); } if (masked) write.add(mask); write.add(Unit.INSTANCE);
                assertSame(Unit.INSTANCE, VectorApiOp.valueOf("WRITE_JAVA_" + kinds[k] + "_VECTOR" + suffix).execute(write.toArray()));
                for (int i = 0; i < n - (masked ? 1 : 0); i++) {
                    int at = 1 + (indexed ? map[i] : i);
                    assertEquals(Array.get(input, at), Array.get(output, at), kinds[k] + suffix + "/" + i);
                }
            }
        }
        boolean[] flags = {true, false, false, true};
        var mask = VectorApiOp.READ_JAVA_MASK.execute(new Object[]{IntVector.SPECIES_128, flags, 0L, Unit.INSTANCE});
        boolean[] copiedFlags = new boolean[6];
        VectorApiOp.WRITE_JAVA_MASK.execute(new Object[]{mask, copiedFlags, 1L, Unit.INSTANCE});
        assertArrayEquals(new boolean[]{false, true, false, false, true, false}, copiedFlags);
        int[] order = {3, 2, 1, 0};
        var shuffle = VectorApiOp.READ_JAVA_SHUFFLE.execute(new Object[]{IntVector.SPECIES_128, order, 0L, Unit.INSTANCE});
        int[] copiedOrder = new int[6];
        VectorApiOp.WRITE_JAVA_SHUFFLE.execute(new Object[]{shuffle, copiedOrder, 1L, Unit.INSTANCE});
        assertArrayEquals(new int[]{0, 3, 2, 1, 0, 0}, copiedOrder);
    }

    @Test void arrayFailuresUseTheNormalHostExceptionProtocolAndImportsKeepIdentity() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(); owner.getThreads().enterCurrent(null, false, false, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var target = new RootNode(language) {
                    @Child private InteropAccess access = new InteropAccess();
                    @Override public Object execute(VirtualFrame frame) {
                        return access.execute((PolyglotOp) frame.getArguments()[0], (Object[]) frame.getArguments()[1]);
                    }
                }.getCallTarget();
                Object character = target.call(PolyglotOp.BOX_JAVA_CHAR, new Object[]{65535, Unit.INSTANCE});
                assertEquals(Character.valueOf('\uffff'), character);
                assertEquals(65535, target.call(PolyglotOp.UNBOX_JAVA_CHAR, new Object[]{character, com.oracle.truffle.api.interop.InteropLibrary.getUncached(character), Unit.INSTANCE}));
                int[] array = {7, 9};
                assertSame(array, target.call(PolyglotOp.OBJECT_AS_JAVA_INT_ARRAY, new Object[]{array, Unit.INSTANCE}));
                for (Object[] args : new Object[][]{{array, -1L, Unit.INSTANCE}, {array, Long.MAX_VALUE, Unit.INSTANCE}}) {
                    RuntimeException failure = assertThrows(RuntimeException.class, () -> target.call(PolyglotOp.READ_JAVA_INT_ARRAY, args));
                    assertTrue(owner.getEnv().isHostException(failure));
                    assertInstanceOf(RuntimeException.class, owner.getEnv().asHostException(failure));
                }
                var mismatch = assertThrows(RuntimeException.class, () -> target.call(PolyglotOp.OBJECT_AS_JAVA_INT_ARRAY, new Object[]{new long[0], Unit.INSTANCE}));
                assertInstanceOf(ClassCastException.class, owner.getEnv().asHostException(mismatch));
                var negative = assertThrows(RuntimeException.class, () -> target.call(PolyglotOp.NEW_JAVA_INT_ARRAY, new Object[]{-1L, Unit.INSTANCE}));
                assertInstanceOf(NegativeArraySizeException.class, owner.getEnv().asHostException(negative));
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
}
