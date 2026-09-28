// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Context owns registrations; snapshots retain only its empty identity token. */
public final class ManagedStackRegistry {
    private final Object token = new Object();
    private boolean closed;
    public Object getToken() { return token; }
    private static final class Provenance {
        final ManagedStackFrame frame;
        final TargetLayout layout;
        ManagedAllocation ipeTemplate; // Never retains the registered info-table key.
        Provenance(ManagedStackFrame frame, TargetLayout layout) { this.frame = frame; this.layout = layout; }
    }
    private record FrameInfo(ManagedAddress standard, ManagedAddress key, Provenance provenance) {}
    private record Images(TargetLayout layout, ManagedAddress stack, List<FrameInfo> frames) {}
    // Snapshot equality is identity; these values never refer to their snapshot.
    private final WeakHashMap<ManagedStackSnapshot, Images> snapshots = new WeakHashMap<>();
    private final ManagedAddress.WeakLocations<Provenance> entries = new ManagedAddress.WeakLocations<>();
    private final Set<TargetLayout> layouts = new HashSet<>();
    private static final String[] TEXT_FIELDS = {"Name", "TyDesc", "Label", "Unit", "Module", "File", "Span"};
    private static final ManagedStackBitmap EMPTY_BITMAP = new ManagedStackBitmap(0, 0);
    private static final ManagedStackAdvance END_OF_STACK = new ManagedStackAdvance(null, 0, 0);

    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private void validate(TargetLayout layout) {
        if (closed) throw RuntimeFault.fault("Managed stack registry is closed");
        snapshots.size(); // Drain weak images even on cached getters.
        entries.getSize();
        if (layouts.contains(layout)) return;
        try {
            // The closed image domain is nonempty one-word RET_SMALL headers, no payload/chunks.
            require(layout.offset("stackHeaderBytes") == layout.getWordBytes() && layout.offset("stackClosurePayloadBytes") == layout.getWordBytes(),
                "Managed diagnostic frames require a one-word nonprofiling header");
            ManagedStackInfoImage.stack(layout); ManagedStackInfoImage.frame(layout);
            require(layout.offset("infoProvDescBytes") == 4, "InfoProv closure_desc must be Word32");
            long base = layout.offset("infoProvEntProvOffset");
            long[][] ranges = new long[TEXT_FIELDS.length + 2][2];
            ranges[0] = new long[] {layout.offset("infoProvEntInfoOffset"), layout.getWordBytes()};
            for (int i = 0; i < TEXT_FIELDS.length; i++) ranges[i + 1] = new long[] {
                base + layout.offset("infoProv" + TEXT_FIELDS[i] + "Offset"), layout.getWordBytes()};
            ranges[ranges.length - 1] = new long[] {base + layout.offset("infoProvDescOffset"), 4};
            for (long[] range : ranges) require(range[0] >= 0 && range[0] + range[1] <= layout.offset("infoProvEntBytes"), "InfoProv field outside entry");
            for (int i = 0; i < ranges.length; i++) for (int j = i + 1; j < ranges.length; j++)
                require(ranges[i][0] + ranges[i][1] <= ranges[j][0] || ranges[j][0] + ranges[j][1] <= ranges[i][0], "Overlapping InfoProv fields");
        } catch (IllegalArgumentException invalid) {
            throw RuntimeFault.fault("Invalid managed stack target layout: " + invalid.getMessage());
        }
        layouts.add(layout);
    }
    private static ManagedAddress address(ManagedStackInfoImage image, TargetLayout layout) {
        return ManagedAddress.Companion.fromStaticBytes$org_intelligence_thc(image.copyBytes(), layout.getWordBytes());
    }
    private Images images(Object value, TargetLayout layout) {
        validate(layout);
        if (!(value instanceof ManagedStackSnapshot snapshot)) throw RuntimeFault.fault("Expected a managed StackSnapshot#");
        if (snapshot.getOwnerToken() != token) throw RuntimeFault.fault("StackSnapshot# belongs to another context or is diagnostic-only");
        Images cached = snapshots.get(snapshot);
        if (cached != null) {
            if (!cached.layout.equals(layout)) throw RuntimeFault.fault("StackSnapshot# target layout changed");
            return cached;
        }
        List<FrameInfo> frames = new ArrayList<>();
        for (ManagedStackFrame frame : snapshot.getFrames()) {
            ManagedAddress standard = address(ManagedStackInfoImage.frame(layout), layout);
            frames.add(new FrameInfo(standard, standard.plus(layout.offset("infoTableBytes")), new Provenance(frame, layout)));
        }
        Images result = new Images(layout, address(ManagedStackInfoImage.stack(layout), layout), frames);
        snapshots.put(snapshot, result);
        for (FrameInfo frame : frames) entries.set(frame.key, frame.provenance);
        return result;
    }
    public synchronized ManagedAddress stackInfo(Object snapshot, TargetLayout layout) { return images(snapshot, layout).stack; }
    /** Each diagnostic frame occupies one virtual word, with no payload. */
    public synchronized ManagedStackFrameInfo frameInfo(Object snapshot, long wordOffset, TargetLayout layout) {
        List<FrameInfo> frames = images(snapshot, layout).frames;
        if (wordOffset < 0 || wordOffset >= frames.size()) throw RuntimeFault.fault("Managed stack frame offset outside snapshot");
        FrameInfo frame = frames.get((int) wordOffset);
        return new ManagedStackFrameInfo(frame.standard, frame.key);
    }
    private Images diagnosticFrame(Object snapshot, long wordOffset, TargetLayout layout) {
        Images images = images(snapshot, layout);
        if (wordOffset < 0 || wordOffset >= images.frames.size()) throw RuntimeFault.fault("Managed stack frame offset outside snapshot");
        return images;
    }
    /** Exact zero-slack virtual capacity, not native stack capacity. */
    public synchronized long stackFields(Object snapshot, TargetLayout layout) { return images(snapshot, layout).frames.size(); }
    public synchronized ManagedStackBitmap smallBitmap(Object snapshot, long wordOffset, TargetLayout layout) {
        diagnosticFrame(snapshot, wordOffset, layout); return EMPTY_BITMAP;
    }
    /** Materialize the same owned immutable info address used by addr2Int#, not a token. */
    public synchronized long word(Object snapshot, long wordOffset, TargetLayout layout) {
        return diagnosticFrame(snapshot, wordOffset, layout).frames.get((int) wordOffset).key.toNativeBits();
    }
    public synchronized ManagedStackAdvance advance(Object snapshot, long wordOffset, TargetLayout layout) {
        Images images = diagnosticFrame(snapshot, wordOffset, layout);
        long next = wordOffset + 1;
        // Original Stack.cmm returns null/zero carriers, not the old snapshot or one-past location.
        return next < images.frames.size() ? new ManagedStackAdvance((ManagedStackSnapshot) snapshot, next, 1) : END_OF_STACK;
    }
    public synchronized Void incompatibleGetter(OriginalStackInfoOp operation, Object snapshot, long wordOffset, TargetLayout layout) {
        diagnosticFrame(snapshot, wordOffset, layout);
        require(operation == OriginalStackInfoOp.CLOSURE || operation == OriginalStackInfoOp.LARGE_BITMAP ||
            operation == OriginalStackInfoOp.BCO_LARGE_BITMAP || operation == OriginalStackInfoOp.RET_FUN_LARGE_BITMAP ||
            operation == OriginalStackInfoOp.RET_FUN_SMALL_BITMAP || operation == OriginalStackInfoOp.RET_FUN_BIG || operation == OriginalStackInfoOp.UNDERFLOW,
            "Failed requirement.");
        throw RuntimeFault.fault(operation.getSymbol() + " cannot read a payload, bitmap kind or chunk absent from a managed diagnostic RET_SMALL frame");
    }
    public synchronized long lookupIpe(ManagedAddress key, ManagedAddress destination, TargetLayout layout) {
        validate(layout);
        Provenance entry = entries.get(key);
        if (entry == null) return 0;
        if (!entry.layout.equals(layout)) throw RuntimeFault.fault("IPE key target layout changed");
        long count = layout.offset("infoProvEntBytes");
        destination.requireRange(0, count, true);
        ManagedAllocation template = entry.ipeTemplate;
        if (template == null) entry.ipeTemplate = template = buildIpeTemplate(entry);
        // A cached key-containing image would strongly retain the weak index's owner.
        ManagedAllocation scratch = ManagedAllocation.mutable(count, layout.getWordBytes());
        scratch.copyFrom(template, 0, 0, count);
        scratch.writeAddressByteOffset(layout.offset("infoProvEntInfoOffset"), key);
        destination.copyFromAllocationBytes$org_intelligence_thc(scratch, 0, count); // Sole destination mutation, after all validation.
        return 1;
    }
    private ManagedAllocation buildIpeTemplate(Provenance entry) {
        TargetLayout layout = entry.layout;
        long count = layout.offset("infoProvEntBytes");
        ManagedAllocation scratch = ManagedAllocation.mutable(count, layout.getWordBytes());
        ManagedStackFrame frame = entry.frame;
        ManagedStackFunctionIdentity identity = frame.getCoreIdentity();
        ManagedStackSource source = frame.getLocation();
        if (source != null && !source.available()) source = null;
        ManagedStackNote note = null;
        for (int i = frame.getNotes().size() - 1; i >= 0; i--) {
            ManagedStackNote candidate = frame.getNotes().get(i);
            if (source != null && candidate.source().uri().equals(source.uri()) &&
                Objects.equals(candidate.startLine(), source.startLine()) && Objects.equals(candidate.startColumn(), source.startColumn())) { note = candidate; break; }
        }
        String span = "";
        if (note != null) span = note.startLine() + ":" + note.startColumn() + "-" + note.endLine() + ":" + note.endColumn() + " (end exclusive)";
        else if (source != null && source.startLine() != null) {
            StringBuilder text = new StringBuilder().append(source.startLine());
            if (source.startColumn() != null) text.append(':').append(source.startColumn());
            if (source.endLine() != null) {
                text.append('-').append(source.endLine());
                if (source.endColumn() != null) text.append(':').append(source.endColumn());
            }
            span = text.toString();
        }
        String[] values = {"THC managed diagnostic frame", "", identity == null ? note == null || note.label() == null ? "" : note.label() : identity.occurrence(),
            identity == null ? "" : identity.unitId(), identity == null ? "" : identity.moduleName(),
            source == null ? "" : source.path() == null ? source.name() : source.path(), span};
        long base = layout.offset("infoProvEntProvOffset");
        for (int i = 0; i < TEXT_FIELDS.length; i++) {
            String value = values[i];
            if (value.indexOf('\u0000') >= 0) throw RuntimeFault.fault("NUL in managed stack provenance");
            ManagedAddress text = ManagedAddress.Companion.fromHex(HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)));
            scratch.writeAddressByteOffset(base + layout.offset("infoProv" + TEXT_FIELDS[i] + "Offset"), text);
        }
        byte[] descriptor = ByteBuffer.allocate(4).order(layout.getEndianness().equals("little") ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
            .putInt(layout.offset("closureRetSmall")).array();
        scratch.copyBytesIn(descriptor, 0, base + layout.offset("infoProvDescOffset"), 4);
        return scratch;
    }
    public synchronized void dispose() { closed = true; snapshots.clear(); entries.clear(); layouts.clear(); }
}
