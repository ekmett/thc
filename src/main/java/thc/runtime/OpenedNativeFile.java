// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/** Preserve the public Truffle channel wrapper for byte operations. Metadata
 * comes from the same configured provider transaction. No private unwrapping. */
public final class OpenedNativeFile implements NativeFileResource {
    private final SeekableByteChannel channel;
    private final NativeFileResource metadata;
    public OpenedNativeFile(SeekableByteChannel channel, NativeFileResource metadata) {
        this.channel = Objects.requireNonNull(channel);
        this.metadata = Objects.requireNonNull(metadata);
    }
    @Override public int read(ByteBuffer destination) throws IOException { return channel.read(destination); }
    @Override public int write(ByteBuffer source) throws IOException { return channel.write(source); }
    @Override public long position() throws IOException { return channel.position(); }
    @Override public SeekableByteChannel position(long position) throws IOException { return channel.position(position); }
    @Override public long size() throws IOException { return channel.size(); }
    @Override public SeekableByteChannel truncate(long size) throws IOException { return channel.truncate(size); }
    @Override public boolean isOpen() { return channel.isOpen(); }
    @Override public long unlinkAt(byte[] path, int flags) { return metadata.unlinkAt(path, flags); }
    @Override public byte[] statAt(byte[] path, int flags) { return metadata.statAt(path, flags); }
    @Override public byte[] statImage() { return metadata.statImage(); }
    @Override public void readTermios(byte[] image) { metadata.readTermios(image); }
    @Override public long terminalStatus() { return metadata.terminalStatus(); }
    @Override public void writeTermios(int action, byte[] image) { metadata.writeTermios(action, image); }
    @Override public long statusFlags() { return metadata.statusFlags(); }
    @Override public long fcntl(int command, long argument, boolean hasArgument) { return metadata.fcntl(command, argument, hasArgument); }
    @Override public boolean fcntlCreatesDescriptor(int command) { return metadata.fcntlCreatesDescriptor(command); }
    @Override public NativeFileResource fcntlDuplicate(int command, long minimum) { return metadata.fcntlDuplicate(command, minimum); }
    @Override public long writeEvent(long value) { return metadata.writeEvent(value); }
    @Override public int duplicateDescriptor() { return metadata.duplicateDescriptor(); }
    @Override public int duplicateInheritableDescriptor() { return metadata.duplicateInheritableDescriptor(); }
    @Override public void requireLive() { metadata.requireLive(); }
    @Override public NativeFdWait readinessWait() { return metadata.readinessWait(); }
    @Override public void close() throws IOException {
        try { channel.close(); } finally { metadata.close(); }
    }
}
