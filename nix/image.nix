# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{ dockerTools, toolchain, jam, glibc, proot }:
dockerTools.buildLayeredImage {
  name = "thc-development";
  tag = "nix";
  contents = [ toolchain.fhsenv ];
  # The same FHS filesystem runs directly under OCI. No nested bubblewrap or
  # second distribution's package manager/toolchain is needed inside the image.
  extraCommands = ''
    mkdir -p home/thc work tmp
    chmod 1777 tmp
    # Match buildFHSEnv's initialization, once, with a read-only runtime root.
    printf "/usr/lib64\n" > etc/ld.so.conf
    ${proot}/bin/proot -r "$PWD" -b /nix /bin/ldconfig -X
    # Nix's loader looks here; bubblewrap normally supplies these two links.
    mkdir -p .${glibc}/etc
    ln -s /etc/ld.so.conf .${glibc}/etc/ld.so.conf
    ln -s /etc/ld.so.cache .${glibc}/etc/ld.so.cache
  '';
  fakeRootCommands = ''
    chown 1000:1000 home/thc work
  '';
  config = {
    User = "1000:1000";
    WorkingDir = "/work";
    Env = [
      "HOME=/home/thc"
      "JAVA_HOME=${jam}"
      "GRAALVM_HOME=${jam}"
      "PATH=${jam}/bin:/usr/bin:/bin"
      "_JAVA_SR_SIGNUM=64"
      "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
      "THC_CACHE_HOME=/home/thc/.cache/thc"
    ];
    Cmd = [ "/bin/bash" "--login" ];
  };
}
