{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    android-nixpkgs.url = "github:HPRIOR/android-nixpkgs";
    gradle2nix-flake.url = "github:expenses/gradle2nix/overrides-fix";
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
      gradle2nix-flake,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        inherit (android-nixpkgs.packages.${system}) ndk-bundle;

        android-abi = "29";

        rust-targets = [
          {
            short = "arm64-v8a";
            triple = "aarch64-linux-android";
            clang = "aarch64-linux-android${android-abi}-clang";
          }
          {
            short = "armeabi-v7a";
            triple = "armv7-linux-androideabi";
            # Note: armv7a not armv7
            clang = "armv7a-linux-androideabi${android-abi}-clang";
          }
          {
            short = "x86";
            triple = "i686-linux-android";
            clang = "i686-linux-android${android-abi}-clang";
          }
          {
            short = "x86_64";
            triple = "x86_64-linux-android";
            clang = "x86_64-linux-android${android-abi}-clang";
          }
        ];

        rust-toolchain =
          with fenix.packages.${system};
          combine (
            [
              stable.cargo
              stable.rustc
            ]
            ++ (builtins.map (target: targets.${target.triple}.stable.toolchain) rust-targets)
          );

        inherit (gradle2nix-flake.packages.${system}) gradle2nix;

        crane-lib = (crane.mkLib pkgs).overrideToolchain rust-toolchain;

        builds = builtins.map (
          target:
          let
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
          target
          // {
            build = crane-lib.buildPackage {
              src = iroh-ffi-src;
              doCheck = false;
              cargoExtraArgs = "--lib";
              CARGO_BUILD_TARGET = triple;
              "CC_${triple}" = clang-path;
              "CARGO_TARGET_${triple-upper}_LINKER" = clang-path;
            };
          }
        ) rust-targets;

        clean-src-filter = (
          name: _type:
          let
            base-name = builtins.baseNameOf name;
          in
          !(pkgs.lib.hasSuffix ".nix" name || pkgs.lib.hasSuffix == ".lock" || base-name == ".gitignore")
        );

        clean-src = pkgs.lib.sources.cleanSourceWith {
          src = ./.;
          filter = clean-src-filter;
        };

        # Merge two gradle.lock files as some deps are missing.
        patched-gradle-lock = pkgs.callPackage ./nix/patch-gradle-lock.nix {
          gradleLock = ./gradle.lock;
          patches = builtins.fromJSON (builtins.readFile ./other-gradle.lock);
        };

        jniLibs =
          let
            commands = builtins.map (build: ''
              mkdir -p $out/${build.short}
              ln -s ${build.build}/lib/libiroh.so $out/${build.short}/libuniffi_iroh.so
            '') builds;
          in
          pkgs.runCommand "jniLibs" { } (pkgs.lib.strings.concatStringsSep "\n" commands);

        src-overlay = pkgs.runCommand "src-overlay" { } ''
          mkdir -p $out/app/src/main
          ln -s ${jniLibs} $out/app/src/main/jniLibs
          mkdir -p $out/app/src/main/java/uniffi
          ln -s ${iroh-ffi-src}/kotlin/iroh $out/app/src/main/java/uniffi/iroh
        '';

        full-src = pkgs.symlinkJoin {
          name = "full-src";
          paths = [
            clean-src
            src-overlay
          ];
        };
      in
      {
        devShells.build = pkgs.mkShell {
          nativeBuildInputs = [ pkgs.openjdk ];

          shellHook = ''
            ln -s -f ${jniLibs} app/src/main/jniLibs
            mkdir app/src/main/java/uniffi
            ln -s -f ${iroh-ffi-src}/kotlin/iroh app/src/main/java/uniffi/iroh
          '';
        };

        devShells.default = pkgs.mkShell {
          nativeBuildInputs = [
            gradle2nix
            pkgs.openjdk
          ];
        };

        packages = {
          inherit
            jniLibs
            patched-gradle-lock
            src-overlay
            full-src
            ;
          app = gradle2nix-flake.builders.${system}.buildGradlePackage {
            lockFile = patched-gradle-lock;
            src = full-src;
            version = "0.1.0";
            gradleBuildFlags = [ "build" ];
            postBuild = ''
              mv app/build/outputs/apk $out
            '';
            ANDROID_HOME = "${
              android-nixpkgs.sdk.${system} (
                sdkPkgs: with sdkPkgs; [
                  cmdline-tools-latest
                  platforms-android-34
                  build-tools-34-0-0
                ]
              )
            }/share/android-sdk";
            overrides = pkgs.callPackage ./nix/patch-aapt2.nix { gradleLock = patched-gradle-lock; };
          };
          repo = gradle2nix-flake.builders.${system}.buildMavenRepo { lockFile = ./gradle.lock; };
        };
      }
    );
}
