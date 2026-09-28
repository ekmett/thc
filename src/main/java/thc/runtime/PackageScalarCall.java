// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;

public final class PackageScalarCall {
    public enum Kind { STATIC, DYNAMIC, CREATE_CALLBACK, FREE_CALLBACK }
    private final Kind kind;
    private final PackageScalarLink link;
    private final PackageScalarSignature signature;
    @CompilationFinal(dimensions = 1) private final String[] arguments;
    private final String result;
    private final ForeignSafety safety;
    public PackageScalarCall(PackageScalarLink link, PackageScalarSignature signature) {
        this(link, signature, Kind.STATIC);
    }
    public PackageScalarCall(PackageScalarLink link, PackageScalarSignature signature, Kind kind) {
        this.kind = kind;
        this.link = link; this.signature = signature;
        arguments = signature.getArguments().toArray(String[]::new);
        result = signature.getResult();
        safety = ForeignSafety.synchronous(signature.getSafety());
    }
    public PackageScalarLink getLink() { return link; }
    public Kind getKind() { return kind; }
    public boolean executesForeign() { return kind == Kind.STATIC || kind == Kind.DYNAMIC; }
    public PackageScalarSignature getSignature() { return signature; }
    public String[] getArguments() { return arguments; }
    public String getResult() { return result; }
    public ForeignSafety getSafety() { return safety; }
}
