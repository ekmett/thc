// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Fail-closed normalization of the pinned processor's generated Java, not guest code. */
public final class BytecodeNormalizers {
    public static final String VERSION = "25.3.4.1";
    private BytecodeNormalizers() {}

    public interface Transform { String apply(String source, String version); }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static String unix(String source, String message) {
        String unix = source.replace("\r\n", "\n");
        require(unix.indexOf('\r') < 0 && (!source.contains("\r\n") || source.equals(unix.replace("\n", "\r\n"))), message);
        return unix;
    }

    static String newline(String original, String result) {
        return original.contains("\r\n") ? result.replace("\n", "\r\n") : result;
    }

    static String replaceOnce(String value, String from, String to, String message) {
        int at = value.indexOf(from);
        require(at >= 0 && value.indexOf(from, at + from.length()) < 0, message);
        return value.substring(0, at) + to + value.substring(at + from.length());
    }

    /** Keep the order explicit: each transform independently checks its version and source shape. */
    public static String all(String source, String version) {
        source = BytecodeColdBranchPreparation.restore(source, version);
        source = BytecodeMetadataPreparation.restore(source, version);
        String result = handlers(staticPreparation(sourceMode(metadata(source, version), version), version), version);
        result = BytecodeColdApplyPreparation.transform(result, version);
        result = BytecodeColdDelimitedPreparation.transform(result, version);
        result = BytecodeColdCompactPreparation.transform(result, version);
        result = BytecodeColdForcePreparation.transform(result, version);
        result = BytecodeColdCapturePreparation.transform(result, version);
        result = BytecodeColdPolyglotPreparation.transform(result, version);
        return BytecodeColdBranchPreparation.transform(BytecodeMetadataPreparation.transform(
                BytecodeStackPreparation.transform(BytecodeBudgetChoice.transform(result, version), version), version), version);
    }

    static final String SIGNATURE = "        private static List<Instruction.Argument> getArguments(int opcode, long bci, AbstractBytecodeNode bytecode, byte[] bytecodes, Object[] constants) {\n";
    static final String SWITCH = "            switch (opcode) {\n";
    static final String END = "            }\n            throw CompilerDirectives.shouldNotReachHere(\"Invalid opcode\");\n        }\n";
    static final String BEGIN_MARKER = "        // THC argument-metadata split v1 BEGIN\n";
    static final String END_MARKER = "        // THC argument-metadata split v1 END\n";
    private static final String METADATA_SHAPE = "Unexpected Truffle argument metadata shape; review src/gradle/bytecode-metadata.gradle for the pinned processor.";
    private static final Pattern CASE = Pattern.compile(" {16}case Instructions\\.[A-Z0-9_$]+ :\\n");
    private static final Pattern ARGUMENT = Pattern.compile(" {28}new [A-Za-z][A-Za-z0-9]*Argument\\([^;{}\\r\\n]*\\)(,|\\);)\\n");
    private static final String CALL_ARGS = "opcode, bci, bytecode, bytecodes, constants";

    record Group(String labels, String expression) {
        String text() { return labels + expression; }
        int labelCount() { return (int) labels.chars().filter(c -> c == '\n').count(); }
    }

    // Intentionally not a general Java parser: only the pinned straight-line case groups are accepted.
    private static List<Group> groups(String body) {
        List<Group> result = new ArrayList<>();
        int offset = 0;
        while (offset < body.length()) {
            int start = offset;
            while (true) {
                var label = CASE.matcher(body).region(offset, body.length());
                if (!label.lookingAt()) break;
                offset = label.end();
            }
            require(offset > start, METADATA_SHAPE);
            String labels = body.substring(start, offset);
            int expressionStart = offset;
            String empty = "                        return List.of();\n";
            String nonempty = "                        return List.of(\n";
            if (body.startsWith(empty, offset)) offset += empty.length();
            else {
                require(body.startsWith(nonempty, offset), METADATA_SHAPE);
                offset += nonempty.length();
                while (true) {
                    var argument = ARGUMENT.matcher(body).region(offset, body.length());
                    require(argument.lookingAt(), METADATA_SHAPE);
                    offset = argument.end();
                    if (!argument.group(1).equals(",")) break;
                }
            }
            result.add(new Group(labels, body.substring(expressionStart, offset)));
        }
        var labels = result.stream().flatMap(g -> g.labels().lines()).filter(s -> !s.isEmpty()).toList();
        require(!labels.isEmpty() && labels.size() <= 4096 && labels.stream().distinct().count() == labels.size(), METADATA_SHAPE);
        return result;
    }

