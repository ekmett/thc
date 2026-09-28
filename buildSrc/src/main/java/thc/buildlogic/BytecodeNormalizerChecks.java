// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import static thc.buildlogic.BytecodeNormalizers.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.tools.ToolProvider;

/** The original pinned shape and compiled-fixture controls, independent of Gradle's DSL language. */
public final class BytecodeNormalizerChecks {
    private BytecodeNormalizerChecks() {}
    private static void check(boolean ok) { if (!ok) throw new IllegalStateException("Normalizer control failed"); }
    private static void reject(Transform transform, String source) { reject(transform, source, VERSION); }
    private static void reject(Transform transform, String source, String version) {
        try { transform.apply(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed generated source was accepted");
    }
    private static String stable(Transform transform, String source) {
        String result = transform.apply(source, VERSION);
        check(transform.apply(result, VERSION).equals(result));
        check(transform.apply(source.replace("\n", "\r\n"), VERSION).equals(result.replace("\n", "\r\n")));
        return result;
    }
    private static URLClassLoader compile(Path directory, String name, String source) throws Exception {
        Files.createDirectories(directory);
        Path input = directory.resolve(name + ".java");
        Files.writeString(input, source);
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "A JDK is required.");
        check(compiler.run(null, null, null, "-d", directory.toString(), input.toString()) == 0);
        return new URLClassLoader(new URL[]{directory.toUri().toURL()}, null);
    }

    public static void staticPreparation() {
        Transform patch = BytecodeNormalizers::staticPreparation;
        String before = "class StaticPreparationFixture {\n" + OLD_TAGS + TRANSITION + "    }\n" +
                "    Object first() { return createCachedTags(numLocals); }\n" +
                "    Object copy() { return createCachedTags(this.localTags_.length); }\n}\n";
        String after = stable(patch, before);
        reject(patch, before, "changed-version");
        reject(patch, before + before);
        reject(patch, before.replace("int numLocals", "long numLocals"));
        reject(patch, before.replace("createCachedTags(numLocals);", "createCachedTags(other);"));
        reject(patch, after.replace("info == FrameSlotKind.Long", "info instanceof FrameSlotKind"));
        reject(patch, after.replace("super.prepareForCompilation", "otherPreparation"));
        for (String kind : List.of("Int", "Float", "Double", "Boolean"))
            reject(patch, after.replace("info == FrameSlotKind." + kind, "info == null"));
    }

    public static void staticTags(Path directory) throws Exception {
        String source = """
            import java.util.Arrays;
            public class StaticTagsFixture {
                static final int LOCALS_LENGTH = 2, LOCALS_OFFSET_LOCAL_INDEX = 0, LOCALS_OFFSET_INFO = 1;
                enum FrameSlotKind {
                    Illegal(0), Long(1), Object(2), Int(3), Float(4), Double(5), Boolean(6);
                    final byte tag; FrameSlotKind(int tag) { this.tag = (byte) tag; }
                }
                public static byte[] probe(int count, int[] locals, String[] names) {
                    Object[] constants = new Object[names.length];
                    for (int i = 0; i < names.length; i++)
                        constants[i] = names[i] == null ? null : FrameSlotKind.valueOf(names[i]);
                    return createCachedTags(count, locals, constants);
                }
            """ + NEW_TAGS + "}\n";
        try (var loader = compile(directory.resolve("staticTags"), "StaticTagsFixture", source)) {
            var probe = loader.loadClass("StaticTagsFixture").getMethod("probe", int.class, int[].class, String[].class);
            var names = new String[]{"Long", "Object", "Int", "Float", "Double", null, "Boolean"};
            check(Arrays.equals((byte[]) probe.invoke(null, 8,
                new int[]{0,0, 1,1, 2,2, 3,3, 4,4, 5,5, 6,-1, 7,6}, names), new byte[]{1,2,3,4,5,0,0,6}));
            // Reused physical slots retain a tag only when every logical lifetime agrees.
            check(Arrays.equals((byte[]) probe.invoke(null, 3,
                new int[]{0,2, 0,2, 1,2, 1,0, 1,2, 2,3, 2,-1}, names), new byte[]{3,0,0}));
        }
    }

    public static void handlers(Path directory) throws Exception {
        Transform patch = BytecodeNormalizers::handlers;
        String before = """
                public class HandlerPreparationFixture {
                    private final boolean capture;
                    private final boolean[] exceptionProfiles_ = new boolean[2];
                    private static final int EXCEPTION_HANDLER_LENGTH = 3;
                    public HandlerPreparationFixture(boolean capture) { this.capture = capture; }
                    HandlerPreparationFixture getRoot() { return this; }
                    boolean requiresUnprofiledExceptionHandlers() { return capture; }
                    public boolean observed(int i) { return exceptionProfiles_[i]; }
                    public int probe(long bci, int first) { return resolveHandler(bci, first, new int[]{0, 5, 10, 3, 8, 20}); }
                    private int resolveHandler(long bci, int handler, int[] localHandlers) {
                        for (int i = handler; i < localHandlers.length; i += EXCEPTION_HANDLER_LENGTH) {
                            if (localHandlers[i] > bci || localHandlers[i + 1] <= bci) continue;
                            int handlerEntryIndex = Math.floorDiv(i, EXCEPTION_HANDLER_LENGTH);
                """ + OLD_HANDLER + """
                            return i;
                        }
                        return -1;
                    }
                    static class CompilerDirectives { static void transferToInterpreterAndInvalidate() {} }
                }
                """;
        String after = stable(patch, before);
        reject(patch, before, "changed-version");
        reject(patch, before + before);
        reject(patch, before.replace("long bci", "int bci"));
        reject(patch, before.replace("[handlerEntryIndex] = true", "[handlerEntryIndex] = false"));
        reject(patch, after.replace("requiresUnprofiledExceptionHandlers() &&", "requiresUnprofiledExceptionHandlers() ||"));
        reject(patch, before.replaceFirst("\n", "\r\n"));
        try (var loader = compile(directory.resolve("handlerPolicy"), "HandlerPreparationFixture", after)) {
            var type = loader.loadClass("HandlerPreparationFixture");
            for (boolean capture : new boolean[]{false, true}) {
                var value = type.getConstructor(boolean.class).newInstance(capture);
                var probe = type.getMethod("probe", long.class, int.class);
                var observed = type.getMethod("observed", int.class);
                check(probe.invoke(value, -1L, 0).equals(-1));
                check(probe.invoke(value, 0L, 0).equals(0));
                check(probe.invoke(value, 4L, 3).equals(3));
                check(probe.invoke(value, 5L, 0).equals(3));
                check(probe.invoke(value, 8L, 0).equals(-1));
                for (int index = 0; index <= 1; index++) check(observed.invoke(value, index).equals(!capture));
            }
        }
    }

    public static void unprofiledBranch() {
        Transform patch = BytecodeNormalizers::unprofiledBranch;
        String before = "// Ordinary and quickened handlers stay untouched.\n" + OLD_BRANCH +
                "// End of exact initial unprofiled handler.\n";
        String after = stable(patch, before);
        check(!before.equals(after));
        check(before.substring(0, before.indexOf("        @EarlyInline")).equals(after.substring(0, after.indexOf("        @EarlyInline"))));
        String suffix = "            if (condition_)";
        check(before.substring(before.indexOf(suffix)).equals(after.substring(after.indexOf(suffix))));
        reject(patch, before, "changed-version");
        reject(patch, before + before);
        reject(patch, before.replace("bci + 8", "bci + 10"));
        reject(patch, before.replace("bci + 2", "bci + 4"));
        reject(patch, before.replace("long sp", "int sp"));
        reject(patch, before.replace("sp, null", "sp, other"));
        reject(patch, after.replace("(boolean) FRAMES", "(Boolean) FRAMES"));
        reject(patch, after.replace("sp - 1", "sp - 2"));
        reject(patch, after.replace("FRAMES.clear(frame, sp - 1);", ""));
        reject(patch, before.replaceFirst("\n", "\r\n"));
    }

    public static void sourceMode(Path directory) throws Exception {
        Transform patch = BytecodeNormalizers::sourceMode;
        String before = "public class SourceModeFixture {\n" +
                "    static class BytecodeBuilder {}\n" + BUILDER + PARSE_SOURCES +
                "        public Builder(boolean enabled) { parseSources = enabled; }\n" + "    }\n}\n";
        String after = stable(patch, before);
        check(!before.equals(after));
        reject(patch, before, "next-version");
        reject(patch, before.replace("private final boolean parseSources", "private boolean parseSources"));
        reject(patch, before.replace("extends BytecodeBuilder", "extends ChangedBuilder"));
        reject(patch, before.replace("        private final boolean parseSources;\n", ""));
        reject(patch, before + "        private final boolean parseSources;\n");
        reject(patch, after.replace("return parseSources", "return true"));
        reject(patch, after + "// isParsingSources\n");
        reject(patch, before.replaceFirst("\n", "\r\n"));
        try (var loader = compile(directory.resolve("sourceMode"), "SourceModeFixture", after)) {
            var builder = loader.loadClass("SourceModeFixture$Builder");
            var constructor = builder.getConstructor(boolean.class);
            var getter = builder.getMethod("isParsingSources");
            for (boolean enabled : new boolean[]{false, true}) {
                var instance = constructor.newInstance(enabled);
                for (int count = 0; count < 2; count++) check(getter.invoke(instance).equals(enabled));
            }
        }
    }

    private static String metadataFixture() {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < 160; index++) {
            body.append("                case Instructions.OP_").append(index).append(" :\n");
            if (index % 5 == 0) body.append("                case Instructions.ALIAS_").append(index).append(" :\n");
            if (index == 7) body.append("                        return List.of();\n");
            else body.append("                        return List.of(\n")
                    .append("                            new IntegerArgument(ArgumentDescriptorImpl.FIRST, bci + ").append(index).append(", bytecodes),\n")
                    .append("                            new IntegerArgument(ArgumentDescriptorImpl.SECOND, bci + 2, bytecodes));\n");
        }
        StringBuilder result = new StringBuilder("import java.util.List;\npublic class MetadataFixture {\n")
                .append("    static class Instruction { interface Argument {} }\n")
                .append("    static class AbstractBytecodeNode {}\n")
                .append("    record IntegerArgument(String descriptor, long offset, byte[] bytes) implements Instruction.Argument {\n")
                .append("        public String toString() { return descriptor + \":\" + offset + \":\" + java.util.Arrays.toString(bytes); }\n    }\n")
                .append("    static class ArgumentDescriptorImpl { static final String FIRST = \"first\", SECOND = \"second\"; }\n")
                .append("    static class CompilerDirectives { static RuntimeException shouldNotReachHere(String reason) { return new IllegalArgumentException(reason); } }\n")
                .append("    static class Instructions {\n");
        for (int index = 0; index < 160; index++) {
            result.append("        static final int OP_").append(index).append(" = ").append(index).append(";\n");
            if (index % 5 == 0) result.append("        static final int ALIAS_").append(index).append(" = ").append(1000 + index).append(";\n");
        }
        return result.append("    }\n").append(SIGNATURE).append(SWITCH).append(body).append(END)
                .append("    public static String probe(int opcode) {\n")
                .append("        try { return getArguments(opcode, 37, new AbstractBytecodeNode(), new byte[] { 2, 3 }, new Object[0]).toString(); }\n")
                .append("        catch (IllegalArgumentException invalid) { return invalid.getMessage(); }\n    }\n}\n").toString();
    }

