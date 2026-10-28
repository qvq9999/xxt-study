package com.xxt.study.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.update.ChangeEntry
import com.xxt.study.core.update.Changelog

/**
 * 版本日志弹窗。
 *
 * 为什么从「我的」页里那坨内嵌列表改成弹窗：
 * 摊在页面里等于把一整个列表塞进设置入口，用户每次进「我的」都被它占掉半屏，
 * 而他一年也未必看一次。现在「我的」页只留一行入口，要看再弹出来看全。
 */
@Composable
fun ChangelogDialog(
    version: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.card)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "版本日志",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.ink,
                    )
                    Text(
                        text = "当前 v$version · 共 ${Changelog.entries.size} 个版本",
                        fontSize = 11.sp,
                        color = Palette.weak,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons24.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp),
                        tint = Palette.weak,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            /*
             * 弹窗内滚动，而不是让弹窗自己长高：
             * 版本数会不断累加，写死高度迟早撑破屏幕；给个上限让它内部滚，
             * 以后加到 20 个版本也不会变形。
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Changelog.entries.forEach { entry ->
                    ChangeLogBlock(entry)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.brand)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = "知道了",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.card,
                    )
                }
            }
        }
    }
}

/** 一条版本记录：版本号 + 日期 + 几条简写 */
@Composable
private fun ChangeLogBlock(entry: ChangeEntry) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "v${entry.versionName}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.brand,
            )
            Text(
                text = entry.date,
                fontSize = 10.sp,
                color = Palette.weak,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        entry.notes.take(Changelog.MAX_NOTES).forEach { note ->
            Text(
                text = "· $note",
                fontSize = 11.sp,
                color = Palette.sub,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
