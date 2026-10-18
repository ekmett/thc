// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

/** Exercise C2's instance-clone expansion with a reference field. */
public final class CloneBarrierSmoke {
    static final class Item implements Cloneable {
        Object reference;

        Item(Object value) { reference = value; }

        Item duplicate() {
            try { return (Item) super.clone(); }
            catch (CloneNotSupportedException e) { throw new AssertionError(e); }
        }
    }

    private static volatile Item sink;

    static Item copy(Item value) { return value.duplicate(); }

    public static void main(String[] args) {
        Object marker = new Object();
        Item value = new Item(marker);
        for (int i = 0; i < 20_000; ++i) {
            Item result = copy(value);
            if (result == value || result.reference != marker)
                throw new AssertionError("clone identity and reference field");
            sink = result;
        }
        System.gc();
        if (sink == value || sink.reference != marker)
            throw new AssertionError("clone identity and reference field after collection");
        System.out.println("reference-field clone passed");
    }
}
