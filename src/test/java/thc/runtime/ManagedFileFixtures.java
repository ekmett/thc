// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;
import java.util.function.Consumer;
import static thc.runtime.OriginalStdioChecks.map;
import static thc.runtime.OriginalStdioChecks.list;

/** Synthetic descriptors, independent of the production operation signature table. */
public final class ManagedFileFixtures {
    private ManagedFileFixtures() {}
    public static final Map<String, List<String>> signatures = new LinkedHashMap<>();
    static {
        signatures.put("open", Arrays.asList("AddrRep", "IntRep", null));
        signatures.put("read", Arrays.asList("IntRep", "AddrRep", "IntRep", null));
        signatures.put("write", Arrays.asList("IntRep", "AddrRep", "IntRep", null));
        signatures.put("close", Arrays.asList("IntRep", null));
        signatures.put("error_kind", Arrays.asList((String) null));
        signatures.put("error_message", Arrays.asList((String) null));
        signatures.put("seek", Arrays.asList("IntRep", "IntRep", "IntRep", null));
        signatures.put("size", Arrays.asList("IntRep", null));
        signatures.put("set_size", Arrays.asList("IntRep", "IntRep", null));
        signatures.put("is_terminal", Arrays.asList("IntRep", null));
        signatures.put("device_type", Arrays.asList("IntRep", null));
    }
    public static Map<String, Object> scalar(String rep) { return scalar(rep, true); }
    public static Map<String, Object> scalar(String rep, boolean evaluated) {
        String kind = switch (rep) {
            case null -> "void"; case "AddrRep" -> "address";
            case "BoxedRep (Just Lifted)" -> "closure"; default -> "long";
        };
        return map("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    public static String output(String name) { return name.equals("error_message") ? "AddrRep" : "IntRep"; }
    public static Map<String, Object> tuple(String name) { return tuple(name, true); }
    public static Map<String, Object> tuple(String name, boolean evaluated) {
        return map("kind", "unknown", "primReps", List.of(output(name)), "aggregate", "unboxed-tuple",
            "components", new ArrayList<>(List.of(scalar(null), scalar(output(name)))), "evaluated", evaluated);
    }
    public static Map<String, Object> closure() { return scalar("BoxedRep (Just Lifted)"); }
    public static Map<String, Object> descriptor(String name) {
        var reps = Objects.requireNonNull(signatures.get(name));
        var arguments = new ArrayList<Object>();
        for (var rep : reps) arguments.add(scalar(rep, false));
        return map("schema", 1L, "target", map("kind", "static", "symbol", "thc_io_v1_" + name, "unit", "main", "isFunction", true),
            "convention", "prim", "safety", "safe", "arity", (long) reps.size(), "suppliedArity", (long) reps.size(),
            "argumentReps", arguments, "resultRep", tuple(name, false));
    }
    public static List<Object> call(String name) {
        var reps = Objects.requireNonNull(signatures.get(name));
        var arguments = new ArrayList<Object>();
        for (int i = 0; i < reps.size(); i++) arguments.add(list("var", "p" + i, map("rep", scalar(reps.get(i)))));
        return new ArrayList<>(list("app", list("var", "foreign-" + name, map("rep", closure())), arguments,
            new ArrayList<>(Collections.nCopies(reps.size(), false)), false, false,
            map("rep", tuple(name), "foreignCall", descriptor(name))));
    }
    public static Map<String, Object> module() { return module(signatures.keySet(), ignored -> {}); }
    public static Map<String, Object> module(Iterable<String> names) { return module(names, ignored -> {}); }
    public static Map<String, Object> module(Consumer<List<Object>> mutate) { return module(signatures.keySet(), mutate); }
    public static Map<String, Object> module(Iterable<String> names, Consumer<List<Object>> mutate) {
        var bindings = new ArrayList<Object>();
        for (String name : names) {
            var reps = Objects.requireNonNull(signatures.get(name));
            var formals = new ArrayList<Object>();
            for (int i = 0; i < reps.size(); i++) formals.add(map("id", "p" + i, "name", name + "_" + i,
                "lifted", false, "rep", scalar(reps.get(i))));
            var expression = call(name); mutate.accept(expression);
            var body = list("case", expression, "pair", list(list("data", "T2", list("s", "value"),
                list("var", "value", map("rep", scalar(output(name)))),
                map("binders", list(map("id", "s", "lifted", false, "rep", scalar(null)),
                    map("id", "value", "lifted", false, "rep", scalar(output(name))))))),
                map("rep", scalar(output(name)), "binder", map("id", "pair", "lifted", false, "rep", tuple(name))));
            bindings.add(map("id", name, "name", name, "arity", reps.size(), "lifted", true, "rep", closure(),
                "expr", list("lam", formals, body, map("rep", closure(), "resultRep", scalar(output(name))))));
        }
        return map("instrument", true, "constructors", list(map("id", "T2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "bindings", bindings);
    }
    /** Explicit equivalents of Kotlin delegation used by provider fault-injection tests. */
    static class FileSystemDelegate implements org.graalvm.polyglot.io.FileSystem {
        protected final org.graalvm.polyglot.io.FileSystem delegate;
        FileSystemDelegate(org.graalvm.polyglot.io.FileSystem delegate) { this.delegate = delegate; }
        public java.nio.file.Path parsePath(java.net.URI uri) { return delegate.parsePath(uri); }
        public java.nio.file.Path parsePath(String path) { return delegate.parsePath(path); }
        public void checkAccess(java.nio.file.Path path, Set<? extends java.nio.file.AccessMode> modes, java.nio.file.LinkOption... options) throws java.io.IOException { delegate.checkAccess(path, modes, options); }
        public void createDirectory(java.nio.file.Path path, java.nio.file.attribute.FileAttribute<?>... attributes) throws java.io.IOException { delegate.createDirectory(path, attributes); }
        public void delete(java.nio.file.Path path) throws java.io.IOException { delegate.delete(path); }
        public java.nio.channels.SeekableByteChannel newByteChannel(java.nio.file.Path path, Set<? extends java.nio.file.OpenOption> options, java.nio.file.attribute.FileAttribute<?>... attributes) throws java.io.IOException { return delegate.newByteChannel(path, options, attributes); }
        public java.nio.file.DirectoryStream<java.nio.file.Path> newDirectoryStream(java.nio.file.Path path, java.nio.file.DirectoryStream.Filter<? super java.nio.file.Path> filter) throws java.io.IOException { return delegate.newDirectoryStream(path, filter); }
        public java.nio.file.Path toAbsolutePath(java.nio.file.Path path) { return delegate.toAbsolutePath(path); }
        public java.nio.file.Path toRealPath(java.nio.file.Path path, java.nio.file.LinkOption... options) throws java.io.IOException { return delegate.toRealPath(path, options); }
        public Map<String, Object> readAttributes(java.nio.file.Path path, String attributes, java.nio.file.LinkOption... options) throws java.io.IOException { return delegate.readAttributes(path, attributes, options); }
        public void setAttribute(java.nio.file.Path path, String attribute, Object value, java.nio.file.LinkOption... options) throws java.io.IOException { delegate.setAttribute(path, attribute, value, options); }
        public void copy(java.nio.file.Path source, java.nio.file.Path target, java.nio.file.CopyOption... options) throws java.io.IOException { delegate.copy(source, target, options); }
        public void move(java.nio.file.Path source, java.nio.file.Path target, java.nio.file.CopyOption... options) throws java.io.IOException { delegate.move(source, target, options); }
        public void createLink(java.nio.file.Path link, java.nio.file.Path existing) throws java.io.IOException { delegate.createLink(link, existing); }
        public void createSymbolicLink(java.nio.file.Path link, java.nio.file.Path target, java.nio.file.attribute.FileAttribute<?>... attributes) throws java.io.IOException { delegate.createSymbolicLink(link, target, attributes); }
        public java.nio.file.Path readSymbolicLink(java.nio.file.Path link) throws java.io.IOException { return delegate.readSymbolicLink(link); }
        public void setCurrentWorkingDirectory(java.nio.file.Path path) { delegate.setCurrentWorkingDirectory(path); }
        public String getSeparator() { return delegate.getSeparator(); }
        public String getPathSeparator() { return delegate.getPathSeparator(); }
        public String getMimeType(java.nio.file.Path path) { return delegate.getMimeType(path); }
        public java.nio.charset.Charset getEncoding(java.nio.file.Path path) { return delegate.getEncoding(path); }
        public java.nio.file.Path getTempDirectory() { return delegate.getTempDirectory(); }
        public boolean isSameFile(java.nio.file.Path path, java.nio.file.Path other, java.nio.file.LinkOption... options) throws java.io.IOException { return delegate.isSameFile(path, other, options); }
        public long getFileStoreTotalSpace(java.nio.file.Path path) throws java.io.IOException { return delegate.getFileStoreTotalSpace(path); }
        public long getFileStoreUnallocatedSpace(java.nio.file.Path path) throws java.io.IOException { return delegate.getFileStoreUnallocatedSpace(path); }
        public long getFileStoreUsableSpace(java.nio.file.Path path) throws java.io.IOException { return delegate.getFileStoreUsableSpace(path); }
        public long getFileStoreBlockSize(java.nio.file.Path path) throws java.io.IOException { return delegate.getFileStoreBlockSize(path); }
        public boolean isFileStoreReadOnly(java.nio.file.Path path) throws java.io.IOException { return delegate.isFileStoreReadOnly(path); }
    }
    static class ChannelDelegate implements java.nio.channels.SeekableByteChannel {
        protected final java.nio.channels.SeekableByteChannel channel;
        ChannelDelegate(java.nio.channels.SeekableByteChannel channel) { this.channel = channel; }
        public int read(java.nio.ByteBuffer destination) throws java.io.IOException { return channel.read(destination); }
        public int write(java.nio.ByteBuffer source) throws java.io.IOException { return channel.write(source); }
        public long position() throws java.io.IOException { return channel.position(); }
        public java.nio.channels.SeekableByteChannel position(long position) throws java.io.IOException { return channel.position(position); }
        public long size() throws java.io.IOException { return channel.size(); }
        public java.nio.channels.SeekableByteChannel truncate(long size) throws java.io.IOException { return channel.truncate(size); }
        public boolean isOpen() { return channel.isOpen(); }
        public void close() throws java.io.IOException { channel.close(); }
    }
}
