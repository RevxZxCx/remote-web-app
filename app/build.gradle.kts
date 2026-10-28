import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.revxzxcx.remotewebapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.revxzxcx.remotewebapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 设置页「高级」段的版本号读 BuildConfig.VERSION_NAME / VERSION_CODE（R017）。
        // 不打开这个开关时 AGP 不生成 BuildConfig，编译期直接 Unresolved reference。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric 需要 merged manifest / 资源才能起 Compose（决策 D22）。
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Robolectric 在 API 34+ 上要调 `jdk.internal.access.SharedSecrets` 去摸 FileDescriptor 的
// 原始 int 值（`ApplicationSharedMemory.create`）。JDK 16+ 默认不导出这个包，于是所有
// 渲染测试都在 beforeTest 阶段炸：
//   IllegalAccessException: … cannot access class jdk.internal.access.SharedSecrets
//     (in module java.base) because module java.base does not export jdk.internal.access
// 这是 JDK 的模块封装，不是 Robolectric 的 bug——显式开一条 add-opens 即可。
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // R017：全 App 图标化（顶栏四个文字按钮收成一个溢出菜单）需要图标。
    // 只引 icons-core（约 40 枚内置矢量图标，不是 extended 那几千枚），
    // 与 material3 同源、走同一个 compose-bom，不单独写版本号。
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 扫码配对：只用它的 DecoratedBarcodeView 嵌进 Compose，不用它的 CaptureActivity。
    implementation(libs.zxing.android.embedded)

    // 代码高亮（决策 D13：轻量高亮库，不用桌面端的 Shiki）。
    implementation(libs.highlights)

    // 图片解码按 EXIF 方向摆正（R007d P2-2）。ui/common/ImageDecoding.kt 是文件查看器
    // 与聊天图片附件共用的解码收口点，摆正必须在这个收口点做，不能各调用方各做一遍。
    implementation(libs.androidx.exifinterface)

    // 离线缓存（决策 D04）：工作区 / 会话列表 / 最近消息。Room 用 KSP 生成实现。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    // 真实渲染测试（决策 D22，推翻 D21）。Robolectric 让 createComposeRule() 跑在 JVM 上，
    // 于是 LazyColumn 的 key 唯一性、卡片文案分支、状态标志复位都能被单测钉住，
    // 不再只能靠真机肉眼（R003 / R005 两轮 P1 都是这么漏过去的）。
    // ui-test-manifest 必须走 debugImplementation：它往 debug manifest 里塞
    // androidx.activity.ComponentActivity，供 createComposeRule() 承载。
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
}
