package com.jiaoay.easy_dev_toolbox.page.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 查询解析器单测：全算子矩阵 / 裸串回退矩阵 / 混写边界 / 解析失败显式化 /
 * 方言混淆防线（裸串子串、查询正则、级别档三套语义边界钉死）/ 级别 chips 合成规则 /
 * 旧构造形态零回归（统一模型对兼容构造逐字等价）。
 */
class LogcatQueryParserTest {

    // ── 行夹具（threadtime / brief / 不可解析三形态） ──────────────────────

    private val camI = "10-11 09:00:00.000  100  200 I Camera: frame"
    private val camE = "10-11 09:00:00.001  100  200 E Camera: fatal"
    private val demoD = "10-11 09:00:00.002  100  201 D DemoTag: noise"
    private val briefE = "E/CamTag( 300): crash"
    private val unparseable = "--------- beginning of main"

    /** 全行含 camera 但 tag 不含（tag 作用域边界钉子） */
    private val demoMentionsCamera = "10-11 09:00:00.003  100  202 I DemoTag: camera frame"

    private fun matches(query: String, chip: LogcatLevel? = null, line: String): Boolean =
        when (val parsed = LogcatQueryParser.parse(query, chip)) {
            is LogcatQueryParseResult.Ok -> LogcatLineMatcher(parsed.spec).matches(line)
            is LogcatQueryParseResult.Err -> error("解析意外失败（$query）：${parsed.reason}")
        }

    private fun errReason(query: String, chip: LogcatLevel? = null): String =
        when (val parsed = LogcatQueryParser.parse(query, chip)) {
            is LogcatQueryParseResult.Ok -> error("解析意外成功（$query）")
            is LogcatQueryParseResult.Err -> parsed.reason
        }

    // ── 裸串回退矩阵（旧语义零回归） ───────────────────────────────────────

    @Test
    fun `bare strings keep legacy semantics verbatim`() {
        assertTrue(matches("", null, unparseable), "空串 = 无过滤，不可解析行恒命中")
        assertTrue(matches("", null, camI), "空串 = 无过滤，恒命中")
        assertTrue(matches("   ", null, camI), "纯空白串 = 无过滤")
        assertTrue(matches("camera", null, camI), "裸词 tag 包含（忽略大小写）")
        assertTrue(matches("CAMERA", null, camI), "包含词大小写不敏感")
        assertTrue(matches("cam", null, camE), "子串语义（非全词匹配）")
        assertTrue(matches("cam, demo", null, demoD), "逗号分隔多词 contains-any")
        assertTrue(matches("cam，demo", null, demoD), "中文逗号分隔同口径")
        assertTrue(matches("cam\tdemo", null, demoD), "制表符分隔同口径")
        assertFalse(matches("camera", null, demoD), "无命中词的行不命中")
        assertFalse(matches("camera", null, unparseable), "有过滤时不可解析行不命中（宁拒不猜）")
        assertFalse(matches("", LogcatLevel.WARN, unparseable), "仅级别档时不可解析行同样不命中")
        assertFalse(matches("camera", LogcatLevel.WARN, camI), "裸词 + 级别档按 AND 合成")
        assertTrue(matches("camera", LogcatLevel.WARN, camE), "tag 命中且级别达档即命中")
        assertTrue(matches("", LogcatLevel.WARN, briefE), "brief 形态同口径命中级别档")
        assertTrue(matches("cam", null, briefE), "brief 形态同口径命中 tag")
    }

    @Test
    fun `bare detection boundary pins the syntax layer switch`() {
        assertTrue(LogcatQueryParser.isBareLegacyQuery(""), "空串走旧语义")
        assertTrue(LogcatQueryParser.isBareLegacyQuery("camera"), "无算子裸串走旧语义")
        assertTrue(LogcatQueryParser.isBareLegacyQuery("cam, demo"), "逗号分隔仍是裸串")
        assertTrue(LogcatQueryParser.isBareLegacyQuery("a-b"), "词中连字符不触发查询语法")
        assertTrue(LogcatQueryParser.isBareLegacyQuery("com.foo"), "词中点号不触发查询语法")
        assertFalse(LogcatQueryParser.isBareLegacyQuery("-x"), "排除前缀触发查询语法")
        assertFalse(LogcatQueryParser.isBareLegacyQuery("tag:x"), "算子前缀触发查询语法")
        assertFalse(LogcatQueryParser.isBareLegacyQuery("a & b"), "AND 触发查询语法")
        assertFalse(LogcatQueryParser.isBareLegacyQuery("a | b"), "OR 触发查询语法")
        assertFalse(LogcatQueryParser.isBareLegacyQuery("(x)"), "括号触发查询语法")
        assertFalse(LogcatQueryParser.isBareLegacyQuery("~/x/"), "正则修饰符形态触发查询语法")
    }

