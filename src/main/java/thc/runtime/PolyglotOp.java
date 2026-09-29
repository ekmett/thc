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
    AS_LONG("thc_interop_v1_as_long", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "Int64Rep"),
    LOOKUP_HOST_SYMBOL("thc_interop_v1_lookup_host_symbol", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    AS_GUEST_VALUE("thc_interop_v1_as_guest_value", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    AS_BOXED_GUEST_VALUE("thc_interop_v1_as_boxed_guest_value", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_NULL("thc_interop_v1_java_null", List.of("State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_STRING_UTF8("thc_interop_v1_java_string_utf8", List.of("AddrRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    AS_HOST_OBJECT("thc_interop_v1_as_host_object", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    EXECUTE("thc_interop_v1_execute", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    INSTANTIATE("thc_interop_v1_instantiate", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    INTEROP_READ_MEMBER("thc_interop_v1_interop_read_member", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    INTEROP_WRITE_MEMBER("thc_interop_v1_interop_write_member", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld"),
    INVOKE_MEMBER("thc_interop_v1_invoke_member", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_BOOLEAN("thc_interop_v1_box_java_boolean", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_BOOLEAN("thc_interop_v1_unbox_java_boolean", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    NEW_JAVA_BOOLEAN_ARRAY("thc_interop_v1_new_java_boolean_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_BOOLEAN_ARRAY_LENGTH("thc_interop_v1_java_boolean_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_BOOLEAN_ARRAY("thc_interop_v1_read_java_boolean_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "IntRep"),
    WRITE_JAVA_BOOLEAN_ARRAY("thc_interop_v1_write_java_boolean_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_BOOLEAN_ARRAY("thc_interop_v1_copy_java_boolean_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_BOOLEAN_ARRAY("thc_interop_v1_object_as_java_boolean_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_BYTE("thc_interop_v1_box_java_byte", List.of("Int8Rep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_BYTE("thc_interop_v1_unbox_java_byte", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "Int8Rep"),
    NEW_JAVA_BYTE_ARRAY("thc_interop_v1_new_java_byte_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_BYTE_ARRAY_LENGTH("thc_interop_v1_java_byte_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_BYTE_ARRAY("thc_interop_v1_read_java_byte_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "Int8Rep"),
    WRITE_JAVA_BYTE_ARRAY("thc_interop_v1_write_java_byte_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int8Rep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_BYTE_ARRAY("thc_interop_v1_copy_java_byte_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_BYTE_ARRAY("thc_interop_v1_object_as_java_byte_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_SHORT("thc_interop_v1_box_java_short", List.of("Int16Rep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_SHORT("thc_interop_v1_unbox_java_short", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "Int16Rep"),
    NEW_JAVA_SHORT_ARRAY("thc_interop_v1_new_java_short_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_SHORT_ARRAY_LENGTH("thc_interop_v1_java_short_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_SHORT_ARRAY("thc_interop_v1_read_java_short_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "Int16Rep"),
    WRITE_JAVA_SHORT_ARRAY("thc_interop_v1_write_java_short_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int16Rep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_SHORT_ARRAY("thc_interop_v1_copy_java_short_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_SHORT_ARRAY("thc_interop_v1_object_as_java_short_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_CHAR("thc_interop_v1_box_java_char", List.of("Word16Rep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_CHAR("thc_interop_v1_unbox_java_char", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "Word16Rep"),
    NEW_JAVA_CHAR_ARRAY("thc_interop_v1_new_java_char_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_CHAR_ARRAY_LENGTH("thc_interop_v1_java_char_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_CHAR_ARRAY("thc_interop_v1_read_java_char_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "Word16Rep"),
    WRITE_JAVA_CHAR_ARRAY("thc_interop_v1_write_java_char_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "Word16Rep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_CHAR_ARRAY("thc_interop_v1_copy_java_char_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_CHAR_ARRAY("thc_interop_v1_object_as_java_char_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_INT("thc_interop_v1_box_java_int", List.of("Int32Rep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_INT("thc_interop_v1_unbox_java_int", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "Int32Rep"),
    NEW_JAVA_INT_ARRAY("thc_interop_v1_new_java_int_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_INT_ARRAY_LENGTH("thc_interop_v1_java_int_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_INT_ARRAY("thc_interop_v1_read_java_int_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "Int32Rep"),
    WRITE_JAVA_INT_ARRAY("thc_interop_v1_write_java_int_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int32Rep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_INT_ARRAY("thc_interop_v1_copy_java_int_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_INT_ARRAY("thc_interop_v1_object_as_java_int_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_LONG("thc_interop_v1_box_java_long", List.of("Int64Rep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_LONG("thc_interop_v1_unbox_java_long", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "Int64Rep"),
    NEW_JAVA_LONG_ARRAY("thc_interop_v1_new_java_long_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_LONG_ARRAY_LENGTH("thc_interop_v1_java_long_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_LONG_ARRAY("thc_interop_v1_read_java_long_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "Int64Rep"),
    WRITE_JAVA_LONG_ARRAY("thc_interop_v1_write_java_long_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int64Rep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_LONG_ARRAY("thc_interop_v1_copy_java_long_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_LONG_ARRAY("thc_interop_v1_object_as_java_long_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_FLOAT("thc_interop_v1_box_java_float", List.of("FloatRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_FLOAT("thc_interop_v1_unbox_java_float", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "FloatRep"),
    NEW_JAVA_FLOAT_ARRAY("thc_interop_v1_new_java_float_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_FLOAT_ARRAY_LENGTH("thc_interop_v1_java_float_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_FLOAT_ARRAY("thc_interop_v1_read_java_float_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "FloatRep"),
    WRITE_JAVA_FLOAT_ARRAY("thc_interop_v1_write_java_float_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "FloatRep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_FLOAT_ARRAY("thc_interop_v1_copy_java_float_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_FLOAT_ARRAY("thc_interop_v1_object_as_java_float_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    BOX_JAVA_DOUBLE("thc_interop_v1_box_java_double", List.of("DoubleRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    UNBOX_JAVA_DOUBLE("thc_interop_v1_unbox_java_double", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "DoubleRep"),
    NEW_JAVA_DOUBLE_ARRAY("thc_interop_v1_new_java_double_array", List.of("IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    JAVA_DOUBLE_ARRAY_LENGTH("thc_interop_v1_java_double_array_length", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    READ_JAVA_DOUBLE_ARRAY("thc_interop_v1_read_java_double_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "DoubleRep"),
    WRITE_JAVA_DOUBLE_ARRAY("thc_interop_v1_write_java_double_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "DoubleRep", "State# RealWorld"), "State# RealWorld"),
    COPY_JAVA_DOUBLE_ARRAY("thc_interop_v1_copy_java_double_array", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "State# RealWorld"), "State# RealWorld"),
    OBJECT_AS_JAVA_DOUBLE_ARRAY("thc_interop_v1_object_as_java_double_array", List.of("BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)"),
    IS_STRING("thc_interop_v1_is_string", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "IntRep"),
    AS_TRUFFLE_STRING("thc_interop_v1_as_truffle_string", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)");
    private final String symbol;
    private final List<String> arguments;
    private final String result;
    PolyglotOp(String symbol, List<String> arguments, String result) { this.symbol = symbol; this.arguments = arguments; this.result = result; }
    public String getSymbol() { return symbol; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
    public boolean explicitLibrary() { return ordinal() >= GET_LIBRARY.ordinal(); }
    public boolean directJava() { return switch (this) {
        case BOX_JAVA_BOOLEAN, NEW_JAVA_BOOLEAN_ARRAY, JAVA_BOOLEAN_ARRAY_LENGTH, READ_JAVA_BOOLEAN_ARRAY, WRITE_JAVA_BOOLEAN_ARRAY, COPY_JAVA_BOOLEAN_ARRAY, OBJECT_AS_JAVA_BOOLEAN_ARRAY,
             BOX_JAVA_BYTE, NEW_JAVA_BYTE_ARRAY, JAVA_BYTE_ARRAY_LENGTH, READ_JAVA_BYTE_ARRAY, WRITE_JAVA_BYTE_ARRAY, COPY_JAVA_BYTE_ARRAY, OBJECT_AS_JAVA_BYTE_ARRAY,
             BOX_JAVA_SHORT, NEW_JAVA_SHORT_ARRAY, JAVA_SHORT_ARRAY_LENGTH, READ_JAVA_SHORT_ARRAY, WRITE_JAVA_SHORT_ARRAY, COPY_JAVA_SHORT_ARRAY, OBJECT_AS_JAVA_SHORT_ARRAY,
             BOX_JAVA_CHAR, NEW_JAVA_CHAR_ARRAY, JAVA_CHAR_ARRAY_LENGTH, READ_JAVA_CHAR_ARRAY, WRITE_JAVA_CHAR_ARRAY, COPY_JAVA_CHAR_ARRAY, OBJECT_AS_JAVA_CHAR_ARRAY,
             BOX_JAVA_INT, NEW_JAVA_INT_ARRAY, JAVA_INT_ARRAY_LENGTH, READ_JAVA_INT_ARRAY, WRITE_JAVA_INT_ARRAY, COPY_JAVA_INT_ARRAY, OBJECT_AS_JAVA_INT_ARRAY,
             BOX_JAVA_LONG, NEW_JAVA_LONG_ARRAY, JAVA_LONG_ARRAY_LENGTH, READ_JAVA_LONG_ARRAY, WRITE_JAVA_LONG_ARRAY, COPY_JAVA_LONG_ARRAY, OBJECT_AS_JAVA_LONG_ARRAY,
             BOX_JAVA_FLOAT, NEW_JAVA_FLOAT_ARRAY, JAVA_FLOAT_ARRAY_LENGTH, READ_JAVA_FLOAT_ARRAY, WRITE_JAVA_FLOAT_ARRAY, COPY_JAVA_FLOAT_ARRAY, OBJECT_AS_JAVA_FLOAT_ARRAY,
             BOX_JAVA_DOUBLE, NEW_JAVA_DOUBLE_ARRAY, JAVA_DOUBLE_ARRAY_LENGTH, READ_JAVA_DOUBLE_ARRAY, WRITE_JAVA_DOUBLE_ARRAY, COPY_JAVA_DOUBLE_ARRAY, OBJECT_AS_JAVA_DOUBLE_ARRAY -> true;
        default -> false;
    }; }
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
