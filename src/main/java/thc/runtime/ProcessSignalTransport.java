// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
public interface ProcessSignalTransport extends AutoCloseable {
    record Result(int action, int errno) {
        public int getAction() { return action; }
        public int getErrno() { return errno; }
        @Override public String toString() { return "Result(action=" + action + ", errno=" + errno + ")"; }
    }
    record Event(int signal, byte[] info) {
        public int getSignal() { return signal; }
        public byte[] getInfo() { return info; }
        @Override public int hashCode() { return 31 * signal + java.util.Arrays.hashCode(info); }
        @Override public String toString() { return "Event(signal=" + signal + ", info=" + java.util.Arrays.toString(info) + ")"; }
    }
    Result install(int signal, int action);
    Event take();
    void wake();
    void resetWake();
    @Override void close();
}
