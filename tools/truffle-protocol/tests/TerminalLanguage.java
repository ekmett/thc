// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
package terminalprobe;

import com.oracle.truffle.api.TruffleLanguage;

@TruffleLanguage.Registration(id = "terminal-probe", name = "Terminal probe", version = "1")
public final class TerminalLanguage extends TruffleLanguage<Object> {
    @Override protected Object createContext(Env env) { return new Object(); }
}
