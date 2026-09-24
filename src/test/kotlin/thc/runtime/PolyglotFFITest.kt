package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import thc.Language

/** The optional JS dependency is loaded only by Gradle's polyglotTest task. */
@Tag("polyglot")
class PolyglotFFITest {
    private fun proof(rep: String): Map<String, Any?> = mapOf(
        "kind" to when (rep) {
            "AddrRep" -> "address"
            "IntRep" -> "long"
            "State# RealWorld" -> "void"
            else -> "object"
        },
        "primReps" to if (rep == "State# RealWorld") emptyList<String>() else listOf(rep),
        "evaluated" to (rep != "BoxedRep (Just Lifted)"))

    private fun tuple(rep: String): Map<String, Any?> = mapOf(
        "kind" to "unknown", "primReps" to listOf(rep), "evaluated" to false,
        "aggregate" to "unboxed-tuple", "components" to listOf(proof("State# RealWorld"), proof(rep)))

    private fun call(op: PolyglotOp): List<Any?> {
        val arguments: List<List<Any?>> = op.arguments.mapIndexed { i, rep ->
            listOf("var", "argument$i", mapOf("rep" to proof(rep)))
        }
        val descriptor = mapOf<String, Any?>(
            "schema" to 1, "target" to mapOf("kind" to "static", "symbol" to op.symbol,
                "isFunction" to true), "convention" to "prim", "safety" to "safe",
            "arity" to arguments.size, "suppliedArity" to arguments.size,
            "argumentReps" to op.arguments.map(::proof), "resultRep" to tuple(op.result))
        return listOf("app", listOf("var", "foreign-id"), arguments,
            op.arguments.map { it == "BoxedRep (Just Lifted)" }, false, false,
            mapOf("rep" to tuple(op.result), "foreignCall" to descriptor))
    }

    private fun changed(call: List<Any?>, transform: (MutableMap<String, Any?>) -> Unit): List<Any?> {
        val result = call.toMutableList()
        val metadata = (result[6] as Map<String, Any?>).toMutableMap()
        val descriptor = (metadata["foreignCall"] as Map<String, Any?>).toMutableMap()
        transform(descriptor)
        metadata["foreignCall"] = descriptor
        result[6] = metadata
        return result
    }

