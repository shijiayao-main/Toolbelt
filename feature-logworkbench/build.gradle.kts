plugins {
    id("easydevtoolbox.kmp.feature")
}

// 日志工作台 feature（P4-C2 openspec log-workbench）：三栏分析页 + 研判 agent 会话。
// convention 已预置 :core/:ui/datastore/icons 依赖；本模块个性面——koog 三件（agent 会话，
// Kotlin jvmTarget 25 宿主侧）经 :core 传递可见性消费（SqliteSessionStore/KoogToolLoop 均在 :core），
// 无需额外声明（feature-ai 同款先例：koog 依赖只在直接构造 executor 的模块声明）。
// 本模块不直接构造 LLM client——executor/model 经 :core LlmClientFactory 门面取用，
// 故无 koog 依赖声明。

// WB-01（重设计批 1）：VM 刷新行为测试构造 WorkbenchViewModel 触 Database.database()
// 真单例（user.home 落数据根）——UserHomeIsolation 隔离（feature-ai 同款先例）。
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
}
