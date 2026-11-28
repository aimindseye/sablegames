# Releasing Sable Games

Releases are built and signed on the owner's computer, the same way as TenSeven Launcher. CI never gets the key: it builds unsigned APKs only, and those are not published.

## The signing key

Sable Games uses the same key as TenSeven Launcher, whose certificate (SHA-256 `9b2047390e5aa77537b8e8875cdfd3c2c72d0a0715e172a7f77457fc4300f292`) is registered in the Android Developer Console. `tools/check-apks.sh --signed` refuses APKs signed with any other key. Every update of an app must be signed with the same key, and Android's developer verification registers each package name against that key's certificate. Keep the keystore and its passwords in a password manager with an offline backup, and never commit them. A new key makes the apps new apps: phones can't update from the old ones.

No password is ever stored. Gradle builds unsigned release APKs (as CI does), and `tools/build-release.sh` signs each one with `apksigner`, the same way as TenSeven Launcher. It asks for the keystore password once and gives it to `apksigner` only through its own environment while it runs:

```
bash tools/build-release.sh /path/to/key.jks KEY_ALIAS
```

The keystore and alias are required, as arguments or as `SABLEGAMES_KEYSTORE` and `SABLEGAMES_KEY_ALIAS`; the repository names no keystore. The key password is taken to be the keystore password, as for the TenSeven key. The script looks for `apksigner` in the newest build-tools under `ANDROID_HOME` (or `~/Library/Android/sdk` on a Mac).

## Making a release

1. Write the release notes in `docs/releases/v<version>.md`, merge to `main` and pull it. The version is not stored in the sources: there is no number to raise in `games/build.gradle.kts`.
2. Run `bash tools/build-release.sh /path/to/key.jks KEY_ALIAS 1.0.1` and type the keystore password. The version (a leading `v` is fine; `SABLEGAMES_VERSION` works too) is passed to Gradle as `-PsablegamesVersion=1.0.1`, which sets `versionName` and derives `versionCode` as major × 100 + minor × 10 + patch (1.0.0 is 100, 1.0.1 is 101; minor and patch must be 0-9). Plain `./gradlew` builds, local and CI, use the default `sableVersionDefault` in `games/build.gradle.kts` (or `-PsablegamesVersion` / `SABLEGAMES_VERSION` if set); bump that default only when you want everyday builds to move on. With clean sources, it runs the unit tests, builds every app's release APK, signs each with `apksigner`, checks each one's package name, that it asks for no permissions and that all are signed with the registered key (`tools/check-apks.sh --signed`), then writes the APKs, `SHA256SUMS.txt` and `SOURCE_COMMIT.txt` to `~/Downloads/sablegames-release/<version>/` (`SABLEGAMES_RELEASE_DIR` overrides it).
3. Install the APKs on a phone and play them. To refresh the pictures in the docs, run `bash tools/screenshots.sh` with the phone connected over adb: it opens each app in turn, waits for you to set up the shot and press Enter, and saves `docs/images/<game>.png`. For Card Games and the console it then names each game inside (ten card games, eight console games) and saves one shot of each, such as `cards-teen-patti.png`, and at the end it lists any shot you skipped (`--auto` skips the waiting, `bash tools/screenshots.sh melons sudoku` takes only those).
4. Publish: the script prints the `gh release create` command, which tags `v<version>`, uploads the APKs and `SHA256SUMS.txt`, and uses `docs/releases/v<version>.md` as the release notes.

## Package names

The 17 names to register in the Android Developer Console, one per app:

```
io.github.aimindseye.sablegames.console
io.github.aimindseye.sablegames.cards
io.github.aimindseye.sablegames.snakesladders
io.github.aimindseye.sablegames.ludo
io.github.aimindseye.sablegames.scaffold
io.github.aimindseye.sablegames.melons
io.github.aimindseye.sablegames.gravelgears
io.github.aimindseye.sablegames.sudoku
io.github.aimindseye.sablegames.minesweeper
io.github.aimindseye.sablegames.game2048
io.github.aimindseye.sablegames.bricks
io.github.aimindseye.sablegames.snake
io.github.aimindseye.sablegames.blocks
io.github.aimindseye.sablegames.reversi
io.github.aimindseye.sablegames.solitaire
io.github.aimindseye.sablegames.mahjong
io.github.aimindseye.sablegames.words
```

All 17 package names above are registered.

The eighteenth app, Tartarus Breach, needs its own name registered before it is released:

```
io.github.aimindseye.sablegames.tartarus
```

It is a separate flavor; no existing app's package name or signing changes.

The nineteenth app, the Sable Installer, also needs its own name registered before it is released:

```
io.github.aimindseye.sablegames.installer
```

It lists the games, downloads the ones you pick from the latest GitHub release and hands them to Android's installer. It checks each file's SHA-256 from `SHA256SUMS.txt`, its package name and that it is signed with the registered key before installing. It is the only app that asks for `INTERNET`, `REQUEST_INSTALL_PACKAGES` and `ACCESS_NETWORK_STATE` (the last only to tell whether the connection is metered, for its Wi-Fi only option), and `tools/check-apks.sh` allows exactly those three for it. It also updates itself: the last row, "This app", is picked automatically when the release is newer than the installed installer. Tartarus Breach asks for `VIBRATE` (an ordinary permission) for its hit feedback, and the check allows only that for it. The installer finds a release's files by the names `tools/build-release.sh` gives them (`sable-<app>-<version>.apk`, `SHA256SUMS.txt`), so keep those names.
