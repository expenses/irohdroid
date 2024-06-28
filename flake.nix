{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    android-nixpkgs.url = "github:HPRIOR/android-nixpkgs";
    crane = {
      url = "github:ipetkov/crane";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fenix = {
      url = "github:nix-community/fenix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    iroh-ffi-src = {
      url = "github:n0-computer/iroh-ffi";
      flake = false;
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      android-nixpkgs,
      crane,
      fenix,
      iroh-ffi-src,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        inherit (android-nixpkgs.packages.${system}) ndk-bundle;

        android-abi = "29";

        rust-targets = {
          arm64-v8a = {
            triple = "aarch64-linux-android";
            clang = "aarch64-linux-android${android-abi}-clang";
          };
          armeabi-v7a = {
            triple = "armv7-linux-androideabi";
            # Note: armv7a not armv7
            clang = "armv7a-linux-androideabi${android-abi}-clang";
          };
          x86 = {
            triple = "i686-linux-android";
            clang = "i686-linux-android${android-abi}-clang";
          };
          x86_64 = {
            triple = "x86_64-linux-android";
            clang = "x86_64-linux-android${android-abi}-clang";
          };
        };

        rust-toolchain =
          with fenix.packages.${system};
          combine (
            [
              stable.cargo
              stable.rustc
            ]
            ++ (builtins.attrValues (builtins.mapAttrs (_: target: targets.${target.triple}.stable.toolchain) rust-targets))
            )
          ;

        crane-lib = (crane.mkLib pkgs).overrideToolchain rust-toolchain;

        builds = builtins.mapAttrs (short: target: let
              inherit (target) triple;
              triple-upper = builtins.replaceStrings [ "-" ] [ "_" ] (pkgs.lib.strings.toUpper triple);
              # Flip the flake-utils system around.
              system-dir =
                let
                  split = builtins.filter (x: builtins.typeOf x == "string") (builtins.split "-" system);
                in
                "${builtins.elemAt split 1}-${builtins.elemAt split 0}";
              clang-path = "${ndk-bundle}/toolchains/llvm/prebuilt/${system-dir}/bin/${target.clang}";
            in
            crane-lib.buildPackage {
              src = iroh-ffi-src;
              doCheck = false;
              cargoExtraArgs = "--lib";
              CARGO_BUILD_TARGET = triple;
              "CC_${triple}" = clang-path;
              "CARGO_TARGET_${triple-upper}_LINKER" = clang-path;
            }
        ) rust-targets;
      in
      {

        packages = {
          jniLibs = pkgs.runCommand "jniLibs" { } ''
            mkdir $out
            ${let
              commands = builtins.mapAttrs (
                  short: build: ''
                    mkdir $out/${short}
                    cp ${build}/lib/libiroh.so $out/${short}/libuniffi_iroh.so
                  ''
                ) builds;
            in 
            pkgs.lib.strings.concatStringsSep "\n" (
              builtins.attrValues commands
            )}
          '';
        };
      }
    );
}
