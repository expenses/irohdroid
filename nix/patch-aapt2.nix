{
  gradleLock,
  stdenv,
  lib,
  runCommand,
  jdk,
  autoPatchelfHook,
}:
let
  inherit (lib)
    filterAttrs
    mapAttrs
    optionalAttrs
    hasPrefix
    hasSuffix
    ;
  lock-attr = builtins.fromJSON (builtins.readFile gradleLock);

  patchJars =
    moduleFilter: artifactFilter: args: f:
    let
      modules = filterAttrs (name: _: moduleFilter name) lock-attr;

      artifacts = filterAttrs (name: _: artifactFilter name);

      patch = src: runCommand src.name args (f src);
    in
    mapAttrs (_: module: mapAttrs (_: _: patch) (artifacts module)) modules;
in
optionalAttrs stdenv.isLinux (
  patchJars (hasPrefix "com.android.tools.build:aapt2:") # moduleFilter
    (hasSuffix "-linux.jar") # artifactFilter
    {
      # args to runCommand
      nativeBuildInputs = [
        jdk
        autoPatchelfHook
      ];
      buildInputs = [ stdenv.cc.cc.lib ];
      dontAutoPatchelf = true;
    }
    (src: ''
      cp ${src} aapt2.jar
      jar xf aapt2.jar aapt2
      chmod +x aapt2
      autoPatchelf aapt2
      jar uf aapt2.jar aapt2
      cp aapt2.jar $out
      echo $out
    '')
)
