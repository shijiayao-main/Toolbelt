package easydevtoolbox.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * 功能域模块约定（:feature-*）：在 [KmpComposeConventionPlugin] 基础上预置
 * - 依赖 :core / :ui（feature 基座，禁止 feature 之间互相依赖）；
 * - DataStore（域配置仓库的持久化骨架，经 :core 的 AppDataStore 访问，
 *   feature 禁止自行 PreferenceDataStoreFactory.create）；
 * - Material Symbols（全仓图标唯一来源，蛇形名 + AppSymbolIcon 渲染）。
 */
class KmpFeatureConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("easydevtoolbox.kmp.compose")

        val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        fun lib(alias: String): String = libs.findLibrary(alias).orElseThrow {
            IllegalStateException("version catalog 缺少 library 别名: $alias")
        }.get().toString()

        extensions.configure(KotlinMultiplatformExtension::class.java) {
            sourceSets.getByName("commonMain").dependencies {
                implementation(project(":core"))
                implementation(project(":ui"))
                implementation(lib("androidx-datastore"))
                implementation(lib("androidx-datastore-preferences"))
                // Material Symbols（字体形态）——全仓图标唯一来源
                // （material-icons-extended 已彻底移除：官方停止维护，全仓迁移至本库）
                implementation(lib("compose-material-symbols"))
                implementation(lib("compose-components-resources"))
            }
        }
    }
}
