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
}
