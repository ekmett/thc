// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import static thc.buildlogic.BytecodeNormalizers.*;

/** Avoid PE-expanding whole immutable metadata tables at every exception edge. */
public final class BytecodeMetadataPreparation {
    private BytecodeMetadataPreparation() {}
    private static final String MESSAGE = "Unexpected Truffle compilation metadata shape";
    private static final String FIELD = "        @CompilationFinal(dimensions = 1) final int[] locals;\n";
    private static final String MEMBERS = """

        // THC sparse compilation metadata v1
        @CompilationFinal(dimensions = 2) volatile int[][] thcMetadata_;

        // Only compilation preparation calls this; unused roots retain no extra tables.
        // Publish both immutable indices together. Old bytecode snapshots own their indices.
        final synchronized void prepareMetadataLookup() {
            CompilerAsserts.neverPartOfCompilation();
            if (thcMetadata_ != null) return;
            var changes = new java.util.TreeMap<Integer, Integer>();
            for (int at = 0; at < locals.length; at += LOCALS_LENGTH) {
                int start = locals[at + LOCALS_OFFSET_START_BCI];
                int end = locals[at + LOCALS_OFFSET_END_BCI];
                if (start >= end) continue;
                changes.merge(start, 1, Integer::sum);
                changes.merge(end, -1, Integer::sum);
            }
            int[] points = new int[changes.size() * 2];
            int count = 0, used = 0;
            for (var change : changes.entrySet()) {
                if (change.getValue() == 0) continue;
                count += change.getValue();
                points[used++] = change.getKey(); points[used++] = count;
            }
            points = Arrays.copyOf(points, used);
            int entries = handlers.length / EXCEPTION_HANDLER_LENGTH;
            int leaves = 1;
            while (leaves < entries) leaves *= 2;
            int[] ranges = new int[entries == 0 ? 0 : leaves * 4];
            for (int node = 0; node < ranges.length / 2; node++) {
                ranges[node * 2] = Integer.MAX_VALUE;
                ranges[node * 2 + 1] = Integer.MIN_VALUE;
            }
            for (int entry = 0; entry < entries; entry++) {
                int at = entry * EXCEPTION_HANDLER_LENGTH;
                int start = handlers[at + EXCEPTION_HANDLER_OFFSET_START_BCI];
                int end = handlers[at + EXCEPTION_HANDLER_OFFSET_END_BCI];
                if (start >= end) continue;
                ranges[(leaves + entry) * 2] = start;
                ranges[(leaves + entry) * 2 + 1] = end;
            }
            for (int node = leaves - 1; node > 0; node--) {
                ranges[node * 2] = Math.min(ranges[node * 4], ranges[node * 4 + 2]);
                ranges[node * 2 + 1] = Math.max(ranges[node * 4 + 1], ranges[node * 4 + 3]);
            }
            thcMetadata_ = new int[][]{points, ranges};
        }

        @ExplodeLoop
        final int preparedHandler(long bci, int first) {
            int[] ranges = thcMetadata_[1];
            if (ranges.length == 0) return -1;
            int leaves = ranges.length / 4;
            int node = 1;
            while (true) {
                int level = Integer.highestOneBit(node);
                int end = (node - level + 1) * (leaves / level);
                if (end > first && ranges[node * 2] <= bci && bci < ranges[node * 2 + 1]) {
                    if (node >= leaves) return (node - leaves) * EXCEPTION_HANDLER_LENGTH;
                    node *= 2;
                } else {
                    // Next right sibling (possibly of an ancestor), preserving table order.
                    node = (node + 1) >>> Integer.numberOfTrailingZeros(node + 1);
                    if (node == 1) return -1;
                }
            }
        }
        // END THC sparse compilation metadata v1
    """.indent(4);
    private static final String COUNTS = """
        @Override
        @ExplodeLoop
        public final int getLocalCount(int bci) {
            assert validateBytecodeIndex(bci);
            CompilerAsserts.partialEvaluationConstant(bci);
            int count = 0;
            for (int index = 0; index < locals.length; index += LOCALS_LENGTH) {
                int startIndex = locals[index + LOCALS_OFFSET_START_BCI];
                int endIndex = locals[index + LOCALS_OFFSET_END_BCI];
                if (bci >= startIndex && bci < endIndex) {
                    count++;
                }
            }
            CompilerAsserts.partialEvaluationConstant(count);
            return count;
        }
    """.indent(4);
    private static final String COUNT_LOOKUP = """
            int[][] metadata = thcMetadata_;
            if (metadata != null) {
                int[] points = metadata[0];
                int low = 0, high = points.length / 2;
                while (low < high) {
                    int middle = (low + high) >>> 1;
                    if (points[middle * 2] <= bci) low = middle + 1;
                    else high = middle;
                }
                int result = low == 0 ? 0 : points[(low - 1) * 2 + 1];
                CompilerAsserts.partialEvaluationConstant(result);
                return result;
            }
    """.indent(4);
    private static final String PROFILE = """
                int handlerEntryIndex = Math.floorDiv(i, EXCEPTION_HANDLER_LENGTH);
                // THC nonadaptive capture handlers v1
                if (!getRoot().requiresUnprofiledExceptionHandlers() && !this.exceptionProfiles_[handlerEntryIndex]) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    this.exceptionProfiles_[handlerEntryIndex] = true;
                }
    """.indent(4);
    private static final String HANDLERS = """
        @ExplodeLoop
        private int resolveHandler(long bci, int handler, int[] localHandlers) {
            for (int i = handler; i < localHandlers.length; i += EXCEPTION_HANDLER_LENGTH) {
                if (localHandlers[i + EXCEPTION_HANDLER_OFFSET_START_BCI] > bci) {
                    continue;
                }
                if (localHandlers[i + EXCEPTION_HANDLER_OFFSET_END_BCI] <= bci) {
                    continue;
                }
    """.indent(4) + PROFILE + """
                return i;
            }
            return -1;
        }
    """.indent(4);
    private static final String HANDLER_LOOKUP = """
            if (thcMetadata_ != null && localHandlers == this.handlers && handler >= 0 && handler % EXCEPTION_HANDLER_LENGTH == 0) {
                int i = preparedHandler(bci, handler / EXCEPTION_HANDLER_LENGTH);
                CompilerAsserts.partialEvaluationConstant(i);
                if (i == -1) return -1;
    """.indent(4) + PROFILE + """
                return i;
            }
    """.indent(4);
    private static final String OSR = """
        @Override
        public void prepareOSR(long target) {
            // do nothing
        }
    """.indent(4);
    private static final String ROOT_PREPARED = PREPARATION.replace(
        "        return super.prepareForCompilation(rootCompilation, compilationTier, lastTier);",
        "        boolean ready = super.prepareForCompilation(rootCompilation, compilationTier, lastTier);\n"
        + "        if (ready) this.bytecode.prepareMetadataLookup();\n        return ready;");
    private static final String SAVED_PREPARED = CONTINUATION_PREPARATION.replace(
        "            return root.prepareForCompilation(rootCompilation, tier, lastTier);",
        "            boolean ready = root.prepareForCompilation(rootCompilation, tier, lastTier);\n"
        + "            BytecodeLocation saved = location;\n"
        + "            if (ready && saved != null) ((AbstractBytecodeNode) saved.getBytecodeNode()).prepareMetadataLookup();\n"
        + "            return ready;");
    private static final String NEW_COUNTS = COUNTS.replace("            int count = 0;\n", COUNT_LOOKUP + "            int count = 0;\n");
    private static final String NEW_HANDLERS = HANDLERS.replace("            for (int i = handler;", HANDLER_LOOKUP + "            for (int i = handler;");
    private static final String NEW_OSR = OSR.replace("// do nothing", "prepareMetadataLookup();");

