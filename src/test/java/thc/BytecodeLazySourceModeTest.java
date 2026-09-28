// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.source.Source;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.runtime.BytecodeRoot;
import thc.runtime.BytecodeRootGen;
import thc.runtime.Calls;
import static org.junit.jupiter.api.Assertions.*;

/** The source-mode query must precede construction of every lazy debug argument. */
class BytecodeLazySourceModeTest {
    private record Instruction(String name, List<String> arguments) {}
    private List<Instruction> instructions(BytecodeRoot root) {
        var result = new ArrayList<Instruction>();
        for (var instruction : root.getBytecodeNode().getInstructions()) {
            var arguments = new ArrayList<String>();
            for (var argument : instruction.getArguments()) arguments.add(argument.toString());
            result.add(new Instruction(instruction.getName(), arguments));
        }
        return result;
    }
    @Test void defaultConstructionIsColdAndExplicitSourceReplayPreservesCodeAndIdentity() {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var origin = new Object(); int[] debugReads = {0}; var replayOrigins = new ArrayList<Object>();
                var nodes = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    replayOrigins.add(origin);
                    if (b.isParsingSources()) {
                        debugReads[0]++;
                        b.beginSource(Source.newBuilder("thc", "answer = 42\n", "Lazy.Source.hs").build());
                        b.beginSourceSection(0, 11);
                    }
                    b.beginRoot(); b.beginReturn(); b.emitLoadArgument(0); b.endReturn(); b.endRoot();
                    if (b.isParsingSources()) { b.endSourceSection(); b.endSource(); }
                });
                var root = nodes.getNode(0); var target = root.getCallTarget(); var before = instructions(root);
                assertEquals(0, debugReads[0]); assertFalse(root.getBytecodeNode().hasSourceInformation()); assertNull(root.getSourceSection());
                assertEquals(42L, Calls.target(target, new Object[]{42L}));
                assertEquals(0, debugReads[0], "Ordinary execution must not resolve source arguments");
                var executed = instructions(root);
                assertEquals(before.stream().map(i -> i.name().split("\\$", 2)[0]).toList(), executed.stream().map(i -> i.name().split("\\$", 2)[0]).toList());
                root.getBytecodeNode().ensureSourceInformation(); assertEquals(1, debugReads[0]); assertEquals(2, replayOrigins.size());
                assertTrue(replayOrigins.stream().allMatch(value -> value == origin));
                assertSame(root, nodes.getNode(0)); assertSame(target, root.getCallTarget());
                assertEquals(executed, instructions(root), "Source replay must preserve instructions and arguments");
                assertEquals("Lazy.Source.hs", root.getSourceSection().getSource().getName());
                assertEquals("answer = 42", root.getSourceSection().getCharacters().toString());
                root.getBytecodeNode().ensureSourceInformation(); assertEquals(1, debugReads[0], "Repeated source access must not reparse");
                assertEquals(77L, Calls.target(target, new Object[]{77L}));
            } finally { context.leave(); }
        }
    }
}
