package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls

/**
 * 飞书调度 agent 专属执行策略。
 *
 * ## 为什么边序与 singleRunStrategy 相反——混合响应工具优先
 * koog 官方 singleRunStrategy 的 nodeSendToolResult 出口边序是 onTextMessage→Finish **先**声明、
 * onToolCalls→ExecuteTool 回环**后**声明；图引擎 resolveEdge 按注册序取第一条能走的边
 * （AIAgentNode.kt:87-100），而 onTextMessage 谓词只看 `parts.any { it is MessagePart.Text }`
 * **存在性**——LLM 常产「收尾文本 + 工具调用」的**混合响应**（如
 * 「分析完成，我把报告发给你。」+ send_file Call），文本边先命中 ⇒ __finish__ ⇒
 * 工具调用**静默丢弃**（用户收不到结论卡与报告文件）。
 *
 * 本策略图结构与 singleRunStrategy 完全一致（nodeLLMRequest → nodeExecuteTools →
 * nodeLLMSendToolResults），仅把 nodeSendToolResult 两条出口边**声明顺序对调**：
 * onToolCalls 回环在前、onTextMessage→Finish 在后 ⇒ 混合响应继续执行工具（工具优先），
 * 纯文本响应照旧走 Finish（语义不变）。零额外 LLM 调用、零消息污染——不引入
 * chatAgentStrategy 的「强塞 "Don't chat with plain text!" user 消息」与「Chat finished」固定返回串。
 */
internal fun larkDispatchStrategy(): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("lark_dispatch") {
        val nodeCallLLM by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()

        edge(nodeStart forwardTo nodeCallLLM)
        // nodeCallLLM 出口边序与 singleRunStrategy 一致（工具边在前）——本来就正确，无需对调。
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 🔴 唯一改动点：nodeSendToolResult 出口边序对调——工具回环在前、Finish 在后
        //    （对调回去会让混合响应里的工具调用静默丢弃，见类头「为什么边序相反」）。
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
    }
