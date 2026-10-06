// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pinned GHC name references. Ordinary names come from the interface itself;
 * built-in identities come from the compiler-generated, versioned catalogue. */
final class CoreHiNames {
    private CoreHiNames() {}

    static CoreHiReader.ExternalName read(CoreHiReader reader, CoreHiReader.Cursor cursor) {
        long word = cursor.unsigned(32);
        if ((word & 0xc0000000L) == 0) {
            cursor.require(word < reader.names.size(), "name table index out of range: " + word);
            return reader.names.get((int) word);
        }
        cursor.require((word & 0xc0000000L) == 0x80000000L, "invalid name reference tag");
        var name = Catalogue.names.get(word);
        cursor.require(name != null, "unsupported GHC known-key name 0x" + Long.toHexString(word));
        return name;
    }

    static String id(CoreHiReader.ExternalName name) {
        // GHC.Types.Name.Occurrence.occNameMangledFS preserves field namespaces.
        String occurrence = name.namespace() == 4
                ? "$fld:" + name.fieldParent() + ":" + name.occurrence() : name.occurrence();
        return name.module().unit() + ":" + name.module().name() + "." + occurrence;
    }

    static boolean isPrimop(CoreHiReader.ExternalName name) { return Catalogue.primops.contains(name); }

    static Map<?, ?> scalarSignature(CoreHiReader.ExternalName name) {
        return isPrimop(name) ? (Map<?, ?>) ScalarSignatures.entries.get(name.occurrence()) : null;
    }

    private static Map<?, ?> resource(String path) {
        try (var stream = CoreHiNames.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing pinned GHC metadata: " + path);
            var document = (Map<?, ?>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            if (!Objects.equals(document.get("schema"), 1L) || !Objects.equals(document.get("ghc"), "9.14.1"))
                throw new IllegalStateException("Incompatible pinned GHC metadata: " + path);
            return document;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read pinned GHC metadata: " + path, failure);
        }
    }

    private static final class Catalogue {
        static final Map<Long, CoreHiReader.ExternalName> names;
        static final Set<CoreHiReader.ExternalName> primops;
        static {
            var identities = new HashMap<Long, CoreHiReader.ExternalName>();
            var primitives = new HashSet<CoreHiReader.ExternalName>();
            var document = resource("/thc/ghc-9.14.1-known-key-names.json");
            for (Object raw : (List<?>) document.get("names")) {
                var entry = (Map<?, ?>) raw;
                int namespace = ((Number) entry.get("namespace")).intValue();
                if (namespace < 0 || namespace > 4) throw new IllegalStateException("Invalid known-key namespace");
                var module = new CoreHiReader.ModuleId((String) entry.get("unit"), (String) entry.get("module"));
                var name = new CoreHiReader.ExternalName(module, namespace, (String) entry.get("fieldParent"), (String) entry.get("occurrence"));
                long word = ((Number) entry.get("nameWord")).longValue();
                if ((word & 0xffffffffc0000000L) != 0x80000000L || identities.putIfAbsent(word, name) != null)
                    throw new IllegalStateException("Invalid or duplicate GHC known key: " + word);
                if (Objects.equals(entry.get("category"), "primop")) primitives.add(name);
            }
            // Algorithmic tuple/sum/constraint names are not all in knownKeyNames.
            // Unknown keys fail above until their corresponding Core forms are supported.
            names = Map.copyOf(identities);
            primops = Set.copyOf(primitives);
        }
    }

    private static final class ScalarSignatures {
        static final Map<?, ?> entries = (Map<?, ?>) resource("/thc/scalar-primop-signatures.json").get("primitives");
    }
}
