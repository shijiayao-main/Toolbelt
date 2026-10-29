plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    alias(libs.plugins.gradleupShadow)
    id("easydevtoolbox.jvm.cli")
}

// convention（easydevtoolbox.jvm.cli）已统一：Java 11 + jvmTarget + kotlin-test + shadowJar exclude；
// 以下仅保留模块个性配置。

dependencies {
    // 哈希加密算法第三方库
    // https://www.bouncycastle.org/download/bouncy-castle-java/
    // https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/
    implementation(libs.bouncycastle)

    // 压缩算法第三方库
    // https://github.com/luben/zstd-jni
    implementation(libs.zstd.jni)

    implementation(libs.clikt)
}

tasks.shadowJar {
    manifest { attributes["Main-Class"] = "com.jiaoay.log.MainKt" }
}
