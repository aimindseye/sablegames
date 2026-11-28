#!/usr/bin/env bash
# Checks each release APK under DIR (games/build/outputs/apk): one per app, with the app's
# io.github.aimindseye.sablegames.* package name, and no Android permissions requested (the installer
# alone may ask for INTERNET and REQUEST_INSTALL_PACKAGES, to download and install the games, and
# Tartarus Breach for VIBRATE).
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

# Each flavor's package is io.github.aimindseye.sablegames.<flavor>. Plain arrays only: macOS ships
# bash 3.2, which has no associative arrays and no mapfile.
flavors=(console sudoku minesweeper game2048 bricks snake blocks reversi solitaire mahjong words cards snakesladders ludo scaffold melons gravelgears tartarus installer)
apksigner="$(dirname "$aapt2")/apksigner"
certs=()
fail=0
for flavor in "${flavors[@]}"; do
    apks=()
    for apk in "$dir/$flavor/release/"*.apk; do [[ -f "$apk" ]] && apks+=("$apk"); done
    if [[ "${#apks[@]}" != 1 ]]; then
        echo "FAIL $flavor: expected one release APK, found ${#apks[@]}"; fail=1; continue
    fi
    badging="$("$aapt2" dump badging "${apks[0]}")"
    package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
    want="io.github.aimindseye.sablegames.$flavor"
    if [[ "$package" != "$want" ]]; then
        echo "FAIL $flavor: package $package, expected $want"; fail=1
    fi
    # AndroidX adds <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, the app's own signature
    # permission for its private receivers; it grants nothing. Any other permission is a failure.
    others="$(grep "^uses-permission" <<< "$badging" | grep -v "name='$want.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'" || true)"
    if [[ "$flavor" == tartarus ]]; then
        others="$(grep -v -e "name='android.permission.VIBRATE'" <<< "$others" || true)"
    fi
    if [[ "$flavor" == installer ]]; then
        others="$(grep -v -e "name='android.permission.INTERNET'" -e "name='android.permission.REQUEST_INSTALL_PACKAGES'" <<< "$others" || true)"
    fi
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
    distinct="$(printf '%s\n' "${certs[@]}" | sort -u)"
    count="$(printf '%s\n' "$distinct" | wc -l | tr -d ' ')"
    if [[ "$count" != 1 ]]; then echo "FAIL the APKs are signed with $count different keys"; fail=1
    elif [[ "$distinct" != "$expected_cert" ]]; then
        echo "FAIL the APKs are signed with $distinct, not the registered certificate $expected_cert"; fail=1
    else echo "PASS every APK signed with the registered certificate $distinct"; fi
fi
exit "$fail"