    @Test
    fun `legacy constructor shape compiles and matches legacy semantics`() {
        // 旧构造形态零改证明：既有夹具的位置参数形态照常编译，行为与旧语义逐字一致
        val unrestricted = LogcatLineMatcher(LogcatFilterSpec("", null))
        assertTrue(unrestricted.matches(camI))
        assertTrue(unrestricted.matches(unparseable), "无过滤恒命中（含不可解析行）")

        val tagOnly = LogcatLineMatcher(LogcatFilterSpec("activitymanager", null))
        assertTrue(
            tagOnly.matches("10-11 12:00:00.001  100  200 I ActivityManager: Start proc"),
        )
        assertFalse(tagOnly.matches(camI))
        assertFalse(tagOnly.matches(unparseable), "tag 过滤挡下不可解析行")

        val levelOnly = LogcatLineMatcher(LogcatFilterSpec("", LogcatLevel.WARN))
        assertFalse(levelOnly.matches(camI))
        assertTrue(levelOnly.matches(camE))
        assertTrue(levelOnly.matches(briefE), "brief 形态同口径")

        val combined = LogcatLineMatcher(LogcatFilterSpec("camera", LogcatLevel.WARN))
        assertFalse(combined.matches(camI), "tag 命中但级别不足不命中")
        assertTrue(combined.matches(camE))
        assertFalse(combined.matches(unparseable))

        // 默认参数形态（单参/零参调用）
        assertTrue(LogcatLineMatcher(LogcatFilterSpec("demo")).matches(demoD))
        assertTrue(LogcatLineMatcher(LogcatFilterSpec()).matches(camI))
    }

    @Test
    fun `parse result equals legacy constructor for every legacy input over line corpus`() {
        val corpus = listOf(camI, camE, demoD, briefE, unparseable, demoMentionsCamera)
        val legacyInputs = listOf(
            "" to null,
            "camera" to null,
            "cam, demo" to null,
            "camera" to LogcatLevel.WARN,
            "" to LogcatLevel.WARN,
            "cam demo" to LogcatLevel.ERROR,
        )
        for ((tagQuery, minLevel) in legacyInputs) {
            val viaConstructor = LogcatLineMatcher(LogcatFilterSpec(tagQuery, minLevel))
            for (line in corpus) {
                assertEquals(
                    viaConstructor.matches(line),
                    matches(tagQuery, minLevel, line),
                    "裸串回退与旧构造须逐字等价（$tagQuery / $minLevel / $line）",
                )
            }
        }
    }

    // ── 全算子矩阵 ────────────────────────────────────────────────────────

    @Test
    fun `tag and level operators match as documented`() {
        assertTrue(matches("tag:camera", null, camI), "tag: 包含语义")
        assertTrue(matches("TAG:camera", null, camI), "key 大小写不敏感")
        assertTrue(matches("tag:CAMERA", null, camI), "包含词大小写不敏感")
        assertFalse(matches("tag:camera", null, demoD))
        assertTrue(matches("tag:cam&level:E", null, camE), "无空格连写同文法")
        assertTrue(matches("level:E", null, camE), "level: = 严重度 >= 档")
        assertTrue(matches("level:i", null, camI), "档位字母大小写不敏感")
        assertTrue(matches("level:error", null, camE), "档位全名同纳")
        assertTrue(matches("level:verbose", null, camI), "最低档全行命中")
        assertFalse(matches("level:E", null, camI))
        assertTrue(matches("level:E", null, briefE), "brief 形态同口径")
        assertFalse(matches("level:E", null, unparseable))
    }

