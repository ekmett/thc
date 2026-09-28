// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

/** GHC 9.14.1's RTS-visible bad-descriptor closure, retained lazily. */
public final class CoreFileWait {
    private CoreFileWait() {}
    public static final String badFd = "ghc-internal:GHC.Internal.Event.Thread.blockedOnBadFD";
    public static boolean named(String name) { return name.equals("waitRead#") || name.equals("waitWrite#"); }
    private static boolean state(CoreRepresentation rep) {
        return !rep.isAggregate() && !rep.isVector() && rep.getKind() == CoreKind.VOID && List.of().equals(rep.getPrimReps());
    }
    public static void validate(String name, List<CoreRepresentation> args, List<?> flags, CoreRepresentation result) {
        var fd = args.isEmpty() ? null : args.getFirst();
        if (!named(name) || args.size() != 2 || fd == null || fd.getKind() != CoreKind.LONG ||
            fd.isAggregate() || fd.isVector() || !List.of("IntRep").equals(fd.getPrimReps()) ||
            !state(args.get(1)) || !flags.equals(List.of(false, false)) || !state(result))
            throw new RuntimeFault(name + ": expected exact Int#, State# -> State# contract");
    }
}
