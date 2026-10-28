package com.xxt.study.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ai.AiClient
import com.xxt.study.core.ui.Palette

/**
 * AI 模型快捷切换。
 *
 * 答题页和刷课页共用这一份：用户在两个页面之间来回切模型时，不应该被逼着先回设置页。
 * 只列**已经配齐三项**（地址/Key/模型）的服务——半配完的点了也用不了，
 * 只会让人误以为切换失败。
 *
 * @param saved 由调用方 `remember(版本号)` 算好传进来，避免每帧都去读密钥库
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSwitchSheet(
    saved: List<AiClient.Saved>,
    activeId: String,
    onPick: (AiClient.Saved) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "切换 AI 模型",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "去设置",
                    fontSize = 12.sp,
                    color = Palette.brand,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "只列已配好地址、Key、模型的服务。切过去立即生效，不影响已保存的其它配置。",
                fontSize = 11.sp,
                color = Palette.weak,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))

            if (saved.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.bg)
                        .padding(16.dp),
                ) {
                    Text(
                        text = "还没有配好任何一个服务。\n去「我的 → 设置」选一个服务商，填 API Key、扫描并选中模型后保存。",
                        fontSize = 12.sp,
                        color = Palette.sub,
                        lineHeight = 18.sp,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saved.forEach { item ->
                        val active = item.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) Palette.brandSoft else Palette.bg)
                                .clickable { onPick(item) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (active) Palette.brand else Palette.ink,
                                )
                                Text(
                                    text = item.model,
                                    fontSize = 11.sp,
                                    color = Palette.weak,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Text(
                                text = if (active) "使用中" else "切换",
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                color = if (active) Palette.brand else Palette.sub,
                            )
                        }
                    }
                }
            }
        }
    }
}
