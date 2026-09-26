// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import thc.Language

internal interface ProcessSignalTransport : AutoCloseable {
    data class Result(val action: Int, val errno: Int)
    fun install(action: Int): Result
    fun take(): ByteArray?
    fun wake()
    fun resetWake()
}

/** Machine-code signal boundary: an asynchronous native handler never enters Truffle. */
internal class NativeSignalTransport : ProcessSignalTransport {
    private val arena = Arena.ofShared()
    private val session: MemorySegment
    private val image: MemorySegment
    private var closed = false
    init {
        try {
            Arena.ofConfined().use { call ->
                image = arena.allocate((Api.size.invokeExact() as Int).toLong(), 8)
                val errors = call.allocate(Api.capture)
                session = Api.open.invokeExact(errors) as MemorySegment
                if (session.address() == 0L) fault("Process signal ownership unavailable (errno " +
                    errors.get(ValueLayout.JAVA_INT, Api.errno) + ")")
            }
        } catch (failure: Throwable) { arena.close(); failed("Process signal setup failed", failure) }
    }
    override fun install(action: Int): ProcessSignalTransport.Result = try {
        Arena.ofConfined().use { call ->
            val errors = call.allocate(Api.capture)
            val old = Api.install.invokeExact(errors, session, action) as Int
            ProcessSignalTransport.Result(old, if (old == -3) errors.get(ValueLayout.JAVA_INT, Api.errno) else 0)
        }
    } catch (failure: Throwable) { failed("Process signal install failed", failure) }
    /** Only the single reader calls this; zero is an explicit wake. */
    override fun take(): ByteArray? = try {
        when (Api.take.invokeExact(session, image) as Int) {
            0 -> null
            1 -> image.toArray(ValueLayout.JAVA_BYTE)
            -2 -> fault("Process signal queue overflow")
            else -> fault("Process signal read failed")
        }
    } catch (failure: Throwable) { failed("Process signal read failed", failure) }
    override fun wake() {
        try { Api.wake.invokeExact(session) }
        catch (failure: Throwable) { failed("Process signal wake failed", failure) }
    }
    override fun resetWake() {
        try { Api.reset.invokeExact(session) }
        catch (failure: Throwable) { failed("Process signal wake reset failed", failure) }
    }
    /** Caller has stopped/joined the reader and unregistered its interrupter. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            Arena.ofConfined().use { call ->
                val errors = call.allocate(Api.capture)
                val result = Api.close.invokeExact(errors, session) as Int
                if (result != 0) fault("Process signal restoration failed")
            }
        } catch (failure: Throwable) { failed("Process signal close failed", failure) }
        finally { arena.close() }
    }
    companion object {
        /** Explicit CLI-only termination after context shutdown, never guest FFI. */
        fun exitBySignal(signal: Int) {
            require(signal in 1..64) { "Invalid process exit signal" }
            try { Api.exit.invokeExact(signal) }
            catch (failure: Throwable) { failed("Process signal exit failed", failure) }
            throw AssertionError("Signal exit returned")
        }
        private fun failed(message: String, failure: Throwable): Nothing {
            if (failure is RuntimeException || failure is Error) throw failure
            throw RuntimeFault(message).also { it.initCause(failure) }
        }
    }
    private object Api {
        private val linker = Linker.nativeLinker()
        val capture = Linker.Option.captureStateLayout()
        val errno = capture.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
        private val library = load()
        private fun load(): SymbolLookup {
            if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64"))
                fault("Process signals require Linux x86_64")
            try {
                NativeSignalTransport::class.java.getResourceAsStream("/thc/native/native-process-signal-api.so").use { input ->
                    if (input == null) throw IOException("Missing native process signal bridge")
                    val library = Files.createTempFile("thc-process-signals-", ".so")
                    library.toFile().deleteOnExit()
                    Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING)
                    // Late handlers may retain this code after a session closes.
                    return SymbolLookup.libraryLookup(library, Arena.global())
                }
            } catch (failure: IOException) { failed("Cannot load process signal bridge", failure) }
        }
        private fun function(name: String, descriptor: FunctionDescriptor, errno: Boolean): MethodHandle =
            linker.downcallHandle(library.find("thc_signal_$name").orElseThrow(), descriptor,
                *if (errno) arrayOf(Linker.Option.captureCallState("errno")) else emptyArray())
        val open = function("open", FunctionDescriptor.of(ValueLayout.ADDRESS), true)
        val size = function("info_size", FunctionDescriptor.of(ValueLayout.JAVA_INT), false)
        val install = function("install", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), true)
        val take = function("take", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), false)
        val wake = function("wake", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), false)
        val reset = function("reset_wake", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), false)
        val close = function("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), true)
        val exit = function("exit", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), false)
    }
}

