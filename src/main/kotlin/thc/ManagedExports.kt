// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.interop.ArityException
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnknownIdentifierException
import com.oracle.truffle.api.interop.UnsupportedTypeException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import thc.runtime.*
import java.math.BigInteger

/** One load transaction owns a program. No guest evaluation runs under this monitor. */
internal class ManagedExportRegistry(private val owner: Language.State, private val language: Language) {
    @Volatile private var closed = false
    @Volatile private var units: Map<String, Any> = emptyMap()
    private var loading = false
    private var loaded = false
    val scope = ManagedExportNamespace(this, "THC managed exports") { units }

    fun checkOwner() {
        if (closed || Language.currentState(null) !== owner)
            throw RuntimeFault("Managed export belongs to another or closed THC context")
    }

    @CompilerDirectives.TruffleBoundary
    fun load(plan: ManagedExportPlan): ManagedExportNamespace {
        checkOwner()
        synchronized(this) {
            if (loading || loaded) throw RuntimeFault("This THC context already has a managed export bundle")
            loading = true
        }
        try {
            (plan.linked["packageScalarLinks"] as List<PackageScalarLink>).forEach { owner.packageCbits.link(it) }
            val program: ExecutableProgram = when (plan.backend) {
                "ast" -> Program(language, plan.linked)
                "bytecode" -> BytecodeProgram(language, plan.linked, true)
                else -> error("Invalid managed backend")
            }
            val namespace = plan.exports.groupBy { it.unit }.mapValues { (unit, unitExports) ->
                val modules = unitExports.groupBy { it.module }.mapValues { (module, exports) ->
                    val symbols = exports.associate { signature -> signature.symbol to
                        ManagedExportValue(this, owner, language, program, signature) }
                    ManagedExportNamespace(this, "$unit:$module") { symbols }
                }
                ManagedExportNamespace(this, unit) { modules }
            }
            synchronized(this) {
                checkOwner()
                @Suppress("UNCHECKED_CAST")
                owner.foreignRoots.retain(program,
                    plan.linked["managedRegistrations"] as List<ManagedExportAdmission>)
                units = namespace
                loaded = true
            }
            return scope
        } finally { synchronized(this) { loading = false } }
    }

    @Synchronized fun close() { closed = true; units = emptyMap() }
}

/** Read-only member names retain exact GHC unit/module and external symbol identities. */
@ExportLibrary(InteropLibrary::class)
internal class ManagedExportNamespace(private val registry: ManagedExportRegistry,
    private val description: String, private val members: () -> Map<String, Any>) : TruffleObject {
    @ExportMessage fun hasMembers(): Boolean { registry.checkOwner(); return true }
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun getMembers(includeInternal: Boolean): Any {
        registry.checkOwner()
        return MemberNames(members().keys.toTypedArray())
    }
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun isMemberReadable(member: String): Boolean {
        registry.checkOwner()
        return member in members()
    }
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun readMember(member: String): Any {
        registry.checkOwner()
        return members()[member] ?: throw UnknownIdentifierException.create(member)
    }
    @ExportMessage fun isScope(): Boolean { registry.checkOwner(); return true }
    @ExportMessage fun hasLanguage() = true
    @ExportMessage fun getLanguage(): Class<out TruffleLanguage<*>> = Language::class.java
    @ExportMessage fun toDisplayString(allowSideEffects: Boolean): String = description
}

/** A declaration alias uses the same program-owned binder, layouts and CAFs as its peers. */
@ExportLibrary(InteropLibrary::class)
internal class ManagedExportValue(private val registry: ManagedExportRegistry, private val owner: Language.State,
    language: Language, internal val program: ExecutableProgram, private val signature: ManagedExportSignature) : TruffleObject {
    private val arguments = signature.arguments.map {
        ManagedExportScalar.fromNormalizedType(it, ManagedExportScalar.Role.ARGUMENT, signature.wordBits, program::constructorLayout)
    }
    private val result = ManagedExportScalar.fromNormalizedType(signature.result,
        ManagedExportScalar.Role.RESULT, signature.wordBits, program::constructorLayout)
    internal val guestTarget = program.hostEntryTarget(arguments.size)
    private val guestEntry = program.entryValue(signature.binder)
    internal val ioTarget = signature.ioResult?.let { ManagedExportIoRoot(language, it).callTarget }

    @ExportMessage fun isExecutable(): Boolean { registry.checkOwner(); return true }
    @ExportMessage fun execute(values: Array<Any?>,
        @Cached(value = "create()", uncached = "create()", neverDefault = true) dispatch: HostDispatch): Any? {
        registry.checkOwner()
        if (values.size != arguments.size) throw ArityException.create(arguments.size, arguments.size, values.size)
        // Complete all host validation before an IO action can run. A Java BigInteger
        // arrives as a HostObject through Value.execute; unwrap only this exact codec.
        val inputs = try {
            Array<Any?>(values.size) { index ->
                val value = values[index]
                val unwrapped = if (value != null && owner.env.isHostObject(value))
                    owner.env.asHostObject(value).let { if (it is BigInteger) it else value } else value
                arguments[index].fromHost(unwrapped)
            }
        } catch (failure: RuntimeFault) { throw UnsupportedTypeException.create(values, failure.message) }
        val threads = owner.threads
        threads.enterCurrent()
        var outcome = GuestThreadStatus.FINISHED
        try {
            try {
                val applied = AsyncContinuations.publicResult(
                    dispatch.executePublic(guestTarget, arrayOf(guestEntry, inputs)), dispatch)
                val boxed = if (ioTarget == null) applied else AsyncContinuations.publicResult(
                    dispatch.executePublic(ioTarget, arrayOf(applied)), dispatch)
                return result.toHost(boxed)
            } catch (suspended: ThunkSuspended) {
                AsyncContinuations.publicSuspension(suspended, dispatch)
            } catch (suspended: CallSegmentSuspended) {
                AsyncContinuations.publicSuspension(suspended, dispatch)
            }
        } catch (failure: Throwable) {
            outcome = GuestThreadStatus.uncaught(failure)
            throw failure
        } finally { threads.leaveCurrent(outcome) }
    }
    @ExportMessage fun hasLanguage() = true
    @ExportMessage fun getLanguage(): Class<out TruffleLanguage<*>> = Language::class.java
    @ExportMessage fun toDisplayString(allowSideEffects: Boolean): String =
        "${signature.unit}:${signature.module}/${signature.symbol}"
}
