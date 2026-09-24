// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import thc.*;
import java.nio.file.*;
import org.graalvm.polyglot.*;
public final class RequestLoadProbe {
  public static void main(String[] args) throws Exception {
    var paths = Files.readAllLines(Path.of(args[0]));
    String request = CoreModules.INSTANCE.request(paths, "sequenceBuild", true, false, args[1], true);
    try (Context context = MainKt.executionContext()) {
      try { context.eval("thc", request); throw new AssertionError("Expected existing Sequence frontier"); }
      catch (PolyglotException e) {
        String message=e.getMessage();
        if (message == null || !message.contains("Unsupported Core aggregate representation: unboxed-tuple (formal argument)")) throw e;
        System.out.println("EXPECTED_REJECTION " + args[1] + " " + message);
      }
    }
  }
}
