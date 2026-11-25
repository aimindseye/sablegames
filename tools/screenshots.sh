#!/usr/bin/env bash
# Takes a screenshot of every Sable Games app from a phone connected over adb, for the docs.
#
#   bash tools/screenshots.sh [--auto] [--out DIR] [--serial SERIAL] [GAME ...]
#
# The apps must already be installed on the phone (a release from tools/build-release.sh, or
# `./gradlew assembleRelease` plus `adb install`). For each game the script starts the app, then:
#   - by default, waits for you to set up the shot on the phone (start a game, make a few moves)
#     and press Enter here, then captures the screen. Type s and Enter to skip a game.
#   - with --auto, waits a few seconds, presses the keys listed for the game below (to get past a
#     menu into play) and captures. Quicker, but the boards are at their start.
# GAME names one or more flavors (sudoku melons ...); with none, every app is captured.
# Screenshots go to docs/images/<name>.png (--out changes the folder), named as the User Guide uses
# them: game2048 is saved as game-2048.png and the rest by flavor. Nothing is uploaded anywhere.
# Plain arrays only: macOS ships bash 3.2, which has no associative arrays.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

auto=no
out="$ROOT/docs/images"
serial=""
games=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --auto) auto=yes ;;
        --out) out="${2:?--out needs a folder}"; shift ;;
        --serial) serial="${2:?--serial needs a device serial}"; shift ;;
        -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "ERROR: unknown option $1" >&2; exit 2 ;;
        *) games+=("$1") ;;
    esac
    shift
done

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found (install Android platform-tools)" >&2; exit 2; }
adb_cmd=(adb)
[[ -n "$serial" ]] && adb_cmd=(adb -s "$serial")
devices="$("${adb_cmd[@]}" devices | sed -n '2,$p' | grep -c 'device$' || true)"
if [[ -z "$serial" && "$devices" != 1 ]]; then
    echo "ERROR: connect exactly one phone (found $devices), or pass --serial" >&2; exit 2
fi

# flavor | file name | keys for --auto (Android key names, sent after the app opens)
table=(
    "console|console|"
    "cards|cards|ENTER"
    "snakesladders|snakesladders|SPACE"
    "ludo|ludo|SPACE"
    "scaffold|scaffold|SPACE"
    "melons|melons|ENTER"
    "sudoku|sudoku|"
    "minesweeper|minesweeper|SPACE"
    "game2048|game-2048|DPAD_LEFT DPAD_UP"
    "bricks|bricks|SPACE"
    "snake|snake|"
    "blocks|blocks|"
    "reversi|reversi|SPACE"
    "solitaire|solitaire|SPACE"
    "mahjong|mahjong|"
    "words|words|"
)

wanted() {
    [[ ${#games[@]} -eq 0 ]] && return 0
    local g
    for g in "${games[@]}"; do [[ "$g" == "$1" ]] && return 0; done
    return 1
}

mkdir -p "$out"
taken=0
for row in "${table[@]}"; do
    IFS='|' read -r flavor name keys <<< "$row"
    wanted "$flavor" || continue
    package="io.github.aimindseye.sablegames.$flavor"
    if ! "${adb_cmd[@]}" shell pm path "$package" >/dev/null 2>&1; then
        echo "skip $flavor: $package is not installed"
        continue
    fi
    "${adb_cmd[@]}" shell am force-stop "$package"
    "${adb_cmd[@]}" shell am start -W -n "$package/org.sableos.games.MainActivity" >/dev/null
    if [[ "$auto" == yes ]]; then
        sleep 3
        for key in $keys; do
            "${adb_cmd[@]}" shell input keyevent "KEYCODE_$key"
            sleep 1
        done
        sleep 2
    else
        read -r -p "$flavor: set up the shot on the phone, then press Enter (s skips) " answer
        if [[ "$answer" == s ]]; then echo "skipped $flavor"; continue; fi
    fi
    "${adb_cmd[@]}" exec-out screencap -p > "$out/$name.png"
    echo "saved $out/$name.png"
    taken=$((taken + 1))
done
echo "$taken screenshot(s) in $out"
