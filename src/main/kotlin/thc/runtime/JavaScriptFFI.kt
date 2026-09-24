// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.InteropException
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.source.Source
import thc.Language
import java.util.concurrent.ConcurrentHashMap

/** GHC has already unboxed the public Int/Double arguments at this boundary. */
internal class JavaScriptImport(val source: String,
    @field:CompilationFinal(dimensions = 1) val arguments: Array<CoreKind>, val result: CoreKind)

internal object CoreJavaScript {
    private const val PREFIX = "thc_javascript_v1_"
    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid JavaScript foreign call: $detail")
    }
    private fun scalar(rep: CoreRepresentation): CoreKind? {
        if (!rep.present || rep.isAggregate || rep.isVector) return null
        return when {
            rep.kind == CoreKind.LONG && rep.primReps == listOf("IntRep") -> CoreKind.LONG
            rep.kind == CoreKind.DOUBLE && rep.primReps == listOf("DoubleRep") -> CoreKind.DOUBLE
            rep.kind == CoreKind.VOID && rep.primReps == emptyList<String>() -> CoreKind.VOID
            else -> null
        }
    }
    private fun result(rep: CoreRepresentation): CoreKind? {
        val fields = rep.components ?: return null
        if (!rep.present || rep.isSum || rep.isVector || fields.size !in 1..2 || scalar(fields[0]) != CoreKind.VOID)
            return null
        val kind = if (fields.size == 1) CoreKind.VOID else scalar(fields[1]) ?: return null
        if (fields.size == 2 && kind == CoreKind.VOID) return null
        return kind.takeIf { rep.primReps == fields.flatMap { it.primReps.orEmpty() } }
    }
    fun validate(expr: List<Any?>, defined: Boolean): JavaScriptImport? {
        val descriptor = CoreRepresentations.metadata(expr)?.get("foreignCall") as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        if (!symbol.startsWith(PREFIX) && descriptor["intrinsic"] != "javascript-v1") return null
        val source = descriptor["javascriptSource"] as? String
            ?: throw RuntimeFault("Invalid JavaScript foreign call: missing source")
        val encoded = source.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
        requireProof(source.isNotEmpty() && symbol == PREFIX + encoded && descriptor["intrinsic"] == "javascript-v1",
            "versioned source marker")
        val function = expr.getOrNull(1) as? List<*>
        requireProof(expr.firstOrNull() == "app" && function?.firstOrNull() == "var" &&
            function.getOrNull(1) is String && !defined, "expected an unresolved foreign identifier")
        fun exact(value: Any?, expected: Int) = (value is Int || value is Long) &&
            (value as Number).toLong() == expected.toLong()
        requireProof(exact(descriptor["schema"], 1) && target["kind"] == "static" && target["isFunction"] == true,
            "static version 1 function declaration")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] in listOf("safe", "unsafe"),
            "synchronous calling convention")
        val declared = descriptor["argumentReps"] as? List<*>
            ?: throw RuntimeFault("Invalid JavaScript foreign call: argument declarations")
        val arguments = expr.getOrNull(2) as? List<List<Any?>>
            ?: throw RuntimeFault("Invalid JavaScript foreign call: arguments")
        val flags = expr.getOrNull(3) as? List<*>
            ?: throw RuntimeFault("Invalid JavaScript foreign call: argument flags")
        val kinds = declared.map { scalar(CoreRepresentations.parse(it)) }
        requireProof(kinds.isNotEmpty() && kinds.last() == CoreKind.VOID &&
            kinds.dropLast(1).all { it == CoreKind.LONG || it == CoreKind.DOUBLE }, "scalar argument types")
        requireProof(exact(descriptor["arity"], kinds.size) && exact(descriptor["suppliedArity"], kinds.size) &&
            arguments.size == kinds.size && flags.size == kinds.size && flags.all { it == false }, "saturated arity")
        requireProof(arguments.indices.all { scalar(CoreRepresentations.expression(arguments[it])) == kinds[it] },
            "actual argument representations")
        val result = result(CoreRepresentations.parse(descriptor["resultRep"]))
        requireProof(result != null && result(CoreRepresentations.expression(expr)) == result, "state/result tuple")
        return JavaScriptImport(source, kinds.dropLast(1).map { it!! }.toTypedArray(), result!!)
    }
}

/** Cached per context, with no integer handles or process-wide foreign roots. */
internal class JavaScriptImports {
    private data class Key(val source: String, val arity: Int)
    private val functions = ConcurrentHashMap<Key, Any>()
    private val unicodeEscape = Regex("""\\u(?:\{([0-9a-fA-F]{1,6})\}|([0-9a-fA-F]{4}))""")

    private fun parameters(source: String, arity: Int): String {
        // A parameter must not capture a name in the foreign expression, even
        // when that name uses JavaScript's Unicode identifier escapes.
        val identifiers = source.replace(unicodeEscape) { match ->
            val code = (match.groups[1]?.value ?: match.groupValues[2]).toInt(16)
            if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        }
        var prefix = "a"
        while ((0 until arity).any { identifiers.contains("$prefix$it") }) prefix = "_$prefix"
        return (0 until arity).joinToString(",") { "$prefix$it" }
    }

