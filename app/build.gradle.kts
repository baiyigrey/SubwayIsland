plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.subwayisland"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.subwayisland"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Compose 未压缩时 dex 会到 16MB，R8 之后回到 4MB 以内
            isMinifyEnabled = true
            isShrinkResources = true
            // 用 debug 签名是为了让 CI / 普通用户 clone 下来就能直接装，
            // 正式发布请换成自己的 keystore（见 README「发布」一节）
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Xposed 入口通过 assets/xposed_init 声明，禁止 AGP 压缩 assets
    androidResources {
        noCompress += "xposed_init"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // de.robv.android.xposed:api:82 是 XposedBridge 旧版 API。
    // LSPosed（API 93+）通过 legacy bridge 提供该 API，模块运行时由框架注入，故用 compileOnly。
    compileOnly("de.robv.android.xposed:api:82")

    // 界面：Compose（MIUIX 视觉规范手写在 ui/theme 里，不额外引第三方 UI 库）
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    // 参数构造是纯函数，可以在 JVM 上直接验证 JSON 结构，不必上真机
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // 用来验证「主界面不是白屏」这类只能靠渲染才发现的问题
    testImplementation("org.robolectric:robolectric:4.13")
}
