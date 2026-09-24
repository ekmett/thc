import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.runtime.BytecodeProgram;
import thc.runtime.ExecutableProgram;
import thc.runtime.Program;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/** Native-backed caller-owned byte arrays; no timing, retries, or post-freeze mutation. */
public final class Word32X4ByteArrayRuntimeGraphProbe {
    static final Set<String> ENTRIES = Set.of("vectorIndexWorker", "scalarIndexWorker", "vectorStoreGraph", "scalarStoreGraph");
    record Row(long offset, long[] lanes, byte[] initial, byte[] expected, long checksum) {}

    static boolean valid(RootCallTarget target) throws Exception {
        return (boolean)target.getClass().getMethod("isValidLastTier").invoke(target);
    }

    static RootCallTarget active(ExecutableProgram program, String id, int arity) {
        RootCallTarget original = program.entryTarget(id);
        List<RootCallTarget> targets = NodeUtil.findAllNodeInstances(program.hostEntryTarget(arity).getRootNode(), DirectCallNode.class)
            .stream().filter(call -> call.getCallTarget() == original)
            .map(call -> (RootCallTarget)call.getCurrentCallTarget()).distinct().toList();
        if (targets.size() != 1) throw new AssertionError("Expected one active direct guest target: " + id + " " + targets);
        return targets.getFirst();
    }

