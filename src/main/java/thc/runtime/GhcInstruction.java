// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public final class GhcInstruction {
    final int opcode;
    final long[] args;
    final int next;
    public GhcInstruction(int opcode, long[] args, int next) { this.opcode = opcode; this.args = args; this.next = next; }
    public int getOpcode() { return opcode; }
    public long[] getArgs() { return args; }
    public int getNext() { return next; }
}