    @Test fun exactVersionedForeignDeclarationIsRequired() {
        for (op in PolyglotOp.entries) {
            val valid = call(op)
            assertEquals(op, CorePolyglot.validate(valid, false), op.symbol)
            val bad = listOf(
                changed(valid) { it["schema"] = 2 },
                changed(valid) { it["convention"] = "ccall" },
                changed(valid) { it["safety"] = "unsafe" },
                changed(valid) { it["arity"] = op.arguments.size - 1 },
                changed(valid) { it["suppliedArity"] = op.arguments.size - 1 },
                changed(valid) { it["target"] = mapOf("kind" to "dynamic", "symbol" to op.symbol,
                    "isFunction" to true) },
                changed(valid) { it["target"] = mapOf("kind" to "static", "symbol" to op.symbol,
                    "isFunction" to false) })
            bad.forEachIndexed { index, candidate ->
                val error = assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(candidate, false) }
                assertTrue(error.message.orEmpty().contains("Invalid polyglot foreign call"),
                    "$op declaration variant $index: ${error.message}")
            }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(valid, true) }
            val spoofedFunction = valid.toMutableList().also { it[1] = listOf("prim", "foreign-id") }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(spoofedFunction, false) }
        }
    }

    @Test fun exactActualArgumentsAndStateTupleAreRequired() {
        for (op in PolyglotOp.entries) {
            val valid = call(op)
            val badDeclared = changed(valid) { descriptor ->
                val declared = (descriptor["argumentReps"] as List<Any?>).toMutableList()
                declared[0] = proof(if (op.arguments[0] == "IntRep") "AddrRep" else "IntRep")
                descriptor["argumentReps"] = declared
            }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(badDeclared, false) }
            val badActual = valid.toMutableList().also { expression ->
                val arguments = (expression[2] as List<List<Any?>>).toMutableList()
                arguments[0] = listOf("var", "argument0", mapOf("rep" to proof("WordRep")))
                expression[2] = arguments
            }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(badActual, false) }
            val boxedIndex = op.arguments.indexOf("BoxedRep (Just Lifted)")
            if (boxedIndex >= 0) {
                val wrongLevity = valid.toMutableList().also { expression ->
                    val arguments = (expression[2] as List<List<Any?>>).toMutableList()
                    arguments[boxedIndex] = listOf("var", "argument$boxedIndex",
                        mapOf("rep" to proof("BoxedRep (Just Unlifted)")))
                    expression[2] = arguments
                }
                assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(wrongLevity, false) }
            }
            val missingArgument = valid.toMutableList().also { expression ->
                expression[2] = (expression[2] as List<*>).dropLast(1)
            }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(missingArgument, false) }
            val badFlags = valid.toMutableList().also { expression ->
                expression[3] = (expression[3] as List<Boolean>).mapIndexed { i, flag -> if (i == 0) !flag else flag }
            }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(badFlags, false) }
            val badDeclaredTuple = changed(valid) { it["resultRep"] = proof(op.result) }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(badDeclaredTuple, false) }
            val badActualTuple = valid.toMutableList().also { expression ->
                val metadata = (expression[6] as Map<String, Any?>).toMutableMap()
                metadata["rep"] = tuple("WordRep")
                expression[6] = metadata
            }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(badActualTuple, false) }
            val badState = changed(valid) { descriptor ->
                descriptor["resultRep"] = mapOf("kind" to "unknown", "primReps" to listOf("IntRep", op.result),
                    "evaluated" to false, "aggregate" to "unboxed-tuple",
                    "components" to listOf(proof("IntRep"), proof(op.result)))
            }
            assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(badState, false) }
        }
    }

    @Test fun unversionedOrSpoofedHeadsCannotEnterTheAbi() {
        val valid = call(PolyglotOp.EVAL)
        assertNull(CorePolyglot.validate(listOf("var", "ordinary"), false))
        val wrongSymbol = changed(valid) { descriptor ->
            descriptor["target"] = mapOf("kind" to "static", "symbol" to "thc_polyglot_eval",
                "isFunction" to true)
        }
        assertThrows(UnsupportedCore::class.java) { CorePolyglot.validate(wrongSymbol, false) }
        val spoofedHead = listOf("prim", "fake", (valid[6] as Map<String, Any?>))
        assertThrows(RuntimeFault::class.java) { CorePolyglot.validate(spoofedHead, false) }
    }

    private fun address(text: String): LiteralAddress = LiteralAddress.fromHex(
        text.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) })

    private inner class AccessRoot(language: Language) : RootNode(language) {
        @Child private var access = PolyglotAccess()
        override fun execute(frame: VirtualFrame): Any = when (frame.arguments[0]) {
            "eval" -> access.eval(address("js"), address(frame.arguments[1] as String), address("ffi-test.js"), Unit)
            "evalBadState" -> access.eval(address("js"), address(frame.arguments[1] as String), address("ffi-test.js"), 0L)
            "read" -> access.readMember(frame, frame.arguments[1], address(frame.arguments[2] as String), Unit)
            "execute" -> access.executeInt(frame, frame.arguments[1], frame.arguments[2] as Long, Unit)
            else -> error("Unknown test operation")
        }
    }

    private fun context(): Context = Context.newBuilder("thc", "js").allowExperimentalOptions(true)
        .allowPolyglotAccess(PolyglotAccess.ALL).build()
    private fun root(): AccessRoot {
        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
        return AccessRoot(language)
    }

    @Test fun javascriptParsingRequiresExplicitCrossLanguageAccess() {
        Context.newBuilder("thc", "js").build().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val denied = assertThrows(IllegalStateException::class.java) {
                    Calls.target(root().callTarget, arrayOf<Any?>("eval", "1"))
                }
                assertTrue(denied.message.orEmpty().contains("No language for id js"), denied.message)
            } finally { context.leave() }
        }
    }

    @Test fun javascriptEvaluationMemberReadAndIntegerCallHonorTheContextBoundary() {
        context().use { first ->
            first.initialize("thc")
            first.enter()
            val value: ForeignValue
            try {
                val target = root().callTarget
                val state = assertThrows(RuntimeFault::class.java) {
                    Calls.target(target, arrayOf<Any?>("evalBadState", "1"))
                }
                assertTrue(state.message.orEmpty().contains("zero-width scalar carrier"), state.message)
                value = Calls.target(target, arrayOf<Any?>("eval", "({twice: x => x * 2})")) as ForeignValue
                val function = Calls.target(target, arrayOf<Any?>("read", value, "twice")) as ForeignValue
                assertEquals(42L, Calls.target(target, arrayOf<Any?>("execute", function, 21L)) as Long)
                val missing = assertThrows(RuntimeFault::class.java) {
                    Calls.target(target, arrayOf<Any?>("read", value, "absent"))
                }
                assertTrue(missing.message.orEmpty().contains("readMember"), missing.message)
                val fakeHandle = assertThrows(RuntimeFault::class.java) {
                    Calls.target(target, arrayOf<Any?>("read", 42L, "twice"))
                }
                assertTrue(fakeHandle.message.orEmpty().contains("THC.Polyglot.Value"), fakeHandle.message)
                val range = assertThrows(RuntimeFault::class.java) {
                    Calls.target(target, arrayOf<Any?>("execute", function, 9_007_199_254_740_992L))
                }
                assertTrue(range.message.orEmpty().contains("exact Number integer range"), range.message)
            } finally { first.leave() }
            context().use { second ->
                second.initialize("thc")
                second.enter()
                try {
                    val foreign = assertThrows(RuntimeFault::class.java) {
                        Calls.target(root().callTarget, arrayOf<Any?>("read", value, "twice"))
                    }
                    assertTrue(foreign.message.orEmpty().contains("different context"), foreign.message)
                } finally { second.leave() }
            }
        }
    }

    @Test fun nonnumericResultsAndForeignExceptionsStayVisible() {
        context().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val target = root().callTarget
                val value = Calls.target(target, arrayOf<Any?>("eval",
                    "({text: x => 'no', fractional: x => x + 0.5, boom: x => { throw new Error('boom from JS') }})"))
                for (name in listOf("text", "fractional")) {
                    val function = Calls.target(target, arrayOf<Any?>("read", value, name))
                    val error = assertThrows(RuntimeFault::class.java) {
                        Calls.target(target, arrayOf<Any?>("execute", function, 1L))
                    }
                    assertTrue(error.message.orEmpty().contains("not an exact Int#"), "$name: ${error.message}")
                }
                val boom = Calls.target(target, arrayOf<Any?>("read", value, "boom"))
                val error = assertThrows(RuntimeException::class.java) {
                    Calls.target(target, arrayOf<Any?>("execute", boom, 1L))
                }
                assertTrue(error.message.orEmpty().contains("boom from JS"), error.message)
            } finally { context.leave() }
        }
    }
}
