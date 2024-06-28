{
  gradleLock,
  patches,
  lib,
  writeText,
}:
let
  lock-attr = builtins.fromJSON (builtins.readFile gradleLock);
  patched-lock-attr = lib.recursiveUpdate lock-attr patches;
in
writeText "gradle.lock" (builtins.toJSON patched-lock-attr)
