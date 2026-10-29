package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * StatusHost toast 三槽堆叠渲染结构守护。
 *
 * 状态面（append/挤最旧/dismiss(id)）由 :core StatusSignalsStackTest 行为直测；
 * 本守护锚渲染壳结构四件事：
 * 1. Column 纵排 + space8 间距 + 尾对齐（预览稿 `.toastcol` 直译——gap:8 右对齐）；
 * 2. `key(entry.id)` 逐条循环——挤最旧时中位/新位的倒计时与淡入不因位置错位重启；
 * 3. hover 暂停三件套逐条独立（per-entry interactionSource + per-entry remainingMs）；
 * 4. dismiss 全链路传 id：无参 `StatusSignals.dismiss()` 零残留（撤销/关闭/到点
 *    消退三出口都按条出局，不误伤堆叠邻居）。
 *
 * 材质与宽度 token 不变（appGlassThin + capsule + toastWidth）；空堆叠零渲染
 * （`toasts.isEmpty()` 短路保留）。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class StatusHostStackGuardTest {

    @Test
    fun `toast stack renders vertical column with space8 gap and end alignment`() {
        val text = anchorText()

        // ① 纵排容器（预览稿 .toastcol：flex-direction column + gap var(--sp-8) + 尾对齐）
        assertTrue(
            "verticalArrangement = Arrangement.spacedBy(Dimens.space8)" in text,
            "DS-15：toast 堆叠必须以 Column spacedBy(space8) 纵排（预览稿 .toastcol gap:8 直译），" +
                "实际源码未命中——改回单槽/散写间距即红。",
        )
        assertTrue(
            "horizontalAlignment = Alignment.End" in text,
            "DS-15：toast 堆叠容器必须尾对齐（右下角浮层锚定，预览稿 align-items:flex-end 直译）。",
        )
    }

    @Test
    fun `entries iterate with key id so eviction does not restart middle cards`() {
        val text = anchorText()

        // ② 逐条循环 + 稳定槽位锚（第 4 条挤最旧时，列表位移不重启幸存条状态）
        assertTrue(
            "toasts.forEach" in text,
            "DS-15：堆叠渲染必须逐条 forEach 循环（单 entry 渲染回潮即红）。",
        )
        assertTrue(
            "key(entry.id)" in text,
            "DS-15：循环体必须 key(entry.id)——纯位置槽在挤最旧时会让中位/新位的倒计时" +
                "与淡入错位重启（id 是 StatusSignals 单调唯一锚）。",
        )
    }

    @Test
    fun `hover pause and countdown are per entry`() {
        val text = anchorText()

        // ③ hover 暂停逐条独立：per-entry interactionSource（remember 在 ToastCard 内，
        //    key(entry.id) 保证换条换源——hover A 条不冻结 B 条倒计时）
        assertTrue(
            "remember { MutableInteractionSource() }" in text,
            "DS-15：hover interactionSource 必须落 ToastCard 单件内（per-entry）——" +
                "外提共享即 hover 串扰，「逐条独立不串扰」即红。",
        )
        // 倒计时状态 per-entry（remember(entry) 重算初始 remaining）
        assertTrue(
            "remember(entry) { mutableStateOf(entry.dismissAt" in text,
            "DS-15：remainingMs 必须 remember(entry) per-entry 初始化（换条重算，不共享单值）。",
        )
    }

    @Test
    fun `dismiss threads entry id with no bare no-arg dismiss left`() {
        val text = anchorText()

        // ④ 全链路 id 出局：撤销/关闭/到点消退三出口均传 id
        assertTrue(
            "StatusSignals.dismiss(entry.id)" in text,
            "DS-15：渲染壳 dismiss 必须传 entry.id（撤销/关闭钮/到点消退），按条精确出局。",
        )
        assertFalse(
            "StatusSignals.dismiss()" in text,
            "DS-15：无参 StatusSignals.dismiss() 不得残留——堆叠下单条关闭误清整栈即红。",
        )
    }

    @Test
    fun `material width tokens unchanged and empty stack renders nothing`() {
        val text = anchorText()

        // 材质与宽度 token 不变（appGlassThin + capsule + toastWidth）
        assertTrue(
            "appGlassThin(shape = AppShapes.capsule)" in text,
            "DS-15：toast 材质恒 thin 玻璃全圆胶囊（R2 口径），多槽化不得改材质档。",
        )
        assertTrue(
            "width(Dimens.toastWidth)" in text,
            "DS-15：单条宽度恒 Dimens.toastWidth（360dp token），多槽化不得改宽度。",
        )
        // 空堆叠零渲染（原「toast ?: return」语义随列表化）
        assertTrue(
            "if (toasts.isEmpty()) return" in text,
            "DS-15：空堆叠必须整壳短路零渲染（原单槽 null return 语义的列表化等价）。",
        )
    }

    /** 守护锚文件（缺失即红——重命名/移动后须同步本守护路径） */
    private fun anchorText(): String {
        val file = File(locateProjectRoot(), STATUS_HOST_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$STATUS_HOST_PATH（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private companion object {
        const val STATUS_HOST_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/StatusHost.kt"
    }
}
