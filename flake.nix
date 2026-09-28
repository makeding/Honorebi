{
  description = "Komorebi Android development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          android = pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "latest";
            platformToolsVersion = "37.0.1";
            buildToolsVersions = [ "37.0.0" ];
            platformVersions = [ "37" ];
            includeCmake = true;
            cmakeVersions = [ "3.22.1" ];
            includeNDK = true;
            ndkVersion = "28.2.13676358";
            abiVersions = [ "armeabi-v7a" "arm64-v8a" ];
          };
        in {
          default = pkgs.mkShell {
            packages = with pkgs; [
              android.androidsdk
              cacert
              git
              jdk21
              pkg-config
            ];

            ANDROID_HOME = "${android.androidsdk}/libexec/android-sdk";

            shellHook = ''
              export JAVA_HOME="${pkgs.jdk21}"
              export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
            '';
          };
        });
    };
}
