#!/usr/bin/env bash
# Build, sign and package a Sable Games release on the owner's computer (docs/RELEASING.md).
# CI never gets the key: it builds unsigned APKs only.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f keystore.properties && -z "${SABLEGAMES_KEYSTORE:-}" ]]; then
    echo "ERROR: configure keystore.properties or the SABLEGAMES_* signing variables (docs/RELEASING.md)." >&2
    exit 2
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: commit tracked source changes before packaging a release." >&2
    exit 2
fi

version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' games/build.gradle.kts | head -n1)"
[[ -n "$version" ]] || { echo "ERROR: no versionName in games/build.gradle.kts" >&2; exit 2; }

./gradlew --no-daemon clean assembleRelease \
    :design:testDebugUnitTest :games:testSudokuDebugUnitTest :games:testConsoleDebugUnitTest
bash tools/check-apks.sh --signed games/build/outputs/apk

out="${SABLEGAMES_RELEASE_DIR:-$HOME/Downloads/sablegames-release/$version}"
mkdir -p "$out"
for apk in games/build/outputs/apk/*/release/*-release.apk; do
    flavor="$(basename "$(dirname "$(dirname "$apk")")")"
    cp "$apk" "$out/sable-$flavor-$version.apk"
done
git rev-parse HEAD > "$out/SOURCE_COMMIT.txt"
(cd "$out" && shasum -a 256 ./*.apk > SHA256SUMS.txt)
printf 'Signed APKs and SHA256SUMS.txt: %s\nSource commit: %s\n' "$out" "$(git rev-parse HEAD)"
echo "Publish with: gh release create v$version \"$out\"/*.apk \"$out\"/SHA256SUMS.txt --title \"Sable Games $version\" --generate-notes"
