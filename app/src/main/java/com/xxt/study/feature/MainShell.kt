package com.xxt.study.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.result.Cx
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Session
import com.xxt.study.feature.home.HomeScreen
import com.xxt.study.feature.mine.MineScreen

enum class MainTab(val label: String, val icon: ImageVector) {
    Courses("课程", Icons24.Book),
    Mine("我的", Icons24.Person),
}

/**
 * 主壳：底部两个 tab。
 *
 * 为什么是这两个：这个 App 只有两类事——看课/刷课，和改配置。
 * 分成「课程」和「我的」比把设置塞进首页右上角更符合手机习惯，
 * 也给后面加"答题记录""学习统计"留了位置。
 */
@Composable
fun MainShell(
    session: Session?,
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    onNeedLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenCourse: (CourseDigest) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit,
    onCheckUpdate: () -> Unit,
    checkingUpdate: Boolean,
) {
    // tab 与 session 都由 MainActivity 持有：进设置再返回时不能重新请求，
    // 否则账号信息会先闪回占位再跳成真名（用户看到的"已登录→真实账户名"）

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                // 课程页只管看课：设置与退出都在「我的」，这里不再挂第二个入口
                MainTab.Courses -> HomeScreen(
                    onNeedLogin = onNeedLogin,
                    onOpenCourse = onOpenCourse,
                )
                MainTab.Mine -> MineScreen(
                    session = session,
                    onOpenSettings = onOpenSettings,
                    onOpenGuide = onOpenGuide,
                    onNeedLogin = onNeedLogin,
                    onLogout = onLogout,
                    onCheckUpdate = onCheckUpdate,
                    checkingUpdate = checkingUpdate,
                )
            }
        }

        BottomBar(selected = tab, onSelect = onTabChange)
    }
}

@Composable
private fun BottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.card)
            .navigationBarsPadding()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in MainTab.entries) {
            val active = t == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = t.icon,
                    contentDescription = t.label,
                    modifier = Modifier.size(21.dp),
                    tint = if (active) Palette.brand else Palette.weak,
                )
                Text(
                    text = t.label,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) Palette.brand else Palette.weak,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