    @Test
    fun `boolean operators honor documented precedence`() {
        // | 优先级低于 &：a | b & level:E = a | (b & E)
        assertTrue(matches("tag:demo | tag:camera & level:E", null, demoD), "左支命中即命中")
        assertTrue(matches("tag:demo | tag:camera & level:E", null, camE), "右支 tag+级别双满足")
        assertFalse(matches("tag:demo | tag:camera & level:E", null, camI), "右支级别不足")
        // 括号强制分组
        assertFalse(matches("(tag:demo | tag:camera) & level:E", null, demoD), "括号把 OR 整体并入 AND")
        assertTrue(matches("(tag:demo | tag:camera) & level:E", null, camE))
        // 相邻并列 = 隐式 AND
        assertTrue(matches("tag:camera level:E", null, camE))
        assertFalse(matches("tag:camera level:E", null, camI))
        // & 与隐式并列混用等价
        assertTrue(matches("tag:camera & level:E | tag:demo", null, demoD), "AND 先于 OR 合成")
        assertFalse(matches("tag:camera & level:E | tag:demo", null, camI))
    }

    @Test
    fun `exclusion prefix negates terms and groups`() {
        assertTrue(matches("-tag:camera", null, demoD), "非 camera 的行命中")
        assertFalse(matches("-tag:camera", null, camI))
        assertFalse(matches("-tag:camera", null, unparseable), "排除项含原子，不可解析行拒收")
        assertTrue(matches("-level:E", null, camI), "级别不足 E 的行命中")
        assertFalse(matches("-level:E", null, camE))
        assertTrue(matches("--tag:camera", null, camI), "双重否定回到肯定")
        assertFalse(matches("-(tag:camera | tag:demo)", null, camI), "括号组整体排除")
        assertFalse(matches("-(tag:camera | tag:demo)", null, demoD))
        assertTrue(matches("tag:camera & -level:E", null, camI), "AND 内嵌排除")
        assertFalse(matches("tag:camera & -level:E", null, camE))
    }

    @Test
    fun `regex modifier applies to tag with substring semantics`() {
        assertTrue(matches("tag:~/^Cam/", null, camI), "tag 正则子串命中（锚定行首）")
        assertFalse(matches("tag:~/^cam/", null, camI), "tag 正则默认大小写敏感")
        assertTrue(matches("tag:~/(?i)^cam/", null, camI), "内联忽略大小写标志同纳")
        assertFalse(matches("tag:~/^Cam/", null, demoD))
        assertFalse(matches("tag:~/frame/", null, demoMentionsCamera), "正则只看 tag，不看消息体")
        assertTrue(matches("~/^Cam/", null, camI), "无 key 正则简写 = tag 正则")
        assertFalse(matches("~/^Cam/", null, unparseable))
        assertTrue(matches("tag:~/a.c/", null, "10-11 09:00:00.004  100  203 I axc: dot"), "点号按正则元字符解释")
        assertFalse(matches("tag:~/a.c/", null, camI))
        assertTrue(matches("-tag:~/^Cam/", null, demoD), "排除前缀与正则修饰符可组合")
    }

    @Test
    fun `bare words inside query syntax are tag shorthands`() {
        // 混写裁定：裸词与算子同现时一律按 tag 包含语义
        assertTrue(matches("cam & level:E", null, camE))
        assertFalse(matches("cam & level:E", null, camI))
        assertFalse(matches("cam & level:E", null, demoD))
        assertTrue(matches("level:E cam", null, camE), "裸词位置不影响语义")
        assertTrue(matches("demo | cam", null, demoD), "裸词进 OR 分支")
        assertTrue(matches("cam-a", null, "10-11 09:00:00.005  100  204 I cam-a: hyphen"), "词中连字符按字面包含")
    }

    // ── msg 算子矩阵（消息体 = 「TAG: 」分隔符后余部，tag 不在匹配域） ─────

    @Test
    fun `msg operator matches message remainder with substring semantics`() {
        assertTrue(matches("msg:camera", null, demoMentionsCamera), "msg 命中消息体里的词（tag: 不命中的对照面）")
        assertTrue(matches("MSG:CAMERA", null, demoMentionsCamera), "key 与包含词大小写不敏感")
        assertTrue(matches("msg:frame", null, camI), "threadtime 消息体命中")
        assertTrue(matches("msg:crash", null, briefE), "brief 形态消息体同口径命中")
        assertFalse(matches("msg:camera", null, camI), "消息体不含词即不命中")
        assertFalse(matches("msg:demotag", null, demoMentionsCamera), "tag 本身不在 msg 匹配域")
        assertFalse(matches("msg:camera", null, unparseable), "不可解析行 + msg 原子 = 拒收（宁拒不猜的自然延伸）")
    }

