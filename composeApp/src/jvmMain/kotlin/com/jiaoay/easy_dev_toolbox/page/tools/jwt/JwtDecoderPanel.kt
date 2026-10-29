package com.jiaoay.easy_dev_toolbox.page.tools.jwt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton

/**
 * JWT 解码面板：「工具」聚合页子签内容体（聚合页原生签——无 palette 深入
 * 模式候选、无持久化，全部状态会话内）。
 *
 * 【解析器非验证器】本面板只解码展示（header/payload 明文 + exp/iat/nbf 人类
 * 时间），签名段原样带「未验证」标注——不得呈现任何「签名有效」暗示。
 *
 * 视觉层（明文块密度、时间声明行排布）为保守默认，待真机终裁。
 */
@Composable
fun JwtDecoderPanel() {
    var token by remember { mutableStateOf("") }

    val result = remember(token) { decodeJwt(token) }

    SectionCard(
        title = "JWT 解析",
        description = "粘贴 JWT → header/payload 明文展开 + exp/iat/nbf 人类时间；只解不验签",
        headerTrailing = {
            PillLabel(
                text = "base64url · 过期判读",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("JWT 文本") },
            placeholder = { Text("粘贴三段式令牌（header.payload.signature，抓包 / 云端下发串）") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
        when (val decoded = result) {
            is JwtDecodeResult.Empty -> Unit

            is JwtDecodeResult.Invalid -> Text(
                text = decoded.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is JwtDecodeResult.Ok -> {
                PlainTextBlock(label = "Header（明文）", json = decoded.headerJson)
                PlainTextBlock(label = "Payload（明文）", json = decoded.payloadJson)
                if (decoded.timeClaims.isEmpty()) {
                    Text(
                        text = "payload 无时间声明（exp/iat/nbf 均缺）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TimeClaimRows(decoded.timeClaims)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    Text(
                        text = "签名段（未验证，本工具只解不验签——不能据此信任令牌内容）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CopyIconButton(
                        textProvider = { decoded.signatureSegment },
                        contentDescription = "复制签名段",
                    )
                }
            }
        }
    }
}

/** 明文块：小标题 + 格式化 JSON + 复制钮（明文整段带走的动线） */
@Composable
private fun PlainTextBlock(label: String, json: String) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            CopyIconButton(
                textProvider = { json },
                contentDescription = "复制${label.substringBefore("（")}明文",
            )
        }
        Text(
            text = json,
            style = DesktopTypography.mono,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 时间声明行：声明名 + 人类时间 + 判定（过期红 / 有效绿 / 无判定灰） */
@Composable
private fun TimeClaimRows(claims: List<JwtTimeClaim>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        claims.forEach { claim ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                Text(
                    text = claim.name,
                    style = DesktopTypography.monoTag,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = claim.humanTime,
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (claim.verdict != null) {
                    Text(
                        text = claim.verdict,
                        style = MaterialTheme.typography.labelSmall,
                        // 已过期 → error；未过期/已生效 → success（判读结论用语义色扫一眼可见）
                        color = when {
                            claim.verdict.contains("已过期") || claim.verdict.contains("尚未生效") ->
                                MaterialTheme.colorScheme.error
                            else -> LocalEasyToolboxColors.current.success
                        },
                    )
                }
            }
        }
    }
}
