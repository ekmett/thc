// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Versioned JVM file operations, deliberately independent of any platform C ABI. */
public enum ManagedFileOp {
    OPEN("thc_io_v1_open", Collections.unmodifiableList(Arrays.asList("AddrRep", "IntRep", null)), "IntRep"),
    READ("thc_io_v1_read", Collections.unmodifiableList(Arrays.asList("IntRep", "AddrRep", "IntRep", null)), "IntRep"),
    WRITE("thc_io_v1_write", Collections.unmodifiableList(Arrays.asList("IntRep", "AddrRep", "IntRep", null)), "IntRep"),
    CLOSE("thc_io_v1_close", Collections.unmodifiableList(Arrays.asList("IntRep", null)), "IntRep"),
    ERROR_KIND("thc_io_v1_error_kind", Collections.singletonList(null), "IntRep"),
    ERROR_MESSAGE("thc_io_v1_error_message", Collections.singletonList(null), "AddrRep"),
    SEEK("thc_io_v1_seek", Collections.unmodifiableList(Arrays.asList("IntRep", "IntRep", "IntRep", null)), "IntRep"),
    SIZE("thc_io_v1_size", Collections.unmodifiableList(Arrays.asList("IntRep", null)), "IntRep"),
    SET_SIZE("thc_io_v1_set_size", Collections.unmodifiableList(Arrays.asList("IntRep", "IntRep", null)), "IntRep"),
    IS_TERMINAL("thc_io_v1_is_terminal", Collections.unmodifiableList(Arrays.asList("IntRep", null)), "IntRep"),
    DEVICE_TYPE("thc_io_v1_device_type", Collections.unmodifiableList(Arrays.asList("IntRep", null)), "IntRep");

    private final String symbol;
    private final List<String> arguments;
    private final String result;
    ManagedFileOp(String symbol, List<String> arguments, String result) {
        this.symbol = symbol; this.arguments = arguments; this.result = result;
    }
    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
}
