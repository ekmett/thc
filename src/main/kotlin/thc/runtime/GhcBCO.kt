// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** GHC 9.14.1 Bytecodes.h, not THC's Truffle bytecode format. Operands are
 * native-endian Word16s; LARGE_ARGS joins four words most-significant first.
 * Decoding owns code/literals, while the pointer table retains real lazy guest
 * references. No native info-table address is interpreted as a JVM object. */
internal object GhcBCO {
    private fun scalar(rep: CoreRepresentation) = !rep.isAggregate && !rep.isVector
    private fun pointer(rep: CoreRepresentation) = scalar(rep) &&
        rep.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE)
    fun validate(name: String, args: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        val fields = result.components
        val valid = if (name == "newBCO#") args.size == 6 &&
            listOf(0, 1, 2, 4).all { pointer(args[it]) } &&
            scalar(args[3]) && args[3].kind == CoreKind.LONG &&
            scalar(args[5]) && args[5].kind == CoreKind.VOID && flags == List(6) { false } &&
            result.isTuple && fields?.size == 2 && fields[0].kind == CoreKind.VOID && pointer(fields[1])
        else args.size == 1 && pointer(args[0]) && flags == listOf(true) &&
            result.isTuple && fields?.size == 1 && pointer(fields[0])
        if (!valid) fault("$name: incompatible BCO operands or result shape")
        TupleShape.validate(result)
    }

    @JvmStatic @TruffleBoundary
    fun create(node: Node, language: Language, metrics: Metrics, code: Any?, literals: Any?, pointers: Any?,
               arity: Long, bitmap: Any?, state: Any?): Closure {
        requireVoidCarrier(state)
        if (arity < 0 || arity >= Int.MAX_VALUE) fault("BCO arity outside managed calling convention")
        val codeBytes = ManagedByteArray.sizeGuest(code)
        val literalBytes = ManagedByteArray.sizeGuest(literals)
        val bitmapBytes = ManagedByteArray.sizeGuest(bitmap)
        if (codeBytes == 0L || codeBytes % 2 != 0L || literalBytes % 8 != 0L || bitmapBytes < 8 || bitmapBytes % 8 != 0L)
            fault("BCO requires Word16 instructions and 64-bit literals/bitmap")
        val stackWords = ManagedByteArray.readIntGuest(bitmap, 0)
        if (stackWords != arity || bitmapBytes < 8 + ((stackWords + 63) / 64) * 8)
            fault("BCO function bitmap must cover exactly its scalar arguments")
        val nonPointers = BooleanArray(arity.toInt()) {
            ManagedByteArray.readIntGuest(bitmap, 1 + it.toLong() / 64) ushr (it % 64) and 1L != 0L
        }
        val words = IntArray((codeBytes / 2).toInt()) {
            ManagedByteArray.readInt16Guest(code, it.toLong(), true).toInt()
        }
        val data = LongArray((literalBytes / 8).toInt()) { ManagedByteArray.readIntGuest(literals, it.toLong()) }
        val refs = ManagedArray.require(pointers)
        val instructions = arrayOfNulls<GhcInstruction>(words.size)
        var pc = 0
        while (pc < words.size) {
            val start = pc
            val encoded = words[pc++]
            if (encoded and 0x7f00 != 0) fault("BCO instruction has unknown flags")
            val opcode = encoded and 255
            val count = when (opcode) {
                1, 2, 11, 55, 88 -> 1
                3, 25, 38, 46, 47, 57, 67, 68 -> 2
                4 -> 3
                in 31..36, 54, 58, in 60..64, in 90..100, in 110..119 -> 0
                else -> fault("Unsupported GHC 9.14.1 BCO opcode $opcode at Word16 $start")
            }
            val operands = LongArray(count) {
                val width = if (encoded and 0x8000 != 0 && opcode != 88) 4 else 1
                if (pc > words.size - width) fault("Truncated BCO operand at Word16 $start")
                var value = 0L
                repeat(width) { value = (value shl 16) or words[pc++].toLong() }
                value
            }
            instructions[start] = GhcInstruction(opcode, operands, pc)
        }
        for (instruction in instructions.filterNotNull()) {
            fun table(index: Long, size: Int) { if (index < 0 || index >= size) fault("BCO table index outside its storage") }
            when (instruction.opcode) {
                11 -> table(instruction.args[0], refs.size)
                25 -> {
                    val first = instruction.args[0]; val count = instruction.args[1]
                    if (first < 0 || count < 0 || first > data.size || count > data.size - first)
                        fault("BCO literal range outside its storage")
                }
                46, 47, 67, 68 -> table(instruction.args[0], data.size)
            }
            val jump = when (instruction.opcode) { 55 -> instruction.args[0]; 46, 47, 67, 68 -> instruction.args[1]; else -> null }
            if (jump != null && (jump < 0 || jump >= instructions.size || instructions[jump.toInt()] == null))
                fault("BCO jump does not name an instruction boundary")
        }
        val root = GhcBCORoot(language, Language.currentState(node), metrics, instructions, data, refs, nonPointers)
        return Closure(null, arity = arity.toInt(), target = root.callTarget)
    }

    @JvmStatic fun updating(node: Node, value: Any?): Thunk {
        val closure = value as? Closure ?: fault("mkApUpd0# requires a BCO")
        val root = closure.target.rootNode as? GhcBCORoot ?: fault("mkApUpd0# requires a BCO")
        if (root.owner !== Language.currentState(node)) fault("BCO belongs to another context")
        if (closure.arity != 0 || closure.suppliedCount != 0 || root.arity != 0)
            fault("mkApUpd0# requires a zero-arity BCO")
        return Thunk(closure.target, null)
    }
}

