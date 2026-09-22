import java.nio.file.Files;
import java.nio.file.Path;

/** Dump the actual generated/quickened instruction streams after guest execution. */
public final class BytecodeDump {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("MODULES_FILE ENTRY INPUT OUTPUT_FILE");
        var modules = Files.readAllLines(Path.of(args[0])).stream().filter(s -> !s.isBlank()).toList();
        long input = Long.parseLong(args[2]);
        try (var context = thc.MainKt.executionContext()) {
            var entry = thc.MainKt.loadEntry(context, modules, args[1], false, "bytecode");
            for (int i = 0; i < 40; i++) entry.execute(input + (i & 3)).asLong();
            entry.invokeMember("compile");
            long result = entry.execute(input).asLong();
            Files.writeString(Path.of(args[3]), entry.getMember("bytecode").asString());
            System.out.println("result=" + result);
            System.out.println("diagnostics=" + entry.getMember("diagnostics").asString());
        }
    }
}
