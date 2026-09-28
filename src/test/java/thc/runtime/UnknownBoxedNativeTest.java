// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Native GHC permits unknown boxed levity in tail-return positions. */
class UnknownBoxedNativeTest {
    @SuppressWarnings("unchecked")
    @Test void polymorphicReturnsTransportLiftedAndUnliftedInstantiations() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"));
        for (var stage : List.of("pre", "post")) {
            var source = (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/aggregate-layout/" + stage + "-core/AggregateLayoutAudit.json")));
            for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var entry : List.of("boxedThroughUse", "boxedTupleThroughUse", "boxedTupleUnliftedThroughUse")) {
                        var module = CoreModules.reachable(source, entry, true);
                        module.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                        var target = program.entryTarget(entry);
                        for (int phase = 0; phase < 2; phase++) {
                            if (phase == 1) {
                                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            }
                            for (long input : new long[]{Long.MIN_VALUE, -4097, 0, 4097, Long.MAX_VALUE}) {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                assertEquals(input, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(entry), new Object[]{input}}), stage + "/" + backend + "/" + entry);
                                if (phase == 1) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                            }
                            if (phase == 1) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        }
                    }
                } finally { context.leave(); }
            }
        }
    }
}
