// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreBindingBodyTest {
    @Test void headerFactsDoNotDecodeAndConcurrentDemandPublishesOneBody() throws Exception {
        var formals = List.of(Map.of("id", "x", "name", "x", "coercion", false));
        var metadata = Map.of("entryStrict", List.of(false), "resultRep", Map.of("kind", "long"));
        var fields = new LinkedHashMap<Integer, Object>();
        fields.put(0, "lam"); fields.put(1, formals); fields.put(3, metadata);
        var header = new CoreBindingBody.Header(4, fields, false);
        fields.put(0, "wrong");
        List<Object> decoded = List.of("lam", formals, List.of("var", "x"), metadata);
        var body = new CoreBindingBody(header, null, () -> decoded);
        assertEquals("lam", body.get(0));
        assertSame(formals, body.get(1));
        assertSame(metadata, body.get(3));
        assertEquals(0, body.decodeAttempts());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Callable<List<Object>>>();
            for (int i = 0; i < 16; i++) jobs.add(body::materialize);
            var copies = pool.invokeAll(jobs);
            for (var copy : copies) assertSame(copies.getFirst().get(), copy.get());
            assertSame(decoded.get(2), body.get(2));
        } finally { pool.shutdown(); }
        assertEquals(1, body.decodeAttempts());
        assertTrue(body.isMaterialized());
    }
    @Test void malformedOrClosedSourcesPublishStableFailuresButCancellationDoesNot() {
        var header = new CoreBindingBody.Header(2, Map.of(0, "void"), false);
        var malformed = new CoreBindingBody(header, null, () -> List.of("var", "x"));
        var bad = assertThrows(IllegalArgumentException.class, malformed::materialize);
        assertSame(bad, assertThrows(IllegalArgumentException.class, malformed::materialize));
        assertEquals(1, malformed.decodeAttempts());
        var closed = new CoreBindingBody(header, null, () -> { throw new IllegalStateException("source owner is closed"); });
        var failure = assertThrows(IllegalStateException.class, () -> closed.get(1));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> closed.get(1)));
        assertEquals("void", closed.get(0));
        assertEquals(1, closed.decodeAttempts());
        var cancel = new AtomicBoolean(true);
        var cancellable = new CoreBindingBody(header, null, () -> {
            if (cancel.get()) throw new CancellationException("test cancellation");
            return Arrays.asList("void", null);
        });
        assertThrows(CancellationException.class, cancellable::materialize);
        cancel.set(false);
        assertEquals(Arrays.asList("void", null), cancellable.materialize());
        assertEquals(2, cancellable.decodeAttempts());
    }
}
