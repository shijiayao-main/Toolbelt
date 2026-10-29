plugins {
    id("easydevtoolbox.kmp.feature")
}

// 时间戳转换 feature：最小配方样板（新 feature 照此复制）。
// convention（easydevtoolbox.kmp.feature）已预置 :core/:ui/datastore/icons 依赖，无个性配置。

// TL-12（重设计批 4）：VM 行为测试构造 TimestampViewModel 触 TimestampConfigStore
// 真单例（DataStore 落数据根）——UserHomeIsolation 隔离（feature-mcp/shelltools 同款先例）。
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
}
