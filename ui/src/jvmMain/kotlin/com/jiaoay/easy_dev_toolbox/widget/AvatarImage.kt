// 头像加载件（coil3 桌面 KMP 线）。
// 架构铁律：图片库不得四处引入——coil3/ktor 的任何 import/类型只允许出现在本文件，
//    消费方（统计页 SenderRow / 用户详情卡）只调 AvatarImage(url, fallbackName, size, modifier)，零图片库感知。
// 不自研 LRU/HTTP——coil3 内建内存缓存（URL 作 key 语义天然正确）+
//    ktor3 网络引擎；零 diskCache（头像高频小图不值得落盘）；零 crossfade（UiGuard 裸动画拦截纪律）。
package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.math.abs

/**
 * 头像三态渲染（加载中/失败/url 为空 ⇒ 首字占位圆兜底，绝不留空白）。
 *
 * @param url 头像 URL；null/blank ⇒ 直接占位圆（零网络）
 * @param fallbackName 取首字与稳定取色 hash 的名字源（无名字时传 openId 亦可）
 * @param size 直径（调用方走 Dimens.avatarInline / avatarProfile token）
 */
@Composable
fun AvatarImage(
    url: String?,
    fallbackName: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    if (url.isNullOrBlank()) {
        PlaceholderCircle(fallbackName, size, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(url)
            .build(),
        imageLoader = AvatarLoaderHolder.loader,
        contentDescription = "用户头像",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        loading = { PlaceholderCircle(fallbackName, size, Modifier) },
        error = { PlaceholderCircle(fallbackName, size, Modifier) },
    )
}

/** 全局唯一 ImageLoader（进程级单例；4MB 内存缓存封顶 ≈ 百余张 240px 头像，R10）。 */
private object AvatarLoaderHolder {
    val loader: ImageLoader by lazy {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(KtorNetworkFetcherFactory(HttpClient(CIO))) }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(AVATAR_MEMORY_CACHE_BYTES)
                    .build()
            }
            // 头像不需要磁盘缓存（进程生命周期内 URL 稳定；coil3 diskCache 默认禁用保持不动）
            .build()
    }

    /** 4MB（R10：内存封顶纪律）。 */
    const val AVATAR_MEMORY_CACHE_BYTES: Long = 4L * 1024 * 1024
}

/**
 * 首字占位圆（无头像/加载中/失败三态共用兜底）。
 * 按 [fallbackName] 的 hash 稳定取色三主题槽（primary/tertiary/secondary）——同名恒同色，不闪不跳。
 */
@Composable
private fun PlaceholderCircle(fallbackName: String, size: Dp, modifier: Modifier = Modifier) {
    val firstChar = fallbackName.trim().firstOrNull()?.toString() ?: "?"
    val color = when (abs(fallbackName.hashCode()) % 3) {
        0 -> MaterialTheme.colorScheme.primary
        1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = AppAlpha.bgSubtle)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = firstChar,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}
