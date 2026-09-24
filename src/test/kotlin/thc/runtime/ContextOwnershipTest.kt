// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import thc.Language
import java.util.concurrent.atomic.AtomicReference

class ContextOwnershipTest {
    @Test fun handoffLayoutsAndThreadUpgradeAreOwnedByEachContext() {
        Engine.create().use { engine ->
            fun capture(context: Context): Pair<Language.State, HandoffLayout> {
                context.initialize("thc")
                context.enter()
                return try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val state = Language.currentState(null)
                    assertSame(state.handoffLayouts, language.handoffLayouts)
                    state to state.handoffLayouts.intern(listOf("IntRep", "BoxedRep (Just Lifted)"))
                } finally {
                    context.leave()
                }
            }

            Context.newBuilder("thc").engine(engine).build().use { firstContext ->
                Context.newBuilder("thc").engine(engine).build().use { secondContext ->
                    val (first, firstLayout) = capture(firstContext)
                    val (second, secondLayout) = capture(secondContext)
                    assertNotSame(first, second)
                    assertNotSame(first.handoffLayouts, second.handoffLayouts)
                    assertNotSame(first.javaScriptImports, second.javaScriptImports)
                    assertNotSame(firstLayout, secondLayout)
                    assertTrue(first.singleThreadedAssumption.isValid)
                    assertTrue(second.singleThreadedAssumption.isValid)

                    // Sequential access by a new thread is enough to leave the
                    // lockless phase, before that thread executes guest code.
                    val workerError = AtomicReference<Throwable?>()
                    val worker = Thread {
                        try {
                            firstContext.enter()
                            try { assertSame(first, Language.currentState(null)) }
                            finally { firstContext.leave() }
                        } catch (error: Throwable) { workerError.set(error) }
                    }
                    worker.isDaemon = true
                    worker.start()
                    worker.join(10_000)
                    assertFalse(worker.isAlive, "Second context thread did not leave")
                    workerError.get()?.let { throw AssertionError("Second context thread failed", it) }
                    assertFalse(first.singleThreadedAssumption.isValid)
                    assertTrue(second.singleThreadedAssumption.isValid)
                    firstContext.enter()
                    try { assertFalse(first.singleThreadedAssumption.isValid) }
                    finally { firstContext.leave() }
                }
            }
        }
    }
}
