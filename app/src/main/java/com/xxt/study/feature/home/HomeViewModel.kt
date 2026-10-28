package com.xxt.study.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xxt.study.App
import com.xxt.study.core.result.Cx
import com.xxt.study.domain.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val snapshot: Snapshot? = null,
    val loading: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val needLogin: Boolean = false,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // 先出缓存里的数字，别让用户对着空屏等网络
        _state.update { it.copy(snapshot = container.repo.cached()) }
        refresh()
    }

    /**
     * @param force true = 忽略缓存全量重扫（用户主动点刷新时用）；
     *              false = 12 分钟内扫过的课直接复用，进 App 时走这条
     */
    fun refresh(force: Boolean = false) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, progress = "正在读取课程列表") }
            val result = container.repo.refresh(force) { done, total ->
                _state.update { it.copy(progress = "正在扫描 $done/$total 门课") }
            }
            when (result) {
                is Cx.Ok -> _state.update {
                    it.copy(snapshot = result.data, loading = false, progress = null, error = null)
                }
                is Cx.NeedLogin -> _state.update {
                    it.copy(loading = false, progress = null, needLogin = true)
                }
                is Cx.Fail -> _state.update {
                    it.copy(loading = false, progress = null, error = result.detail)
                }
            }
        }
    }

    fun logout() = container.repo.logout()
}
