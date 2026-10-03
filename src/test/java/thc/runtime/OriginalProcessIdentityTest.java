// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** THC's native-access boundary; no package acquisition or cached process IDs. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class OriginalProcessIdentityTest {
    @Test void nativePermissionRejectsBeforeChangingContextErrno() {
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.NONE).allowNativeAccess(false).build()) {
            context.initialize("thc"); context.enter();
            try {
                var stdio = Language.currentState().getStdio();
                stdio.setErrno(-23L);
                for (var operation : new OriginalStdioOp[]{OriginalStdioOp.GET_PID, OriginalStdioOp.GET_EUID}) {
                    var failure = assertThrows(RuntimeFault.class, () -> ProcessIdentity.query(null, operation));
                    assertTrue(failure.getMessage().contains("requires native access"));
                    assertEquals(-23L, stdio.errno());
                }
            } finally { context.leave(); }
        }
    }

    @Test void liveQueryUsesTheCurrentProcessAndPreservesContextErrno() {
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.NONE).allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var stdio = Language.currentState().getStdio();
                stdio.setErrno(17L);
                assertEquals(ProcessHandle.current().pid(), ProcessIdentity.query(null, OriginalStdioOp.GET_PID));
                assertEquals(17L, stdio.errno());
            } finally { context.leave(); }
        }
    }
}
