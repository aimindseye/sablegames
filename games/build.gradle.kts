plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
        // Sable Games Console: games written in Lua, added from a folder without rebuilding the app.
        create("console") {
            dimension = "game"
            applicationId = "io.github.aimindseye.sablegames.console"
            resValue("string", "app_name", "Sable Games Console")
            buildConfigField("String", "SABLE_GAME", "\"console\"")
        }
    }

    // Release signing comes from the environment (CI secrets, docs/RELEASING.md). Without it the
    // release APKs are built unsigned, as SableOS takes them (its image build signs them).
    val releaseKeystore = System.getenv("SABLEGAMES_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SABLEGAMES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SABLEGAMES_KEY_ALIAS")
                keyPassword = System.getenv("SABLEGAMES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Release variants are shrunk: the debug APKs are about 29 MB each.
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