    private static String render(List<Group> groups) {
        List<List<Group>> chunks = new ArrayList<>();
        for (Group group : groups) {
            require(group.text().length() <= 32000 && group.labelCount() <= 512, METADATA_SHAPE);
            var last = chunks.isEmpty() ? null : chunks.getLast();
            if (last == null || last.size() >= 64 ||
                    last.stream().mapToInt(g -> g.text().length()).sum() + group.text().length() > 32000 ||
                    last.stream().mapToInt(Group::labelCount).sum() + group.labelCount() > 512) {
                last = new ArrayList<>();
                chunks.add(last);
            }
            last.add(group);
        }
        require(chunks.size() <= 128, METADATA_SHAPE);
        StringBuilder out = new StringBuilder(BEGIN_MARKER).append(SIGNATURE).append(SWITCH);
        for (int index = 0; index < chunks.size(); index++) {
            for (Group group : chunks.get(index)) out.append(group.labels());
            out.append("                    return getArgumentsThcPart").append(index).append('(').append(CALL_ARGS).append(");\n");
        }
        out.append(END);
        for (int index = 0; index < chunks.size(); index++) {
            out.append('\n').append(SIGNATURE.replace("getArguments(", "getArgumentsThcPart" + index + "(")).append(SWITCH);
            for (Group group : chunks.get(index)) out.append(group.text());
            out.append(END);
        }
        return out.append(END_MARKER).toString();
    }

    private static Pattern helpers() {
        int at = SIGNATURE.indexOf("getArguments");
        return Pattern.compile(Pattern.quote(SIGNATURE.substring(0, at)) + "getArgumentsThcPart[0-9]+" +
                Pattern.quote(SIGNATURE.substring(at + "getArguments".length()) + SWITCH) + "([\\s\\S]*?)" + Pattern.quote(END));
    }

    public static Map<String, String> metadataMap(String source) {
        String unix = source.replace("\r\n", "\n");
        List<String> bodies = new ArrayList<>();
        if (unix.contains(BEGIN_MARKER)) {
            var matcher = helpers().matcher(unix);
            while (matcher.find()) bodies.add(matcher.group(1));
        } else {
            String prefix = SIGNATURE + SWITCH;
            int start = unix.indexOf(prefix);
            String body = start < 0 ? unix : unix.substring(start + prefix.length());
            int end = body.indexOf(END);
            bodies.add(end < 0 ? body : body.substring(0, end));
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String body : bodies) for (Group group : groups(body)) {
            group.labels().lines().filter(s -> !s.isEmpty()).forEach(label -> result.put(label, group.expression()));
        }
        return result;
    }

