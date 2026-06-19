plugins {
    alias(libs.plugins.kotlin.multiplatform)
}


kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    js {
        browser()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(ktorLibs.client.core)
            implementation(project(":core"))
        }

    }
}
