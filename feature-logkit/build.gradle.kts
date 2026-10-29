plugins {
    id("easydevtoolbox.kmp.feature")
}

// 日志分析域（xlog 解密 / tag 过滤 / 文件分割）：依赖 :log-tools 的解码/过滤/分割管线。

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":log-tools"))
        }
    }
}

// 审计批 2026-09-27 配方：跨模块测试基建共享件（UserHomeIsolation）——TL-04 首建 jvmTest
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
}
