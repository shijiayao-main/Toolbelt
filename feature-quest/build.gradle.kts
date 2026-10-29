plugins {
    id("easydevtoolbox.kmp.feature")
}

// 流程域 feature（Quest 域 change 2——openspec add-flow-page）：agent 主动交互流程页。
// 用户开新流程/选历史流程（可续聊）→ 挑能力 → agent 以 AskCard 主动索要信息 → 执行会话。
//
// 域名裁定（plan v3 维护者拍板）：中文文案「流程」+ 代码域名 Quest（避开 Kotlin Flow 冲突）。
// 依赖面：仅 :core（api 传递 Koog/会话基建/ask_user 工具）；🔴 零 feature 互依赖——
// 工作台名集与 prompts 经值同源自持（既有铁律）。无 @Serializable 工具 Args（ask_user 在
// :core），零 serialization 插件需求。

// 审计批 2026-09-27：跨模块测试基建共享件（UserHomeIsolation/KoogFakeExecutor）
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
}
