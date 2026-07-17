plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}


kotlin {
    jvmToolchain(21)

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
            implementation("com.github.LightWhite520:thymos:802bad1b2679c7f392e2d8497712ea8a56acb0c5")
            implementation("com.knuddels:jtokkit:1.1.0")
            implementation(libs.djl.api)
            implementation(libs.djl.pytorch.engine)
            implementation(ktorLibs.client.contentNegotiation)
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.cio)
            implementation(ktorLibs.serialization.kotlinx.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
