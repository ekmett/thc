// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Path;
import java.util.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelfTailProfileTest {
    private final Path project = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> modules = List.of("THC.Prim.Test", "Fixtures").stream()
        .map(name -> project.resolve("build/core/" + name + ".cbd").toString()).toList();
    private long count(Value function, String name) {
        return ((Number) ((Map<?, ?>) Json.parse(function.getMember("diagnostics").asString())).get(name)).longValue();
    }
    private void compileBaseCase(Value function, long result) {
        for (int i = 0; i < 40; i++) assertEquals(result, function.execute(0L).asLong());
        assertTrue(function.invokeMember("compile").asBoolean());
        long before = count(function, "compiledEntries");
        assertEquals(result, function.execute(0L).asLong());
        assertTrue(count(function, "compiledEntries") > before, "Base case must execute installed code");
    }
    @Test void firstSelfTailAfterCompiledBaseCaseRemainsStackSafeAndRecompiles() {
        try (var context = Main.executionContext(false)) {
            var function = Main.loadEntry(context, modules, "main:Fixtures.sumLoop");
            compileBaseCase(function, 0L);
            long reentries = count(function, "selfTailReentries"), bounces = count(function, "tailBounces");
            assertEquals(5_000_050_000L, function.execute(100_000L).asLong());
            assertTrue(count(function, "selfTailReentries") - reentries >= 100_000L);
            assertEquals(bounces, count(function, "tailBounces"), "Direct self entry needs no tail packet");
            assertTrue(function.invokeMember("compile").asBoolean());
            long compiled = count(function, "compiledEntries");
            assertEquals(55L, function.execute(10L).asLong());
            assertEquals(0L, function.execute(0L).asLong());
            assertTrue(count(function, "compiledEntries") > compiled);
        }
    }
    @Test void firstSelfTailAfterCompiledBaseCaseRestoresChangingCaptures() {
        try (var context = Main.executionContext(false)) {
            var function = Main.loadEntry(context, modules, "main:Fixtures.capturedChangingEnv");
            compileBaseCase(function, 17L);
            long reentries = count(function, "selfTailReentries"), bounces = count(function, "tailBounces");
            assertEquals(100_017L, function.execute(100_000L).asLong());
            assertTrue(count(function, "selfTailReentries") - reentries >= 100_000L);
            assertEquals(bounces, count(function, "tailBounces"), "Direct self entry needs no tail packet");
            assertTrue(function.invokeMember("compile").asBoolean());
            long compiled = count(function, "compiledEntries");
            assertEquals(24L, function.execute(7L).asLong());
            assertEquals(17L, function.execute(0L).asLong());
            assertEquals(10_017L, function.execute(10_000L).asLong());
            assertTrue(count(function, "compiledEntries") > compiled);
            assertEquals(0L, count(function, "blackholes"));
        }
    }
}
