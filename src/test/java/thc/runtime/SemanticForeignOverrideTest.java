// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Lowering must select ownership semantics before generic component linkage. */
class SemanticForeignOverrideTest {
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalDescriptorOwnershipWinsOverGenericComponent(String backend) {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = OriginalStdioFixtures.module(List.of("dup"));
                // This component must never execute: dup operates on THC's managed descriptor table.
                module.put("packageScalarLinks", List.of(new PackageScalarLink("ghc-internal", "unused", "", "", new byte[0],
                    List.of(new PackageScalarSignature("dup", "unused", List.of("Int32Rep"), "Int32Rep")))));
                var program = program(language, module, backend);
                assertEquals(3, callScalarTestTarget(program.entryTarget("dup"), new Object[]{0L, 1, Unit.INSTANCE}));
                assertEquals(0L, Language.currentState().getFiles().close(3));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    @SuppressWarnings("unchecked")
    void unrelatedOwnerDoesNotEnterOriginalSymbolValidator(String backend) {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String symbol : List.of("dup", "malloc", "getenv")) {
                    var module = OriginalStdioFixtures.module(List.of("dup"), call -> {
                        var descriptor = (Map<String, Object>) ((Map<?, ?>) call.get(6)).get("foreignCall");
                        var target = (Map<String, Object>) descriptor.get("target");
                        target.put("unit", "ordinary-user-package"); target.put("symbol", symbol);
                    });
                    var program = program(language, module, backend);
                    var failure = assertThrows(UnsupportedCore.class, () ->
                        callScalarTestTarget(program.entryTarget("dup"), new Object[]{0L, 1, Unit.INSTANCE}));
                    assertEquals("Unsupported foreign call: " + symbol, failure.getMessage());
                }
                assertEquals(3L, Language.currentState().getFiles().duplicate(1), "unlinked user import has no managed descriptor effect");
                assertEquals(0L, Language.currentState().getFiles().close(3));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    @SuppressWarnings("unchecked")
    void selectedOverrideStillRejectsAnInvalidAbiBeforeEffects(String backend) {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = OriginalStdioFixtures.module(List.of("dup"), call -> {
                    var descriptor = (Map<String, Object>) ((Map<?, ?>) call.get(6)).get("foreignCall");
                    descriptor.put("arity", 1L);
                });
                assertThrows(RuntimeFault.class, () -> program(language, module, backend));
                assertEquals(3L, Language.currentState().getFiles().duplicate(1));
                assertEquals(0L, Language.currentState().getFiles().close(3));
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void nativeOnlyConstantRequiresOrdinaryLinkage(String backend) {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = program(language, OriginalStdioFixtures.module(List.of("seek_set")), backend);
                assertThrows(UnsupportedCore.class, () ->
                    callScalarTestTarget(program.entryTarget("seek_set"), new Object[]{0L, Unit.INSTANCE}));
            } finally { context.leave(); }
        }
    }
}
