// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import static thc.buildlogic.BytecodeNormalizers.*;

/** Balance physical root depth for both initial entry and generated continuation entry. */
public final class BytecodeStackPreparation {
    private BytecodeStackPreparation() {}
    // Four provided tags occupy bits 3..6; the pinned DSL places continuation state at bit 7.
    private static final String TAG_ENCODING = "((tags & 0xfL) << 3) | (state.continuationsIndex != 0 ? 0x80L : 0L)";
    private static final String SIGNATURE = "    private Object continueAt(AbstractBytecodeNode bc, long bci, long sp, FrameWithoutBoxing frame, ContinuationRootNodeImpl continuationRootNode) {\n";
    private static final String BODY = SIGNATURE.replace("continueAt(", "continueAtStackBody(");
    private static final String VIRTUAL_OPERANDS = """
                FrameWithoutBoxing targetFrame = parentFrame;
                if (CompilerDirectives.inCompiledCode()) {
                    // Create a fresh virtual frame in compiled code so frame accesses can be optimized.
                    // If this execution deoptimizes, continueAt syncs active stack operands back to parentFrame.
                    // Execution then re-enters the bytecode loop with the materialized frame.
                    FrameWithoutBoxing virtualFrame = (FrameWithoutBoxing) Truffle.getRuntime().createVirtualFrame(parentFrame.getArguments(), root.getFrameDescriptor());
                    if (root.stackBase < sp - 1) {
                        // Restore any stack operands below the resume value.
                        // These operands belong to the interval [stackBase, sp - 1).
                        FRAMES.copyTo(parentFrame, root.stackBase, virtualFrame, root.stackBase, sp - 1 - root.stackBase);
                    }
                    FRAMES.setObject(virtualFrame, CONTINUATION_FRAME_INDEX, parentFrame);
                    targetFrame = virtualFrame;
                }
    """;
    private static final String SAVED_OPERANDS = """
                // THC saved continuation operands v1
                // A yield with live operands copies frame arrays across a catch merge. The pinned
                // compiler can then require the fresh operand frame to escape, which it forbids.
                // Reuse the already materialized saved frame, as interpreter continuation entry does.
                FrameWithoutBoxing targetFrame = parentFrame;
    """;
    private static final String RESERVED_SLOT = "    private static final int CONTINUATION_FRAME_INDEX = 0;\n";
    private static final String USER_SLOTS = "    private static final int USER_LOCALS_START_INDEX = 1;\n";
    // Keep the pinned repair intact, but outside the compiled matching-tag path.
    // A captured frame can outlive a local-tag change in a different activation.
    private static final String RECONCILE = """
        @ExplodeLoop
        private void reconcileContinuationLocals(int bci, FrameWithoutBoxing frame) {
            CompilerAsserts.partialEvaluationConstant(bci);
            int localCount = getLocalCount(bci);
            CompilerAsserts.partialEvaluationConstant(localCount);
            for (int localOffset = 0; localOffset < localCount; localOffset++) {
                int frameIndex = USER_LOCALS_START_INDEX + localOffset;
                byte frameTag = FRAMES.getTag(frame, frameIndex);
                if (frameTag == FrameTags.ILLEGAL) {
                    continue;
                }
                int localIndex = localOffsetToLocalIndex(bci, localOffset);
                byte cachedTag = getCachedLocalTagInternal(this.localTags_, localIndex);
                if (frameTag == cachedTag) {
                    continue;
                }
                if (cachedTag == FrameTags.ILLEGAL) {
                    // Deopt eagerly to reduce compiled code size (setLocalValue will deopt when initializing the cached tag).
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                }
                Object value;
                switch (frameTag) {
                    case FrameTags.INT :
                        value = frame.getInt(frameIndex);
                        break;
                    case FrameTags.LONG :
                        value = frame.getLong(frameIndex);
                        break;
                    case FrameTags.FLOAT :
                        value = frame.getFloat(frameIndex);
                        break;
                    case FrameTags.DOUBLE :
                        value = frame.getDouble(frameIndex);
                        break;
                    case FrameTags.BOOLEAN :
                        value = frame.getBoolean(frameIndex);
                        break;
                    case FrameTags.OBJECT :
                        value = frame.getObject(frameIndex);
                        break;
                    default :
                        throw CompilerDirectives.shouldNotReachHere("Unexpected frame tag.");
                }
                setLocalValueImpl(frame, localOffset, value, bci);
            }
        }
""";
    private static final String COLD_RECONCILE = RECONCILE.replace("""
                if (cachedTag == FrameTags.ILLEGAL) {
                    // Deopt eagerly to reduce compiled code size (setLocalValue will deopt when initializing the cached tag).
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                }
""", """
                // THC stale continuation repair v1: mismatch is an interpreter slow path.
                CompilerDirectives.transferToInterpreterAndInvalidate();
""");
    // SAVED_OPERANDS removes the sole producer of the split-frame pointer. Remove its
    // consumers in the same pinned transform, not the actual stores/clears or slot 0.
    // Reversible markers allow the whole generated-source pipeline to run again.
    private static final String[][] FORWARDING = {
        {"""
            if (CompilerDirectives.inCompiledCode() && (this.configEncoding & 0x80L) != 0 && frame.isObject(CONTINUATION_FRAME_INDEX)) {
                localFrame = (FrameWithoutBoxing) frame.getObject(CONTINUATION_FRAME_INDEX);
            }
""", "            // THC unified frame v1: compiled locals\n"},
        {"""
            if ((this.configEncoding & 0x80L) != 0 && frame.isObject(CONTINUATION_FRAME_INDEX)) {
                localFrame = (FrameWithoutBoxing) frame.getObject(CONTINUATION_FRAME_INDEX);
            }
""", "            // THC unified frame v1: interpreter locals\n"},
        {"""
                FrameWithoutBoxing localFrame = frame.isObject(CONTINUATION_FRAME_INDEX)
                    ? (FrameWithoutBoxing) frame.getObject(CONTINUATION_FRAME_INDEX) : frame;
""", "                // THC unified frame v1: transaction locals\n                FrameWithoutBoxing localFrame = frame;\n"},
        {"""
            MaterializedFrame localFrame = frame;
            if (CompilerDirectives.inCompiledCode() && frame.isObject(CONTINUATION_FRAME_INDEX)) {
                localFrame = (MaterializedFrame) frame.getObject(CONTINUATION_FRAME_INDEX);
                // The yield result will be stored at sp - 1. The operands below it need to be preserved for resumption.
                // These operands belong to the interval [stackBase, sp - 1).
                long stackBase = getRoot().stackBase;
                if (stackBase < sp - 1) {
                    FRAMES.copyTo(frame, stackBase, localFrame, stackBase, sp - 1 - stackBase);
                }
            } else {
                localFrame = frame.materialize();
            }
""", "            // THC unified frame v1: yield\n            MaterializedFrame localFrame = frame.materialize();\n"},
        {"""
            if ((this.configEncoding & 0x80L) != 0 && frame.isObject(CONTINUATION_FRAME_INDEX)) {
                FrameWithoutBoxing localFrame = (FrameWithoutBoxing) frame.getObject(CONTINUATION_FRAME_INDEX);
                result = root.interceptControlFlowException(cfe, localFrame, this, (int) bci);
            } else {
                result = root.interceptControlFlowException(cfe, frame, this, (int) bci);
            }
""", """
            // THC unified frame v1: control flow
            // The cold interceptor may not inline; leave compiled code before forwarding its virtual frame.
            CompilerDirectives.transferToInterpreter();
            result = root.interceptControlFlowException(cfe, frame, this, (int) bci);
"""},
        {"""
            if ((this.configEncoding & 0x80L) != 0 && frame.isObject(CONTINUATION_FRAME_INDEX)) {
                FrameWithoutBoxing localFrame = (FrameWithoutBoxing) frame.getObject(CONTINUATION_FRAME_INDEX);
                for (int localOffset = targetLocalCount; localOffset < originalLocalCount; localOffset++) {
                    FRAMES.clear(localFrame, USER_LOCALS_START_INDEX + localOffset);
                }
            } else {
                for (int localOffset = targetLocalCount; localOffset < originalLocalCount; localOffset++) {
                    FRAMES.clear(frame, USER_LOCALS_START_INDEX + localOffset);
                }
            }
""", """
            // THC unified frame v1: exception locals
            for (int localOffset = targetLocalCount; localOffset < originalLocalCount; localOffset++) {
                FRAMES.clear(frame, USER_LOCALS_START_INDEX + localOffset);
            }
"""},
        {"""
        FrameWithoutBoxing syncToMaterializedFrame(FrameWithoutBoxing frame, int currentSp) {
            if (!frame.isObject(CONTINUATION_FRAME_INDEX)) {
                return frame;
            }
            FrameWithoutBoxing materializedFrame = (FrameWithoutBoxing) frame.getObject(CONTINUATION_FRAME_INDEX);
            if (root.stackBase < currentSp) {
                FRAMES.copyTo(frame, root.stackBase, materializedFrame, root.stackBase, currentSp - root.stackBase);
            }
            return materializedFrame;
        }
""", """
        // THC unified frame v1: resume synchronization
        FrameWithoutBoxing syncToMaterializedFrame(FrameWithoutBoxing frame, int currentSp) {
            return frame;
        }
"""}
    };
    private static final String WRAPPER = """
            // THC balanced bytecode stack entry v1
            private Object continueAt(AbstractBytecodeNode bc, long bci, long sp, FrameWithoutBoxing frame, ContinuationRootNodeImpl continuationRootNode) {
                if (!isAsyncEnabled()) return continueAtStackBody(bc, bci, sp, frame, continuationRootNode);
                thc.runtime.AstStackScope scope = thc.runtime.AstStacks.astStackScope(this);
                boolean driver = !scope.getDriving();
                if (driver) scope.setDriving(true);
                boolean resumed = continuationRootNode != null;
                thc.runtime.ManagedSTM.Transaction ambient = null;
                boolean restored = false;
                try {
                    if (resumed) {
                        // Resolve the logical saved locals without changing stack or transaction ownership.
                        FrameWithoutBoxing localFrame = frame.isObject(CONTINUATION_FRAME_INDEX)
                            ? (FrameWithoutBoxing) frame.getObject(CONTINUATION_FRAME_INDEX) : frame;
                        ambient = resumeStackTransaction(localFrame); restored = true;
                    }
                    Object result;
                    scope.setDepth(scope.getDepth() + 1);
                    try { result = continueAtStackBody(bc, bci, sp, frame, continuationRootNode); }
                    finally { scope.setDepth(scope.getDepth() - 1); }
                    return driver ? finishStackEntry(result) : result;
                } finally {
                    if (restored) restoreStackTransaction(ambient);
                    if (driver) scope.setDriving(false);
                }
            }

        """;

