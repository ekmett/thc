package protocolprobe;

/** Probe class initialization before any context, in a fresh VM for each artifact selection. */
public final class RuntimeLinkageProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !(args[0].equals("stock") || args[0].equals("overlay")))
      throw new AssertionError("usage: RuntimeLinkageProbe stock|overlay");
    Class.forName("thc.runtime.GuestRoot");
    Class.forName("thc.runtime.BytecodeRoot");
    System.out.println("PASS pre-context runtime linkage " + args[0]);
  }
}
