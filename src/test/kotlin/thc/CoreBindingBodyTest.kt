// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreBindingBodyTest {
    @Test fun headerFactsDoNotDecodeAndConcurrentDemandPublishesOneBody() {
        val formals = listOf(mapOf("id" to "x", "name" to "x", "coercion" to false))
        val metadata = mapOf("entryStrict" to listOf(false), "resultRep" to mapOf("kind" to "long"))
        val fields = linkedMapOf<Int, Any?>(0 to "lam", 1 to formals, 3 to metadata)
        val header = CoreBindingBody.Header(4, fields, false)
        fields[0] = "wrong"
        val decoded = listOf("lam", formals, listOf("var", "x"), metadata)
        val body = CoreBindingBody(header) { decoded }
        assertEquals("lam", body[0])
        assertSame(formals, body[1])
        assertSame(metadata, body[3])
        assertEquals(0, body.decodeAttempts())
        val pool = Executors.newFixedThreadPool(4)
        try {
            val copies = pool.invokeAll(List(16) { java.util.concurrent.Callable { body.materialize() } })
            for (copy in copies) assertSame(copies.first().get(), copy.get())
            assertSame(decoded[2], body[2])
        } finally { pool.shutdown() }
        assertEquals(1, body.decodeAttempts())
        assertTrue(body.isMaterialized())
    }

    @Test fun malformedOrClosedSourcesPublishStableFailuresButCancellationDoesNot() {
        val header = CoreBindingBody.Header(2, mapOf(0 to "void"), false)
        val malformed = CoreBindingBody(header) { listOf("var", "x") }
        val bad = assertThrows(IllegalArgumentException::class.java) { malformed.materialize() }
        assertSame(bad, assertThrows(IllegalArgumentException::class.java) { malformed.materialize() })
        assertEquals(1, malformed.decodeAttempts())
        val closed = CoreBindingBody(header) { throw IllegalStateException("source owner is closed") }
        val failure = assertThrows(IllegalStateException::class.java) { closed[1] }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { closed[1] })
        assertEquals("void", closed[0])
        assertEquals(1, closed.decodeAttempts())
        var cancel = true
        val cancellable = CoreBindingBody(header) {
            if (cancel) throw CancellationException("test cancellation")
            listOf("void", null)
        }
        assertThrows(CancellationException::class.java) { cancellable.materialize() }
        cancel = false
        assertEquals(listOf("void", null), cancellable.materialize())
        assertEquals(2, cancellable.decodeAttempts())
    }
}
