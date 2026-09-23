import com.oracle.truffle.api.TruffleOptions;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.VMField;

/** Read-only VM layout metadata. No Truffle context, target, or guest invocation. */
public final class VmMetadata {
    public static void main(String[] args) {
        System.out.println("TruffleOptions.AOT=" + TruffleOptions.AOT);
        var fields = HotSpotJVMCIRuntime.runtime().getConfigStore().getFields();
        for (String name : new String[] {"Method::_code", "Method::_i2i_entry",
                "Method::_from_compiled_entry", "Method::_from_interpreted_entry"}) {
            VMField field = fields.get(name);
            if (field == null) {
                System.out.println(name + "=not exported by JVMCI");
            } else {
                System.out.printf("%s=0x%x%n", name, field.offset);
            }
        }
        if (TruffleOptions.AOT) {
            throw new IllegalStateException("This inspection is for the non-AOT HotSpot runtime");
        }
    }
}
