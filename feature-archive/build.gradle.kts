plugins {
    id("easydevtoolbox.kmp.feature")
}

// 压缩包解压域（zip / tar / tar.gz / tgz / gz / zip 分卷 / 7z）：
// - 依赖 :log-tools 的 SafeExtract（**纯 JDK 实现**——ZipInputStream / GZIPInputStream +
//   手写 ustar 解析，零第三方库、零系统命令调用，含 zip-slip 防护）。
// - 🔴 刻意**不依赖 :feature-ai**：解压不需要 LLM/agent，本页存在的意义正是让不配置
//   agent 的用户也能解压；且因此它永不被 AI 工具开关隐藏。
// - 批 9 Tier 1（用户拍板推翻「零第三方压缩库」原则）：PKZip 规范分卷（.z01 族，
//   分段标记 + 跨段中央目录）与 7z/LZMA 容器手工实现不现实——commons-compress 只进
//   本模块（SafeExtract 禁止重写块不动，log-tools 维持零依赖）。
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":log-tools"))
            implementation(libs.commons.compress)
            // commons-compress 1.28 不再传递 xz——7z/LZMA 解码运行时必需
            implementation(libs.xz.java)
        }
    }
}
