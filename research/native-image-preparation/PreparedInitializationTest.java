// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.util.Arrays;

/** Pure input controls; resource-registry ordering is checked in the image builder. */
public final class PreparedInitializationTest {
    public static void main(String[] args) {
        var names = PreparedInitializationFeature.classNames(
            "--initialize-at-build-time=thc.LanguageProvider,thc.Outer$Nested,thc.LanguageProvider\n");
        if (!Arrays.equals(names, new String[]{"thc.LanguageProvider", "thc.Outer$Nested", "thc.LanguageProvider"}))
            throw new AssertionError("Inventory order, nesting and duplicates must be preserved");
        for (String invalid : new String[]{"", "--initialize-at-build-time=", "thc.LanguageProvider",
                "--initialize-at-build-time=,thc.LanguageProvider", "--initialize-at-build-time=thc.LanguageProvider,",
                "--initialize-at-build-time=thc.LanguageProvider,,thc.Other", "--initialize-at-build-time=thc.*",
                "--initialize-at-build-time=thc.LanguageProvider\n--initialize-at-build-time=thc.Other"}) {
            try {
                PreparedInitializationFeature.classNames(invalid);
                throw new AssertionError("Accepted malformed inventory: " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS 9 prepared initialization input controls");
    }
}
