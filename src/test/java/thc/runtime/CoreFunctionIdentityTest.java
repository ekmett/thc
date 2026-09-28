// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class CoreFunctionIdentityTest {
    private Map<String, Object> module(String unit) {
        Map<String, Object> argument = Map.of("id", "x", "name", "x", "lifted", false);
        var entry = unit + ":Shared.entry";
        return Map.of("schema", 1, "ghc", "9.14.1", "unit", unit, "module", "Shared",
            "bindings", List.of(
                Map.of("id", entry, "name", "entry", "lifted", true, "arity", 1,
                    "expr", List.of("lam", List.of(argument), List.of("var", "x"))),
                Map.of("id", unit + ":Shared.$wentry_r1", "name", "$wentry", "lifted", true,
                    "arity", 1, "expr", List.of("lam", List.of(argument), List.of("var", "x"))),
                Map.of("id", unit + ":Shared.alias", "name", "alias", "lifted", true,
                    "expr", List.of("var", entry))),
            "constructors", List.of());
    }
    @Test void rootsRetainExactGhcOwnerAcrossMergedUnitsWithoutSourcePaths() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var merged = CoreModules.merge(List.of(module("pkg-a"), module("pkg-b")));
                for (ExecutableProgram program : List.of(new Program(language, merged), new BytecodeProgram(language, merged))) {
                    for (var unit : List.of("pkg-a", "pkg-b")) {
                        var entry = program.entryTarget(unit + ":Shared.entry");
                        assertEquals("lambda x", entry.getRootNode().getName());
                        assertEquals(new CoreFunctionIdentity(unit + ":Shared.entry", unit, "Shared", "entry"),
                            ((GuestRoot) entry.getRootNode()).getCoreIdentity());
                        var alias = program.entryTarget(unit + ":Shared.alias");
                        if (alias != entry) assertNull(((GuestRoot) alias.getRootNode()).getCoreIdentity());
                        assertEquals(unit + ":Shared.entry", ((GuestRoot) entry.getRootNode()).getCoreIdentity().getBindingId(),
                            "An alias must not claim another binding's target");
                        var worker = program.entryTarget(unit + ":Shared.$wentry_r1");
                        assertEquals("$wentry_r1", ((GuestRoot) worker.getRootNode()).getCoreIdentity().getOccurrence(),
                            "Post-Tidy local worker IDs retain their unique suffix");
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void interfaceFragmentsAndSyntheticMainDoNotInventOwners() {
        var module = module("pkg-a");
        var entry = (Map<String, Object>) ((List<?>) module.get("bindings")).getFirst();
        var fragment = new LinkedHashMap<>(module); fragment.put("unit", "dependency-closure"); fragment.put("module", "THC.InterfaceClosure");
        assertNull(CoreFunctionIdentity.from(fragment, entry));
        var main = new LinkedHashMap<>(entry); main.put("id", "main::Main.main");
        assertNull(CoreFunctionIdentity.from(module, main));
    }
}
