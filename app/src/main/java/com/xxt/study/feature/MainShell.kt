package com.xxt.study.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.study.core.ui.Icons24
import com.xxt.study.core.ui.Palette
import com.xxt.study.core.update.UpdateUiState
import com.xxt.study.domain.CourseDigest
import com.xxt.study.domain.Session
import com.xxt.study.feature.home.HomeScreen
import com.xxt.study.feature.mine.MineScreen

enum class MainTab(val label: String, val icon: ImageVector) {
    Courses("课程", Icons24.Book),
    Mine("我的", Icons24.Person),
}

/**
 * 主壳：两个 tab，导航栏**悬浮**在内容之上。
 *
 * 为什么用 Box 叠而不是 Column 上下排：
 * Column 里导航是"占位"的，内容停在栏体上方，看起来只是"贴着底边的一张圆角卡"，
 * 不是悬浮。改成 Box 叠放后导航真正脱离布局流，列表能从栏体下面穿过去。
 * 代价是每个页面必须自己留底部内边距（首页 LazyColumn 100dp、
 * 「我的」页底部 96dp），否则最后一项会被永久挡住——所以这些不能删。
 *
 * 胶囊宽度 108dp：两 tab 时每格有 156dp 富余，能撑出"胶囊浮在槽位中央"的观感。
 * （三 tab 时每格只剩 112dp，108dp 几乎贴满整格，三颗胶囊会连成一条色带，
 * 悬浮感消失，那时候必须收到 96dp 并留出左右各 8dp 的呼吸位；现已回两 tab。）
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
    updateState: UpdateUiState,
    onUpdateClick: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    // tab 与 session 都由 MainActivity 持有：进设置再返回时不能重新请求，
    // 否则账号信息会先闪回占位再跳成真名（用户看到的"已登录→真实账户名"）

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg),
    ) {
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
                updateState = updateState,
                onUpdateClick = onUpdateClick,
                onOpenPermissions = onOpenPermissions,
            )
        }

        BottomBar(
            selected = tab,
            onSelect = onTabChange,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 悬浮导航：整块底 + 两个等宽槽位，选中的槽位是一颗实心主色胶囊。
 *
 * 选中态为什么包住"图标 + 文字"而不是只包图标：
 * 只包图标时，文字孤零零挂在下面，视觉重心被切成上下两块，看着像"图标被框住了"。
 * 胶囊同时包住两者，重心才合成一个整体。
 *
 * 为什么没有动画：
 * 1. 胶囊尺寸固定（108×38），切换只换底色与文字色，不参与布局测量，
 *    所以不可能出现"切换时互相挤压、文字被压成省略号"；
 * 2. 41 门课的长列表页面上，任何逐帧重绘都在抢滚动帧。颜色瞬切在这里不损失什么。
 *
 * 阴影只此一处：整页唯一一个 elevation。深色下阴影不可见，靠 1dp 描边维持边界。
 */
@Composable
private fun BottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(31.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .height(62.dp)
            .shadow(elevation = 10.dp, shape = shape, clip = false)
            .background(Palette.card, shape)
            .border(1.dp, Palette.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in MainTab.entries) {
            val active = t == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()

            val capBg = when {
                active -> Palette.brand
                pressed -> Palette.weak.copy(alpha = 0.14f)
                else -> Color.Transparent
            }
            val capFg = when {
                active -> Palette.onBrand
                pressed -> Palette.sub
                else -> Palette.weak
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(t) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .width(108.dp)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(capBg),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = t.icon,
                        contentDescription = t.label,
                        modifier = Modifier.size(19.dp),
                        tint = capFg,
                    )
                    Text(
                        // 胶囊宽度按"图标 + 两个字"固定，配合不换行、不省略，
                        // 保证 tab 名永远完整显示
                        text = t.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = capFg,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
        }
    }
}
