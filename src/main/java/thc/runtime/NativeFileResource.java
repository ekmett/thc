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
    long setStatusFlags(long flags);
    long setDescriptorFlags(long flags);
    long writeEvent(long value);
    /** Private owned duplicate; never a guest descriptor number. */
    int duplicateDescriptor();
    void requireLive();
    NativeFdWait readinessWait();
}
