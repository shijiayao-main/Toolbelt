package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 知识库管理面板深链直达载荷槽（导航深链行「知识库管理 · 智能体中心 · 蒸馏签 ·
 * 库面板」——「KbManagePanelContent public 化直达」）。
 *
 * **中立一次性布尔槽（GoReportHook 中立槽家族的镜像面）**：GoReportHook 是
 * feature offer → 宿主 wire；本槽方向相反——写入方 = 宿主域（palette「知识库
 * 管理」候选行点击，composeApp 域 CommandPalette），消费方 = feature-ai 蒸馏签
 * （[consume] 读即清，等 kbStats 就绪后展开首仓库浏览面——DistillViewModel.openKbRepo）。
 * 双方都只见 :core，互不 import。
 *
 * - [offer]：palette 深链候选点击时置位（幂等——深链语义「到达即展开」）；
 * - [consume]：读即清（一次性载荷——面板已展开后再次组合不复活旧载荷）；
 * - 消费契约双路径（槽 = [pending] StateFlow）：① 落签瞬间——面板首次组合时
 *   消费面组合期 LaunchedEffect(Unit) 初值承载一次取走槽内既有载荷（用户停在
 *   别的签时 offer 的载荷滞留待取，落签即消费——深链语义不变）；② 在签期间——
 *   用户已停在蒸馏签时 palette 浮层候选行点击 offer（宿主导航是同值不发射、
 *   组合不重跑，初值承载不再有机会），消费面订阅 [pending] 流即时收到新载荷。
 *   两路径同用 [consume] 读即清收口，同一载荷恰消费一次，不滞留切签返签迟发。
 * - [clearForTest]：单测隔离缝（GoReportHook 同款）。
 */
object KbManagePanelHook {

    private val _pending = MutableStateFlow(false)

    /**
     * 待消费深链载荷槽（false = 槽空）。StateFlow 形态承载双消费路径：落签瞬间
     * 路径经 [consume] 初值承载；在签路径订阅本流（非 false 即消费——palette
     * 浮层在蒸馏签已组合时 offer 的新载荷经流发射即时到达订阅面，不滞留迟发）。
     */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** 深链候选点击置位（幂等——「到达即展开」语义，重复 offer 不叠加） */
    fun offer() {
        _pending.value = true
    }

    /** 读即清（一次性载荷：true = 本轮组合应展开库面板浏览面） */
    fun consume(): Boolean {
        val value = _pending.value
        _pending.value = false
        return value
    }

    /** 测试缝：清空载荷（单测隔离用） */
    fun clearForTest() {
        _pending.value = false
    }
}