/** One explicit CLI context owns SIGINT for its process lifetime. Ordinary
 * native-access contexts cannot acquire it. Pending OS events contain real
 * siginfo bytes; GHC's own dispatcher chooses the current Haskell handler.
 *
 * The native handler slot is intentionally never reused by another context in
 * this JVM. This prevents late old handlers from targeting a new context.
 */
internal class ManagedSignals(private val owner: Language.State, private val language: Language,
                              private val factory: () -> ProcessSignalTransport = { NativeSignalTransport() }) {
    private var authorized = false
    private var binding: SignalDispatchRoot? = null
    private var transport: ProcessSignalTransport? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false
    private var closed = false
    internal fun authorizeLauncher() { authorized = true }
    @Synchronized fun bind(program: ExecutableProgram) {
        current()
        if (closed || binding != null) fault("Process signal dispatcher already bound")
        if (program !is BytecodeProgram) fault("Process signal delivery requires the bytecode backend")
        binding = SignalDispatchRoot(language, program)
    }
    private fun current() {
        if (Language.currentState() !== owner) fault("Process signals belong to another context")
    }

    @Synchronized @TruffleBoundary(transferToInterpreterOnException = false)
    fun install(signal: Long, action: Long, mask: ManagedAddress): Long {
        current()
        if (!authorized) fault("Process signals require explicit NativeIO launcher authority")
        if (signal != 2L || (action != -1L && action != -2L && action != -4L && action != -5L) ||
            mask !== ManagedAddress.nullAddress())
            fault("stg_sig_install supports only SIGINT, DFL/IGN/HAN/RST and a null mask")
        if (closed || stopping) fault("Process signal service is closed")
        val root = binding ?: fault("Missing original bytecode signal dispatcher")
        val native = transport ?: factory().also { acquired ->
            transport = acquired
            val child = owner.env.newTruffleThreadBuilder(Runnable { consume(acquired, root) }).build()
            worker = child
            try { child.start() } catch (failure: Throwable) {
                transport = null; worker = null; closed = true; acquired.close(); throw failure
            }
        }
        val result = native.install(action.toInt())
        if (result.action == -3) owner.stdio.nativeError(result.errno.toLong())
        return result.action.toLong()
    }

    private fun consume(native: ProcessSignalTransport, root: SignalDispatchRoot) {
        var registered = false
        var failure: Throwable? = null
        var outcome = GuestThreadStatus.FINISHED
        var pending: ByteArray? = null
        val interrupted = AtomicBoolean()
        val interrupter = object : TruffleSafepoint.Interrupter {
            override fun interrupt(thread: Thread) { interrupted.set(true); native.wake() }
            override fun resetInterrupted() { native.resetWake(); interrupted.set(false) }
        }
        try {
            owner.threads.enterCurrent(MaskingState.UNMASKED, forked = true)
            registered = true
            while (!stopping) {
                val image = TruffleSafepoint.getCurrent().setBlockedFunction(null, interrupter,
                    TruffleSafepoint.InterruptibleFunction<Unit, ByteArray?> {
                        if (interrupted.get()) throw InterruptedException()
                        if (stopping) null else (pending ?: native.take().also { pending = it }).also {
                            if (interrupted.get()) throw InterruptedException()
                        }
                    }, Unit, null, null)
                if (image != null && !stopping) {
                    pending = null
                    val address = owner.nativeAllocations.malloc(image.size.toLong())
                    if (address === ManagedAddress.nullAddress()) fault("Cannot allocate signal information")
                    image.indices.forEach { address.writeWord8(it.toLong(), image[it].toLong() and 255L) }
                    // Ownership is transferred to original newForeignPtr(&free).
                    // On a guest failure the context registry still owns the base.
                    root.callTarget.call(address)
                }
            }
        } catch (caught: Throwable) {
            outcome = GuestThreadStatus.uncaught(caught)
            // closeExited/closeCancelled use ThreadDeath as hard control flow.
            // A stop request never makes it an ignorable reader failure.
            if (caught is ThreadDeath || !stopping) failure = caught
        } finally {
            failure = finishSignalConsumer(failure, {
                synchronized(this) {
                    closed = true; stopping = true
                    try { native.close() } finally { transport = null }
                }
            }, { if (registered) owner.threads.leaveCurrent(outcome) })
        }
        if (failure != null) owner.env.context.closeCancelled(null, "Process signal dispatcher failed: ${failure!!.javaClass.simpleName}")
    }

    /** Exit notification only: no guest call, context lookup or safepoint join.
     * The reader wakes (or receives hard-exit ThreadDeath), then restores the
     * native disposition in its host-only cleanup after unregistering the
     * blocked-state interrupter. Context closure waits for that owned thread. */
    @Synchronized fun requestStop() {
        stopping = true
        transport?.wake()
        if (worker == null) closed = true
    }

    /** Stop before leaving runIO; finalizeContext also covers cancellation. The
     * reader owns destruction after its safepoint interrupter is unregistered. */
    fun close() {
        requestStop()
        val child = synchronized(this) { worker }
        if (child != null && child !== Thread.currentThread() && child.isAlive)
            TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<Thread, Unit> { it.join() }, child)
    }

    companion object {
        @JvmStatic fun install(node: Node, signal: Long, action: Long, mask: ManagedAddress): Long =
            Language.currentState(node).signals.install(signal, action, mask)
    }
}

