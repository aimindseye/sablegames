#!/usr/bin/env bash
# Checks each release APK under DIR (games/build/outputs/apk): one per app, with the app's
# io.github.aimindseye.sablegames.* package name, and no Android permissions requested.
# With --signed (tools/build-release.sh), every APK must also be signed with the registered key: the
# owner's TenSeven Launcher key, whose certificate is registered in the Android Developer Console.
# SABLEGAMES_EXPECTED_CERT overrides the expected certificate SHA-256.
set -euo pipefail

signed=no
expected_cert="${SABLEGAMES_EXPECTED_CERT:-9b2047390e5aa77537b8e8875cdfd3c2c72d0a0715e172a7f77457fc4300f292}"
if [[ "${1:-}" == "--signed" ]]; then signed=yes; shift; fi
dir="${1:?usage: tools/check-apks.sh [--signed] APK_DIR}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt2="$(find "$sdk/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | LC_ALL=C sort | tail -n1)"
[[ -x "$aapt2" ]] || { echo "aapt2 not found under $sdk/build-tools"; exit 1; }

declare -A expected=(
    [console]=console [sudoku]=sudoku [minesweeper]=minesweeper [game2048]=game2048
    [bricks]=bricks [snake]=snake [blocks]=blocks [reversi]=reversi
    [solitaire]=solitaire [mahjong]=mahjong [words]=words
    [cards]=cards [snakesladders]=snakesladders [ludo]=ludo
)
apksigner="$(dirname "$aapt2")/apksigner"
certs=()
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
    if [[ "$signed" == yes ]]; then
        cert="$("$apksigner" verify --print-certs "${apks[0]}" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -n1)"
        if [[ -z "$cert" ]]; then echo "FAIL $flavor is not signed"; fail=1; else certs+=("$cert"); fi
    fi
    [[ "$fail" == 1 ]] || echo "PASS $flavor $package"
done
if [[ "$signed" == yes && "$fail" == 0 ]]; then
    mapfile -t distinct < <(printf '%s\n' "${certs[@]}" | sort -u)
    if [[ "${#distinct[@]}" != 1 ]]; then echo "FAIL the APKs are signed with ${#distinct[@]} different keys"; fail=1
    elif [[ "${distinct[0]}" != "$expected_cert" ]]; then
        echo "FAIL the APKs are signed with ${distinct[0]}, not the registered certificate $expected_cert"; fail=1
    else echo "PASS every APK signed with the registered certificate ${distinct[0]}"; fi
fi
exit "$fail"
