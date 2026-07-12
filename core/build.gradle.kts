plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            implementation(libs.djl.api)
            implementation(libs.djl.pytorch.engine)
            implementation(ktorLibs.client.contentNegotiation)
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.cio)
            implementation(ktorLibs.serialization.kotlinx.json)
            if (System.getProperty("os.name").startsWith("Windows")) {
                runtimeOnly("ai.djl.pytorch:pytorch-native-cpu:${libs.versions.pytorch.native.get()}:win-x86_64")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
