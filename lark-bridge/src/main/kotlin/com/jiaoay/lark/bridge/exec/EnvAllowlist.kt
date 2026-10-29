package com.jiaoay.lark.bridge.exec

/**
 * agent 子进程环境变量白名单（env 过滤，behavior-contract.md §4.9）。
 *
 * 🔴 语义：**过滤**而非追加——宿主进程环境只有命中白名单的键才透传给子进程（沙箱化，防凭据/敏感变量泄漏）。
 * `BASE_ENV_KEYS` 11 键含 http_proxy/https_proxy/no_proxy 三个小写变体（Linux 惯例，curl/git/python 优先读小写）——
 *    逐字等于历史实现（零回归锚点），**不是 8 键**。
 * 🔴 `WINDOWS_REQUIRED_ENV_KEYS` 平台必需键**不可被用户配置移除**：Windows 无 HOME，claude/node 依赖这些键定位配置目录。
 */
object EnvAllowlist {

    /** 基础白名单（Unix 语义锚点，11 键，顺序即字面值）。 */
    val BASE_ENV_KEYS: Set<String> = linkedSetOf(
        "PATH", "HOME", "LANG", "LC_ALL", "TZ",
        "http_proxy", "https_proxy", "no_proxy",
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
    )

    /**
     * Windows 系统必需键（封闭列表，11 键，不含凭据类变量）：
     * 缺 SystemRoot/COMSPEC 时 node 及多数 Win32 程序初始化异常甚至崩溃；缺 USERPROFILE/APPDATA/LOCALAPPDATA 时
     * claude 找不到凭证与配置目录（Windows 无 HOME）；PATHEXT/TEMP/TMP 为 .cmd 解析与临时文件所必需。
     */
    val WINDOWS_REQUIRED_ENV_KEYS: Set<String> = linkedSetOf(
        "SystemRoot", "SystemDrive", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
        "APPDATA", "LOCALAPPDATA", "COMSPEC", "PATHEXT", "TEMP", "TMP",
    )

    /**
     * 平台化基础白名单：
     * Unix 与历史 BASE_ENV_KEYS **逐键相等**；Windows 追加封闭系统键（共 22 键）。
     */
    fun effectiveBaseKeys(isWindows: Boolean): Set<String> =
        if (isWindows) BASE_ENV_KEYS + WINDOWS_REQUIRED_ENV_KEYS else BASE_ENV_KEYS

    /**
     * 解析最终白名单：用户配置（`workspace.envAllowlist`，默认 = BASE 11 键）∪ 平台必需键。
     * Windows 必需键**强制并入**（不可被用户配置移除）；Unix 无强制键，全凭用户配置。
     */
    fun resolveAllowed(userAllowlist: Collection<String>, isWindows: Boolean): Set<String> =
        if (isWindows) userAllowlist.toSet() + WINDOWS_REQUIRED_ENV_KEYS else userAllowlist.toSet()

    /**
     * 就地过滤子进程环境（ProcessBuilder.environment() 是可变 Map）：只保留命中 [allowed] 的键。
     * 由 [ProgramLauncher] 在 `builder.start()` **之前**调用（Process API 无启动后 env 定制）。
     *
     * 🔴 Windows 上按**大小写不敏感**匹配——Windows 环境块保留原始拼写
     *    （系统存的是 `Path`/`SystemRoot`），而白名单字面是 `PATH`；旧 `keys.retainAll(allowed)`
     *    是普通 Set 的 case-sensitive contains ⇒ `Path` 命不中 `PATH` 被剥掉 ⇒ 子进程丢 PATH。
     *    （JDK 的 ProcessEnvironment 在 Windows 是 case-insensitive map，但那只救 map 自身的
     *    put/get，救不了 retainAll 里的 `allowed.contains(key)`——后者是普通 LinkedHashSet。）
     */
    fun retainAllowed(env: MutableMap<String, String>, allowed: Set<String>, isWindows: Boolean = isWindows()) {
        if (!isWindows) {
            env.keys.retainAll(allowed)
            return
        }
        val toRemove = env.keys.filter { key -> allowed.none { it.equals(key, ignoreCase = true) } }
        env.keys.removeAll(toRemove.toSet())
    }

    /** 当前平台是否 Windows（os.name 探测）。 */
    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")
}
