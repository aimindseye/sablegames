# Releasing Sable Games

A release is a git tag `vX.Y.Z` on `main`. The **Release** workflow builds every app's release APK, signs it with the Sable Games key, and publishes a GitHub release with the APKs and `SHA256SUMS.txt`.

## The signing key (once)

Every update of an app must be signed with the same key, and Android's developer verification registers each package name against it, so make the key once and keep it safe.

1. Make the key on your computer (never in the repository):

   ```
   keytool -genkeypair -v -keystore sablegames-release.jks -alias sablegames \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Keep `sablegames-release.jks` and its passwords in a password manager, with an offline backup. Losing it means the apps can't be updated; a new key makes them new apps.
3. Add four repository secrets (Settings > Secrets and variables > Actions):

   | Secret | Value |
   | --- | --- |
   | `SABLEGAMES_KEYSTORE_BASE64` | `base64 -w0 sablegames-release.jks` |
   | `SABLEGAMES_KEYSTORE_PASSWORD` | The keystore password |
   | `SABLEGAMES_KEY_ALIAS` | `sablegames` |
   | `SABLEGAMES_KEY_PASSWORD` | The key password |

Without these secrets the Release workflow stops before publishing, and the Android workflow builds unsigned APKs only.

## Making a release

1. Raise `versionCode` and `versionName` in `games/build.gradle.kts`.
2. Merge to `main`, then tag: `git tag v0.2.0 && git push origin v0.2.0`.
3. The Release workflow publishes the release. Check that `SHA256SUMS.txt` is there and that one APK installs on a phone.

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