    public static void metadata(Path directory, Path realSource) throws Exception {
        Transform patch = BytecodeNormalizers::metadata;
        String before = metadataFixture();
        String after = stable(patch, before);
        check(!before.equals(after));
        check(metadataMap(before).equals(metadataMap(after)));
        reject(patch, before, "next-version");
        reject(patch, before.replace("switch (opcode)", "switch (opcode + 1)"));
        reject(patch, before.replace("case Instructions.OP_1 :", "case Instructions.OP_0 :"));
        reject(patch, before.replace("return List.of();", "break;"));
        reject(patch, before.replace("return List.of();", "return java.util.List.of();"));
        reject(patch, before.replace("case Instructions.OP_1 :", "default :"));
        reject(patch, after.replace("return getArgumentsThcPart0", "return getArgumentsThcPart1"));
        reject(patch, after.replace("split v1 END", "split v2 END"));
        List<List<Object>> results = new ArrayList<>();
        int index = 0;
        for (String source : List.of(before, after)) {
            try (var loader = compile(directory.resolve("variant" + index++), "MetadataFixture", source)) {
                var probe = loader.loadClass("MetadataFixture").getMethod("probe", int.class);
                List<Object> values = new ArrayList<>();
                for (int opcode = 0; opcode < 160; opcode++) values.add(probe.invoke(null, opcode));
                for (int opcode = 0; opcode < 160; opcode += 5) values.add(probe.invoke(null, 1000 + opcode));
                for (int opcode : new int[]{-1, 160, 9999}) values.add(probe.invoke(null, opcode));
                results.add(values);
            }
        }
        check(results.get(0).equals(results.get(1)));
        if (realSource != null) {
            String source = Files.readString(realSource);
            String normalized = patch.apply(source, VERSION);
            check(patch.apply(normalized, VERSION).equals(normalized));
            check(metadataMap(source).equals(metadataMap(normalized)));
            System.out.println("Real generated metadata validated: " + metadataMap(source).size() +
                    " opcode argument descriptions unchanged (" + realSource + ").");
        }
    }
}
