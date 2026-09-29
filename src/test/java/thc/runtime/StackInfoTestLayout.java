// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class StackInfoTestLayout {
    private StackInfoTestLayout() {}
    private static final String platform = (Set.of("arm64", "aarch64").contains(System.getProperty("os.arch").toLowerCase(Locale.ROOT)) ? "aarch64" : "x86_64") +
        (System.getProperty("os.name").startsWith("Mac") ? "-osx" : "-linux");
    public static final String endian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big";
    // Complete synthetic typed-layout construction for stack-layout controls.
    public static Map<String, Object> fields() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("schema", 1); fields.put("profiled", false); fields.put("wordBytes", 8); fields.put("targetPlatform", platform);
        fields.put("tablesNextToCode", true); fields.put("endianness", endian);
        Object[][] offsets = {
            {"infoTableBytes", 16}, {"infoTablePtrsOffset", 0}, {"infoTablePtrsBytes", 4}, {"infoTableNptrsOffset", 4}, {"infoTableNptrsBytes", 4},
            {"infoTableTypeOffset", 8}, {"infoTableTypeBytes", 4}, {"infoTableSrtOffset", 12}, {"infoTableSrtBytes", 4},
            {"infoProvEntBytes", 72}, {"infoProvBytes", 64}, {"infoProvEntInfoOffset", 0}, {"infoProvEntProvOffset", 8},
            {"infoProvNameOffset", 0}, {"infoProvDescOffset", 8}, {"infoProvDescBytes", 4}, {"infoProvTyDescOffset", 16},
            {"infoProvLabelOffset", 24}, {"infoProvUnitOffset", 32}, {"infoProvModuleOffset", 40}, {"infoProvFileOffset", 48}, {"infoProvSpanOffset", 56},
            {"closureRetBco", 29}, {"closureRetSmall", 30}, {"closureRetBig", 31}, {"closureRetFun", 32}, {"closureUpdateFrame", 33},
            {"closureCatchFrame", 34}, {"closureUnderflowFrame", 35}, {"closureStopFrame", 36}, {"closureStack", 53}, {"closureAtomicallyFrame", 55},
            {"closureCatchRetryFrame", 56}, {"closureCatchStmFrame", 57}, {"closureAnnFrame", 65}, {"stackHeaderBytes", 8},
            {"stackCatchHandlerBytes", 8}, {"stackCatchFrameBytes", 16}, {"stackCatchStmCodeBytes", 8}, {"stackCatchStmHandlerBytes", 16},
            {"stackCatchStmFrameBytes", 24}, {"stackUpdateeBytes", 8}, {"stackUpdateFrameBytes", 16}, {"stackAtomicallyCodeBytes", 8},
            {"stackAtomicallyResultBytes", 16}, {"stackAtomicallyFrameBytes", 24}, {"stackCatchRetryAltCodeBytes", 8}, {"stackCatchRetryFirstCodeBytes", 16},
            {"stackCatchRetryAltBytes", 24}, {"stackCatchRetryFrameBytes", 32}, {"stackRetFunSizeBytes", 8}, {"stackRetFunFunBytes", 16},
            {"stackRetFunPayloadBytes", 24}, {"stackRetFunFrameBytes", 24}, {"stackAnnPayloadBytes", 8}, {"stackAnnFrameBytes", 16}, {"stackClosurePayloadBytes", 8}
        };
        for (var field : offsets) fields.put((String) field[0], field[1]);
        return fields;
    }
    public static Map<String, Object> document() { return document(fields()); }
    public static Map<String, Object> document(Map<String, Object> fields) {
        return Map.of("format", "thc-target-layout", "schema", 1,
            "compiler", Map.of("id", "ghc-9.14.1", "abi", "info-image-test", "platform", platform, "way", "dynamic-nonprofiling"), "layout", fields);
    }
    public static TargetLayout layout() { return layout(Map.of()); }
    public static TargetLayout layout(Map<String, ?> changes) {
        var fields = fields(); fields.putAll(changes); return TargetLayout.fromDocument(document(fields));
    }
}
