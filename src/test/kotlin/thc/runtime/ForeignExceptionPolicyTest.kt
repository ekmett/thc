// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.ExceptionType
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

@ExportLibrary(InteropLibrary::class)
class ForeignProtocolFailure(private val kind: ExceptionType, private val brokenClassifier: Boolean = false,
    private val classifierFailure: Throwable? = null) :
    AbstractTruffleException("inert test failure") {
    var metadataReads = 0
    @ExportMessage fun getExceptionType(): ExceptionType {
        classifierFailure?.let { throw it }
        if (brokenClassifier) throw IllegalStateException("broken classification protocol")
        return kind
    }
    @ExportMessage fun hasExceptionMessage() = true
    @ExportMessage fun getExceptionMessage(): Any {
        metadataReads++
        throw IllegalStateException("foreign message accessor failed")
    }
}

class ForeignExceptionPolicyTest {
    @Test fun classificationNeverCallsForeignMessageAndPreservesControlKinds() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val classifier = object : RootNode(language) {
                    @Child var access = ForeignExceptionAccess()
                    override fun execute(frame: VirtualFrame): Any = access.eligible(frame.arguments[0] as AbstractTruffleException)
                }.callTarget
                for (kind in ExceptionType.entries) {
                    val error = ForeignProtocolFailure(kind)
                    assertEquals(kind in listOf(ExceptionType.RUNTIME_ERROR, ExceptionType.PARSE_ERROR), classifier.call(error))
                    assertEquals(0, error.metadataReads)
                }
                val invalid = ForeignProtocolFailure(ExceptionType.RUNTIME_ERROR, true)
                assertEquals(false, classifier.call(invalid), "Failed classification must preserve the original foreign failure")
                assertEquals(0, invalid.metadataReads)
                for (control in listOf(InterruptedException(), java.util.concurrent.CancellationException(),
                        RuntimeFault("classifier invariant"), AssertionError("classifier infrastructure"),
                        object : AbstractTruffleException("private transfer"), InternalGuestControl {})) {
                    val error = ForeignProtocolFailure(ExceptionType.RUNTIME_ERROR, classifierFailure = control)
                    assertSame(control, assertThrows(Throwable::class.java) { classifier.call(error) })
                    assertEquals(0, error.metadataReads)
                }
            } finally { context.leave() }
        }
    }

    @Test fun applicationHostFailuresAreDistinctFromFatalCancellationAndRuntimeFailures() {
        assertTrue(ForeignExceptionPolicy.host(IllegalStateException("application")))
        assertTrue(ForeignExceptionPolicy.host(AssertionError("application assertion")))
        assertFalse(ForeignExceptionPolicy.host(object : VirtualMachineError("synthetic classification control") {}))
        assertFalse(ForeignExceptionPolicy.host(LinkageError("infrastructure")))
        assertFalse(ForeignExceptionPolicy.host(InterruptedException()))
        assertFalse(ForeignExceptionPolicy.host(java.util.concurrent.CancellationException()))
        assertFalse(ForeignExceptionPolicy.host(RuntimeFault("runtime invariant")))
    }

    @Test fun unknownPrimitivePayloadIsNotProjectedOrForcedAtPublicExit() {
        val opaque = Any()
        val location = object : com.oracle.truffle.api.nodes.Node() {}
        val failure = GuestException(opaque, location)
        val access = ForeignExceptionAccess()
        assertSame(failure, assertThrows(GuestException::class.java) { access.escaping(failure) })
        assertSame(opaque, failure.payload)
    }
}