    // Restore first when the complete normalization pipeline is reapplied: root/handler
    // preparation passes must still recognize their own unmodified pinned shapes.
    public static String restore(String source, String version) {
        require(VERSION.equals(version), "Review compilation metadata for Truffle " + version);
        String result = unix(source, MESSAGE);
        if (result.contains("THC sparse compilation metadata")) {
            result = replaceOnce(result, FIELD + MEMBERS, FIELD, MESSAGE);
            result = replaceOnce(result, ROOT_PREPARED, PREPARATION, MESSAGE);
            result = replaceOnce(result, SAVED_PREPARED, CONTINUATION_PREPARATION, MESSAGE);
            result = replaceOnce(result, NEW_OSR, OSR, MESSAGE);
            result = replaceOnce(result, NEW_COUNTS, COUNTS, MESSAGE);
            result = replaceOnce(result, NEW_HANDLERS, HANDLERS, MESSAGE);
        }
        require(!result.contains("thcMetadata_") && !result.contains("prepareMetadataLookup"), MESSAGE);
        return newline(source, result);
    }

    public static String transform(String source, String version) {
        String result = unix(restore(source, version), MESSAGE);
        result = replaceOnce(result, FIELD, FIELD + MEMBERS, MESSAGE);
        result = replaceOnce(result, PREPARATION, ROOT_PREPARED, MESSAGE);
        result = replaceOnce(result, CONTINUATION_PREPARATION, SAVED_PREPARED, MESSAGE);
        result = replaceOnce(result, OSR, NEW_OSR, MESSAGE);
        result = replaceOnce(result, COUNTS, NEW_COUNTS, MESSAGE);
        result = replaceOnce(result, HANDLERS, NEW_HANDLERS, MESSAGE);
        return newline(source, result);
    }

    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Changed metadata preparation was accepted");
    }

    public static void check() {
        String before = FIELD + PREPARATION + CONTINUATION_PREPARATION + OSR + COUNTS + HANDLERS;
        String after = transform(before, VERSION);
        require(transform(after, VERSION).equals(after), "Metadata preparation is not idempotent");
        require(restore(after, VERSION).equals(before), "Metadata preparation does not roundtrip");
        require(transform(before.replace("\n", "\r\n"), VERSION).equals(after.replace("\n", "\r\n")), MESSAGE);
        reject(before, "changed-version"); reject(before + before, VERSION);
        reject(before.replace("bci < endIndex", "bci <= endIndex"), VERSION);
        reject(before.replace("[handlerEntryIndex] = true", "[handlerEntryIndex] = false"), VERSION);
        reject(after.replace("saved.getBytecodeNode()", "root.getBytecodeNode()"), VERSION);
        reject(after.replace("volatile int[][]", "int[][]"), VERSION);
        reject(after.replace("prepareMetadataLookup();", "prepareMetadataLookup(1);"), VERSION);
    }
}
