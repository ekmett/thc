// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.channels.SeekableByteChannel;

public interface NativeFileResource extends SeekableByteChannel {
    long unlinkAt(byte[] path, int flags);
    byte[] statAt(byte[] path, int flags);
    byte[] statImage();
    void readTermios(byte[] image);
    long terminalStatus();
    void writeTermios(int action, byte[] image);
    long statusFlags();
    long fcntl(int command, long argument, boolean hasArgument);
    boolean fcntlCreatesDescriptor(int command);
    NativeFileResource fcntlDuplicate(int command, long minimum);
    long writeEvent(long value);
    /** Private owned duplicate; never a guest descriptor number. */
    int duplicateDescriptor();
    /** Private owned duplicate, or -1 when FD_CLOEXEC excludes inheritance. */
    int duplicateInheritableDescriptor();
    void requireLive();
    NativeFdWait readinessWait();
}
