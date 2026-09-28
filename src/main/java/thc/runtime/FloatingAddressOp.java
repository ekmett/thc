// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;
public enum FloatingAddressOp {
    INDEX_FLOAT("indexFloatOffAddr#", true, false, false), INDEX_DOUBLE("indexDoubleOffAddr#", false, false, false),
    READ_FLOAT("readFloatOffAddr#", true, true, false), READ_DOUBLE("readDoubleOffAddr#", false, true, false),
    WRITE_FLOAT("writeFloatOffAddr#", true, false, true), WRITE_DOUBLE("writeDoubleOffAddr#", false, false, true);
    private final String primitive;
    private final boolean floating, tuple, write;
    FloatingAddressOp(String primitive, boolean floating, boolean tuple, boolean write) { this.primitive = primitive; this.floating = floating; this.tuple = tuple; this.write = write; }
    public String getPrimitive() { return primitive; }
    public boolean getFloating() { return floating; }
    public boolean getTuple() { return tuple; }
    public boolean getWrite() { return write; }
    private static boolean exact(CoreRepresentation proof, List<String> reps, CoreKind kind) {
        return !proof.isAggregate() && !proof.isVector() && (kind == CoreKind.LONG || reps.equals(proof.getPrimReps())) && proof.getKind() == kind;
    }
    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation result) {
        var payload = List.of(floating ? "FloatRep" : "DoubleRep");
        var valueKind = floating ? CoreKind.FLOAT : CoreKind.DOUBLE;
        int count = 2 + (write ? 2 : tuple ? 1 : 0);
        if (actual.size() != count || !flags.equals(java.util.Collections.nCopies(count, false)) ||
            !exact(actual.get(0), List.of("AddrRep"), CoreKind.ADDRESS) || !exact(actual.get(1), List.of("IntRep"), CoreKind.LONG) ||
            write && !exact(actual.get(2), payload, valueKind) || (write || tuple) && !exact(actual.getLast(), List.of(), CoreKind.VOID))
            throw fault("Floating Addr# argument representation mismatch: " + primitive);
        var fields = result.getComponents();
        boolean valid = tuple ? result.isTuple() && result.getKind() == CoreKind.UNKNOWN && fields != null && fields.size() == 2 &&
            exact(fields.get(0), List.of(), CoreKind.VOID) && exact(fields.get(1), payload, valueKind) && payload.equals(result.getPrimReps()) :
            write ? exact(result, List.of(), CoreKind.VOID) : exact(result, payload, valueKind);
        if (!valid) throw fault("Floating Addr# result representation mismatch: " + primitive);
    }
    public static FloatingAddressOp named(String name) {
        var alias = switch (name) {
            case "indexWord8OffAddrAsFloat#" -> INDEX_FLOAT;
            case "indexWord8OffAddrAsDouble#" -> INDEX_DOUBLE;
            case "readWord8OffAddrAsFloat#" -> READ_FLOAT;
            case "readWord8OffAddrAsDouble#" -> READ_DOUBLE;
            case "writeWord8OffAddrAsFloat#" -> WRITE_FLOAT;
            case "writeWord8OffAddrAsDouble#" -> WRITE_DOUBLE;
            default -> null;
        };
        if (alias != null) return alias;
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
}