    public static String transform(String source, String version) {
        require(VERSION.equals(version), "Review bytecode stack entry for Truffle " + version);
        String result = unix(source, "Mixed generated source newlines");
        require(result.contains(TAG_ENCODING), "Changed four-tag continuation configuration encoding");
        if (result.contains("THC stale continuation repair")) {
            result = replaceOnce(result, COLD_RECONCILE, RECONCILE, "Changed stale continuation repair");
        }
        for (String[] pair : FORWARDING) result = result.replace(pair[1], pair[0]);
        require(!result.contains("THC unified frame"), "Changed unified frame preparation");
        if (result.contains("THC saved continuation operands")) {
            result = replaceOnce(result, SAVED_OPERANDS, VIRTUAL_OPERANDS, "Changed saved continuation operands");
        }
        if (result.contains("THC balanced bytecode stack entry")) {
            result = replaceOnce(result, WRAPPER + BODY, SIGNATURE, "Changed bytecode stack wrapper");
        } else require(!result.contains("continueAtStackBody"), "Unexpected bytecode stack helper");
        require(result.contains("return continueAt(bytecode, 0, stackBase, (FrameWithoutBoxing) frame, null);"),
            "Changed initial bytecode root entry");
        require(result.contains("root.continueAt(bytecodeNode,"), "Changed bytecode continuation entry");
        require(result.contains("FRAMES.setObject(virtualFrame, CONTINUATION_FRAME_INDEX, parentFrame);"),
            "Changed bytecode continuation parent frame");
        result = replaceOnce(result, SIGNATURE, WRAPPER + BODY, "Changed bytecode continueAt entry");
        result = replaceOnce(result, VIRTUAL_OPERANDS, SAVED_OPERANDS, "Changed continuation operand frame");
        for (String[] pair : FORWARDING) {
            require(result.contains(pair[0]), "Changed split-frame consumer: " + pair[1].strip());
            result = result.replace(pair[0], pair[1]);
        }
        String consumers = replaceOnce(result, RESERVED_SLOT, "", "Changed reserved continuation slot");
        consumers = replaceOnce(consumers, USER_SLOTS, "", "Changed user local offset");
        require(!consumers.contains("CONTINUATION_FRAME_INDEX"), "Unknown split-frame producer or consumer");
        result = replaceOnce(result, RECONCILE, COLD_RECONCILE, "Changed continuation local reconciliation");
        return newline(source, result);
    }

    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed stack entry was accepted");
    }

    public static void check() {
        String before = "class Entry {\n"
            + "    long encoding = " + TAG_ENCODING + ";\n"
            + RESERVED_SLOT + USER_SLOTS
            + "    Object initial() { return continueAt(bytecode, 0, stackBase, (FrameWithoutBoxing) frame, null); }\n"
            + "    Object resume() { return root.continueAt(bytecodeNode, index, sp, frame, this); }\n"
            + "    void savedLocals() {\n" + VIRTUAL_OPERANDS + "    }\n"
            + SIGNATURE + "        return existingBody();\n    }\n"
            + RECONCILE
            + java.util.Arrays.stream(FORWARDING).filter(p -> !p[0].contains("? (FrameWithoutBoxing)"))
                .map(p -> p[0]).collect(java.util.stream.Collectors.joining()) + "}\n";
        String after = transform(before, VERSION);
        reject(before.replace(TAG_ENCODING, TAG_ENCODING.replace("0xfL", "0x7L")), VERSION);
        String controlFlowCall = "            result = root.interceptControlFlowException(cfe, frame, this, (int) bci);\n";
        String coldControlFlow = "            CompilerDirectives.transferToInterpreter();\n" + controlFlowCall;
        require(after.contains(coldControlFlow), "Missing interpreter transfer before control-flow frame forwarding");
        reject(after.replace(coldControlFlow, controlFlowCall), VERSION);
        reject(after.replace(coldControlFlow, controlFlowCall + "            CompilerDirectives.transferToInterpreter();\n"), VERSION);
        require(transform(after, VERSION).equals(after), "Stack entry preparation is not idempotent");
        require(transform(before.replace("\n", "\r\n"), VERSION).equals(after.replace("\n", "\r\n")),
            "Stack entry preparation changed newlines");
        reject(before, "changed-version");
        reject(before + before, VERSION);
        reject(before.replace("root.continueAt(bytecodeNode,", "root.other(bytecodeNode,"), VERSION);
        reject(before.replace("CONTINUATION_FRAME_INDEX, parentFrame", "CONTINUATION_FRAME_INDEX, otherFrame"), VERSION);
        reject(before.replace("continueAt(bytecode, 0,", "continueAt(bytecode, 1,"), VERSION);
        reject(after.replace("scope.getDepth() - 1", "scope.getDepth()"), VERSION);
        reject(after.replace("if (driver) scope.setDriving(false)", "if (true) scope.setDriving(false)"), VERSION);
        reject(after.replace("if (restored) restoreStackTransaction(ambient)", "restoreStackTransaction(null)"), VERSION);
        reject(before.replace("sp - 1 - root.stackBase", "sp - root.stackBase"), VERSION);
        reject(after.replace("targetFrame = parentFrame", "targetFrame = otherFrame"), VERSION);
        reject(after + VIRTUAL_OPERANDS, VERSION);
        reject(before + "FRAMES.setObject(frame, CONTINUATION_FRAME_INDEX, parentFrame);", VERSION);
        reject(before + "frame.getObject(CONTINUATION_FRAME_INDEX);", VERSION);
        reject(before.replace(RESERVED_SLOT, RESERVED_SLOT.replace("= 0", "= 1")), VERSION);
        reject(before.replace(USER_SLOTS, USER_SLOTS.replace("= 1", "= 0")), VERSION);
        for (String[] pair : FORWARDING) {
            reject(after.replace(pair[1], pair[1].replace("THC unified frame v1", "THC unified frame changed")), VERSION);
        }
        require(after.contains("FRAMES.clear(frame, USER_LOCALS_START_INDEX + localOffset)"), "Lost local cleanup");
        require(after.contains("frame.materialize()"), "Lost yield materialization");
        require(!RECONCILE.equals(COLD_RECONCILE), "Missing stale-frame slow path");
        reject(before.replace("if (frameTag == cachedTag)", "if (frameTag != cachedTag)"), VERSION);
        reject(before.replace("if (frameTag == FrameTags.ILLEGAL)", "if (false)"), VERSION);
        reject(before.replace("setLocalValueImpl(frame, localOffset, value, bci)", "setLocalValueImpl(frame, 0, value, bci)"), VERSION);
        for (String kind : new String[]{"Int", "Long", "Float", "Double", "Boolean", "Object"}) {
            reject(before.replace("frame.get" + kind + "(frameIndex)", "frame.get" + kind + "(0)"), VERSION);
        }
        reject(after.replace("THC stale continuation repair v1", "THC stale continuation repair changed"), VERSION);
        reject(after.replace(COLD_RECONCILE, COLD_RECONCILE.replace(
            "CompilerDirectives.transferToInterpreterAndInvalidate();", "")), VERSION);
    }
}
