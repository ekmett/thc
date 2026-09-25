// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import thc.EntryValue
import thc.Language
import thc.executionContext

class ExecutableLifecycleTest {
    private val state = CoreRepresentation(CoreKind.VOID, evaluated = true, present = true, primReps = emptyList())
    private val unit = CoreRepresentation(CoreKind.DATA, evaluated = true, present = true,
        primReps = listOf("BoxedRep (Just Lifted)"))
    private val result = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true, present = true,
        primReps = listOf("BoxedRep (Just Lifted)"), components = listOf(state, unit))

    private class ActionRoot(language: Language, private val shape: TupleShape, private val unitValue: DataValue,
                             private val effect: () -> Unit) : GuestRoot(language, FrameLayout().build()) {
        init { configureTupleResult(shape) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any {
            requireVoidCarrier(frame.arguments[1])
            effect()
            return shape.layout.create().also { shape.layout.setObject(it, 0, unitValue) }
        }
    }

    private class Actions(private val main: RootCallTarget, private val shutdown: RootCallTarget) : ExecutableProgram {
        override fun hostEntryTarget(arity: Int) = main
        override fun entryValue(name: String): Any = Closure(null, arity = 1,
            target = when (name) { "main" -> main; "shutdown" -> shutdown; else -> error(name) })
        override fun entryTarget(name: String) = when (name) {
            "main" -> main; "shutdown" -> shutdown; else -> error(name)
        }
        override fun constructorLayout(id: String): DataLayout =
            error("Unexpected constructor layout request in lifecycle test: $id")
        override fun diagnostics(): Map<String, Any> = emptyMap()
    }

    @Test fun successfulExecutableSharesStateAndRunsShutdownOnce() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val shape = TupleShape(result, language)
                val boxedUnit = DataLayout(language, "ghc-internal:GHC.Internal.Tuple.()", "()",
                    emptyArray()).create(emptyArray())
                var state = 0
                val events = mutableListOf<String>()
                val main = ActionRoot(language, shape, boxedUnit) { events += "main"; state++ }.callTarget
                val shutdown = ActionRoot(language, shape, boxedUnit) {
                    events += "shutdown:$state"; state++
                }.callTarget
                val program = Actions(main, shutdown)
                val action = context.asValue(EntryValue(program, "main", 0, ioResult = result,
                    language = language, shutdownEntry = "shutdown", shutdownResult = result))
                assertTrue(action.invokeMember("runIO").asBoolean())
                assertEquals(listOf("main", "shutdown:1"), events)
                assertEquals(2, state)
                val second = assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
                assertTrue(second.message.orEmpty().contains("already started"))
                assertEquals(2, state)
            } finally { context.leave() }
        }
    }

    @Test fun failedMainDoesNotRunShutdownOrRetryEffects() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val shape = TupleShape(result, language)
                val boxedUnit = DataLayout(language, "ghc-internal:GHC.Internal.Tuple.()", "()",
                    emptyArray()).create(emptyArray())
                var mainCalls = 0
                var shutdownCalls = 0
                val main = ActionRoot(language, shape, boxedUnit) {
                    mainCalls++
                    throw RuntimeFault("main failed")
                }.callTarget
                val shutdown = ActionRoot(language, shape, boxedUnit) { shutdownCalls++ }.callTarget
                val action = context.asValue(EntryValue(Actions(main, shutdown), "main", 0,
                    ioResult = result, language = language, shutdownEntry = "shutdown", shutdownResult = result))
                assertTrue(assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
                    .message.orEmpty().contains("main failed"))
                assertTrue(assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
                    .message.orEmpty().contains("already started"))
                assertEquals(1, mainCalls)
                assertEquals(0, shutdownCalls)
            } finally { context.leave() }
        }
    }

    @Test fun failedShutdownDoesNotRepeatTheCompletedMain() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val shape = TupleShape(result, language)
                val boxedUnit = DataLayout(language, "ghc-internal:GHC.Internal.Tuple.()", "()",
                    emptyArray()).create(emptyArray())
                var mainCalls = 0
                var shutdownCalls = 0
                val main = ActionRoot(language, shape, boxedUnit) { mainCalls++ }.callTarget
                val shutdown = ActionRoot(language, shape, boxedUnit) {
                    shutdownCalls++
                    throw RuntimeFault("shutdown failed")
                }.callTarget
                val action = context.asValue(EntryValue(Actions(main, shutdown), "main", 0,
                    ioResult = result, language = language, shutdownEntry = "shutdown", shutdownResult = result))
                assertTrue(assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
                    .message.orEmpty().contains("shutdown failed"))
                assertTrue(assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
                    .message.orEmpty().contains("already started"))
                assertEquals(1, mainCalls)
                assertEquals(1, shutdownCalls)
            } finally { context.leave() }
        }
    }
}
