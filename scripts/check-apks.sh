#!/usr/bin/env bash
# Checks each release APK under DIR (games/build/outputs/apk): one per app, with the app's
# io.github.aimindseye.sablegames.* package name, and no Android permissions requested.
set -euo pipefail

dir="${1:?usage: scripts/check-apks.sh APK_DIR}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt2="$(find "$sdk/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | LC_ALL=C sort | tail -n1)"
[[ -x "$aapt2" ]] || { echo "aapt2 not found under $sdk/build-tools"; exit 1; }

declare -A expected=(
    [console]=console [sudoku]=sudoku [minesweeper]=minesweeper [game2048]=game2048
    [bricks]=bricks [snake]=snake [blocks]=blocks [reversi]=reversi
    [solitaire]=solitaire [mahjong]=mahjong [words]=words
)
fail=0
for flavor in "${!expected[@]}"; do
    mapfile -t apks < <(compgen -G "$dir/$flavor/release/*.apk" || true)
    if [[ "${#apks[@]}" != 1 ]]; then
        echo "FAIL $flavor: expected one release APK, found ${#apks[@]}"; fail=1; continue
    fi
    badging="$("$aapt2" dump badging "${apks[0]}")"
    package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
    want="io.github.aimindseye.sablegames.${expected[$flavor]}"
    if [[ "$package" != "$want" ]]; then
        echo "FAIL $flavor: package $package, expected $want"; fail=1
    fi
    # AndroidX adds <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, the app's own signature
    # permission for its private receivers; it grants nothing. Any other permission is a failure.
    others="$(grep "^uses-permission" <<< "$badging" | grep -v "name='$want.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'" || true)"
    if [[ -n "$others" ]]; then
        echo "FAIL $flavor asks for permissions:"; echo "$others"; fail=1
    fi
    [[ "$fail" == 1 ]] || echo "PASS $flavor $package"
done
exit "$fail"
