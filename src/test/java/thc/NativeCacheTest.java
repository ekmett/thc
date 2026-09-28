// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

class NativeCacheTest {
    @TempDir Path directory;

    @Test void publicCachedSourceIdentityAndDynamicArgumentsUseFreshPrograms() throws Exception {
        String module = """
            {"schema":1,"ghc":"9.14.1","module":"CacheTest","constructors":[],"bindings":[
              {"id":"plus","name":"plus","arity":2,"lifted":true,
               "rep":{"kind":"closure","evaluated":true,"primReps":["BoxedRep (Just Lifted)"]},
               "expr":["lam",[
                 {"id":"x","name":"x","type":"Int#","lifted":false,"coercion":false,"rep":{"kind":"long","evaluated":true,"primReps":["IntRep"]}},
                 {"id":"y","name":"y","type":"Int#","lifted":false,"coercion":false,"rep":{"kind":"long","evaluated":true,"primReps":["IntRep"]}}],
                 ["app",["prim","+#"],[["var","x"],["var","y"]],[false,false]],
                 {"rep":{"kind":"closure","evaluated":true,"primReps":["BoxedRep (Just Lifted)"]},
                  "resultRep":{"kind":"long","evaluated":true,"primReps":["IntRep"]}}]}]}
            """;
        Path file = directory.resolve("module.json"); Files.writeString(file, module);
        String request = NativeCache.request(List.of(file.toString()), "plus");
        Map<?, ?> document = (Map<?, ?>) Json.parse(request);
        assertEquals(true, document.get("prepareCode"));
        assertEquals(false, document.get("asyncExceptions"));
        assertEquals("ast", document.get("backend"));
        Source source = Source.newBuilder("thc", request, NativeCache.sourceName(request)).cached(true).buildLiteral();
        try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            assertThrows(IllegalStateException.class, () -> NativeCache.selectedSource(engine));
            try (Context preparation = Context.newBuilder("thc").engine(engine).build()) { preparation.parse(source); }
            assertEquals(source, NativeCache.selectedSource(engine));
            for (int i = 0; i < 2; i++) {
                try (Context context = Context.newBuilder("thc").engine(engine).build()) {
                    var first = context.parse(NativeCache.selectedSource(engine)).execute();
                    var second = context.parse(NativeCache.selectedSource(engine)).execute();
                    assertEquals(47L, first.execute(40L, 7L).asLong());
                    assertEquals(-8L, second.execute(3L, -11L).asLong());
                    assertEquals(Long.MIN_VALUE, first.execute(Long.MAX_VALUE, 1L).asLong());
                    assertEquals(0L, ((Map<?, ?>) Json.parse(first.getMember("diagnostics").asString())).get("loweredRootCount"));
                }
            }
            // A second selected entry/source must not silently select an arbitrary cache member.
            String secondRequest = request + " ";
            try (Context context = Context.newBuilder("thc").engine(engine).build()) {
                context.parse(Source.newBuilder("thc", secondRequest, NativeCache.sourceName(secondRequest)).cached(true).buildLiteral());
                assertThrows(IllegalStateException.class, () -> NativeCache.selectedSource(engine));
            }
        }
    }

    @Test void invalidSelectionAndArgumentsFailBeforeProviderUse() {
        assertThrows(IllegalArgumentException.class, () -> NativeCache.request(List.of(), "plus"));
        assertThrows(IllegalArgumentException.class, () -> NativeCache.request(List.of(""), "plus"));
        assertThrows(IllegalArgumentException.class, () -> NativeCache.main(new String[]{"store", "cache"}));
        assertNotEquals(NativeCache.sourceName("a"), NativeCache.sourceName("b"));
    }

    @Test void commandArgumentsPreserveExplicitNumericCarriers() {
        assertEquals(Long.MIN_VALUE, NativeCache.argument("-9223372036854775808"));
        assertEquals(16777216f, NativeCache.argument("f:16777217"));
        assertEquals(16777217d, NativeCache.argument("d:16777217"));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits((Float)NativeCache.argument("f:-0.0")));
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits((Double)NativeCache.argument("d:-0.0")));
        assertEquals(Float.POSITIVE_INFINITY, NativeCache.argument("f:Infinity"));
        assertTrue(Double.isNaN((Double)NativeCache.argument("d:NaN")));
        for (String value : List.of("f:", "d:bad", "1.5", "9223372036854775808"))
            assertThrows(NumberFormatException.class, () -> NativeCache.argument(value));
    }

    @Test void detachedCompactFloatingLiteralsKeepRawBitsInBothReaders() {
        var values = List.of(new thc.runtime.CoreFloatingLiteral.Single(0x80000000),
            new thc.runtime.CoreFloatingLiteral.Single(0x7fc01234), new thc.runtime.CoreFloatingLiteral.Single(1),
            new thc.runtime.CoreFloatingLiteral.Double(0x8000000000000000L),
            new thc.runtime.CoreFloatingLiteral.Double(0x7ff8000000005678L), new thc.runtime.CoreFloatingLiteral.Double(1));
        for (String backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc")
                .allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            for (var value : values) {
                boolean single = value instanceof thc.runtime.CoreFloatingLiteral.Single;
                var proof = map("kind", single ? "float" : "double", "evaluated", true,
                    "primReps", list(single ? "FloatRep" : "DoubleRep"));
                var binding = map("id", "value", "name", "value", "arity", 0, "lifted", false, "rep", proof,
                    "expr", list("lit", single ? "float" : "double", value, map("rep", proof)));
                var request = map("modules", list(map("schema", 1, "ghc", "9.14.1", "module", "DetachedFloating",
                    "bindings", list(binding), "constructors", list())),
                    "entry", "value", "backend", backend, "asyncExceptions", false, "prepareCode", backend.equals("ast"));
                var entry = context.eval("thc", CoreModules.detachedRequest(request, "value"));
                if (value instanceof thc.runtime.CoreFloatingLiteral.Single expected)
                    assertEquals(expected.bits(), Float.floatToRawIntBits(entry.execute().asFloat()));
                else assertEquals(((thc.runtime.CoreFloatingLiteral.Double)value).bits(),
                    Double.doubleToRawLongBits(entry.execute().asDouble()));
            }
        }
    }
}
