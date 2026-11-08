#!/bin/sh
# Builds the native core for whichever iOS destination Xcode is currently
# building, and copies the static library to a fixed path so the app
# target's library search path never has to change per-architecture.
# Run as an Xcode "Run Script" build phase; PLATFORM_NAME is set by Xcode.
set -eu
cd "$(dirname "$0")"

# Xcode's Run Script phases use a minimal PATH that usually excludes rustup/cargo.
export PATH="$PATH:$HOME/.cargo/bin"

if [ "${PLATFORM_NAME:-}" = "iphonesimulator" ]; then
  target=aarch64-apple-ios-sim
else
  target=aarch64-apple-ios
fi

cargo build --target "$target" --release --locked

out="target/ios-xcode"
mkdir -p "$out"
cp "target/$target/release/liblovense_native.a" "$out/liblovense_native.a"