    static long integer(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) throw new AssertionError("Expected exact JSON integer: " + value);
        return ((Number)value).longValue();
    }

    static byte[] bytes(Object value) {
        if (!(value instanceof List<?> values) || values.size() != 64) throw new AssertionError("Expected 64 bytes");
        byte[] result = new byte[64];
        for (int i = 0; i < 64; i++) {
            long number = integer(values.get(i));
            if (number < 0 || number > 255) throw new AssertionError("Invalid byte");
            result[i] = (byte)number;
        }
        return result;
    }

    static String key(String name, long[] values) {
        StringBuilder result = new StringBuilder(name);
        for (long value : values) result.append('\t').append(value);
        return result.toString();
    }

    @SuppressWarnings("unchecked") static List<Row> rows(Map<String,Object> definition, Map<String,Long> nativeRows) {
        String entry = (String)definition.get("name");
        boolean store = entry.endsWith("StoreGraph");
        int scale = entry.startsWith("vector") ? 16 : 4;
        if (!definition.get("operation").equals(store ? "store" : "index")
                || integer(definition.get("arity")) != (store ? 7 : 2)
                || integer(definition.get("offsetUnitBytes")) != scale)
            throw new AssertionError("Wrong operation/offset unit/arity");
        List<Row> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String,Object> item : (List<Map<String,Object>>)definition.get("cases")) {
            long offset = integer(item.get("offset"));
            List<?> values = (List<?>)item.get("lanes");
            if (offset < 0 || offset > 48 / scale || values.size() != 4) throw new AssertionError("Wrong offset/lanes");
            long[] lanes = new long[4];
            long score = 0;
            int[] weights = {3,5,7,11};
            byte[] initial = new byte[64];
            for (int i = 0; i < 16; i++) {
                int word = 0x89abcdef + i * 0x01030507;
                for (int b = 0; b < 4; b++) initial[4*i+b] = (byte)(word >>> (8*b));
            }
            byte[] expected = initial.clone();
            for (int lane = 0; lane < 4; lane++) {
                lanes[lane] = integer(values.get(lane));
                if (lanes[lane] < 0 || lanes[lane] > 0xffffffffL) throw new AssertionError("Noncanonical unsigned32 lane");
                score += lanes[lane] * weights[lane];
                for (int b = 0; b < 4; b++) expected[(int)offset*scale+4*lane+b] = (byte)((int)lanes[lane] >>> (8*b));
            }
            if (!store) initial = expected.clone();
            if (!Arrays.equals(initial, bytes(item.get("initialBytes")))
                    || !Arrays.equals(expected, bytes(item.get("expectedBytes"))) || score != integer(item.get("expectedScalar")))
                throw new AssertionError("Independent byte/checksum model mismatch");
            String nativeName = (entry.startsWith("vector") ? "vector" : "scalar") + (store ? "StoreCase" : "IndexCase");
            List<?> declared = (List<?>)item.get("nativeArguments");
            long[] args = {offset, lanes[0], lanes[1], lanes[2], lanes[3]};
            if (!nativeName.equals(item.get("nativeEntry")) || declared.size() != 5) throw new AssertionError("Wrong native mapping");
            for (int i = 0; i < 5; i++) if (integer(declared.get(i)) != args[i]) throw new AssertionError("Native argument mismatch");
            if (!seen.add(key(entry, args))) throw new AssertionError("Duplicate input");
            if (store) {
                long[] selected = Arrays.copyOf(args, 6);
                for (int i = 0; i < 64; i++) {
                    selected[5] = i;
                    if (!Objects.equals(nativeRows.get(key(nativeName, selected)), score*257+(expected[i]&255)))
                        throw new AssertionError("Native byte witness mismatch: " + i);
                }
            } else if (!Objects.equals(nativeRows.get(key(nativeName, args)), score)) {
                throw new AssertionError("Native checksum mismatch");
            }
            result.add(new Row(offset, lanes, initial, expected, score));
        }
        if (result.size() != 36) throw new AssertionError("Expected 36 native-backed graph cases");
        return result;
    }

    static void check(ExecutableProgram program, String id, String entry, List<Row> rows, RootCallTarget compiled) throws Exception {
        boolean store = entry.endsWith("StoreGraph");
        int arity = store ? 7 : 2;
        RootCallTarget host = program.hostEntryTarget(arity);
        Object closure = program.entryValue(id);
        for (Row row : rows) {
            if (compiled != null && (active(program, id, arity) != compiled || !valid(compiled)))
                throw new AssertionError("Entry inactive/invalid before input: " + entry);
            // Fresh caller-owned storage for every warm and measured invocation.
            byte[] array = row.initial.clone();
            Object[] arguments = store
                ? new Object[]{array, row.offset, row.lanes[0], row.lanes[1], row.lanes[2], row.lanes[3], kotlin.Unit.INSTANCE}
                : new Object[]{array, row.offset};
            Object actual = host.call(closure, arguments);
            if (store ? actual != array : !(actual instanceof Long) || ((Long)actual).longValue() != row.checksum)
                throw new AssertionError("Wrong result/array identity: " + entry);
            if (!Arrays.equals(array, row.expected)) throw new AssertionError("Wrong backing bytes: " + entry);
            // No write to array after a store root's final unsafeFreeze action.
            if (compiled != null && (active(program, id, arity) != compiled || !valid(compiled)))
                throw new AssertionError("Entry changed/invalidated on input: " + entry);
        }
    }

    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        if (args.length != 7 || !ENTRIES.contains(args[3]) || !Set.of("ast", "bytecode").contains(args[4])
                || !args[5].equals("inline") || !args[6].equals("native"))
            throw new IllegalArgumentException("module.json graph-cases.json oracle.tsv entry ast|bytecode inline native");
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) throw new AssertionError("Native corpus is little endian");
        String entry = args[3], backend = args[4];
        int arity = entry.endsWith("StoreGraph") ? 7 : 2;
        Map<String,Long> nativeRows = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[2]))) {
            int last = line.lastIndexOf('\t');
            if (last < 0 || nativeRows.put(line.substring(0,last), Long.parseLong(line.substring(last+1))) != null)
                throw new AssertionError("Malformed/duplicate native row");
        }
        if (nativeRows.size() != 9666) throw new AssertionError("Wrong native corpus cardinality");
        List<Map<String,Object>> definitions = (List<Map<String,Object>>)Json.INSTANCE.parse(Files.readString(Path.of(args[1])));
        List<Map<String,Object>> chosen = definitions.stream().filter(e -> entry.equals(e.get("name"))).toList();
        if (chosen.size() != 1) throw new AssertionError("Missing/duplicate graph definition");
        List<Row> rows = rows(chosen.getFirst(), nativeRows);
        Map<String,Object> module = (Map<String,Object>)Json.INSTANCE.parse(Files.readString(Path.of(args[0])));
        Map<String,Object> linked = new LinkedHashMap<>(CoreModules.INSTANCE.reachable(module, entry));
        linked.put("instrument", false); linked.put("diagnosticUnsupported", false);
        List<Map<String,Object>> bindings = (List<Map<String,Object>>)linked.get("bindings");
        if (bindings.size() != 1 || !entry.equals(bindings.getFirst().get("name"))
                || integer(bindings.getFirst().get("arity")) != arity) throw new AssertionError("Expected exact one-root Core closure");
        String id = (String)bindings.getFirst().get("id");
        Context.Builder builder = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000");
        try (Context context = builder.build()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                for (int i = 0; i < 40; i++) check(program, id, entry, rows, null);
                RootCallTarget target = active(program, id, arity);
                if (valid(target)) throw new AssertionError("Unexpected automatic compilation");
                System.out.println("GRAPH_TARGET=" + Json.INSTANCE.stringify(Map.of("entry", entry, "backend", backend, "root", target.getRootNode().getName())));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                if (!valid(target)) throw new AssertionError("No installed last-tier code");
                check(program, id, entry, rows, target); check(program, id, entry, rows, target);
                System.out.println("PASS entry=" + entry + " backend=" + backend + " mode=inline oracleOrigin=native oracleRows=" + rows.size()
                    + " arity=" + arity + " compiledPasses=2 backingBytes=64 freshArrayEveryCall=true validAfterExecution=true");
                System.out.println("diagnostics=" + Json.INSTANCE.stringify(program.diagnostics()));
            } finally { context.leave(); }
        }
    }
}