internal class GhcInstruction(val opcode: Int, val args: LongArray, val next: Int)

/** A real callable guest root. Normal Dispatch provides PAPs/overapplication;
 * normal Thunk/Force provides blackholing, sharing and failure memoization. */
internal class GhcBCORoot(language: Language, val owner: Language.State, metrics: Metrics,
                         private val code: Array<GhcInstruction?>, private val literals: LongArray,
                         private val pointers: Array<Any?>, private val nonPointers: BooleanArray) :
    GuestRoot(language, FrameDescriptor.newBuilder().build()) {
    val arity: Int get() = nonPointers.size
    @Child private var force = Force(metrics)
    @Children private var calls: Array<Dispatch> = Array(6) { DispatchNodeGen.create(it + 1, false, metrics) }
    private class Apply(val count: Int)
    override fun getName(): String = "<GHC BCO>"
    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long
    override fun execute(frame: VirtualFrame): Any? {
        // Check the active context before node-local lookup can consult a
        // foreign root's Truffle sharing layer (including after owner close).
        if (Language.currentState(null) !== owner) fault("BCO belongs to another context")
        if (frame.arguments.size != arity + 1) fault("BCO argument packet mismatch")
        val stack = ArrayList<Any?>()
        fun word(value: Any?): Long = when (value) {
            is Long -> value
            is Float -> value.toRawBits().toLong() and 0xffffffffL
            is Double -> value.toRawBits()
            else -> fault("BCO word operation received a pointer or stack marker")
        }
        for (i in arity - 1 downTo 0) stack.add(if (nonPointers[i]) word(frame.arguments[i + 1]) else frame.arguments[i + 1])
        fun index(offset: Long): Int {
            if (offset < 0 || offset >= stack.size) fault("BCO stack offset outside live values")
            return stack.size - 1 - offset.toInt()
        }
        fun pop(): Any? = stack.removeAt(index(0))
        fun finish(value: Any?, enter: Boolean): Any? {
            try {
                var answer = if (enter) force.execute(frame, value) else value
                while (stack.isNotEmpty()) {
                    val apply = pop() as? Apply ?: fault("BCO returned over unconsumed arguments")
                    val arguments = Array(apply.count) { pop() }
                    val function = force.execute(frame, answer) as? Closure ?: fault("BCO application requires a function")
                    answer = calls[apply.count - 1].execute(frame, function, arguments)
                    if (answer is TailYield || savedGuestContinuation(answer) != null)
                        fault("GHC BCO asynchronous continuation is not supported")
                    answer = force.execute(frame, answer)
                }
                return answer
            } catch (_: DelimitedCut) {
                fault("Delimited capture through a GHC BCO is not supported")
            } catch (_: ThunkSuspended) {
                fault("GHC BCO asynchronous thunk suspension is not supported")
            } catch (_: CallSegmentSuspended) {
                fault("GHC BCO asynchronous call suspension is not supported")
            } catch (_: CapturedCallSuspension) {
                fault("GHC BCO asynchronous call suspension is not supported")
            }
        }
        var pc = 0
        while (true) {
            TruffleSafepoint.poll(this)
            val instruction = code.getOrNull(pc) ?: fault("BCO execution left its instruction stream")
            pc = instruction.next
            val args = instruction.args
            when (val op = instruction.opcode) {
                1 -> if (args[0] < 0 || args[0] > Int.MAX_VALUE) fault("BCO stack request outside managed bounds")
                2, 3, 4 -> {
                    // PUSH_LL/LLL offsets all refer to the original stack pointer.
                    val copied = args.map { stack[index(it)] }
                    stack.addAll(copied)
                }
                11 -> stack.add(pointers[args[0].toInt()])
                25 -> for (i in args[1].toInt() - 1 downTo 0) stack.add(literals[args[0].toInt() + i])
                in 31..36 -> stack.add(Apply(op - 30))
                38 -> {
                    val keep = args[0]; val drop = args[1]
                    if (keep < 0 || drop < 0 || keep > stack.size || drop > stack.size - keep)
                        fault("BCO SLIDE outside live values")
                    stack.subList(stack.size - keep.toInt() - drop.toInt(), stack.size - keep.toInt()).clear()
                }
                46, 47, 67, 68 -> {
                    val a = word(stack[index(0)]); val b = literals[args[0].toInt()]
                    val failure = when (op) { 46 -> a >= b; 67 -> java.lang.Long.compareUnsigned(a, b) >= 0; else -> a != b }
                    if (failure) pc = args[1].toInt()
                }
                54 -> fault("GHC BCO CASEFAIL")
                55 -> pc = args[0].toInt()
                57 -> { val i = index(args[0]); stack[i] = word(stack[i]) + args[1] }
                58 -> return finish(pop(), true)
                60 -> return finish(pop(), false)
                61, 64 -> return finish(word(pop()), false)
                62 -> return finish(Float.fromBits(word(pop()).toInt()), false)
                63 -> return finish(Double.fromBits(word(pop())), false)
                88 -> Unit // diagnostic name-table annotation, not executable work
                94, 95 -> { val a = word(pop()); stack.add(if (op == 94) a.inv() else -a) }
                in 90..100, in 110..119 -> {
                    val a = word(pop()); val b = word(pop())
                    if (op in 97..99 && b !in 0..63) fault("BCO shift outside GHC's defined domain")
                    val unsigned = java.lang.Long.compareUnsigned(a, b)
                    stack.add(when (op) {
                        90 -> a + b; 91 -> a - b; 92 -> a and b; 93 -> a xor b; 96 -> a * b
                        97 -> a shl b.toInt(); 98 -> a shr b.toInt(); 99 -> a ushr b.toInt(); 100 -> a or b
                        else -> if (when (op) {
                            110 -> a != b; 111 -> a == b; 112 -> unsigned >= 0; 113 -> unsigned > 0
                            114 -> unsigned < 0; 115 -> unsigned <= 0; 116 -> a >= b; 117 -> a > b
                            118 -> a < b; else -> a <= b
                        }) 1L else 0L
                    })
                }
            }
        }
    }
}

internal class GhcBCOExpression(private val name: String, @field:Children private var operands: Array<Expr>,
                                private val language: Language, private val metrics: Metrics,
                                proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("$name requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = if (name == "mkApUpd0#") GhcBCO.updating(this, operands[0].execute(frame)) else {
            val code = operands[0].execute(frame); val literals = operands[1].execute(frame)
            val pointers = operands[2].execute(frame); val arity = operands[3].executeRequiredLong(frame)
            val bitmap = operands[4].execute(frame); val state = operands[5].execute(frame)
            GhcBCO.create(this, language, metrics, code, literals, pointers, arity, bitmap, state)
        }
        FrameAccess.write(frame, slots[offset], value)
        return null
    }
}
