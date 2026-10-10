# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{
  description = "THC compiler toolchain";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/7c8764b7c7b09b34f632464276218ef9090eaa11";
    hide = {
      url = "github:ekmett/hide/4e696d91eaa9c2f3535661273b01d396d654e1e0";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, hide }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
      pin = (builtins.fromJSON (builtins.readFile ./etc/jam-graalvm.json)).platforms.Linux-x86_64;
      jam = pkgs.callPackage ./nix/jam.nix { inherit pin; };
      toolchain = pkgs.callPackage ./nix/toolchain.nix { inherit jam; };
      nativeTools = pkgs.callPackage ./nix/native-tools.nix {};
      jvm = pkgs.callPackage ./nix/jvm.nix { inherit jam toolchain; revision = self.rev or "unversioned"; };
      installed = pkgs.callPackage ./nix/installed.nix { inherit nativeTools jvm jam toolchain; };
      withHide = pkgs.callPackage ./nix/toolchain.nix {
        inherit jam;
        name = "thc-hide-toolchain";
        extraPackages = [ hide.packages.${system}.hide ];
      };
    in {
      packages.${system} = {
        jam-graalvm = jam;
        inherit toolchain;
        native-tools = nativeTools;
        inherit jvm;
        thc = installed;
        default = installed;
        development-image = pkgs.callPackage ./nix/image.nix { inherit toolchain jam; };
        toolchain-with-hide = withHide;
      };
      apps.${system}.toolchain = {
        type = "app";
        program = "${toolchain}/bin/thc-toolchain";
        meta.description = "Pinned THC compiler development environment";
      };
      devShells.${system} = {
        default = toolchain.env;
        with-hide = withHide.env;
      };
    };
}
