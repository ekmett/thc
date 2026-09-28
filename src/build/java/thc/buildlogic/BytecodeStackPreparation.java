// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import static thc.buildlogic.BytecodeNormalizers.*;

/** Balance physical root depth for both initial entry and generated continuation entry. */
public final class BytecodeStackPreparation {
    private BytecodeStackPreparation() {}
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
        return newline(source, result);
    }

    private static void reject(String source, String version) {
        try { transform(source, version); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed stack entry was accepted");
    }

    public static void check() {
        String before = "class Entry {\n"
            + "    Object initial() { return continueAt(bytecode, 0, stackBase, (FrameWithoutBoxing) frame, null); }\n"
            + "    Object resume() { return root.continueAt(bytecodeNode, index, sp, frame, this); }\n"
            + "    void savedLocals() {\n" + VIRTUAL_OPERANDS + "    }\n"
            + SIGNATURE + "        return existingBody();\n    }\n}\n";
        String after = transform(before, VERSION);
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
    }
}
