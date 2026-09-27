package protocolprobe;

/** Probe class initialization before any context, in a fresh VM for each artifact selection. */
public final class RuntimeLinkageProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !(args[0].equals("stock") || args[0].equals("overlay")))
      throw new AssertionError("usage: RuntimeLinkageProbe stock|overlay");
    boolean overlay = args[0].equals("overlay");
    try {
      Class.forName("thc.runtime.GuestRoot");
      if (!overlay) throw new AssertionError("Stock optimized runtime silently accepted THC roots");
    } catch (NoSuchMethodError unavailable) {
      if (overlay || !unavailable.getMessage().contains("declaredReturnPolicyVersion")) throw unavailable;
    }
    System.out.println("PASS pre-context runtime linkage " + args[0]);
  }
}
