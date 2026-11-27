#!/usr/bin/env bash
# Takes a screenshot of every Sable Games app from a phone connected over adb, for the docs.
#
#   bash tools/screenshots.sh [--auto] [--out DIR] [--serial SERIAL] [GAME ...]
#
# The apps must already be installed on the phone (a release from tools/build-release.sh, or
# `./gradlew assembleRelease` plus `adb install`). For each game the script starts the app, then:
#   - by default, waits for you to set up the shot on the phone (start a game, make a few moves)
#     and press Enter here, then captures the screen. Type s and Enter to skip a shot. Card Games
#     and the console have games inside them: after the app's own shot, the script names each of
#     them in turn (Teen Patti, Indian Rummy, ... Tilt Racer, ...), you open it on the phone and
#     press Enter, and it saves <app>-<game>.png, such as cards-teen-patti.png. It then lets you add
#     shots of your own (type a short name, empty Enter moves on), and at the end lists every shot
#     that was skipped, so none of the inside games are missed by accident.
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
        -h|--help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
    "gravelgears|gravelgears|ENTER"
    "tartarus|tartarus|ENTER"
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

# The games inside Card Games and the console: file name suffix | name on the phone.
# Keep in step with CARD_GAMES in IndianUi.kt and the folders in games/src/console/assets/console.
cards_inside=(
    "teen-patti|Teen Patti"
    "rummy|Indian Rummy"
    "mendicot|Mendicot"
    "callbreak|Callbreak"
    "judgement|Judgement"
    "2-3-5|2-3-5"
    "bluff|Bluff"
    "donkey|Donkey"
    "gulam-choor|Gulam Choor"
    "filmi-rummy|Filmi Rummy"
)
console_inside=(
    "ball-maze|Ball Maze"
    "motion-2048|Motion 2048"
    "ramp-rush|Ramp Rush"
    "sky-roll|Sky Roll"
    "sokoban-tilt|Sokoban Tilt"
    "tilt-circuit|Tilt Circuit"
    "tilt-maze|Tilt Maze"
    "tilt-racer|Tilt Racer"
)

wanted() {
    [[ ${#games[@]} -eq 0 ]] && return 0
    local g
    for g in "${games[@]}"; do [[ "$g" == "$1" ]] && return 0; done
    return 1
}

# Saves the phone's screen to the file $1 as a PNG. It captures on the phone and pulls the file,
# because `adb exec-out screencap -p > file` can damage the picture: a redirect that rewrites bytes
# (PowerShell, some Windows shells), or text the device prints before the PNG. The saved file's
# first eight bytes are then checked, and a file that is not a PNG is deleted and reported.
remote_shot=/data/local/tmp/sable-shot.png
capture() {
    local file="$1" magic
    if ! "${adb_cmd[@]}" shell screencap -p "$remote_shot" >/dev/null 2>&1 \
        || ! "${adb_cmd[@]}" pull "$remote_shot" "$file" >/dev/null 2>&1; then
        echo "FAILED to capture $file" >&2; rm -f "$file"; return 1
    fi
    "${adb_cmd[@]}" shell rm -f "$remote_shot" >/dev/null 2>&1 || true
    magic="$(head -c 8 "$file" | od -An -tx1 | tr -d ' \n')"
    if [[ "$magic" != 89504e470d0a1a0a ]]; then
        echo "FAILED: $file is not a valid PNG (starts $magic)" >&2; rm -f "$file"; return 1
    fi
    echo "saved $file"
}

mkdir -p "$out"
taken=0
missed=()
for row in "${table[@]}"; do
    IFS='|' read -r flavor name keys <<< "$row"
    wanted "$flavor" || continue
    package="io.github.aimindseye.sablegames.$flavor"
    if ! "${adb_cmd[@]}" shell pm path "$package" >/dev/null 2>&1; then
        echo "skip $flavor: $package is not installed"; missed+=("$name.png (not installed)")
        continue
    fi
    "${adb_cmd[@]}" shell am force-stop "$package"
    # Start the app's launcher activity, whatever it is called: the console's is ConsoleActivity, not
    # MainActivity. A failed start is reported and skipped, never a silent exit.
    activity="$("${adb_cmd[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package" | tr -d '\r' | tail -n 1)"
    if [[ "$activity" != */* ]] || ! "${adb_cmd[@]}" shell am start -W -n "$activity" >/dev/null; then
        echo "skip $flavor: could not start $package"; missed+=("$name.png (did not start)")
        continue
    fi
    if [[ "$auto" == yes ]]; then
        sleep 3
        for key in $keys; do
            "${adb_cmd[@]}" shell input keyevent "KEYCODE_$key"
            sleep 1
        done
        sleep 2
    else
        read -r -p "$flavor: set up the shot on the phone, then press Enter (s skips) " answer
        if [[ "$answer" == s ]]; then echo "skipped $flavor"; missed+=("$name.png"); continue; fi
    fi
    if capture "$out/$name.png"; then taken=$((taken + 1)); else missed+=("$name.png (capture failed)"); continue; fi
    [[ "$auto" == yes ]] && continue
    # The games inside Card Games and the console, one shot each.
    inside=()
    [[ "$flavor" == cards ]] && inside=("${cards_inside[@]}")
    [[ "$flavor" == console ]] && inside=("${console_inside[@]}")
    for game in ${inside[@]+"${inside[@]}"}; do
        IFS='|' read -r suffix label <<< "$game"
        read -r -p "$flavor: open $label on the phone and set up the shot, then press Enter (s skips) " answer
        if [[ "$answer" == s ]]; then echo "skipped $label"; missed+=("$name-$suffix.png"); continue; fi
        if capture "$out/$name-$suffix.png"; then taken=$((taken + 1)); else missed+=("$name-$suffix.png (capture failed)"); fi
    done
    # Any other shots of the same app, named by you.
    while true; do
        read -r -p "$flavor: for another shot of this app type a short name for it (e.g. settings), or Enter for the next app: " extra
        extra="$(printf '%s' "$extra" | tr 'A-Z ' 'a-z-' | tr -cd 'a-z0-9-')"
        [[ -z "$extra" ]] && break
        if capture "$out/$name-$extra.png"; then taken=$((taken + 1)); else missed+=("$name-$extra.png (capture failed)"); fi
    done
done
echo "$taken screenshot(s) in $out"
if [[ ${#missed[@]} -gt 0 ]]; then
    echo "skipped, still to take (run again with the app's name to retake):"
    printf '  %s\n' "${missed[@]}"
fi
