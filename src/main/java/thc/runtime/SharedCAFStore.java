// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Selected GHC 9.14.1 RTS slots holding context-owned shared-CAF StablePtrs. */
public enum SharedCAFStore {
    EVENT_MANAGER("getOrSetSystemEventThreadEventManagerStore"),
    EVENT_MANAGER_THREAD("getOrSetSystemEventThreadIOManagerThreadStore"),
    TIMER_MANAGER("getOrSetSystemTimerThreadEventManagerStore"),
    TIMER_MANAGER_THREAD("getOrSetSystemTimerThreadIOManagerThreadStore"),
    SIGNAL_HANDLER("getOrSetGHCConcSignalSignalHandlerStore"),
    WINDOWS_PENDING_DELAYS("getOrSetGHCConcWindowsPendingDelaysStore"),
    WINDOWS_IO_MANAGER_THREAD("getOrSetGHCConcWindowsIOManagerThreadStore"),
    WINDOWS_PRODDING("getOrSetGHCConcWindowsProddingStore"),
    FAST_STRING("getOrSetLibHSghcFastStringTable", "ghc-9.14.1-inplace"),
    PPR_DEBUG("getOrSetLibHSghcGlobalHasPprDebug", "ghc-9.14.1-inplace"),
    NO_DEBUG_OUTPUT("getOrSetLibHSghcGlobalHasNoDebugOutput", "ghc-9.14.1-inplace"),
    NO_STATE_HACK("getOrSetLibHSghcGlobalHasNoStateHack", "ghc-9.14.1-inplace");

    private final String symbol;
    private final String unit;
    SharedCAFStore(String symbol) { this(symbol, "ghc-internal"); }
    SharedCAFStore(String symbol, String unit) { this.symbol = symbol; this.unit = unit; }
    public String getSymbol() { return symbol; }
    public String getUnit() { return unit; }
    public static SharedCAFStore named(Object symbol) {
        for (var store : values()) if (store.symbol.equals(symbol)) return store;
        return null;
    }
}
