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
      url = "github:n0-computer/iroh-ffi/feat-async-macros";
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
        build-tools = android-nixpkgs.packages.${system}.build-tools-34-0-0;

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

        all-builds = builtins.listToAttrs (
          builtins.map (
            target:
            let
              inherit (target) triple;
              triple-upper = builtins.replaceStrings [ "-" ] [ "_" ] (pkgs.lib.strings.toUpper triple);
              # Flip the flake-utils system around.
              system-dir =
                let
                  split = builtins.split "-" system;
                in
                "${builtins.elemAt split 2}-${builtins.elemAt split 0}";
              clang-path = "${ndk-bundle}/toolchains/llvm/prebuilt/${system-dir}/bin/${target.clang}";
            in
            {
              name = target.short;
              value = crane-lib.buildPackage {
                src = iroh-ffi-src;
                doCheck = false;
                cargoExtraArgs = "--lib";
                CARGO_BUILD_TARGET = triple;
                "CC_${triple}" = clang-path;
                "CARGO_TARGET_${triple-upper}_LINKER" = clang-path;
              };
            }
          ) rust-targets
        );

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

        jni-libs-for-builds =
          builds:
          let
            commands = builtins.attrValues (
              builtins.mapAttrs (name: build: ''
                mkdir -p $out/${name}
                ln -s ${build}/lib/libiroh_ffi.so $out/${name}/libiroh_ffi.so
              '') builds
            );
          in
          pkgs.runCommand "jniLibs" { } (pkgs.lib.strings.concatStringsSep "\n" commands);

        apk-for-rust-builds =
          rust-builds:
          let
            jniLibs = jni-libs-for-builds rust-builds;

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
          gradle2nix-flake.builders.${system}.buildGradlePackage {
            lockFile = ./gradle.lock;
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
            overrides = pkgs.callPackage ./nix/patch-aapt2.nix { gradleLock = ./gradle.lock; };
          };
      in
      {
        devShells.default = pkgs.mkShell {
          nativeBuildInputs = [
            gradle2nix
            pkgs.openjdk
          ];

          shellHook = ''
            rm app/src/main/jniLibs
            ln -s ${jni-libs-for-builds { inherit (all-builds) x86_64; }} app/src/main/jniLibs
            mkdir app/src/main/java/uniffi
            rm app/src/main/java/uniffi/iroh
            ln -s ${iroh-ffi-src}/kotlin/iroh app/src/main/java/uniffi/iroh
          '';
        };

        packages = let
          aarch64-only = { inherit (all-builds) arm64-v8a; };
          keystore-password = "android";
        in rec {
          keystore = pkgs.runCommand "keystore.keystore" { } ''
            mkdir $out
            ${pkgs.openjdk}/bin/keytool -genkey -v -keystore $out/keystore.keystore -storepass ${keystore-password} -alias \
            androiddebugkey -keypass ${keystore-password} -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA \
            -keysize 2048 -validity 10000
          '';

          app-jni-libs = jni-libs-for-builds aarch64-only;
          app = apk-for-rust-builds aarch64-only;
          signed-app = pkgs.runCommand "signed-apk" { } ''
            cp ${app}/release/app-release-unsigned.apk .
            chmod +w app-release-unsigned.apk
            mkdir $out
            ${build-tools}/apksigner sign --ks ${keystore}/keystore.keystore \
            --ks-pass pass:${keystore-password} app-release-unsigned.apk
            mv app-release-unsigned.apk $out/app-release.apk
          '';
          universal-app = apk-for-rust-builds all-builds;
          install-release = pkgs.writeShellScriptBin "install-release" ''
            adb install ${signed-app}/app-release.apk
          '';
        };
      }
    );
}
