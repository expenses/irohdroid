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
      url = "github:expenses/iroh-ffi/irohdroid-extensions";
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
        ndk-bundle = pkgs.fetchzip {
          url = "https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r26b-aarch64.zip";
          hash = "sha256-W5NOlirVqba1DDOwIaknL3P9bK088PdfGfvW+nt7r8E";
        };
        android-sdk = pkgs.fetchzip {
          url = "https://github.com/Lzhiyong/termux-ndk/releases/download/android-sdk/android-sdk-aarch64.zip";
          hash = "sha256-9xlcsk+dYr4K8rAXYqpVlGmqf2/8h/rAfUNZqf8+2Bw=";
        };
        build-tools = pkgs.fetchzip {
          url = "https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r26b-aarch64.zip";
          stripRoot = false;
          hash = "sha256-IKrhkxV8YjzHPSx5CyZS5H9WDWBTzA7IEKh8QWloEWs=";
        };

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
                doNotRemoveReferencesToVendorDir = true;
              };
            }
          ) rust-targets
        );

        uniffi = crane-lib.buildPackage {
          src = ./uniffi-bindgen;
          doNotRemoveReferencesToVendorDir = true;
        };

        # The bindings are the same regardless of what build is used, but
        # paramatizing it means that we don't need to build either the aarch64 or x86_64 build redundantly
        # to being either the emulator or device apk.
        kotlin-bindings-for = rust-build:
          pkgs.runCommand "kotlin-bindings"
            {
              nativeBuildInputs = [ rust-toolchain ];
              CARGO_HOME = crane-lib.vendorCargoDeps { cargoLock = "${iroh-ffi-src}/Cargo.lock"; };
            }
            ''
              cd ${iroh-ffi-src}
              ${uniffi}/bin/uniffi-bindgen generate --library ${rust-build}/lib/*.so --language kotlin --out-dir $out
            '';

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
              mkdir -p $out/app/src/main/java
              ln -s ${jniLibs} $out/app/src/main/jniLibs
              ln -s ${kotlin-bindings-for all-builds.arm64-v8a} $out/app/src/main/java/uniffi
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
            gradleBuildFlags = [ "build" "-Dorg.gradle.project.android.aapt2FromMavenOverride=${build-tools}/build-tools/aapt2"];
            postBuild = ''
              mv app/build/outputs/apk $out
            '';
            ANDROID_HOME = "${android-sdk}";
            #overrides = pkgs.callPackage ./nix/patch-aapt2.nix { gradleLock = ./gradle.lock; };
          };
        x86_64-only = {
          inherit (all-builds) x86_64;
        };
        aarch64-only = {
          inherit (all-builds) arm64-v8a;
        };
      in
      {
        devShells.default = pkgs.mkShell {
          ANDROID_HOME = "${android-sdk}";
          nativeBuildInputs = [
            gradle2nix
            pkgs.openjdk
          ];

          shellHook = ''
            rm app/src/main/jniLibs
            ln -s ${jni-libs-for-builds aarch64-only} app/src/main/jniLibs
            rm app/src/main/java/uniffi
            ln -s ${kotlin-bindings-for all-builds.arm64-v8a} app/src/main/java/uniffi
          '';
        };

        packages =
          let
            keystore-password = "android";
          in
          rec {
            inherit uniffi android-sdk build-tools;

            keystore = pkgs.runCommand "keystore.keystore" { } ''
              mkdir $out
              ${pkgs.openjdk}/bin/keytool -genkey -v -keystore $out/keystore.keystore -storepass ${keystore-password} -alias \
              androiddebugkey -keypass ${keystore-password} -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA \
              -keysize 2048 -validity 10000
            '';

            emu-jni-libs = jni-libs-for-builds x86_64-only;
            app-jni-libs = jni-libs-for-builds aarch64-only;
            app = apk-for-rust-builds aarch64-only;
            signed-app = pkgs.runCommand "signed-apk" { } ''
              cp ${app}/release/app-release-unsigned.apk .
              chmod +w app-release-unsigned.apk
              mkdir $out
              ${android-sdk}/build-tools/34.0.0/apksigner sign --ks ${keystore}/keystore.keystore \
              echo ${android-sdk}/build-tools/34.0.0/apksigner sign --ks ${keystore}/keystore.keystore \
              --ks-pass pass:${keystore-password} app-release-unsigned.apk
              mv app-release-unsigned.apk $out/app-release.apk
            '';
            universal-app = apk-for-rust-builds all-builds;
            install-release = pkgs.writeShellScriptBin "install-release" ''
              adb install ${signed-app}/app-release.apk
            '';
            app-bundle =
              (apk-for-rust-builds x86_64-only).overrideAttrs
                (_: {
                  gradleBuildFlags = ["bundleRelease"];
                  postBuild = ''
                    mv app/build/outputs/bundle $out
                  '';
                });
          };
      }
    );
}
