// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
public enum PolyglotOp {
    EVAL("thc_polyglot_v1_eval", List.of("AddrRep", "AddrRep", "AddrRep", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    READ_MEMBER("thc_polyglot_v1_read_member", List.of("BoxedRep (Just Lifted)", "AddrRep", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    EXECUTE_INT("thc_polyglot_v1_execute_int", List.of("BoxedRep (Just Lifted)", "IntRep", "State# RealWorld"), "IntRep"),
    EXECUTE_VALUE("thc_polyglot_v1_execute_value", List.of("BoxedRep (Just Lifted)", "BoxedRep (Just Lifted)", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    BUFFER_VIEW("thc_polyglot_v1_buffer_view", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    BUFFER_MUTABLE_VIEW("thc_polyglot_v1_buffer_mutable_view", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    ARRAY_VIEW("thc_polyglot_v1_array_view", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    ARRAY_MUTABLE_VIEW("thc_polyglot_v1_array_mutable_view", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    BUFFER_SIZE("thc_polyglot_v1_buffer_size", List.of("BoxedRep (Just Lifted)", "State# RealWorld"), "IntRep"),
    BUFFER_READ_BYTE("thc_polyglot_v1_buffer_read_byte", List.of("BoxedRep (Just Lifted)", "IntRep", "State# RealWorld"), "IntRep"),
    BUFFER_WRITE_BYTE("thc_polyglot_v1_buffer_write_byte", List.of("BoxedRep (Just Lifted)", "IntRep", "IntRep", "State# RealWorld"), "IntRep"),
    BUFFER_READ_LONG("thc_polyglot_v1_buffer_read_long", List.of("BoxedRep (Just Lifted)", "IntRep", "IntRep", "State# RealWorld"), "IntRep"),
    BUFFER_WRITE_LONG("thc_polyglot_v1_buffer_write_long", List.of("BoxedRep (Just Lifted)", "IntRep", "IntRep", "IntRep", "State# RealWorld"), "IntRep"),
    BUFFER_COPY("thc_polyglot_v1_buffer_copy", List.of("BoxedRep (Just Lifted)", "IntRep", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BUFFER_COPY_INTO("thc_polyglot_v1_buffer_copy_into", List.of("BoxedRep (Just Lifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "IntRep"),
    ARRAY_SIZE("thc_polyglot_v1_array_size", List.of("BoxedRep (Just Lifted)", "State# RealWorld"), "IntRep"),
    ARRAY_READ("thc_polyglot_v1_array_read", List.of("BoxedRep (Just Lifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Lifted)"),
    ARRAY_WRITE("thc_polyglot_v1_array_write", List.of("BoxedRep (Just Lifted)", "IntRep", "BoxedRep (Just Lifted)", "State# RealWorld"), "IntRep"),
    ARRAY_COPY("thc_polyglot_v1_array_copy", List.of("BoxedRep (Just Lifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    GET_LIBRARY("thc_interop_v1_get_library", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)"),
    IMPORT_VALUE("thc_interop_v1_import_value", List.of("BoxedRep (Just Lifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    HAS_BUFFER_ELEMENTS("thc_interop_v1_has_buffer_elements", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    IS_BUFFER_WRITABLE("thc_interop_v1_is_buffer_writable", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    GET_BUFFER_SIZE("thc_interop_v1_get_buffer_size", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_BUFFER_BYTE("thc_interop_v1_read_buffer_byte", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "Int8Rep"),
    WRITE_BUFFER_BYTE("thc_interop_v1_write_buffer_byte", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "Int8Rep", "State# RealWorld"), "State# RealWorld"),
    HAS_ARRAY_ELEMENTS("thc_interop_v1_has_array_elements", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    GET_ARRAY_SIZE("thc_interop_v1_get_array_size", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_ARRAY_ELEMENT("thc_interop_v1_read_array_element", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    WRITE_ARRAY_ELEMENT("thc_interop_v1_write_array_element", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld"),
    AS_LONG("thc_interop_v1_as_long", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "Int64Rep");
    private final String symbol;
    private final List<String> arguments;
    private final String result;
    PolyglotOp(String symbol, List<String> arguments, String result) { this.symbol = symbol; this.arguments = arguments; this.result = result; }
    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
    public boolean explicitLibrary() { return ordinal() >= GET_LIBRARY.ordinal(); }
    public boolean scalarResult() { return this == GET_LIBRARY || result.equals("State# RealWorld"); }
    /** GHC retains nominal byte-array mutability in schema 2; RuntimeRep erases it. */
    public List<String> getArgumentTypes() {
        return switch (this) {
            case BUFFER_VIEW -> java.util.Arrays.asList("ByteArray#", null);
            case BUFFER_MUTABLE_VIEW -> java.util.Arrays.asList("MutableByteArray#", null);
            case BUFFER_COPY_INTO -> java.util.Arrays.asList(null, null, "MutableByteArray#", null, null, null);
            default -> null;
        };
    }
}