/** Run both host cleanup steps, preserving hard Truffle control flow even if
 * restoration or thread bookkeeping also fails. Never translate ThreadDeath
 * into closeCancelled, and never let a cleanup exception replace it. */
internal fun finishSignalConsumer(initial: Throwable?, restore: () -> Unit, unregister: () -> Unit): Throwable? {
    var failure = initial
    for (cleanup in listOf(restore, unregister)) try { cleanup() } catch (caught: Throwable) {
        val previous = failure
        when {
            previous === caught -> Unit
            previous == null -> failure = caught
            caught is ThreadDeath && previous !is ThreadDeath -> {
                caught.addSuppressed(previous)
                failure = caught
            }
            else -> previous.addSuppressed(caught)
        }
    }
    val result = failure
    if (result is ThreadDeath) throw result
    return result
}

/** CInt is erased to boxed Int32; Ptr's exact AddrRep field carries the owned
 * image. Both layouts come from this program, including case identity. */
private class SignalDispatchRoot(language: Language, program: ExecutableProgram) : RootNode(language, FrameLayout().build()) {
    private val action = program.entryValue(CoreSignalForeign.dispatcher)
    private val pointer = program.constructorLayout("ghc-internal:GHC.Internal.Ptr.Ptr")
    private val signal = program.constructorLayout("ghc-internal:GHC.Internal.Int.I32#")
    private val result = CoreRepresentation(CoreKind.UNKNOWN, present = true,
        primReps = listOf("BoxedRep (Just Lifted)"), components = listOf(
            CoreRepresentation(CoreKind.VOID, true, true, emptyList()),
            CoreRepresentation(CoreKind.DATA, false, true, listOf("BoxedRep (Just Lifted)"))))
    private val shape = TupleShape(result, language)
    @Child private var force = Force(Metrics(false), true)
    @Child private var unitForce = Force(Metrics(false), true)
    @Child private var dispatch = TupleDispatch(IoUnitDestination(shape, language), Metrics(false), 3, false)
    init {
        if (pointer.arity != 1 || !pointer.hasFieldRepresentation(0, "AddrRep") ||
            signal.arity != 1 || !signal.hasFieldRepresentation(0, "Int32Rep"))
            fault("Original signal dispatcher requires exact Ptr/Int32 constructor layouts")
    }
    override fun execute(frame: VirtualFrame): Any {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L)
        val closure = requireClosure(force.execute(frame, action))
        val target = closure.target.rootNode as? BytecodeRoot ?: fault("Signal dispatcher must be bytecode")
        if (!target.isAsyncEnabled || target.tupleResult?.matches(shape) != true)
            fault("Signal dispatcher requires an async IO unit tuple")
        dispatch.execute(frame, closure, arrayOf(pointer.create(arrayOf(frame.arguments[0])), signal.createLong(2L), Unit))
        val unit = unitForce.execute(frame, frame.getObject(FrameLayout.TAIL_RESULT)) as? DataValue
            ?: fault("Signal dispatcher did not return boxed unit")
        if (unit.layout.id != "ghc-internal:GHC.Internal.Tuple.()" || unit.layout.arity != 0)
            fault("Signal dispatcher did not return boxed unit")
        return Unit
    }
    override fun getName() = "THC original SIGINT dispatcher"
}
