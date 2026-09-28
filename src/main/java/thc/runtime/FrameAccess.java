// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Primitive locals widen monotonically to objects across shared descriptors. */
public final class FrameAccess {
    public static final FrameAccess INSTANCE = new FrameAccess();
    private FrameAccess() {}
    static boolean primitiveKind(FrameDescriptor descriptor, int slot, FrameSlotKind wanted) {
        FrameSlotKind kind = descriptor.getSlotKind(slot);
        if (kind == wanted) return true;
        if (kind != FrameSlotKind.Illegal) return false;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        synchronized (descriptor) {
            if (descriptor.getSlotKind(slot) == FrameSlotKind.Illegal) descriptor.setSlotKind(slot, wanted);
            return descriptor.getSlotKind(slot) == wanted;
        }
    }
    public static void writeObject(Frame frame, int slot, Object value) {
        FrameDescriptor descriptor = frame.getFrameDescriptor();
        objectKind(descriptor, slot);
        frame.setObject(slot, value);
    }
    static void objectKind(FrameDescriptor descriptor, int slot) {
        if (descriptor.getSlotKind(slot) != FrameSlotKind.Object) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            synchronized (descriptor) {
                if (descriptor.getSlotKind(slot) != FrameSlotKind.Object) descriptor.setSlotKind(slot, FrameSlotKind.Object);
            }
        }
    }
    /** Consult activation tags: another activation may have widened its descriptor. */
    public static Object read(Frame frame, int slot) {
        if (frame.isInt(slot)) return frame.getInt(slot);
        if (frame.isLong(slot)) return frame.getLong(slot);
        if (frame.isFloat(slot)) return frame.getFloat(slot);
        if (frame.isDouble(slot)) return frame.getDouble(slot);
        if (frame.isBoolean(slot)) return frame.getBoolean(slot);
        if (frame.isObject(slot)) return frame.getObject(slot);
        throw fault("Unsupported runtime frame slot tag");
    }
    public static void writeInt(Frame frame, int slot, int value) {
        if (primitiveKind(frame.getFrameDescriptor(), slot, FrameSlotKind.Int)) frame.setInt(slot, value);
        else writeObject(frame, slot, value);
    }
    public static void writeLong(Frame frame, int slot, long value) {
        if (primitiveKind(frame.getFrameDescriptor(), slot, FrameSlotKind.Long)) frame.setLong(slot, value);
        else writeObject(frame, slot, value);
    }
    public static void writeFloat(Frame frame, int slot, float value) {
        if (primitiveKind(frame.getFrameDescriptor(), slot, FrameSlotKind.Float)) frame.setFloat(slot, value);
        else writeObject(frame, slot, value);
    }
    public static void writeDouble(Frame frame, int slot, double value) {
        if (primitiveKind(frame.getFrameDescriptor(), slot, FrameSlotKind.Double)) frame.setDouble(slot, value);
        else writeObject(frame, slot, value);
    }
    public static void write(Frame frame, int slot, Object value) {
        // Preserve the caller's existing box once a slot is an object.
        if (frame.getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Object) { frame.setObject(slot, value); return; }
        if (value instanceof Float number) { writeFloat(frame, slot, number); return; }
        if (value instanceof Double number) { writeDouble(frame, slot, number); return; }
        if (value instanceof Integer number) { writeInt(frame, slot, number); return; }
        if (value instanceof Long number) { writeLong(frame, slot, number); return; }
        if (value instanceof Boolean flag && primitiveKind(frame.getFrameDescriptor(), slot, FrameSlotKind.Boolean)) {
            frame.setBoolean(slot, flag); return;
        }
        writeObject(frame, slot, value);
    }
}