    @Test
    fun `msg regex matches message remainder with grep semantics`() {
        assertTrue(matches("msg:~/^frame/", null, camI), "msg 正则锚定消息体行首")
        assertFalse(matches("msg:~/^Frame/", null, camI), "msg 正则默认大小写敏感")
        assertTrue(matches("msg:~/(?i)^FRAME/", null, camI), "内联忽略大小写标志同纳")
        assertFalse(matches("msg:~/Camera/", null, demoMentionsCamera), "msg 正则不看 tag")
        assertTrue(matches("msg:~/^crash/", null, briefE), "brief 形态正则同口径")
        assertTrue(matches("-msg:~/^frame/", null, demoD), "排除前缀与 msg 正则可组合")
    }

    @Test
    fun `msg combines with tag level and boolean operators`() {
        assertTrue(matches("msg:camera & level:I", null, demoMentionsCamera), "msg 与级别 AND")
        assertFalse(matches("msg:camera & level:E", null, demoMentionsCamera))
        assertTrue(matches("tag:demo & msg:camera", null, demoMentionsCamera), "tag 与 msg 跨域双命中")
        assertFalse(matches("tag:camera | msg:noise", null, demoMentionsCamera), "两域都未命中即不命中")
        assertTrue(matches("tag:demo | msg:crash", null, briefE), "msg 进 OR 分支")
        assertTrue(matches("(msg:camera | msg:crash) & level:I", null, demoMentionsCamera), "括号分组同纳")
        assertTrue(matches("-msg:frame", null, demoD), "排除消息体词")
        assertFalse(matches("-msg:noise", null, demoD))
    }

    // ── 消息体余部边界（引擎解析层两形态逐形态钉死） ───────────────────────

    private fun parsed(line: String): LogcatLineMatcher.ParsedLine = LogcatLineMatcher.parse(line)!!