    public static String metadata(String source, String version) {
        require(VERSION.equals(version), "Review the argument metadata splitter before changing Truffle " + VERSION + " to " + version + ".");
        String unix = unix(source, METADATA_SHAPE);
        int start, finish;
        List<Group> original;
        if (unix.contains(BEGIN_MARKER)) {
            start = unix.indexOf(BEGIN_MARKER);
            int end = unix.indexOf(END_MARKER, start);
            require(end >= 0, METADATA_SHAPE);
            finish = end + END_MARKER.length();
            require(unix.indexOf(BEGIN_MARKER, start + 1) < 0 && unix.indexOf(END_MARKER, finish) < 0, METADATA_SHAPE);
            String region = unix.substring(start, finish);
            original = new ArrayList<>();
            var matcher = helpers().matcher(region);
            while (matcher.find()) original.addAll(groups(matcher.group(1)));
            require(!original.isEmpty() && render(original).equals(region), METADATA_SHAPE);
            groups(String.join("", original.stream().map(Group::text).toList()));
        } else {
            require(!unix.contains("getArgumentsThcPart") && !unix.contains("THC argument-metadata split"), METADATA_SHAPE);
            start = unix.indexOf(SIGNATURE);
            require(start >= 0 && unix.indexOf(SIGNATURE, start + SIGNATURE.length()) < 0, METADATA_SHAPE);
            int bodyStart = start + SIGNATURE.length() + SWITCH.length();
            require(unix.startsWith(SWITCH, start + SIGNATURE.length()), METADATA_SHAPE);
            int bodyEnd = unix.indexOf(END, bodyStart);
            require(bodyEnd >= 0, METADATA_SHAPE);
            finish = bodyEnd + END.length();
            original = groups(unix.substring(bodyStart, bodyEnd));
        }
        return newline(source, unix.substring(0, start) + render(original) + unix.substring(finish));
    }

    static final String BUILDER = "    public static final class Builder extends BytecodeBuilder {\n";
    static final String PARSE_SOURCES = "        private final boolean parseSources;\n";
    static final String ACCESSOR = "\n        // THC lazy source-mode accessor v1\n" +
            "        public boolean isParsingSources() { return parseSources; }\n";

    public static String sourceMode(String source, String version) {
        require(VERSION.equals(version), "Review the source-mode accessor before changing Truffle " + VERSION + " to " + version + ".");
        String message = "Unexpected Truffle source-mode builder shape.";
        String unix = unix(source, message);
        int start = unix.indexOf(BUILDER), at = unix.indexOf(PARSE_SOURCES);
        require(start >= 0 && unix.indexOf(BUILDER, start + 1) < 0, message);
        require(at > start && unix.indexOf(PARSE_SOURCES, at + 1) < 0, message);
        String original = unix;
        int insert = at + PARSE_SOURCES.length();
        if (unix.contains("isParsingSources") || unix.contains("THC lazy source-mode")) {
            require(unix.startsWith(ACCESSOR, insert), message);
            original = unix.substring(0, insert) + unix.substring(insert + ACCESSOR.length());
            require(!original.contains("isParsingSources") && !original.contains("THC lazy source-mode"), message);
        }
        return newline(source, original.substring(0, insert) + ACCESSOR + original.substring(insert));
    }

