package protocolprobe;
import java.io.*;
import java.nio.file.*;
public final class LegacyProducer {
  public static void main(String[] args) throws Exception {
    try (var out = new DataOutputStream(Files.newOutputStream(Path.of(args[0])))) {
      BranchRootGen.serialize(out, (context, data, value) -> BranchRoot.writeConstant(data, value), b -> {
        b.beginRoot(); b.beginIfThen(); b.emitLoadArgument(0); b.emitMark(); b.endIfThen();
        b.beginReturn(); b.emitLoadConstant(42L); b.endReturn(); b.endRoot();
      });
    }
  }
}
