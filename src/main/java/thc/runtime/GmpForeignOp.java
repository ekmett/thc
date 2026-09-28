// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static thc.runtime.CoreGmpForeign.GMP_ARRAY_REP;

/** Actual primitive FCallId ABIs, including State on the source-pure imports. */
public enum GmpForeignOp {
    ADD("__gmpn_add", GmpForm.BINARY,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null)), "WordRep"),
    ADD_WORD("__gmpn_add_1", GmpForm.WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    COMPARE("__gmpn_cmp", GmpForm.COMPARE,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", null)), "IntRep"),
    DIVIDE_WORD("__gmpn_divrem_1", GmpForm.DIVIDE_WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    MODULO_WORD("__gmpn_mod_1", GmpForm.MODULO_WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    MULTIPLY("__gmpn_mul", GmpForm.BINARY,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null)), "WordRep"),
    MULTIPLY_WORD("__gmpn_mul_1", GmpForm.WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    SUBTRACT("__gmpn_sub", GmpForm.BINARY,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null)), "WordRep"),
    DIVIDE("__gmpn_tdiv_qr", GmpForm.DIVIDE,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null)), null),
    QUOTIENT("integer_gmp_mpn_tdiv_q", GmpForm.BINARY,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null)), null),
    REMAINDER("integer_gmp_mpn_tdiv_r", GmpForm.BINARY,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null)), null),
    SHIFT_RIGHT("integer_gmp_mpn_rshift", GmpForm.WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    SHIFT_RIGHT_NEGATIVE("integer_gmp_mpn_rshift_2c", GmpForm.WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    GET_DOUBLE("integer_gmp_mpn_get_d", GmpForm.GET_DOUBLE,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, "IntRep", "IntRep", null)), "DoubleRep"),
    ENCODE_DOUBLE("__int_encodeDouble", GmpForm.ENCODE_DOUBLE,
        Collections.unmodifiableList(Arrays.asList("IntRep", "IntRep", null)), "DoubleRep"),
    GCD_WORDS("integer_gmp_gcd_word", GmpForm.WORD_PAIR,
        Collections.unmodifiableList(Arrays.asList("WordRep", "WordRep", null)), "WordRep"),
    GCD_WORD("integer_gmp_mpn_gcd_1", GmpForm.MODULO_WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    GCD("integer_gmp_mpn_gcd", GmpForm.BINARY,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null)), "IntRep"),
    SHIFT_LEFT("integer_gmp_mpn_lshift", GmpForm.WORD,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null)), "WordRep"),
    AND("integer_gmp_mpn_and_n", GmpForm.LOGICAL,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", null)), null),
    AND_NOT("integer_gmp_mpn_andn_n", GmpForm.LOGICAL,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", null)), null),
    OR("integer_gmp_mpn_ior_n", GmpForm.LOGICAL,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", null)), null),
    XOR("integer_gmp_mpn_xor_n", GmpForm.LOGICAL,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", null)), null),
    POPCOUNT("__gmpn_popcount", GmpForm.COUNT,
        Collections.unmodifiableList(Arrays.asList(GMP_ARRAY_REP, "IntRep", null)), "WordRep");

    private final String symbol;
    private final GmpForm form;
    private final List<String> arguments;
    private final String result;
    private final List<Integer> objectIndices;
    private final List<Integer> longIndices;

    GmpForeignOp(String symbol, GmpForm form, List<String> arguments, String result) {
        this.symbol = symbol;
        this.form = form;
        this.arguments = arguments;
        this.result = result;
        var objects = new ArrayList<Integer>();
        var longs = new ArrayList<Integer>();
        for (int i = 0; i < arguments.size(); i++) {
            if (GMP_ARRAY_REP.equals(arguments.get(i))) objects.add(i);
            if ("IntRep".equals(arguments.get(i)) || "WordRep".equals(arguments.get(i))) longs.add(i);
        }
        objectIndices = List.copyOf(objects);
        longIndices = List.copyOf(longs);
    }

    public String getSymbol() { return symbol; }
    public GmpForm getForm() { return form; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
    public List<Integer> getObjectIndices() { return objectIndices; }
    public List<Integer> getLongIndices() { return longIndices; }
}
