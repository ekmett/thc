// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

@ExportLibrary(InteropLibrary.class)
final class ForeignProtocolFailure extends AbstractTruffleException {
    private final ExceptionType kind;
    private final boolean brokenClassifier;
    private final Throwable classifierFailure;
    int metadataReads;
    ForeignProtocolFailure(ExceptionType kind, boolean brokenClassifier, Throwable classifierFailure) {
        super("inert test failure");
        this.kind = kind; this.brokenClassifier = brokenClassifier; this.classifierFailure = classifierFailure;
    }
    @ExportMessage ExceptionType getExceptionType() {
        if (classifierFailure != null) return rethrow(classifierFailure);
        if (brokenClassifier) throw new IllegalStateException("broken classification protocol");
        return kind;
    }
    // The foreign protocol must preserve even checked interruption/control failures verbatim.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> ExceptionType rethrow(Throwable failure) throws T { throw (T) failure; }
    @ExportMessage boolean hasExceptionMessage() { return true; }
    @ExportMessage Object getExceptionMessage() {
        metadataReads++;
        throw new IllegalStateException("foreign message accessor failed");
    }
}

class ForeignExceptionPolicyTest {
    private static final class PrivateTransfer extends AbstractTruffleException implements InternalGuestControl {
        PrivateTransfer() { super("private transfer"); }
    }
    @Test void classificationNeverCallsForeignMessageAndPreservesControlKinds() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var classifier = new RootNode(language) {
                    @Child private ForeignExceptionAccess access = new ForeignExceptionAccess();
                    @Override public Object execute(VirtualFrame frame) {
                        return access.eligible$org_intelligence_thc((AbstractTruffleException) frame.getArguments()[0]);
                    }
                }.getCallTarget();
                for (var kind : ExceptionType.values()) {
                    var error = new ForeignProtocolFailure(kind, false, null);
                    assertEquals(kind == ExceptionType.RUNTIME_ERROR || kind == ExceptionType.PARSE_ERROR, classifier.call(error));
                    assertEquals(0, error.metadataReads);
                }
                var invalid = new ForeignProtocolFailure(ExceptionType.RUNTIME_ERROR, true, null);
                assertEquals(false, classifier.call(invalid), "Failed classification must preserve the original foreign failure");
                assertEquals(0, invalid.metadataReads);
                for (var control : List.of(new InterruptedException(), new CancellationException(),
                    new RuntimeFault("classifier invariant"), new AssertionError("classifier infrastructure"), new PrivateTransfer())) {
                    var error = new ForeignProtocolFailure(ExceptionType.RUNTIME_ERROR, false, control);
                    assertSame(control, assertThrows(Throwable.class, () -> classifier.call(error)));
                    assertEquals(0, error.metadataReads);
                }
            } finally { context.leave(); }
        }
    }
    @Test void applicationHostFailuresAreDistinctFromFatalCancellationAndRuntimeFailures() {
        assertTrue(ForeignExceptionPolicy.INSTANCE.host(new IllegalStateException("application")));
        assertTrue(ForeignExceptionPolicy.INSTANCE.host(new AssertionError("application assertion")));
        assertFalse(ForeignExceptionPolicy.INSTANCE.host(new VirtualMachineError("synthetic classification control") {}));
        assertFalse(ForeignExceptionPolicy.INSTANCE.host(new LinkageError("infrastructure")));
        assertFalse(ForeignExceptionPolicy.INSTANCE.host(new InterruptedException()));
        assertFalse(ForeignExceptionPolicy.INSTANCE.host(new CancellationException()));
        assertFalse(ForeignExceptionPolicy.INSTANCE.host(new RuntimeFault("runtime invariant")));
    }
    @Test void unknownPrimitivePayloadIsNotProjectedOrForcedAtPublicExit() {
        var opaque = new Object();
        var location = new Node() {};
        var failure = new GuestException(opaque, location);
        var access = new ForeignExceptionAccess();
        assertSame(failure, assertThrows(GuestException.class, () -> access.escaping(failure)));
        assertSame(opaque, failure.getPayload());
    }
}