    @TruffleBoundary
    fun resolve(owner: Language.State, declaration: JavaScriptImport): Any {
        val key = Key(declaration.source, declaration.arguments.size)
        functions[key]?.let { return it }
        val arguments = parameters(declaration.source, declaration.arguments.size)
        // Cache the call wrapper, preserving lookup/evaluation of the import
        // expression on every invocation (a global function can be rebound).
        val text = "(($arguments) => (${declaration.source})($arguments))"
        val target = owner.env.parsePublic(Source.newBuilder("js", text, "foreign-import.js").build())
        val value = Calls.target(target, emptyArray()) ?: fault("JavaScript import produced a host null")
        // Wrapper creation is pure; racing initializers may discard one. Never
        // execute another language while holding our cache's publication lock.
        return functions.putIfAbsent(key, value) ?: value
    }
}

internal class JavaScriptAccess(private val declaration: JavaScriptImport) : Node() {
    private class CachedFunction(val owner: Language.State, val function: Any)
    @Volatile @CompilationFinal private var cached: CachedFunction? = null
    @Child private var calls = InteropLibrary.getFactory().createDispatched(3)
    @Child private var numbers = InteropLibrary.getFactory().createDispatched(3)

    private fun function(): Any {
        val owner = Language.currentState(this)
        val entry = cached
        return if (entry?.owner === owner) entry.function else initialize(owner)
    }

    @TruffleBoundary
    private fun initialize(owner: Language.State): Any {
        cached?.takeIf { it.owner === owner }?.let { return it.function }
        val value = owner.javaScriptImports.resolve(owner, declaration)
        return synchronized(this) {
            cached?.takeIf { it.owner === owner }?.function ?: run {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                cached = CachedFunction(owner, value)
                value
            }
        }
    }

    @ExplodeLoop
    private fun execute(arguments: Array<Any?>, state: Any?): Any {
        requireVoidCarrier(state)
        if (arguments.size != declaration.arguments.size) fault("JavaScript import argument count mismatch")
        for (index in arguments.indices) when (declaration.arguments[index]) {
            CoreKind.LONG -> {
                val number = arguments[index] as? Long ?: fault("JavaScript import expected Int#")
                if (number !in -9_007_199_254_740_991L..9_007_199_254_740_991L)
                    fault("JavaScript import input exceeds the exact Number integer range")
            }
            CoreKind.DOUBLE -> if (arguments[index] !is Double) fault("JavaScript import expected Double#")
            else -> fault("Invalid JavaScript import argument type")
        }
        return try { Calls.interop(calls, function(), arguments) }
        catch (error: InteropException) { failure(error) }
    }

    fun executeLong(arguments: Array<Any?>, state: Any?): Long {
        val value = execute(arguments, state)
        if (!numbers.fitsInLong(value)) fault("JavaScript import result is not an exact Int#")
        return numbers.asLong(value)
    }
    fun executeDouble(arguments: Array<Any?>, state: Any?): Double {
        val value = execute(arguments, state)
        if (!numbers.fitsInDouble(value)) fault("JavaScript import result is not a Double#")
        return numbers.asDouble(value)
    }
    fun executeVoid(arguments: Array<Any?>, state: Any?) { execute(arguments, state) }
    @TruffleBoundary private fun failure(error: InteropException): Nothing =
        throw RuntimeFault("JavaScript import: ${error.message}")
}

internal class JavaScriptExpression(private val declaration: JavaScriptImport,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    @Child private var access = JavaScriptAccess(declaration)
    override fun execute(frame: VirtualFrame): Nothing = fault("JavaScript IO requires a tuple destination")
    @ExplodeLoop
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val values = arrayOfNulls<Any>(declaration.arguments.size)
        for (index in values.indices) values[index] = when (declaration.arguments[index]) {
            CoreKind.LONG -> arguments[index].executeRequiredLong(frame)
            CoreKind.DOUBLE -> arguments[index].executeRequiredDouble(frame)
            else -> fault("Invalid JavaScript argument type")
        }
        val state = arguments.last().execute(frame)
        when (declaration.result) {
            CoreKind.LONG -> FrameAccess.writeLong(frame, slots[offset], access.executeLong(values, state))
            CoreKind.DOUBLE -> FrameAccess.writeDouble(frame, slots[offset], access.executeDouble(values, state))
            CoreKind.VOID -> access.executeVoid(values, state)
            else -> fault("Invalid JavaScript result type")
        }
        return null
    }
}

/** Bytecode operands stay in typed locals until the interop protocol needs them. */
internal class BytecodeJavaScriptArguments(val declaration: JavaScriptImport,
    @field:CompilationFinal(dimensions = 1) private val slots: Array<LocalAccessor>,
    private val state: LocalAccessor) {
    @ExplodeLoop
    fun read(bytecode: BytecodeNode, frame: VirtualFrame): Array<Any?> {
        val values = arrayOfNulls<Any>(slots.size)
        for (index in slots.indices) values[index] = when (declaration.arguments[index]) {
            CoreKind.LONG -> slots[index].getLong(bytecode, frame)
            CoreKind.DOUBLE -> slots[index].getDouble(bytecode, frame)
            else -> fault("Invalid JavaScript argument type")
        }
        return values
    }
    fun state(bytecode: BytecodeNode, frame: VirtualFrame): Any? = state.getObject(bytecode, frame)
}
