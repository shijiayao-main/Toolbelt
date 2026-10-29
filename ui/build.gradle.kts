plugins {
    id("easydevtoolbox.kmp.compose")
}

// 通用组件层：widget/* + 共享 UI 抽象（卡片头 / Section / SwitchRow / 空态 / 确认对话框等）。
// 依赖 :core（theme token + JResult + 导航契约），无 desktop.currentOs（仅宿主需要）。

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            // 批 9b：Material Symbols（字体形态）——全仓图标唯一来源
            // （material-icons-extended 已彻底移除：官方停止维护，全仓迁移至本库）
            implementation(libs.compose.material.symbols)
            // 2026-09-14 用户身份链批：头像加载三件（🔴 全仓唯一依赖落点——
            // coil3/ktor 类型只允许出现在 widget/AvatarImage.kt，消费方零图片库 import）
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.cio)
            // 2026-09-23 液态玻璃批 1：Haze 2.0（毛玻璃/液态玻璃材质）——
            // 🔴 全仓唯一依赖落点：widget/glass/ 内单文件 import，
            // 消费方一律走包装修饰符，不直接引 haze 类型（复刻 coil3 隔离先例）。
            implementation(libs.haze.core)
            implementation(libs.haze.glass)
        }
    }
}
