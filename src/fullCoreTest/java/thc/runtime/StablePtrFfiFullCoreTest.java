// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.CoreModules;
import thc.ContextProfile;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.Main.withContextProfile;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
public class StablePtrFfiFullCoreTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/stableptr-ffi";
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath(), StandardCharsets.UTF_8));
    }
    private Map<String, Object> diagnostics(Value function) { return (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString()); }
    private void check(Context context, Value function, String backend, String name, Map<String, String> row) {
        long input = Long.parseLong(row.get("argument")), expected = Long.parseLong(row.get("result"));
        assertEquals(input + (name.equals("stableRoundtrip") ? 35 : 1), expected);
        assertEquals(expected, function.execute(input).asLong(), backend + "/" + name + "/" + input);
        context.enter();
        try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var state = language.getHandoffState().get();
            assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
            assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
        } finally { context.leave(); }
    }
    @Test public void ordinaryForeignStablePtrMatchesNativeWithCcallAndCapiInBothBackends() throws Exception {
        var manifest = json(prefix + "/manifest.json");
        assertEquals(1L, manifest.get("schema")); assertEquals(true, manifest.get("strictAccepted"));
        assertEquals(true, manifest.get("runtimeVerified")); assertEquals(12L, manifest.get("nativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of(
            "test/fixtures/run-stableptr-ffi/src/StableForeign.hs", "test/fixtures/run-stableptr-ffi/cbits/stable.c",
            "test/fixtures/run-stableptr-ffi/cbits/stable.h", "test/haskell-fixtures/StablePtrFFIFixtures.hs"), null);
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), Set.of(
            prefix + "/packages.json", prefix + "/audit.json", prefix + "/logs/native-run.stdout"), prefix + "/");
        var rows = (List<Map<String, String>>) manifest.get("observations");
        var names = new LinkedHashSet<String>(); var lines = new ArrayList<String>();
        for (var row : rows) {
            names.add(row.get("entry")); lines.add(row.get("entry") + "\t" + row.get("argument") + "\t" + row.get("result"));
        }
        assertEquals(Set.of("stableRoundtrip", "stableLazy"), names);
        assertEquals(Files.readAllLines(new File(root, prefix + "/logs/native-run.stdout").toPath(), StandardCharsets.UTF_8), lines);
        assertEquals(12, rows.size());
        for (String backend : List.of("ast", "bytecode")) for (String name : List.of("stableRoundtrip", "stableLazy"))
            try (Context context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
                String entry = manifest.get("unit") + ":StableForeign." + name;
                Value function = context.eval("thc", CoreModules.request(List.of("@" + new File(root, prefix + "/packages.json").getPath()), entry, true, false, backend));
                var selected = new ArrayList<Map<String, String>>();
                for (var row : rows) if (name.equals(row.get("entry"))) selected.add(row);
                for (var row : selected) check(context, function, backend, name, row);
                assertTrue(function.invokeMember("compile").asBoolean());
                for (var row : selected.reversed()) {
                    long before = (Long) diagnostics(function).get("compiledEntries");
                    check(context, function, backend, name, row);
                    assertTrue((Long) diagnostics(function).get("compiledEntries") > before, backend + "/" + name + " entered compiled guest code");
                }
                assertEquals(0L, diagnostics(function).get("unsupportedTraps"));
                System.out.println("StablePtrFFI PASS " + backend + "/" + name + " nativeRows=" + selected.size() + " compiled=true");
            }
    }
}
