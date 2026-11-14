plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 版本走，单独声明插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