    @Test
    fun `threadtime message boundary is everything after TAG separator`() {
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.INFO, "Cam", "frame start  123  end"),
            parsed("10-11 09:00:00.010  100  210 I Cam: frame start  123  end"),
            "「TAG: 」分隔符后整段原文（含消息内部空白）",
        )
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.INFO, "Cam", ""),
            parsed("10-11 09:00:00.011  100  211 I Cam"),
            "tag token 无冒号 ⇒ 无消息体（空）",
        )
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.INFO, "Cam", "a:b c"),
            parsed("10-11 09:00:00.012  100  212 I Cam: a:b c"),
            "消息体含冒号原样保留",
        )
    }

    @Test
    fun `brief and time message boundaries are pinned per form`() {
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.ERROR, "CamTag", "crash"),
            parsed("E/CamTag( 300): crash"),
            "brief 形态（pid 含空白）：`): ` 分隔符后余部",
        )
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.ERROR, "CamTag", "crash occurred: twice"),
            parsed("E/CamTag(300): crash occurred: twice"),
            "brief 形态（pid 无空白）：余部内冒号原样保留",
        )
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.ERROR, "CamTag", "boot fail"),
            parsed("10-11 09:00:00.013 E/CamTag(300): boot fail"),
            "time 形态（日期时间前缀）：同一 `): ` 分隔符口径",
        )
        assertEquals(
            // 无括号不成形头：tag 按既有 BRIEF_HEAD 契约带冒号原样入列（[^(\s]* 不排冒号）
            LogcatLineMatcher.ParsedLine(LogcatLevel.ERROR, "CamTag:", ""),
            parsed("E/CamTag: crash"),
            "分隔符不成形（无右括号）⇒ 空消息体（宁拒不猜）",
        )
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.ERROR, "CamTag", ""),
            parsed("E/CamTag(300) crash"),
            "分隔符不成形（括号后无冒号）⇒ 空消息体",
        )
        assertEquals(
            LogcatLineMatcher.ParsedLine(LogcatLevel.ERROR, "CamTag", ""),
            parsed("E/CamTag(300):"),
            "分隔符成形但消息体为空 ⇒ 空串",
        )
    }

    // ── 级别 chips 合成规则 ────────────────────────────────────────────────

    @Test
    fun `chip level combines as top-level AND with query predicate`() {
        assertFalse(matches("tag:camera", LogcatLevel.WARN, camI), "chips 与查询 AND 合成")
        assertTrue(matches("tag:camera", LogcatLevel.WARN, camE))
        assertTrue(matches("level:I", LogcatLevel.ERROR, camE), "两档并存取较严者")
        assertFalse(matches("level:I", LogcatLevel.ERROR, camI))
        assertFalse(matches("level:F", LogcatLevel.WARN, camE), "显式更严档不被 chips 放宽")
        assertFalse(matches("tag:demo | level:E", LogcatLevel.WARN, camI), "查询内 OR 不旁路 chips")
        assertFalse(matches("tag:demo | level:E", LogcatLevel.WARN, demoD), "级别不足 chips 档即不命中")
        assertTrue(matches("tag:demo | level:E", LogcatLevel.WARN, camE))
        assertFalse(matches("-tag:camera", LogcatLevel.ERROR, demoD), "排除与 chips 同为顶层 AND")
        assertTrue(matches("-tag:camera", LogcatLevel.ERROR, briefE))
    }

    // ── 解析失败显式化 ────────────────────────────────────────────────────

    @Test
    fun `parse failures carry human reasons without exceptions`() {
        assertTrue(errReason("text:foo").contains("未知算子"), "未知 key 显式失败：${errReason("text:foo")}")
        assertTrue(errReason("text:foo").contains("msg:"), "未知算子文案须列全可用算子集")
        assertTrue(errReason("tag:").contains("缺操作数"))
        assertTrue(errReason("tag:a &").contains("意外结束"), "悬空 AND 显式失败")
        assertTrue(errReason("| tag:a").contains("算子或词项"), "行首 OR 显式失败")
        assertTrue(errReason("(tag:a").contains("缺右括号"))
        assertTrue(errReason("tag:a )").contains("无法解析"), "残余右括号显式失败")
        assertTrue(errReason("()").contains("括号"), "空括号显式失败")
        assertTrue(errReason("tag:~/(( ").contains("正则未闭合"), "正则源内的 / 前内容到串尾无闭合")
        assertTrue(errReason("tag:~/x").contains("未闭合"))
        assertTrue(errReason("tag:~//").contains("正则为空"))
        assertTrue(errReason("tag:~/((/").contains("正则无效"), "非法正则编译失败转人话")
        assertTrue(errReason("level:X").contains("档位无效"))
        assertTrue(errReason("level:").contains("缺操作数"))
    }

    // ── 方言混淆防线（三套正则语义边界钉死） ──────────────────────────────

    @Test
    fun `dialect boundaries pin literal versus regex semantics`() {
        // 裸串方言：点号按字面子串解释（不是正则）
        assertTrue(matches("a.c", null, "10-11 09:00:00.006  100  205 I foo.a.c.bar: literal"))
        assertFalse(matches("a.c", null, "10-11 09:00:00.007  100  206 I axc: not-literal"))
        // 查询正则方言：同一写法按正则元字符解释
        assertTrue(matches("tag:~/a.c/", null, "10-11 09:00:00.007  100  206 I axc: not-literal"))
        // 查询正则只锚定 tag 域；武装特征正则锚定全行原文——两域不串（武装通道独立性
        // 由引擎级用例钉死，此处钉查询正则不看消息体）
        assertFalse(matches("tag:~/frame/", null, demoMentionsCamera))
        assertFalse(matches("tag:camera", null, demoMentionsCamera), "包含语义同样只看 tag——消息体里的 camera 不算")
        assertTrue(matches("tag:demo", null, demoMentionsCamera))
    }

    // ── 不可解析行裁决矩阵 ────────────────────────────────────────────────

    @Test
    fun `unparseable lines are rejected iff predicate has constraints`() {
        assertTrue(matches("", null, unparseable), "无约束恒命中")
        assertFalse(matches("tag:camera", null, unparseable))
        assertFalse(matches("-tag:camera", null, unparseable), "纯排除查询对不可解析行同样拒收（宁拒不猜）")
        assertFalse(matches("level:E", null, unparseable))
        assertFalse(matches("", LogcatLevel.WARN, unparseable), "chips 级别档是约束")
        assertFalse(matches("tag:demo | level:E", null, unparseable), "树内含任一原子即拒收")
    }
}
