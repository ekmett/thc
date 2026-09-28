// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Durable selected closure values, separate from invocation and continuation frames. */
public class CapturedFrame extends ValidatedStorage {
    private final CaptureLayout layout;
    private final ExecutableProgram program;
    public CapturedFrame(CaptureLayout layout, Object allocationKey) {
        this(layout, allocationKey, null);
    }
    public CapturedFrame(CaptureLayout layout, Object allocationKey, ExecutableProgram program) {
        super(layout.checkAllocationKey(allocationKey));
        this.layout = layout;
        this.program = program;
    }
    public CaptureLayout getLayout() { return layout; }
    /** Per-load identity travels with the existing environment, never a call wrapper. */
    public ExecutableProgram getProgram() { return program; }
    public Object getValue(int index) { return layout.read(this, index); }
    public int getInt(int index) { return layout.readInt(this, index); }
    public boolean isInt(int index) { return layout.isInt(this, index); }
    public long getLong(int index) { return layout.readLong(this, index); }
    public boolean isLong(int index) { return layout.isLong(this, index); }
    public Object getObject(int index) { return layout.readObject(this, index); }
    public boolean isObject(int index) { return layout.isObject(this, index); }
}
