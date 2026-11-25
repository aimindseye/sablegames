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

1. Raise `versionCode` and `versionName` in `games/build.gradle.kts`, merge to `main` and pull it.
2. Run `bash tools/build-release.sh /path/to/key.jks KEY_ALIAS` and type the keystore password. With clean sources, it runs the unit tests, builds every app's release APK, signs each with `apksigner`, checks each one's package name, that it asks for no permissions and that all are signed with the registered key (`tools/check-apks.sh --signed`), then writes the APKs, `SHA256SUMS.txt` and `SOURCE_COMMIT.txt` to `~/Downloads/sablegames-release/<version>/` (`SABLEGAMES_RELEASE_DIR` overrides it).
3. Install one APK on a phone and play it.
4. Publish: the script prints the `gh release create` command, which tags `v<version>` and uploads the APKs and `SHA256SUMS.txt`.

## Package names

The 16 names to register in the Android Developer Console, one per app:

```
io.github.aimindseye.sablegames.console
io.github.aimindseye.sablegames.cards
io.github.aimindseye.sablegames.snakesladders
io.github.aimindseye.sablegames.ludo
io.github.aimindseye.sablegames.scaffold
io.github.aimindseye.sablegames.melons
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

Registered so far (2026-11-13): console, cards, snakesladders, ludo, sudoku, minesweeper, game2048, bricks, snake and blocks. The console stopped after ten that day; reversi, solitaire, mahjong and words are still to register. Until then those four still install over USB with `adb install`.

Planned, not built yet: `io.github.aimindseye.sablegames.installer`.
