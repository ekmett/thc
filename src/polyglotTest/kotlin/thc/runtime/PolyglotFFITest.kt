// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

/** The optional JS dependency is loaded only by Gradle's polyglotTest task. */
class PolyglotFFITest {
    private fun proof(rep: String): Map<String, Any?> = mapOf(
        "kind" to when (rep) {
            "AddrRep" -> "address"
            "IntRep" -> "long"
            "DoubleRep" -> "double"
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

    private fun javascriptCall(source: String = "((x) => x + 7)",
                               input: List<String> = listOf("IntRep"), output: String = "IntRep",
                               safety: String = "unsafe"): List<Any?> {
        val registers = input + "State# RealWorld"
        val arguments: List<List<Any?>> = registers.mapIndexed { index, rep ->
            listOf("var", "argument$index", mapOf("rep" to proof(rep)))
        }
        val result = if (output == "void") mapOf<String, Any?>(
            "kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to false,
            "aggregate" to "unboxed-tuple", "components" to listOf(proof("State# RealWorld")))
            else tuple(output)
        val symbol = "thc_javascript_v1_" + source.encodeToByteArray().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val descriptor = mapOf<String, Any?>(
            "schema" to 1, "intrinsic" to "javascript-v1", "javascriptSource" to source,
            "target" to mapOf("kind" to "static", "symbol" to symbol, "isFunction" to true),
            "convention" to "ccall", "safety" to safety,
            "arity" to registers.size, "suppliedArity" to registers.size,
            "argumentReps" to registers.map(::proof), "resultRep" to result)
        return listOf("app", listOf("var", "javascript-id"), arguments,
            List(arguments.size) { false }, false, false, mapOf("rep" to result, "foreignCall" to descriptor))
    }

    @Test fun javascriptIntrinsicRequiresExactSourceAndScalarStateSignature() {
        val cases = listOf(
            Triple(listOf<String>(), "void", "safe"),
            Triple(listOf("IntRep"), "IntRep", "unsafe"),
            Triple(listOf("DoubleRep"), "DoubleRep", "safe"),
            Triple(listOf("IntRep", "DoubleRep"), "DoubleRep", "unsafe"))
        for ((arguments, result, safety) in cases) {
            val source = "((x, y) => x + y)"
            val validated = CoreJavaScript.validate(javascriptCall(source, arguments, result, safety), false)
            assertNotNull(validated)
            assertEquals(source, validated!!.source)
            assertEquals(arguments.map { if (it == "IntRep") CoreKind.LONG else CoreKind.DOUBLE },
                validated.arguments.toList())
            assertEquals(when (result) {
                "IntRep" -> CoreKind.LONG
                "DoubleRep" -> CoreKind.DOUBLE
                else -> CoreKind.VOID
            }, validated.result)
        }
    }

    @Test fun javascriptIntrinsicRejectsSpoofedMarkersAndIncorrectRepresentations() {
        val valid = javascriptCall()
        val forged = listOf(
            changed(valid) { it.remove("intrinsic") },
            changed(valid) { it["intrinsic"] = "javascript-v2" },
            changed(valid) { it["javascriptSource"] = "((x) => x + 8)" },
            changed(valid) { descriptor ->
                val target = (descriptor["target"] as Map<String, Any?>).toMutableMap()
                target["symbol"] = "thc_javascript_v1_00"
                descriptor["target"] = target
            },
            changed(valid) { it["convention"] = "javascript" },
            changed(valid) { it["safety"] = "interruptible" },
            changed(valid) { it["arity"] = 1 },
            changed(valid) { it["resultRep"] = proof("IntRep") })
        forged.forEachIndexed { index, candidate ->
            val error = assertThrows(RuntimeFault::class.java) {
                CoreJavaScript.validate(candidate, false)
            }
            assertTrue(error.message.orEmpty().contains("JavaScript"),
                "javascript variant $index: ${error.message}")
        }
        assertThrows(RuntimeFault::class.java) { CoreJavaScript.validate(valid, true) }
        val wrongActual = valid.toMutableList().also { expression ->
            val arguments = (expression[2] as List<List<Any?>>).toMutableList()
            arguments[0] = listOf("var", "argument0", mapOf("rep" to proof("DoubleRep")))
            expression[2] = arguments
        }
        assertThrows(RuntimeFault::class.java) { CoreJavaScript.validate(wrongActual, false) }
        val wrongFlags = valid.toMutableList().also { it[3] = listOf(true, false) }
        assertThrows(RuntimeFault::class.java) { CoreJavaScript.validate(wrongFlags, false) }
        val spoofedHead = valid.toMutableList().also { it[1] = listOf("prim", "javascript-id") }
        assertThrows(RuntimeFault::class.java) { CoreJavaScript.validate(spoofedHead, false) }
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

    private class JavaScriptRoot(language: Language, private val declaration: JavaScriptImport) : RootNode(language) {
        @Child private var access = JavaScriptAccess(declaration)
        override fun execute(frame: VirtualFrame): Any? {
            val supplied = frame.arguments
            val arguments = supplied.copyOfRange(0, supplied.size - 1)
            val state = supplied.last()
            return when (declaration.result) {
                CoreKind.LONG -> access.executeLong(arguments, state)
                CoreKind.DOUBLE -> access.executeDouble(arguments, state)
                CoreKind.VOID -> access.executeVoid(arguments, state)
                else -> error("Unsupported test result kind")
            }
        }
    }

    private fun context(): Context = Context.newBuilder("thc", "js").allowExperimentalOptions(true)
        .allowPolyglotAccess(PolyglotAccess.ALL).build()
    private fun root(): AccessRoot {
        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
        return AccessRoot(language)
    }

    private fun javascriptRoot(source: String, input: List<String>, output: String): JavaScriptRoot {
        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
        val declaration = CoreJavaScript.validate(javascriptCall(source, input, output), false)!!
        return JavaScriptRoot(language, declaration)
    }

    @Test fun javascriptImportExecutesScalarAndVoidResults() {
        context().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val integer = javascriptRoot("((x) => x + 7)", listOf("IntRep"), "IntRep").callTarget
                assertEquals(48L, Calls.target(integer, arrayOf<Any?>(41L, Unit)) as Long)
                val floating = javascriptRoot("((x) => x * 0.5)", listOf("DoubleRep"), "DoubleRep").callTarget
                assertEquals(1.5, Calls.target(floating, arrayOf<Any?>(3.0, Unit)) as Double)
                val effect = javascriptRoot("(() => { globalThis.thcCount = (globalThis.thcCount || 0) + 1; })",
                    emptyList(), "void").callTarget
                Calls.target(effect, arrayOf<Any?>(Unit))
                Calls.target(effect, arrayOf<Any?>(Unit))
                assertEquals(2, context.eval("js", "globalThis.thcCount").asInt())
            } finally { context.leave() }
        }
    }

    @Test fun javascriptImportRebindsGlobalFunctionAndKeepsContextCachePrivate() {
        for ((increment, expected) in listOf(1L to 11L, 100L to 110L)) context().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                context.eval("js", "globalThis.thcAdd = x => x + $increment")
                val target = javascriptRoot("globalThis.thcAdd", listOf("IntRep"), "IntRep").callTarget
                assertEquals(expected, Calls.target(target, arrayOf<Any?>(10L, Unit)) as Long)
                context.eval("js", "globalThis.thcAdd = x => x + 3")
                assertEquals(13L, Calls.target(target, arrayOf<Any?>(10L, Unit)) as Long)
            } finally { context.leave() }
        }
    }

    @Test fun javascriptImportDoesNotCaptureBareGlobalsOrLoseMethodReceiver() {
        context().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                context.eval("js", "globalThis.a0 = x => x + 7")
                for (source in listOf("a0", "\\u0061\\u0030")) {
                    val target = javascriptRoot(source, listOf("IntRep"), "IntRep").callTarget
                    assertEquals(18L, Calls.target(target, arrayOf<Any?>(11L, Unit)) as Long,
                        "import source $source must resolve the global function")
                }
                context.eval("js", "globalThis.obj = { base: 40, add(x) { return this.base + x; } }")
                val method = javascriptRoot("globalThis.obj.add", listOf("IntRep"), "IntRep").callTarget
                assertEquals(51L, Calls.target(method, arrayOf<Any?>(11L, Unit)) as Long,
                    "method import must retain its JavaScript receiver")
            } finally { context.leave() }
        }
    }

    @Test fun javascriptImportRejectsMalformedInputsAndResults() {
        context().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val integer = javascriptRoot("((x) => x + 7)", listOf("IntRep"), "IntRep").callTarget
                for ((arguments, message) in listOf(
                    arrayOf<Any?>(9_007_199_254_740_992L, Unit) to "exact Number integer range",
                    arrayOf<Any?>(3.0, Unit) to "expected Int#",
                    arrayOf<Any?>(3L, 0L) to "zero-width scalar carrier",
                    arrayOf<Any?>(Unit) to "argument count mismatch")) {
                    val error = assertThrows(RuntimeFault::class.java) { Calls.target(integer, arguments) }
                    assertTrue(error.message.orEmpty().contains(message), error.message)
                }
                val string = javascriptRoot("((x) => 'not a number')", listOf("IntRep"), "IntRep").callTarget
                val nonnumeric = assertThrows(RuntimeFault::class.java) {
                    Calls.target(string, arrayOf<Any?>(1L, Unit))
                }
                assertTrue(nonnumeric.message.orEmpty().contains("not an exact Int#"), nonnumeric.message)
                val exactlyRepresentable = javascriptRoot("(() => 9007199254740992)", emptyList(), "IntRep").callTarget
                assertEquals(9_007_199_254_740_992L,
                    Calls.target(exactlyRepresentable, arrayOf<Any?>(Unit)) as Long)
                for (source in listOf("(() => 1.5)", "(() => 9223372036854775808)")) {
                    val invalid = javascriptRoot(source, emptyList(), "IntRep").callTarget
                    val error = assertThrows(RuntimeFault::class.java) {
                        Calls.target(invalid, arrayOf<Any?>(Unit))
                    }
                    assertTrue(error.message.orEmpty().contains("not an exact Int#"), error.message)
                }
                val throwing = javascriptRoot("((x) => { throw new Error('javascript import boom'); })",
                    listOf("IntRep"), "IntRep").callTarget
                val foreign = assertThrows(RuntimeException::class.java) {
                    Calls.target(throwing, arrayOf<Any?>(1L, Unit))
                }
                assertTrue(foreign.message.orEmpty().contains("javascript import boom"), foreign.message)
            } finally { context.leave() }
        }
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
