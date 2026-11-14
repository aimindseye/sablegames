# Releasing Sable Games

Releases are built and signed on the owner's computer, the same way as TenSeven Launcher. CI never gets the key: it builds unsigned APKs only, and those are not published.

## The signing key

Every update of an app must be signed with the same key, and Android's developer verification registers each package name against that key's certificate. Keep the keystore and its passwords in a password manager with an offline backup, and never commit them. A new key makes the apps new apps: phones can't update from the old ones.

Give the build the key in either of two ways:

- a file `keystore.properties` at the repository root (git-ignored), with absolute paths:

  ```properties
  storeFile=/Users/you/sablegames-release.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

- or the environment variables `SABLEGAMES_KEYSTORE`, `SABLEGAMES_KEYSTORE_PASSWORD`, `SABLEGAMES_KEY_ALIAS` and `SABLEGAMES_KEY_PASSWORD`.

With neither, `assembleRelease` still succeeds and writes `*-release-unsigned.apk`, as in CI.

## Making a release

1. Raise `versionCode` and `versionName` in `games/build.gradle.kts`, merge to `main` and pull it.
2. Run `bash tools/build-release.sh`. With clean sources and the key configured, it runs the unit tests, builds every app's release APK, checks each one's package name, that it asks for no permissions and that all are signed with one key (`tools/check-apks.sh --signed`), then writes the APKs, `SHA256SUMS.txt` and `SOURCE_COMMIT.txt` to `~/Downloads/sablegames-release/<version>/` (`SABLEGAMES_RELEASE_DIR` overrides it).
3. Install one APK on a phone and play it.
4. Publish: the script prints the `gh release create` command, which tags `v<version>` and uploads the APKs and `SHA256SUMS.txt`.

## Package names

The names to register in the Android Developer Console, one per app:

```
io.github.aimindseye.sablegames.console
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

Planned, not built yet: `io.github.aimindseye.sablegames.cards`, `.snakesladders`, `.ludo` and `.installer`.
