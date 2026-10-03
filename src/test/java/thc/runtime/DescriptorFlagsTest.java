// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.ContextProfile;
import static org.junit.jupiter.api.Assertions.*;

/** Observable append/resize policy for THC-owned file descriptors. No Core fixture. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class DescriptorFlagsTest {
    @TempDir Path directory;
    private Context context() { return NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST); }
    @Test void appendChangesAffectActualWritesAndPrivateExtensionPolicy() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var abi = StdioHostAbi.load(); var path = directory.resolve("append"); Files.writeString(path,"abc"); long fd = state.getStdio().open(ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8)),abi.flagConstant(OriginalStdioOp.O_RDWR),0);
                long get = abi.flagConstant(OriginalStdioOp.F_GETFL), set = abi.flagConstant(OriginalStdioOp.F_SETFL); assertEquals(-1L,state.getStdio().fcntl(1,get,0,false),"ungranted embedding stream is not a host fd"); assertEquals(abi.error(7),state.getStdio().errno());
                long original = state.getStdio().fcntl(fd,get,0,false); assertEquals(0L,state.getStdio().fcntl(fd,set,original | abi.flagConstant(OriginalStdioOp.O_APPEND),true)); assertEquals(1L,state.getStdio().write(fd,ManagedAddress.fromByteArray(new byte[]{90}),1)); assertEquals("abcZ",Files.readString(path));
                assertEquals(-1L,state.getFiles().setSize(fd,8)); assertEquals(0L,state.getStdio().fcntl(fd,set,original,true)); assertEquals(0L,state.getFiles().setSize(fd,8)); assertEquals(8L,Files.size(path)); assertEquals(0L,state.getStdio().close(fd));
            } finally { context.leave(); }
        }
    }
}
