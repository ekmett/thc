// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import static thc.buildlogic.BytecodeNormalizers.*;

/** Balance physical root depth for both initial entry and generated continuation entry. */
public final class BytecodeStackPreparation {
    private BytecodeStackPreparation() {}
    private static final String SIGNATURE = "    private Object continueAt(AbstractBytecodeNode bc, long bci, long sp, FrameWithoutBoxing frame, ContinuationRootNodeImpl continuationRootNode) {\n";
    private static final String BODY = SIGNATURE.replace("continueAt(", "continueAtStackBody(");
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
                    if (resumed) { ambient = resumeStackTransaction(frame); restored = true; }
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
        if (result.contains("THC balanced bytecode stack entry")) {
            result = replaceOnce(result, WRAPPER + BODY, SIGNATURE, "Changed bytecode stack wrapper");
        } else require(!result.contains("continueAtStackBody"), "Unexpected bytecode stack helper");
        require(result.contains("return continueAt(bytecode, 0, stackBase, (FrameWithoutBoxing) frame, null);"),
            "Changed initial bytecode root entry");
        require(result.contains("root.continueAt(bytecodeNode,"), "Changed bytecode continuation entry");
        result = replaceOnce(result, SIGNATURE, WRAPPER + BODY, "Changed bytecode continueAt entry");
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
            + SIGNATURE + "        return existingBody();\n    }\n}\n";
        String after = transform(before, VERSION);
        require(transform(after, VERSION).equals(after), "Stack entry preparation is not idempotent");
        require(transform(before.replace("\n", "\r\n"), VERSION).equals(after.replace("\n", "\r\n")),
            "Stack entry preparation changed newlines");
        reject(before, "changed-version");
        reject(before + before, VERSION);
        reject(before.replace("root.continueAt(bytecodeNode,", "root.other(bytecodeNode,"), VERSION);
        reject(before.replace("continueAt(bytecode, 0,", "continueAt(bytecode, 1,"), VERSION);
        reject(after.replace("scope.getDepth() - 1", "scope.getDepth()"), VERSION);
        reject(after.replace("if (driver) scope.setDriving(false)", "if (true) scope.setDriving(false)"), VERSION);
        reject(after.replace("if (restored) restoreStackTransaction(ambient)", "restoreStackTransaction(null)"), VERSION);
    }
}
