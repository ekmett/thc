// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Immutable source of one RHS. Header access never demands its body. Executable
 * nodes, lowered targets, CAFs and guest failures remain per-program state. */
public final class CoreBindingBody extends AbstractList<Object> {
    @FunctionalInterface public interface Decoder { List<Object> decode() throws Exception; }
    public static final class Header {
        private final int fieldCount;
        private final Map<Integer, Object> fields;
        private final boolean containsDelimitedControl;
        private final String tag;
        public Header(int fieldCount, Map<Integer, ?> fields, boolean containsDelimitedControl) {
            this.fieldCount = fieldCount;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            this.containsDelimitedControl = containsDelimitedControl;
            if (!(fields.get(0) instanceof String opcode)) throw new IllegalStateException("Core body header lacks its exact opcode");
            tag = opcode;
            if (fieldCount <= 0 || fields.keySet().stream().anyMatch(index -> index < 0 || index >= fieldCount))
                throw new IllegalArgumentException("Invalid Core body header extent");
        }
        public int getFieldCount() { return fieldCount; }
        public Map<Integer, Object> getFields() { return fields; }
        public boolean getContainsDelimitedControl() { return containsDelimitedControl; }
        public String getTag() { return tag; }
    }
    private final Header header;
    private final Consumer<Consumer<List<Object>>> linking;
    private final Decoder decode;
    private List<Object> decoded;
    private Exception failure;
    private boolean decoding;
    private long attempts;
    public CoreBindingBody(Header header, Decoder decode) { this(header, null, decode); }
    public CoreBindingBody(Header header, Consumer<Consumer<List<Object>>> linking, Decoder decode) {
        this.header = header; this.linking = linking; this.decode = decode;
    }
    public Header getHeader() { return header; }
    @Override public int size() { return header.fieldCount; }
    public synchronized long decodeAttempts() { return attempts; }
    public synchronized boolean isMaterialized() { return decoded != null; }
    public void visitForLinking(Consumer<List<Object>> visitor) {
        if (linking == null) visitor.accept(this); else linking.accept(visitor);
    }
    @Override public Object get(int index) {
        Objects.checkIndex(index, size());
        return header.fields.containsKey(index) ? header.fields.get(index) : materialize().get(index);
    }
    /** Cache ordinary failures exactly; cancellation/interruption and fatal errors
     * are not malformed Core. The immutable outer view never traverses children. */
    public synchronized List<Object> materialize() {
        if (failure != null) return rethrow(failure);
        if (decoded != null) return decoded;
        if (decoding) throw new IllegalStateException("Recursive Core source decoding");
        decoding = true;
        attempts++;
        try {
            List<Object> body = decode.decode();
            if (body == this || body.size() != size() || !Objects.equals(body.isEmpty() ? null : body.getFirst(), header.tag))
                throw new IllegalArgumentException("Core body differs from its indexed header");
            return decoded = Collections.unmodifiableList(body);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return rethrow(interrupted);
        } catch (Exception caught) {
            failure = caught;
            return rethrow(caught);
        } finally { decoding = false; }
    }
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
