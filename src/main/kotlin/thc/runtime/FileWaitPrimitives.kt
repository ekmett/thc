// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import java.nio.channels.ClosedChannelException
import thc.Language

/** GHC 9.14.1's RTS-visible bad-descriptor closure, retained lazily. */
internal object CoreFileWait {
    const val badFd = "ghc-internal:GHC.Internal.Event.Thread.blockedOnBadFD"

    fun named(name: String): Boolean = name == "waitRead#" || name == "waitWrite#"

    fun validate(name: String, args: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun state(rep: CoreRepresentation) = !rep.isAggregate && !rep.isVector &&
            rep.kind == CoreKind.VOID && rep.primReps == emptyList<String>()
        val fd = args.firstOrNull()
        if (!named(name) || args.size != 2 || fd == null || fd.kind != CoreKind.LONG ||
            fd.isAggregate || fd.isVector || fd.primReps != listOf("IntRep") ||
            !state(args[1]) || flags != listOf(false, false) || !state(result))
            throw RuntimeFault("$name: expected exact Int#, State# -> State# contract")
    }
}

@TruffleBoundary private fun badFileDescriptor(payload: GlobalBinding, node: Node): Nothing =
    throw GuestException(payload.read(), node)

/** One logical token survives a captured async cut; a new poll request does not. */
internal class WaitFileDescriptor(@field:Child private var fd: Expr,
    @field:Child private var state: Expr, private val payload: GlobalBinding,
    private val writing: Boolean, private val async: Boolean, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }

    private class Resume(private val node: WaitFileDescriptor, private val token: ManagedFiles.WaitToken) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any {
            if (input !== Unit) fault("Invalid descriptor-wait resume value")
            node.await(token)
            return Unit
        }
    }

    private fun await(token: ManagedFiles.WaitToken) {
        try { token.await(this, async) }
        catch (blocked: AsyncBlocked) {
            throw AstCapture(blocked.request, SynchronousMasking.current(this)).append(Resume(this, token))
        }
        catch (_: ClosedChannelException) { badFileDescriptor(payload, this) }
    }

    override fun execute(frame: VirtualFrame): Any {
        val descriptor = fd.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        await(Language.currentState(this).files.waitToken(descriptor, writing))
        return Unit
    }
}

/** Java bytecode operations use these package functions to keep WaitToken private. */
internal fun prepareFileWait(fd: Long, state: Any?, writing: Boolean, node: Node): Any {
    requireVoidCarrier(state)
    return Language.currentState(node).files.waitToken(fd, writing)
}

internal fun awaitFileWait(token: Any?, payload: GlobalBinding, async: Boolean, node: Node): Any {
    val saved = token as? ManagedFiles.WaitToken ?: fault("Invalid descriptor-wait token")
    try { saved.await(node, async) }
    catch (_: ClosedChannelException) { badFileDescriptor(payload, node) }
    return Unit
}
