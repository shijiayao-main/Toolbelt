// build-logic included build：集中维护本仓库的 convention plugin。
// 设计要点（对齐 docs/architecture.md 约定）：
// - plugin 类仅做编译期声明（compileOnly），运行期插件由各模块经根 build.gradle.kts 的
//   `apply false` 声明进主构建 classpath，避免 KGP/Compose 插件类被双加载；
// - 共享主构建的 version catalog（../gradle/libs.versions.toml）。
