// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.util.List;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import jdk.incubator.vector.*;

/** JDK Vector API operations. Values are the JDK carriers themselves. */
@SuppressWarnings({"rawtypes", "unchecked"})
public enum VectorApiOp {
    INT8_SPECIES("thc_vector_v1_int8_species", List.of("IntRep"), "BoxedRep (Just Unlifted)", false),
    BROADCAST_INT8("thc_vector_v1_broadcast_int8", List.of("BoxedRep (Just Unlifted)", "Int8Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT8_LANE("thc_vector_v1_vec_int8_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "Int8Rep", false),
    VEC_INT8_WITH_LANE("thc_vector_v1_vec_int8_with_lane", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int8Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT8_REDUCE_ADD("thc_vector_v1_vec_int8_reduce_add", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "Int8Rep", false),
    INDEX_INT8_VECTOR("thc_vector_v1_index_int8_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false, 1, "ByteArray#"),
    READ_INT8_VECTOR("thc_vector_v1_read_int8_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true, 1, "MutableByteArray#"),
    WRITE_INT8_VECTOR("thc_vector_v1_write_int8_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false, 0, "MutableByteArray#"),
    INT16_SPECIES("thc_vector_v1_int16_species", List.of("IntRep"), "BoxedRep (Just Unlifted)", false),
    BROADCAST_INT16("thc_vector_v1_broadcast_int16", List.of("BoxedRep (Just Unlifted)", "Int16Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT16_LANE("thc_vector_v1_vec_int16_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "Int16Rep", false),
    VEC_INT16_WITH_LANE("thc_vector_v1_vec_int16_with_lane", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int16Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT16_REDUCE_ADD("thc_vector_v1_vec_int16_reduce_add", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "Int16Rep", false),
    INDEX_INT16_VECTOR("thc_vector_v1_index_int16_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false, 1, "ByteArray#"),
    READ_INT16_VECTOR("thc_vector_v1_read_int16_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true, 1, "MutableByteArray#"),
    WRITE_INT16_VECTOR("thc_vector_v1_write_int16_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false, 0, "MutableByteArray#"),
    INT32_SPECIES("thc_vector_v1_int32_species", List.of("IntRep"), "BoxedRep (Just Unlifted)", false),
    BROADCAST_INT32("thc_vector_v1_broadcast_int32", List.of("BoxedRep (Just Unlifted)", "Int32Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT32_LANE("thc_vector_v1_vec_int32_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "Int32Rep", false),
    VEC_INT32_WITH_LANE("thc_vector_v1_vec_int32_with_lane", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int32Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT32_REDUCE_ADD("thc_vector_v1_vec_int32_reduce_add", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "Int32Rep", false),
    INDEX_INT32_VECTOR("thc_vector_v1_index_int32_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false, 1, "ByteArray#"),
    READ_INT32_VECTOR("thc_vector_v1_read_int32_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true, 1, "MutableByteArray#"),
    WRITE_INT32_VECTOR("thc_vector_v1_write_int32_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false, 0, "MutableByteArray#"),
    INT64_SPECIES("thc_vector_v1_int64_species", List.of("IntRep"), "BoxedRep (Just Unlifted)", false),
    BROADCAST_INT64("thc_vector_v1_broadcast_int64", List.of("BoxedRep (Just Unlifted)", "Int64Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT64_LANE("thc_vector_v1_vec_int64_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "Int64Rep", false),
    VEC_INT64_WITH_LANE("thc_vector_v1_vec_int64_with_lane", List.of("BoxedRep (Just Unlifted)", "IntRep", "Int64Rep"), "BoxedRep (Just Unlifted)", false),
    VEC_INT64_REDUCE_ADD("thc_vector_v1_vec_int64_reduce_add", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "Int64Rep", false),
    INDEX_INT64_VECTOR("thc_vector_v1_index_int64_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false, 1, "ByteArray#"),
    READ_INT64_VECTOR("thc_vector_v1_read_int64_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true, 1, "MutableByteArray#"),
    WRITE_INT64_VECTOR("thc_vector_v1_write_int64_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false, 0, "MutableByteArray#"),
    FLOAT_SPECIES("thc_vector_v1_float_species", List.of("IntRep"), "BoxedRep (Just Unlifted)", false),
    BROADCAST_FLOAT("thc_vector_v1_broadcast_float", List.of("BoxedRep (Just Unlifted)", "FloatRep"), "BoxedRep (Just Unlifted)", false),
    VEC_FLOAT_LANE("thc_vector_v1_vec_float_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "FloatRep", false),
    VEC_FLOAT_WITH_LANE("thc_vector_v1_vec_float_with_lane", List.of("BoxedRep (Just Unlifted)", "IntRep", "FloatRep"), "BoxedRep (Just Unlifted)", false),
    VEC_FLOAT_REDUCE_ADD("thc_vector_v1_vec_float_reduce_add", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "FloatRep", false),
    INDEX_FLOAT_VECTOR("thc_vector_v1_index_float_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false, 1, "ByteArray#"),
    READ_FLOAT_VECTOR("thc_vector_v1_read_float_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true, 1, "MutableByteArray#"),
    WRITE_FLOAT_VECTOR("thc_vector_v1_write_float_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false, 0, "MutableByteArray#"),
    DOUBLE_SPECIES("thc_vector_v1_double_species", List.of("IntRep"), "BoxedRep (Just Unlifted)", false),
    BROADCAST_DOUBLE("thc_vector_v1_broadcast_double", List.of("BoxedRep (Just Unlifted)", "DoubleRep"), "BoxedRep (Just Unlifted)", false),
    VEC_DOUBLE_LANE("thc_vector_v1_vec_double_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "DoubleRep", false),
    VEC_DOUBLE_WITH_LANE("thc_vector_v1_vec_double_with_lane", List.of("BoxedRep (Just Unlifted)", "IntRep", "DoubleRep"), "BoxedRep (Just Unlifted)", false),
    VEC_DOUBLE_REDUCE_ADD("thc_vector_v1_vec_double_reduce_add", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "DoubleRep", false),
    INDEX_DOUBLE_VECTOR("thc_vector_v1_index_double_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false, 1, "ByteArray#"),
    READ_DOUBLE_VECTOR("thc_vector_v1_read_double_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true, 1, "MutableByteArray#"),
    WRITE_DOUBLE_VECTOR("thc_vector_v1_write_double_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false, 0, "MutableByteArray#"),
    SPECIES_WITH_SHAPE("thc_vector_v1_species_with_shape", List.of("BoxedRep (Just Unlifted)", "IntRep"), "BoxedRep (Just Unlifted)", false),
    SPECIES_LENGTH("thc_vector_v1_species_length", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    SPECIES_ELEMENT_BITS("thc_vector_v1_species_element_bits", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    SPECIES_VECTOR_BITS("thc_vector_v1_species_vector_bits", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    SPECIES_VECTOR_BYTES("thc_vector_v1_species_vector_bytes", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    SPECIES_LOOP_BOUND("thc_vector_v1_species_loop_bound", List.of("BoxedRep (Just Unlifted)", "IntRep"), "IntRep", false),
    SPECIES_PART_LIMIT("thc_vector_v1_species_part_limit", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "IntRep", false),
    SPECIES_INDEX_IN_RANGE("thc_vector_v1_species_index_in_range", List.of("BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "BoxedRep (Just Unlifted)", false),
    SPECIES_MASK_ALL("thc_vector_v1_species_mask_all", List.of("BoxedRep (Just Unlifted)", "IntRep"), "BoxedRep (Just Unlifted)", false),
    SPECIES_ZERO("thc_vector_v1_species_zero", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_SPECIES("thc_vector_v1_vec_species", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    MASK_SPECIES("thc_vector_v1_mask_species", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    SHUFFLE_SPECIES("thc_vector_v1_shuffle_species", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    MASK_FROM_BITS("thc_vector_v1_mask_from_bits", List.of("BoxedRep (Just Unlifted)", "WordRep"), "BoxedRep (Just Unlifted)", false),
    MASK_TO_BITS("thc_vector_v1_mask_to_bits", List.of("BoxedRep (Just Unlifted)"), "WordRep", false),
    MASK_TRUE_COUNT("thc_vector_v1_mask_true_count", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    MASK_FIRST_TRUE("thc_vector_v1_mask_first_true", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    MASK_LAST_TRUE("thc_vector_v1_mask_last_true", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    MASK_ANY_TRUE("thc_vector_v1_mask_any_true", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    MASK_ALL_TRUE("thc_vector_v1_mask_all_true", List.of("BoxedRep (Just Unlifted)"), "IntRep", false),
    MASK_LANE("thc_vector_v1_mask_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "IntRep", false),
    MASK_AND("thc_vector_v1_mask_and", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    MASK_OR("thc_vector_v1_mask_or", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    MASK_XOR("thc_vector_v1_mask_xor", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    MASK_AND_NOT("thc_vector_v1_mask_and_not", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    MASK_NOT("thc_vector_v1_mask_not", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    MASK_CAST("thc_vector_v1_mask_cast", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    SHUFFLE_IOTA("thc_vector_v1_shuffle_iota", List.of("BoxedRep (Just Unlifted)", "IntRep", "IntRep", "IntRep"), "BoxedRep (Just Unlifted)", false),
    SHUFFLE_LANE("thc_vector_v1_shuffle_lane", List.of("BoxedRep (Just Unlifted)", "IntRep"), "IntRep", false),
    SHUFFLE_VALID("thc_vector_v1_shuffle_valid", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    SHUFFLE_WRAP("thc_vector_v1_shuffle_wrap", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    SHUFFLE_CAST("thc_vector_v1_shuffle_cast", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_TO_SHUFFLE("thc_vector_v1_vec_to_shuffle", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_ADD("thc_vector_v1_vec_add", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_SUB("thc_vector_v1_vec_sub", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_MUL("thc_vector_v1_vec_mul", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_DIV("thc_vector_v1_vec_div", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_MIN("thc_vector_v1_vec_min", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_MAX("thc_vector_v1_vec_max", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_ADD_MASKED("thc_vector_v1_vec_add_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_SUB_MASKED("thc_vector_v1_vec_sub_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_MUL_MASKED("thc_vector_v1_vec_mul_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_DIV_MASKED("thc_vector_v1_vec_div_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_ABS("thc_vector_v1_vec_abs", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_NEG("thc_vector_v1_vec_neg", List.of("BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_EQ("thc_vector_v1_vec_eq", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_NE("thc_vector_v1_vec_ne", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_LT("thc_vector_v1_vec_lt", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_LE("thc_vector_v1_vec_le", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_GT("thc_vector_v1_vec_gt", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_GE("thc_vector_v1_vec_ge", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_UNSIGNED_LT("thc_vector_v1_vec_unsigned_lt", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_BLEND("thc_vector_v1_vec_blend", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_REARRANGE("thc_vector_v1_vec_rearrange", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_REARRANGE_MASKED("thc_vector_v1_vec_rearrange_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_COMPRESS("thc_vector_v1_vec_compress", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_EXPAND("thc_vector_v1_vec_expand", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)", false),
    VEC_CONVERT("thc_vector_v1_vec_convert", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "BoxedRep (Just Unlifted)", false),
    VEC_REINTERPRET("thc_vector_v1_vec_reinterpret", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "BoxedRep (Just Unlifted)", false),
    READ_JAVA_BOOLEAN_VECTOR("thc_vector_v1_read_java_boolean_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BOOLEAN_VECTOR("thc_vector_v1_write_java_boolean_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_BOOLEAN_VECTOR_MASKED("thc_vector_v1_read_java_boolean_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BOOLEAN_VECTOR_MASKED("thc_vector_v1_write_java_boolean_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_BOOLEAN_VECTOR_INDEXED("thc_vector_v1_read_java_boolean_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BOOLEAN_VECTOR_INDEXED("thc_vector_v1_write_java_boolean_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_boolean_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_boolean_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_BYTE_VECTOR("thc_vector_v1_read_java_byte_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BYTE_VECTOR("thc_vector_v1_write_java_byte_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_BYTE_VECTOR_MASKED("thc_vector_v1_read_java_byte_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BYTE_VECTOR_MASKED("thc_vector_v1_write_java_byte_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_BYTE_VECTOR_INDEXED("thc_vector_v1_read_java_byte_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BYTE_VECTOR_INDEXED("thc_vector_v1_write_java_byte_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_BYTE_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_byte_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_BYTE_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_byte_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_SHORT_VECTOR("thc_vector_v1_read_java_short_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_SHORT_VECTOR("thc_vector_v1_write_java_short_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_SHORT_VECTOR_MASKED("thc_vector_v1_read_java_short_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_SHORT_VECTOR_MASKED("thc_vector_v1_write_java_short_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_SHORT_VECTOR_INDEXED("thc_vector_v1_read_java_short_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_SHORT_VECTOR_INDEXED("thc_vector_v1_write_java_short_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_SHORT_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_short_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_SHORT_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_short_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_CHAR_VECTOR("thc_vector_v1_read_java_char_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_CHAR_VECTOR("thc_vector_v1_write_java_char_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_CHAR_VECTOR_MASKED("thc_vector_v1_read_java_char_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_CHAR_VECTOR_MASKED("thc_vector_v1_write_java_char_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_CHAR_VECTOR_INDEXED("thc_vector_v1_read_java_char_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_CHAR_VECTOR_INDEXED("thc_vector_v1_write_java_char_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_CHAR_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_char_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_CHAR_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_char_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_INT_VECTOR("thc_vector_v1_read_java_int_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_INT_VECTOR("thc_vector_v1_write_java_int_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_INT_VECTOR_MASKED("thc_vector_v1_read_java_int_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_INT_VECTOR_MASKED("thc_vector_v1_write_java_int_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_INT_VECTOR_INDEXED("thc_vector_v1_read_java_int_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_INT_VECTOR_INDEXED("thc_vector_v1_write_java_int_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_INT_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_int_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_INT_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_int_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_LONG_VECTOR("thc_vector_v1_read_java_long_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_LONG_VECTOR("thc_vector_v1_write_java_long_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_LONG_VECTOR_MASKED("thc_vector_v1_read_java_long_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_LONG_VECTOR_MASKED("thc_vector_v1_write_java_long_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_LONG_VECTOR_INDEXED("thc_vector_v1_read_java_long_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_LONG_VECTOR_INDEXED("thc_vector_v1_write_java_long_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_LONG_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_long_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_LONG_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_long_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_FLOAT_VECTOR("thc_vector_v1_read_java_float_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_FLOAT_VECTOR("thc_vector_v1_write_java_float_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_FLOAT_VECTOR_MASKED("thc_vector_v1_read_java_float_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_FLOAT_VECTOR_MASKED("thc_vector_v1_write_java_float_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_FLOAT_VECTOR_INDEXED("thc_vector_v1_read_java_float_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_FLOAT_VECTOR_INDEXED("thc_vector_v1_write_java_float_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_FLOAT_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_float_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_FLOAT_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_float_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_DOUBLE_VECTOR("thc_vector_v1_read_java_double_vector", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_DOUBLE_VECTOR("thc_vector_v1_write_java_double_vector", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_DOUBLE_VECTOR_MASKED("thc_vector_v1_read_java_double_vector_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_DOUBLE_VECTOR_MASKED("thc_vector_v1_write_java_double_vector_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_DOUBLE_VECTOR_INDEXED("thc_vector_v1_read_java_double_vector_indexed", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_DOUBLE_VECTOR_INDEXED("thc_vector_v1_write_java_double_vector_indexed", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_DOUBLE_VECTOR_INDEXED_MASKED("thc_vector_v1_read_java_double_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_DOUBLE_VECTOR_INDEXED_MASKED("thc_vector_v1_write_java_double_vector_indexed_masked", List.of("BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "BoxedRep (Just Unlifted)", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_MASK("thc_vector_v1_read_java_mask", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_MASK("thc_vector_v1_write_java_mask", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false),
    READ_JAVA_SHUFFLE("thc_vector_v1_read_java_shuffle", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "BoxedRep (Just Unlifted)", true),
    WRITE_JAVA_SHUFFLE("thc_vector_v1_write_java_shuffle", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "State# RealWorld"), "State# RealWorld", false);

    final String symbol;
    final List<String> arguments;
    final String result;
    final boolean tuple;
    final int arrayIndex;
    final String arrayType;

    /** Profiles belong to the primitive site, never to a wrapper around a value. */
    static final class Site extends Node {
        private final VectorApiOp operation;
        @Child private ForeignExceptionAccess exceptions;
        @CompilationFinal(dimensions = 1) private final ValueProfile[] identities;
        @CompilationFinal(dimensions = 1) private final ValueProfile[] classes;
        private final ValueProfile resultSpecies = ValueProfile.createIdentityProfile();
        // Keep unobserved masked fallbacks out of full-block vector graphs.
        private final ConditionProfile fullMask = ConditionProfile.create();

        Site(VectorApiOp operation) {
            this.operation = operation;
            if (operation.javaArray()) exceptions = new ForeignExceptionAccess();
            identities = new ValueProfile[operation.arguments.size()];
            classes = new ValueProfile[identities.length];
            for (int i = 0; i < identities.length; i++) {
                identities[i] = ValueProfile.createIdentityProfile();
                classes[i] = ValueProfile.createClassProfile();
            }
        }
        private Object profile(int index, Object[] values) {
            if (index >= identities.length) return null;
            Object value = values[index];
            return value instanceof VectorSpecies<?> ? identities[index].profile(value) : classes[index].profile(value);
        }
        Object execute(Object[] values) {
            Object result;
            try { result = operation.execute(profile(0, values), profile(1, values), profile(2, values), profile(3, values), profile(4, values), profile(5, values), profile(6, values), fullMask); }
            catch (RuntimeException failure) { if (exceptions != null) throw exceptions.raiseHost(failure); throw failure; }
            return result instanceof VectorSpecies<?> ? resultSpecies.profile(result) : result;
        }
    }

    boolean javaArray() { return ordinal() >= READ_JAVA_BOOLEAN_VECTOR.ordinal(); }

    VectorApiOp(String symbol, List<String> arguments, String result, boolean tuple) {
        this(symbol, arguments, result, tuple, -1, null);
    }
    VectorApiOp(String symbol, List<String> arguments, String result, boolean tuple, int arrayIndex, String arrayType) {
        this.symbol = symbol; this.arguments = arguments; this.result = result;
        this.tuple = tuple; this.arrayIndex = arrayIndex; this.arrayType = arrayType;
    }
    static VectorApiOp validate(List<?> expression, boolean defined) {
        var metadata = CoreRepresentations.metadata(expression);
        if (metadata == null || !(metadata.get("foreignCall") instanceof java.util.Map<?, ?> call) ||
                !(call.get("target") instanceof java.util.Map<?, ?> target) ||
                !(target.get("symbol") instanceof String symbol) || !symbol.startsWith("thc_vector_v1_")) return null;
        for (var operation : values()) if (operation.symbol.equals(symbol)) {
            List<String> types = null;
            if (operation.arrayIndex >= 0) {
                types = new java.util.ArrayList<>(java.util.Collections.nCopies(operation.arguments.size(), null));
                types.set(operation.arrayIndex, operation.arrayType);
            }
            CorePolyglot.validateAbi(expression, defined, operation.arguments, operation.result, !operation.tuple, types);
            return operation;
        }
        throw new UnsupportedCore("Unsupported Vector API primitive: " + symbol);
    }

    private static VectorShape shape(long bits) {
        return bits == -1 ? VectorShape.S_Max_BIT : VectorShape.forBitSize(Math.toIntExact(bits));
    }
    private static VectorSpecies species(VectorSpecies preferred, VectorSpecies maximum, long bits) {
        return bits == 0 ? preferred : bits == -1 ? maximum : preferred.withShape(shape(bits));
    }

    private static VectorMask indexInRange(VectorSpecies species, long offset, long limit) {
        if (offset < 0) return negativeRange(species, offset, limit);
        // Retain a nonnegative stamp while PE expands the JDK mask implementation.
        return species.indexInRange(Math.max(0L, offset), limit);
    }
    @TruffleBoundary private static VectorMask negativeRange(VectorSpecies species, long offset, long limit) {
        return species.indexInRange(offset, limit);
    }

    /** The operation is constant at a lowered call site. */
    public Object execute(Object[] a) {
        return execute(a.length > 0 ? a[0] : null, a.length > 1 ? a[1] : null,
            a.length > 2 ? a[2] : null, a.length > 3 ? a[3] : null, a.length > 4 ? a[4] : null, a.length > 5 ? a[5] : null, a.length > 6 ? a[6] : null, null);
    }

    private Object execute(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, ConditionProfile maskProfile) {
        return switch (this) {
            case READ_JAVA_BOOLEAN_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield ByteVector.fromBooleanArray((VectorSpecies) a0, (boolean[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_BOOLEAN_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((ByteVector) a2).intoBooleanArray((boolean[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_BOOLEAN_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_BOOLEAN_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_BOOLEAN_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield ByteVector.fromBooleanArray((VectorSpecies) a0, (boolean[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_BOOLEAN_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((ByteVector) a2).intoBooleanArray((boolean[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_BYTE_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield ByteVector.fromArray((VectorSpecies) a0, (byte[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_BYTE_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((ByteVector) a2).intoArray((byte[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_BYTE_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_BYTE_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_BYTE_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield ByteVector.fromArray((VectorSpecies) a0, (byte[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_BYTE_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((ByteVector) a2).intoArray((byte[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_BYTE_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_BYTE_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_SHORT_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield ShortVector.fromArray((VectorSpecies) a0, (short[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_SHORT_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((ShortVector) a2).intoArray((short[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_SHORT_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_SHORT_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_SHORT_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield ShortVector.fromArray((VectorSpecies) a0, (short[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_SHORT_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((ShortVector) a2).intoArray((short[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_SHORT_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_SHORT_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_CHAR_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield ShortVector.fromCharArray((VectorSpecies) a0, (char[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_CHAR_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((ShortVector) a2).intoCharArray((char[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_CHAR_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_CHAR_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_CHAR_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield ShortVector.fromCharArray((VectorSpecies) a0, (char[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_CHAR_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((ShortVector) a2).intoCharArray((char[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_CHAR_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_CHAR_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_INT_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield IntVector.fromArray((VectorSpecies) a0, (int[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_INT_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((IntVector) a2).intoArray((int[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_INT_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_INT_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_INT_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield IntVector.fromArray((VectorSpecies) a0, (int[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_INT_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((IntVector) a2).intoArray((int[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_INT_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_INT_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_LONG_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield LongVector.fromArray((VectorSpecies) a0, (long[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_LONG_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((LongVector) a2).intoArray((long[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_LONG_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_LONG_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_LONG_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield LongVector.fromArray((VectorSpecies) a0, (long[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_LONG_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((LongVector) a2).intoArray((long[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_LONG_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_LONG_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_FLOAT_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield FloatVector.fromArray((VectorSpecies) a0, (float[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_FLOAT_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((FloatVector) a2).intoArray((float[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_FLOAT_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_FLOAT_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_FLOAT_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield FloatVector.fromArray((VectorSpecies) a0, (float[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_FLOAT_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((FloatVector) a2).intoArray((float[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_FLOAT_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_FLOAT_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_DOUBLE_VECTOR -> { TupleResults.requireVoidCarrier(a3); yield DoubleVector.fromArray((VectorSpecies) a0, (double[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_DOUBLE_VECTOR -> { TupleResults.requireVoidCarrier(a3); ((DoubleVector) a2).intoArray((double[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_DOUBLE_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_DOUBLE_VECTOR_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_DOUBLE_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); yield DoubleVector.fromArray((VectorSpecies) a0, (double[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_DOUBLE_VECTOR_INDEXED -> { TupleResults.requireVoidCarrier(a5); ((DoubleVector) a2).intoArray((double[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_DOUBLE_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case WRITE_JAVA_DOUBLE_VECTOR_INDEXED_MASKED -> javaArrayMemory(a0, a1, a2, a3, a4, a5, a6, maskProfile);
            case READ_JAVA_MASK -> { TupleResults.requireVoidCarrier(a3); yield VectorMask.fromArray((VectorSpecies) a0, (boolean[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_MASK -> { TupleResults.requireVoidCarrier(a3); ((VectorMask) a0).intoArray((boolean[]) a1, Math.toIntExact((Long) a2)); yield Unit.INSTANCE; }
            case READ_JAVA_SHUFFLE -> { TupleResults.requireVoidCarrier(a3); yield VectorShuffle.fromArray((VectorSpecies) a0, (int[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_SHUFFLE -> { TupleResults.requireVoidCarrier(a3); ((VectorShuffle) a0).intoArray((int[]) a1, Math.toIntExact((Long) a2)); yield Unit.INSTANCE; }
            case INT8_SPECIES -> species(ByteVector.SPECIES_PREFERRED, ByteVector.SPECIES_MAX, ((Long) a0));
            case BROADCAST_INT8 -> ByteVector.broadcast(((VectorSpecies) a0), (byte) (int) (Integer) a1);
            case VEC_INT8_LANE -> (int) ((ByteVector) a0).lane(Math.toIntExact(((Long) a1)));
            case VEC_INT8_WITH_LANE -> ((ByteVector) a0).withLane(Math.toIntExact(((Long) a1)), (byte) (int) (Integer) a2);
            case VEC_INT8_REDUCE_ADD -> (int) ((ByteVector) a0).reduceLanes(VectorOperators.ADD, ((VectorMask) a1));
            case INT16_SPECIES -> species(ShortVector.SPECIES_PREFERRED, ShortVector.SPECIES_MAX, ((Long) a0));
            case BROADCAST_INT16 -> ShortVector.broadcast(((VectorSpecies) a0), (short) (int) (Integer) a1);
            case VEC_INT16_LANE -> (int) ((ShortVector) a0).lane(Math.toIntExact(((Long) a1)));
            case VEC_INT16_WITH_LANE -> ((ShortVector) a0).withLane(Math.toIntExact(((Long) a1)), (short) (int) (Integer) a2);
            case VEC_INT16_REDUCE_ADD -> (int) ((ShortVector) a0).reduceLanes(VectorOperators.ADD, ((VectorMask) a1));
            case INT32_SPECIES -> species(IntVector.SPECIES_PREFERRED, IntVector.SPECIES_MAX, ((Long) a0));
            case BROADCAST_INT32 -> IntVector.broadcast(((VectorSpecies) a0), (int) (Integer) a1);
            case VEC_INT32_LANE -> ((IntVector) a0).lane(Math.toIntExact(((Long) a1)));
            case VEC_INT32_WITH_LANE -> ((IntVector) a0).withLane(Math.toIntExact(((Long) a1)), (int) (Integer) a2);
            case VEC_INT32_REDUCE_ADD -> ((IntVector) a0).reduceLanes(VectorOperators.ADD, ((VectorMask) a1));
            case INT64_SPECIES -> species(LongVector.SPECIES_PREFERRED, LongVector.SPECIES_MAX, ((Long) a0));
            case BROADCAST_INT64 -> LongVector.broadcast(((VectorSpecies) a0), (long) (Long) a1);
            case VEC_INT64_LANE -> ((LongVector) a0).lane(Math.toIntExact(((Long) a1)));
            case VEC_INT64_WITH_LANE -> ((LongVector) a0).withLane(Math.toIntExact(((Long) a1)), (long) (Long) a2);
            case VEC_INT64_REDUCE_ADD -> ((LongVector) a0).reduceLanes(VectorOperators.ADD, ((VectorMask) a1));
            case FLOAT_SPECIES -> species(FloatVector.SPECIES_PREFERRED, FloatVector.SPECIES_MAX, ((Long) a0));
            case BROADCAST_FLOAT -> FloatVector.broadcast(((VectorSpecies) a0), (float) (Float) a1);
            case VEC_FLOAT_LANE -> ((FloatVector) a0).lane(Math.toIntExact(((Long) a1)));
            case VEC_FLOAT_WITH_LANE -> ((FloatVector) a0).withLane(Math.toIntExact(((Long) a1)), (float) (Float) a2);
            case VEC_FLOAT_REDUCE_ADD -> ((FloatVector) a0).reduceLanes(VectorOperators.ADD, ((VectorMask) a1));
            case DOUBLE_SPECIES -> species(DoubleVector.SPECIES_PREFERRED, DoubleVector.SPECIES_MAX, ((Long) a0));
            case BROADCAST_DOUBLE -> DoubleVector.broadcast(((VectorSpecies) a0), (double) (Double) a1);
            case VEC_DOUBLE_LANE -> ((DoubleVector) a0).lane(Math.toIntExact(((Long) a1)));
            case VEC_DOUBLE_WITH_LANE -> ((DoubleVector) a0).withLane(Math.toIntExact(((Long) a1)), (double) (Double) a2);
            case VEC_DOUBLE_REDUCE_ADD -> ((DoubleVector) a0).reduceLanes(VectorOperators.ADD, ((VectorMask) a1));
            case SPECIES_WITH_SHAPE -> ((VectorSpecies) a0).withShape(shape(((Long) a1)));
            case SPECIES_LENGTH -> (long) ((VectorSpecies) a0).length();
            case SPECIES_ELEMENT_BITS -> (long) ((VectorSpecies) a0).elementSize();
            case SPECIES_VECTOR_BITS -> (long) ((VectorSpecies) a0).vectorBitSize();
            case SPECIES_VECTOR_BYTES -> (long) ((VectorSpecies) a0).vectorByteSize();
            case SPECIES_LOOP_BOUND -> ((VectorSpecies) a0).loopBound(((Long) a1));
            case SPECIES_PART_LIMIT -> (long) ((VectorSpecies) a0).partLimit(((VectorSpecies) a1), ((Long) a2) != 0);
            case SPECIES_INDEX_IN_RANGE -> indexInRange((VectorSpecies) a0, (Long) a1, (Long) a2);
            case SPECIES_MASK_ALL -> ((VectorSpecies) a0).maskAll(((Long) a1) != 0);
            case SPECIES_ZERO -> ((VectorSpecies) a0).zero();
            case VEC_SPECIES -> ((Vector) a0).species();
            case MASK_SPECIES -> ((VectorMask) a0).vectorSpecies();
            case SHUFFLE_SPECIES -> ((VectorShuffle) a0).vectorSpecies();
            case MASK_FROM_BITS -> VectorMask.fromLong(((VectorSpecies) a0), ((Long) a1));
            case MASK_TO_BITS -> ((VectorMask) a0).toLong();
            case MASK_TRUE_COUNT -> (long) ((VectorMask) a0).trueCount();
            case MASK_FIRST_TRUE -> (long) ((VectorMask) a0).firstTrue();
            case MASK_LAST_TRUE -> (long) ((VectorMask) a0).lastTrue();
            case MASK_ANY_TRUE -> ((VectorMask) a0).anyTrue() ? 1L : 0L;
            case MASK_ALL_TRUE -> ((VectorMask) a0).allTrue() ? 1L : 0L;
            case MASK_LANE -> ((VectorMask) a0).laneIsSet(Math.toIntExact(((Long) a1))) ? 1L : 0L;
            case MASK_AND -> ((VectorMask) a0).and(((VectorMask) a1));
            case MASK_OR -> ((VectorMask) a0).or(((VectorMask) a1));
            case MASK_XOR -> ((VectorMask) a0).xor(((VectorMask) a1));
            case MASK_AND_NOT -> ((VectorMask) a0).andNot(((VectorMask) a1));
            case MASK_NOT -> ((VectorMask) a0).not();
            case MASK_CAST -> ((VectorMask) a0).cast(((VectorSpecies) a1));
            case SHUFFLE_IOTA -> ((VectorSpecies) a0).iotaShuffle(Math.toIntExact(((Long) a1)), Math.toIntExact(((Long) a2)), ((Long) a3) != 0);
            case SHUFFLE_LANE -> (long) ((VectorShuffle) a0).laneSource(Math.toIntExact(((Long) a1)));
            case SHUFFLE_VALID -> ((VectorShuffle) a0).laneIsValid();
            case SHUFFLE_WRAP -> ((VectorShuffle) a0).wrapIndexes();
            case SHUFFLE_CAST -> ((VectorShuffle) a0).cast(((VectorSpecies) a1));
            case VEC_TO_SHUFFLE -> ((Vector) a0).toShuffle();
            case VEC_ADD -> ((Vector) a0).add(((Vector) a1));
            case VEC_SUB -> ((Vector) a0).sub(((Vector) a1));
            case VEC_MUL -> ((Vector) a0).mul(((Vector) a1));
            case VEC_DIV -> ((Vector) a0).div(((Vector) a1));
            case VEC_MIN -> ((Vector) a0).min(((Vector) a1));
            case VEC_MAX -> ((Vector) a0).max(((Vector) a1));
            case VEC_ADD_MASKED -> ((Vector) a0).add(((Vector) a1), ((VectorMask) a2));
            case VEC_SUB_MASKED -> ((Vector) a0).sub(((Vector) a1), ((VectorMask) a2));
            case VEC_MUL_MASKED -> ((Vector) a0).mul(((Vector) a1), ((VectorMask) a2));
            case VEC_DIV_MASKED -> ((Vector) a0).div(((Vector) a1), ((VectorMask) a2));
            case VEC_ABS -> ((Vector) a0).abs();
            case VEC_NEG -> ((Vector) a0).neg();
            case VEC_EQ -> ((Vector) a0).compare(VectorOperators.EQ, ((Vector) a1));
            case VEC_NE -> ((Vector) a0).compare(VectorOperators.NE, ((Vector) a1));
            case VEC_LT -> ((Vector) a0).compare(VectorOperators.LT, ((Vector) a1));
            case VEC_LE -> ((Vector) a0).compare(VectorOperators.LE, ((Vector) a1));
            case VEC_GT -> ((Vector) a0).compare(VectorOperators.GT, ((Vector) a1));
            case VEC_GE -> ((Vector) a0).compare(VectorOperators.GE, ((Vector) a1));
            case VEC_UNSIGNED_LT -> ((Vector) a0).compare(VectorOperators.ULT, ((Vector) a1));
            case VEC_BLEND -> ((Vector) a0).blend(((Vector) a1), ((VectorMask) a2));
            case VEC_REARRANGE -> ((Vector) a0).rearrange(((VectorShuffle) a1));
            case VEC_REARRANGE_MASKED -> ((Vector) a0).rearrange(((VectorShuffle) a1), ((VectorMask) a2));
            case VEC_COMPRESS -> ((Vector) a0).compress(((VectorMask) a1));
            case VEC_EXPAND -> ((Vector) a0).expand(((VectorMask) a1));
            case VEC_CONVERT -> ((Vector) a0).convertShape(VectorOperators.Conversion.ofCast(((Vector) a0).elementType(), ((VectorSpecies) a1).elementType()), ((VectorSpecies) a1), Math.toIntExact(((Long) a2)));
            case VEC_REINTERPRET -> ((Vector) a0).reinterpretShape(((VectorSpecies) a1), Math.toIntExact(((Long) a2)));
            case INDEX_INT8_VECTOR, READ_INT8_VECTOR, WRITE_INT8_VECTOR,
                 INDEX_INT16_VECTOR, READ_INT16_VECTOR, WRITE_INT16_VECTOR,
                 INDEX_INT32_VECTOR, READ_INT32_VECTOR, WRITE_INT32_VECTOR,
                 INDEX_INT64_VECTOR, READ_INT64_VECTOR, WRITE_INT64_VECTOR,
                 INDEX_FLOAT_VECTOR, READ_FLOAT_VECTOR, WRITE_FLOAT_VECTOR,
                 INDEX_DOUBLE_VECTOR, READ_DOUBLE_VECTOR, WRITE_DOUBLE_VECTOR ->
                memory(a0, a1, a2, a3, a4, maskProfile);
        };
    }


    private Object javaArrayMemory(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, ConditionProfile profile) {
        VectorMask mask = (VectorMask) (arguments.size() == 7 ? a5 : a3);
        VectorSpecies species = result.equals("State# RealWorld") ? ((Vector) a2).species() : (VectorSpecies) a0;
        mask.check(species);
        boolean full = mask.allTrue();
        if (profile != null) full = profile.profile(full);
        if (!full) return maskedJavaArray(a0, a1, a2, a3, a4, a5, a6);
        return switch (this) {
            case READ_JAVA_BOOLEAN_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ByteVector.fromBooleanArray((VectorSpecies) a0, (boolean[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_BOOLEAN_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ByteVector) a2).intoBooleanArray((boolean[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ByteVector.fromBooleanArray((VectorSpecies) a0, (boolean[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ByteVector) a2).intoBooleanArray((boolean[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_BYTE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ByteVector.fromArray((VectorSpecies) a0, (byte[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_BYTE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ByteVector) a2).intoArray((byte[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_BYTE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ByteVector.fromArray((VectorSpecies) a0, (byte[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_BYTE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ByteVector) a2).intoArray((byte[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_SHORT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ShortVector.fromArray((VectorSpecies) a0, (short[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_SHORT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ShortVector) a2).intoArray((short[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_SHORT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ShortVector.fromArray((VectorSpecies) a0, (short[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_SHORT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ShortVector) a2).intoArray((short[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_CHAR_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ShortVector.fromCharArray((VectorSpecies) a0, (char[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_CHAR_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ShortVector) a2).intoCharArray((char[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_CHAR_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ShortVector.fromCharArray((VectorSpecies) a0, (char[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_CHAR_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ShortVector) a2).intoCharArray((char[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_INT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield IntVector.fromArray((VectorSpecies) a0, (int[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_INT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((IntVector) a2).intoArray((int[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_INT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield IntVector.fromArray((VectorSpecies) a0, (int[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_INT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((IntVector) a2).intoArray((int[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_LONG_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield LongVector.fromArray((VectorSpecies) a0, (long[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_LONG_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((LongVector) a2).intoArray((long[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_LONG_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield LongVector.fromArray((VectorSpecies) a0, (long[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_LONG_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((LongVector) a2).intoArray((long[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_FLOAT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield FloatVector.fromArray((VectorSpecies) a0, (float[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_FLOAT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((FloatVector) a2).intoArray((float[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_FLOAT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield FloatVector.fromArray((VectorSpecies) a0, (float[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_FLOAT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((FloatVector) a2).intoArray((float[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            case READ_JAVA_DOUBLE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield DoubleVector.fromArray((VectorSpecies) a0, (double[]) a1, Math.toIntExact((Long) a2)); }
            case WRITE_JAVA_DOUBLE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((DoubleVector) a2).intoArray((double[]) a0, Math.toIntExact((Long) a1)); yield Unit.INSTANCE; }
            case READ_JAVA_DOUBLE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield DoubleVector.fromArray((VectorSpecies) a0, (double[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4)); }
            case WRITE_JAVA_DOUBLE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((DoubleVector) a2).intoArray((double[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4)); yield Unit.INSTANCE; }
            default -> throw new AssertionError(this);
        };
    }
    // Keep the JDK masked bounds-error formatting off the compiled hot path.
    @TruffleBoundary private Object maskedJavaArray(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        return switch (this) {
            case READ_JAVA_BOOLEAN_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ByteVector.fromBooleanArray((VectorSpecies) a0, (boolean[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_BOOLEAN_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ByteVector) a2).intoBooleanArray((boolean[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ByteVector.fromBooleanArray((VectorSpecies) a0, (boolean[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_BOOLEAN_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ByteVector) a2).intoBooleanArray((boolean[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            case READ_JAVA_BYTE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ByteVector.fromArray((VectorSpecies) a0, (byte[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_BYTE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ByteVector) a2).intoArray((byte[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_BYTE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ByteVector.fromArray((VectorSpecies) a0, (byte[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_BYTE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ByteVector) a2).intoArray((byte[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            case READ_JAVA_SHORT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ShortVector.fromArray((VectorSpecies) a0, (short[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_SHORT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ShortVector) a2).intoArray((short[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_SHORT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ShortVector.fromArray((VectorSpecies) a0, (short[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_SHORT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ShortVector) a2).intoArray((short[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            case READ_JAVA_CHAR_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield ShortVector.fromCharArray((VectorSpecies) a0, (char[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_CHAR_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((ShortVector) a2).intoCharArray((char[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_CHAR_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield ShortVector.fromCharArray((VectorSpecies) a0, (char[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_CHAR_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((ShortVector) a2).intoCharArray((char[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            case READ_JAVA_INT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield IntVector.fromArray((VectorSpecies) a0, (int[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_INT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((IntVector) a2).intoArray((int[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_INT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield IntVector.fromArray((VectorSpecies) a0, (int[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_INT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((IntVector) a2).intoArray((int[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            case READ_JAVA_LONG_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield LongVector.fromArray((VectorSpecies) a0, (long[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_LONG_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((LongVector) a2).intoArray((long[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_LONG_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield LongVector.fromArray((VectorSpecies) a0, (long[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_LONG_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((LongVector) a2).intoArray((long[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            case READ_JAVA_FLOAT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield FloatVector.fromArray((VectorSpecies) a0, (float[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_FLOAT_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((FloatVector) a2).intoArray((float[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_FLOAT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield FloatVector.fromArray((VectorSpecies) a0, (float[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_FLOAT_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((FloatVector) a2).intoArray((float[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            case READ_JAVA_DOUBLE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); yield DoubleVector.fromArray((VectorSpecies) a0, (double[]) a1, Math.toIntExact((Long) a2), (VectorMask) a3); }
            case WRITE_JAVA_DOUBLE_VECTOR_MASKED -> { TupleResults.requireVoidCarrier(a4); ((DoubleVector) a2).intoArray((double[]) a0, Math.toIntExact((Long) a1), (VectorMask) a3); yield Unit.INSTANCE; }
            case READ_JAVA_DOUBLE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); yield DoubleVector.fromArray((VectorSpecies) a0, (double[]) a1, Math.toIntExact((Long) a2), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); }
            case WRITE_JAVA_DOUBLE_VECTOR_INDEXED_MASKED -> { TupleResults.requireVoidCarrier(a6); ((DoubleVector) a2).intoArray((double[]) a0, Math.toIntExact((Long) a1), (int[]) a3, Math.toIntExact((Long) a4), (VectorMask) a5); yield Unit.INSTANCE; }
            default -> throw new AssertionError(this);
        };
    }

    private Object memory(Object a0, Object a1, Object a2, Object a3, Object a4, ConditionProfile maskProfile) {
        boolean write = result.equals("State# RealWorld");
        Object storage = write ? a0 : a1;
        VectorSpecies species = write ? ((Vector) a2).species() : (VectorSpecies) a0;
        VectorMask mask = (VectorMask) a3;
        mask.check(species);
        long index = (Long) (write ? a1 : a2);
        long offset = Math.multiplyExact(index, species.elementSize() / 8);
        if (tuple || write) TupleResults.requireVoidCarrier(a4);
        if (storage instanceof ManagedAllocation owner) {
            synchronized (owner) {
                return accessMemory(a0, a2, a3, owner.maskedVectorSegment(offset, species.elementSize() / 8, mask, write), offset, maskProfile);
            }
        }
        return accessMemory(a0, a2, a3, MemorySegment.ofArray(ManagedByteArray.require(storage)), offset, maskProfile);
    }

    private Object accessMemory(Object a0, Object a2, Object a3, MemorySegment bytes, long offset, ConditionProfile maskProfile) {
        boolean allTrue = ((VectorMask) a3).allTrue();
        if (maskProfile != null) allTrue = maskProfile.profile(allTrue);
        if (allTrue) return unmaskedMemory(a0, a2, bytes, offset);
        return maskedMemory(a0, a2, a3, bytes, offset);
    }

    private Object unmaskedMemory(Object a0, Object a2, MemorySegment bytes, long offset) {
        return switch (this) {
            case INDEX_INT8_VECTOR, READ_INT8_VECTOR -> ByteVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder());
            case WRITE_INT8_VECTOR -> { ((ByteVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder()); yield Unit.INSTANCE; }
            case INDEX_INT16_VECTOR, READ_INT16_VECTOR -> ShortVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder());
            case WRITE_INT16_VECTOR -> { ((ShortVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder()); yield Unit.INSTANCE; }
            case INDEX_INT32_VECTOR, READ_INT32_VECTOR -> IntVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder());
            case WRITE_INT32_VECTOR -> { ((IntVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder()); yield Unit.INSTANCE; }
            case INDEX_INT64_VECTOR, READ_INT64_VECTOR -> LongVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder());
            case WRITE_INT64_VECTOR -> { ((LongVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder()); yield Unit.INSTANCE; }
            case INDEX_FLOAT_VECTOR, READ_FLOAT_VECTOR -> FloatVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder());
            case WRITE_FLOAT_VECTOR -> { ((FloatVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder()); yield Unit.INSTANCE; }
            case INDEX_DOUBLE_VECTOR, READ_DOUBLE_VECTOR -> DoubleVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder());
            case WRITE_DOUBLE_VECTOR -> { ((DoubleVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder()); yield Unit.INSTANCE; }
            default -> throw new AssertionError(this);
        };
    }
    // The JDK masked range checker formats errors before PE can fold segment
    // lengths. Keep it out of PE; all-true masks use the unmasked overloads above.
    @TruffleBoundary private Object maskedMemory(Object a0, Object a2, Object a3, MemorySegment bytes, long offset) {
        return switch (this) {
            case INDEX_INT8_VECTOR, READ_INT8_VECTOR -> ByteVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3);
            case WRITE_INT8_VECTOR -> { ((ByteVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3); yield Unit.INSTANCE; }
            case INDEX_INT16_VECTOR, READ_INT16_VECTOR -> ShortVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3);
            case WRITE_INT16_VECTOR -> { ((ShortVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3); yield Unit.INSTANCE; }
            case INDEX_INT32_VECTOR, READ_INT32_VECTOR -> IntVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3);
            case WRITE_INT32_VECTOR -> { ((IntVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3); yield Unit.INSTANCE; }
            case INDEX_INT64_VECTOR, READ_INT64_VECTOR -> LongVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3);
            case WRITE_INT64_VECTOR -> { ((LongVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3); yield Unit.INSTANCE; }
            case INDEX_FLOAT_VECTOR, READ_FLOAT_VECTOR -> FloatVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3);
            case WRITE_FLOAT_VECTOR -> { ((FloatVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3); yield Unit.INSTANCE; }
            case INDEX_DOUBLE_VECTOR, READ_DOUBLE_VECTOR -> DoubleVector.fromMemorySegment((VectorSpecies) a0, bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3);
            case WRITE_DOUBLE_VECTOR -> { ((DoubleVector) a2).intoMemorySegment(bytes, offset, ByteOrder.nativeOrder(), (VectorMask) a3); yield Unit.INSTANCE; }
            default -> throw new AssertionError(this);
        };
    }
}
