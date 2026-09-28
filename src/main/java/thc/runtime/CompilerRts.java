// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import thc.Language;

/** Context-owned original RTS unique-supply cells; atomics lock their backing arrays. */
public final class CompilerRts {
    private final ManagedAddress counter = ManagedAddress.compilerCell(new byte[8], this);
    private final ManagedAddress increment = ManagedAddress.compilerCell(
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(1L).array(), this);
    private ManagedAddress flags;
    private volatile boolean closed;

    public void requireCurrent() {
        if (closed || Language.currentState(null).getCompilerRts() != this)
            throw RuntimeFault.fault("Compiler RTS cell belongs to another or closed THC context");
    }

    public ManagedAddress address(String symbol) {
        requireCurrent();
        return switch (symbol) {
            case "ghc_unique_counter64" -> counter;
            case "ghc_unique_inc" -> increment;
            default -> throw RuntimeFault.fault("Unknown compiler RTS data label " + symbol);
        };
    }

    /** Preserve lazy, synchronized publication and retry after initialization failure. */
    private synchronized ManagedAddress flags() {
        if (flags == null) flags = ManagedAddress.rtsFlags(this);
        return flags;
    }

    /** Pinned GHC 9.14.1 Flags/Test.hs reads TraceFlags +392 and user +11. */
    public ManagedAddress flagsAddress(TargetLayout layout) {
        requireCurrent();
        if (layout == null || !layout.getCompilerId().equals("ghc-9.14.1") ||
            !layout.getCompilerAbi().equals("inplace") || !layout.getPlatform().equals("x86_64-linux") ||
            !layout.getWay().equals("dynamic-nonprofiling") || layout.getWordBytes() != 8 ||
            !layout.getEndianness().equals("little"))
            throw RuntimeFault.fault("RtsFlags requires the supported GHC 9.14.1 Linux producing layout");
        return flags();
    }

    public long readFlagByte(long byteOffset) {
        requireCurrent();
        if (byteOffset != 403L) {
            CompilerDirectives.transferToInterpreter();
            throw RuntimeFault.fault("Unsupported RtsFlags byte field at offset " + byteOffset);
        }
        // User trace always emits to context stderr; this is not native eventlog status.
        return 1L;
    }

    public void close() { closed = true; }
}
