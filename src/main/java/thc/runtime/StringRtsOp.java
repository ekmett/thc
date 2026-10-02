// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Original Posix and ByteString declarations with their exact installed units. */
public enum StringRtsOp {
    STRLEN("strlen", Arrays.asList("AddrRep", null), "ghc-internal", "IntRep"),
    STRLEN_CSIZE("strlen", Arrays.asList("AddrRep", null), "bytestring-0.12.2.0-inplace", "Word64Rep"),
    THREADED("rts_isThreaded", Collections.singletonList(null), "ghc-internal", "IntRep"),
    KEEP_CAFS("keepCAFsForGHCi", Collections.singletonList(null), "ghc-9.14.1-inplace", "IntRep"),
    HOST_DYNAMIC("rts_isDynamic", Collections.singletonList(null), "ghc-9.14.1-inplace", "IntRep"),
    HOST_PROFILED("rts_isProfiled", Collections.singletonList(null), "ghc-9.14.1-inplace", "IntRep"),
    HOST_THREADED("rts_isThreaded", Collections.singletonList(null), "ghc-9.14.1-inplace", "IntRep"),
    HOST_DEBUGGED("rts_isDebugged", Collections.singletonList(null), "ghc-9.14.1-inplace", "IntRep"),
    HOST_TRACING("rts_isTracing", Collections.singletonList(null), "ghc-9.14.1-inplace", "IntRep");

    private final String symbol;
    private final List<String> arguments;
    private final String unit;
    private final String result;

    StringRtsOp(String symbol, List<String> arguments, String unit, String result) {
        this.symbol = symbol;
        this.arguments = Collections.unmodifiableList(arguments);
        this.unit = unit;
        this.result = result;
    }

    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getUnit() { return unit; }
    public String getResult() { return result; }

    public boolean isHostWay() {
        return switch (this) {
            case HOST_DYNAMIC, HOST_PROFILED, HOST_THREADED, HOST_DEBUGGED, HOST_TRACING -> true;
            default -> false;
        };
    }

    /** Compiler object compatibility comes from its admitted producing layout.
     * THC uses the nonthreaded RTS contract and supplies neither GHC DEBUG nor
     * native eventlog modes; JVM diagnostics and stderr traces are independent. */
    public long constantValue(Object targetLayout) {
        if (isHostWay()) {
            if (!(targetLayout instanceof TargetLayout layout))
                throw RuntimeFault.fault("Compiler host-way query requires an admitted target layout");
            // TargetLayout admits only nonprofiling object layouts.
            return this == HOST_DYNAMIC && layout.getWay().equals("dynamic-nonprofiling") ? 1L : 0L;
        }
        // Live THC programs retain CAFs without native RTS CAF reversion.
        return this == KEEP_CAFS ? 1L : 0L;
    }

    public boolean acceptsUnit(Object value) {
        return this == STRLEN_CSIZE ? value instanceof String name && name.matches("bytestring-0\\.12\\.2\\.0(?:-[A-Za-z0-9]+)?") : unit.equals(value);
    }
}
