plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release APKs are always unsigned here; tools/build-release.sh signs them with apksigner on the
// owner's computer and asks for the password, so no password is ever stored (docs/RELEASING.md).
android {
    namespace = "org.sableos.games"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "game"
    productFlavors {
        create("sudoku") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.sudoku"
            resValue("string", "app_name", "Sable Sudoku")
            buildConfigField("String", "SABLE_GAME", "\"sudoku\"")
        }
        create("minesweeper") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.minesweeper"
            resValue("string", "app_name", "Sable Minesweeper")
            buildConfigField("String", "SABLE_GAME", "\"minesweeper\"")
        }
        create("game2048") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.game2048"
            resValue("string", "app_name", "Sable 2048")
            buildConfigField("String", "SABLE_GAME", "\"2048\"")
        }
        create("bricks") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.bricks"
            resValue("string", "app_name", "Sable Bricks")
            buildConfigField("String", "SABLE_GAME", "\"bricks\"")
        }
        create("snake") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.snake"
            resValue("string", "app_name", "Sable Snake")
            buildConfigField("String", "SABLE_GAME", "\"snake\"")
        }
        create("blocks") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.blocks"
            resValue("string", "app_name", "Sable Blocks")
            buildConfigField("String", "SABLE_GAME", "\"blocks\"")
        }
        create("reversi") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.reversi"
            resValue("string", "app_name", "Sable Reversi")
            buildConfigField("String", "SABLE_GAME", "\"reversi\"")
        }
        create("solitaire") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.solitaire"
            resValue("string", "app_name", "Sable Solitaire")
            buildConfigField("String", "SABLE_GAME", "\"solitaire\"")
        }
        create("mahjong") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.mahjong"
            resValue("string", "app_name", "Sable Mahjong")
            buildConfigField("String", "SABLE_GAME", "\"mahjong\"")
        }
        create("words") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.words"
            resValue("string", "app_name", "Sable Words")
            buildConfigField("String", "SABLE_GAME", "\"words\"")
        }
        // Sable Card Games: ten Indian card games in one app, with a game menu.
        create("cards") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.cards"
            resValue("string", "app_name", "Sable Card Games")
            buildConfigField("String", "SABLE_GAME", "\"cards\"")
        }
        create("snakesladders") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.snakesladders"
            resValue("string", "app_name", "Sable Snakes & Ladders")
            buildConfigField("String", "SABLE_GAME", "\"snakesladders\"")
        }
        create("ludo") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.ludo"
            resValue("string", "app_name", "Sable Ludo")
            buildConfigField("String", "SABLE_GAME", "\"ludo\"")
        }
        create("scaffold") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.scaffold"
            resValue("string", "app_name", "Sable Scaffold")
            buildConfigField("String", "SABLE_GAME", "\"scaffold\"")
        }
        // Sable Games Console: games written in Lua, added from a folder without rebuilding the app.
        create("console") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.console"
            resValue("string", "app_name", "Sable Games Console")
            buildConfigField("String", "SABLE_GAME", "\"console\"")
        }
    }

    buildTypes {
        // Release variants are shrunk: the debug APKs are about 29 MB each.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":design"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Only the Games Console carries the Lua engine.
    "consoleImplementation"(libs.luaj.jse)

    testImplementation(libs.junit)
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        variantBuilder.enableAndroidTest = false
    }
}