    static final String STATIC_MARKER = "THC static local preparation v1";
    static final String TRANSITION = "    private void transitionToCached() {\n";
    static final String PREPARATION = "    // " + STATIC_MARKER + "\n" +
            "    @Override\n" +
            "    protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {\n" +
            "        if (this.bytecode instanceof UninitializedBytecodeNode) transitionToCached();\n" +
            "        return super.prepareForCompilation(rootCompilation, compilationTier, lastTier);\n" +
            "    }\n\n";
    static final String CONTINUATION_PREPARATION = "        @Override\n" +
            "        protected boolean prepareForCompilation(boolean rootCompilation, int tier, boolean lastTier) {\n" +
            "            return root.prepareForCompilation(rootCompilation, tier, lastTier);\n" +
            "        }\n";
    static final String OLD_TAGS = "    private static byte[] createCachedTags(int numLocals) {\n" +
            "        byte[] localTags = new byte[numLocals];\n" +
            "        Arrays.fill(localTags, FrameSlotKind.Illegal.tag);\n" +
            "        return localTags;\n    }\n";
    static final String NEW_TAGS = "    private static byte[] createCachedTags(int numLocals, int[] locals, Object[] constants) {\n" +
            "        byte[] localTags = new byte[numLocals];\n" +
            "        Arrays.fill(localTags, FrameSlotKind.Illegal.tag);\n" +
            "        boolean[] seen = new boolean[numLocals];\n" +
            "        for (int at = 0; at < locals.length; at += LOCALS_LENGTH) {\n" +
            "            int local = locals[at + LOCALS_OFFSET_LOCAL_INDEX];\n" +
            "            int index = locals[at + LOCALS_OFFSET_INFO];\n" +
            "            Object info = index < 0 ? null : constants[index];\n" +
            "            byte tag = info == FrameSlotKind.Long ? FrameSlotKind.Long.tag :\n" +
            "                info == FrameSlotKind.Int ? FrameSlotKind.Int.tag :\n" +
            "                info == FrameSlotKind.Float ? FrameSlotKind.Float.tag :\n" +
            "                info == FrameSlotKind.Double ? FrameSlotKind.Double.tag :\n" +
            "                info == FrameSlotKind.Boolean ? FrameSlotKind.Boolean.tag :\n" +
            "                info == FrameSlotKind.Object ? FrameSlotKind.Object.tag : FrameSlotKind.Illegal.tag;\n" +
            "            if (!seen[local]) { localTags[local] = tag; seen[local] = true; }\n" +
            "            else if (localTags[local] != tag) localTags[local] = FrameSlotKind.Illegal.tag;\n" +
            "        }\n" +
            "        return localTags;\n    }\n";
    static final String[][] TAG_CALLS = {
            {"createCachedTags(numLocals)", "createCachedTags(numLocals, this.locals, this.constants)"},
            {"createCachedTags(this.localTags_.length)", "createCachedTags(this.localTags_.length, this.locals, clonedConstants)"}};

    public static String staticPreparation(String source, String version) {
        require(VERSION.equals(version), "Review static preparation before changing Truffle " + VERSION + ".");
        String message = "Unexpected Truffle static preparation shape.";
        String result = unix(source, message);
        if (result.contains(STATIC_MARKER)) {
            result = replaceOnce(result, PREPARATION, "", message);
            result = replaceOnce(result, NEW_TAGS, OLD_TAGS, message);
            for (String[] call : TAG_CALLS) result = replaceOnce(result, call[1], call[0], message);
            require(!result.contains(STATIC_MARKER), message);
        }
        String checked = result;
        if (checked.contains(CONTINUATION_PREPARATION)) {
            int continuation = checked.indexOf("    private static final class ContinuationRootNodeImpl extends ContinuationRootNode {\n");
            require(continuation >= 0 && checked.indexOf(CONTINUATION_PREPARATION) > continuation, message);
            checked = replaceOnce(checked, CONTINUATION_PREPARATION, "", message);
        }
        require(!Pattern.compile("protected boolean prepareForCompilation\\(").matcher(checked).find(), message);
        result = replaceOnce(result, TRANSITION, PREPARATION + TRANSITION, message);
        result = replaceOnce(result, OLD_TAGS, NEW_TAGS, message);
        for (String[] call : TAG_CALLS) result = replaceOnce(result, call[0], call[1], message);
        return newline(source, result);
    }

    static final String OLD_HANDLER = "                if (!this.exceptionProfiles_[handlerEntryIndex]) {\n" +
            "                    CompilerDirectives.transferToInterpreterAndInvalidate();\n" +
            "                    this.exceptionProfiles_[handlerEntryIndex] = true;\n" +
            "                }\n";
    /** Validate the ordinary pinned profile before metadata preparation copies it. */
    public static String handlers(String source, String version) {
        require(VERSION.equals(version), "Review handler preparation before changing Truffle " + VERSION + ".");
        String message = "Unexpected Truffle handler preparation shape.";
        String result = unix(source, message);
        String signature = "private int resolveHandler(long bci, int handler, int[] localHandlers)";
        int at = result.indexOf(signature);
        require(at >= 0 && result.indexOf(signature, at + signature.length()) < 0, message);
        return newline(source, replaceOnce(result, OLD_HANDLER, OLD_HANDLER, message));
    }

}
