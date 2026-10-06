// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import thc.Language;

/** Context-owned original RTS unique-supply cells; atomics lock their backing arrays. */
public final class CompilerRts {
    private final ManagedAddress counter = ManagedAddress.compilerCell(new byte[8], this);
    private final ManagedAddress increment = ManagedAddress.compilerCell(
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(1L).array(), this);
    private ManagedAddress flags;
    private TargetLayout flagsLayout;
    private long userFlagOffset;
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
    private synchronized ManagedAddress flags(TargetLayout layout) {
        if (flags == null) {
            var address = ManagedAddress.rtsFlags(this);
            userFlagOffset = (long) layout.offset("rtsTraceFlagsOffset") + layout.offset("traceUserOffset");
            flagsLayout = layout;
            flags = address;
        } else if (!flagsLayout.equals(layout)) {
            throw RuntimeFault.fault("RtsFlags producing layout differs from this context's RTS view: " +
                flagsLayout.getCompilerId() + "/" + flagsLayout.getCompilerAbi() + "/" + flagsLayout.getPlatform() + "/" + flagsLayout.getWay() +
                " user byte " + userFlagOffset + "; received " + layout.getCompilerId() + "/" + layout.getCompilerAbi() +
                "/" + layout.getPlatform() + "/" + layout.getWay() + " user byte " +
                ((long) layout.offset("rtsTraceFlagsOffset") + layout.offset("traceUserOffset")));
        }
        return flags;
    }

    /** The selected GHC headers prove the original TraceFlags.user byte getter. */
    @TruffleBoundary public ManagedAddress flagsAddress(TargetLayout layout) {
        requireCurrent();
        if (layout == null || !layout.hasRtsFlags())
            throw RuntimeFault.fault("RtsFlags requires selected-GHC RTS flag layout metadata");
        return flags(layout);
    }

    public long readFlagByte(long byteOffset) {
        requireCurrent();
        if (flagsLayout == null || byteOffset != userFlagOffset) {
            CompilerDirectives.transferToInterpreter();
            throw RuntimeFault.fault("Unsupported RtsFlags byte field at offset " + byteOffset);
        }
        // Selected context sink, independent of process-wide JFR recording state.
        return Language.currentState(null).getRuntimeTrace().isPrimopDisabled() ? 0L : 1L;
    }

    public void close() { closed = true; }
}
