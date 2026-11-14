#!/usr/bin/env bash
# Build, sign and package a Sable Games release on the owner's computer (docs/RELEASING.md).
#
#   bash tools/build-release.sh [KEYSTORE [ALIAS]]
#
# KEYSTORE defaults to $SABLEGAMES_KEYSTORE, then ~/tenseven-release-2.jks; ALIAS to
# $SABLEGAMES_KEY_ALIAS, then tenseven. Gradle builds unsigned release APKs, then apksigner signs each
# one, as for TenSeven Launcher. The password is asked once and is never written to disk: it lives
# only in this script's environment while apksigner runs. CI never gets the key.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

keystore="${1:-${SABLEGAMES_KEYSTORE:-$HOME/tenseven-release-2.jks}}"
alias_name="${2:-${SABLEGAMES_KEY_ALIAS:-tenseven}}"
[[ -f "$keystore" ]] || { echo "ERROR: keystore not found: $keystore (pass it as the first argument)" >&2; exit 2; }
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: commit tracked source changes before packaging a release." >&2
    exit 2
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
bt="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n1)"
apksigner="$bt/apksigner"
[[ -x "$apksigner" ]] || { echo "ERROR: apksigner not found under $sdk/build-tools (set ANDROID_HOME)" >&2; exit 2; }

version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' games/build.gradle.kts | head -n1)"
[[ -n "$version" ]] || { echo "ERROR: no versionName in games/build.gradle.kts" >&2; exit 2; }

read -rs -p "Password for $keystore ($alias_name): " SABLEGAMES_KS_PASS
echo
export SABLEGAMES_KS_PASS
trap 'unset SABLEGAMES_KS_PASS' EXIT

# Gradle builds unsigned APKs and never sees the password.
env -u SABLEGAMES_KS_PASS ./gradlew --no-daemon clean assembleRelease \
    :design:testDebugUnitTest :games:testSudokuDebugUnitTest :games:testConsoleDebugUnitTest :games:testCardsDebugUnitTest

out="${SABLEGAMES_RELEASE_DIR:-$HOME/Downloads/sablegames-release/$version}"
mkdir -p "$out"
rm -f "$out"/*.apk
for apk in games/build/outputs/apk/*/release/*-release*.apk; do
    flavor="$(basename "$(dirname "$(dirname "$apk")")")"
    "$apksigner" sign --ks "$keystore" --ks-key-alias "$alias_name" \
        --ks-pass env:SABLEGAMES_KS_PASS --key-pass env:SABLEGAMES_KS_PASS \
        --out "$out/sable-$flavor-$version.apk" "$apk"
    echo "signed $flavor"
done
rm -f "$out"/*.idsig

# check-apks expects the build layout (<flavor>/release/*.apk), so check the signed copies in one.
check="$(mktemp -d)"
trap 'unset SABLEGAMES_KS_PASS; rm -rf "$check"' EXIT
for apk in "$out"/sable-*-"$version".apk; do
    flavor="$(basename "$apk" "-$version.apk")"; flavor="${flavor#sable-}"
    mkdir -p "$check/$flavor/release"
    cp "$apk" "$check/$flavor/release/"
done
ANDROID_HOME="$sdk" bash tools/check-apks.sh --signed "$check"

git rev-parse HEAD > "$out/SOURCE_COMMIT.txt"
if command -v shasum >/dev/null 2>&1; then sum=(shasum -a 256); else sum=(sha256sum); fi
(cd "$out" && "${sum[@]}" ./*.apk > SHA256SUMS.txt)
printf 'Signed APKs and SHA256SUMS.txt: %s\nSource commit: %s\n' "$out" "$(git rev-parse HEAD)"
echo "Publish with: gh release create v$version \"$out\"/*.apk \"$out\"/SHA256SUMS.txt --title \"Sable Games $version\" --generate-notes"
