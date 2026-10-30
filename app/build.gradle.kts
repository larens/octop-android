import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 签名信息从 keystore.properties（不入 git）或环境变量读取。
// 两者都没有时，release 构建回退到 debug 签名，保证 clone 后开箱可构建。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String? =
    keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

// 变量名不能叫 keyAlias / keyPassword —— 在 signingConfigs {} 里会被 SigningConfig
// 自身的同名属性遮蔽，赋值等于自赋值，Gradle 会报 missing required property。
val signingStoreFile = secret("KEYSTORE_FILE")
val signingStorePassword = secret("KEYSTORE_PASSWORD")
val signingKeyAlias = secret("KEY_ALIAS")
val signingKeyPassword = secret("KEY_PASSWORD")
val hasSigningConfig = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
).all { !it.isNullOrBlank() } && rootProject.file(signingStoreFile!!).exists()

android {
    namespace = "com.octopmobile.app"
    compileSdk = 36

    defaultConfig {
        // 原生版与 v1 的 WebView 壳（com.octopmobile.app）分开包名：
        // 两者签名不同，混装必先卸载；分开可避免用户误装覆盖。
        applicationId = "com.octopmobile.native"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resourceConfigurations += listOf("en", "zh")
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasSigningConfig) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }

        // 测量真实帧耗时用：debug 变体是 debuggable 的，ART 会拒绝 AOT，
        // 测出来的数据不能代表用户环境；perf 既保留 minify=false 又不 debuggable。
        create("perf") {
            initWith(getByName("debug"))
            isDebuggable = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
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
        viewBinding = true
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // lifecycle 2.8.x 自带的 lint 检查基于新版 Kotlin 分析 API，与 AGP 8.7.3 内嵌的 lint
        // 不兼容，会在 lintVitalRelease 阶段抛 IncompatibleClassChangeError 直接让 release
        // 构建失败。这是工具链问题、不是代码问题，只关掉这一个检查，其余检查照旧。
        disable += listOf(
            "NullSafeMutableLiveData",
            // Compose lint bundled by the current BOM expects a newer Kotlin analysis API than AGP 8.7.3.
            "FrequentlyChangingValue",
            "RememberInComposition",
            "AutoboxingStateCreation",
        )
    }
}

// Room schema 导出目录提交进仓库，供迁移测试使用。
// 禁止 fallbackToDestructiveMigration（见 docs/spec.md §8.4）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
    // perf 变体的配置名没有生成类型安全访问器（自定义 buildType），用字符串形式。
    add("perfImplementation", libs.compose.ui.tooling)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 手机数据桥：后台可靠上传与系统健康数据聚合。
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.health.connect)

    // 网络与序列化
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    // 图片与凭据
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.security.crypto)

    // Markdown 正文渲染
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
