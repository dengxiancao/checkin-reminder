// 职责：新增/编辑打卡点页面宿主 Activity，接收 pointId 并承载 Compose 表单
package com.example.checkin.ui.point

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.checkin.ui.theme.CheckinTheme

/**
 * 新增 / 编辑打卡点。
 *
 * 独立为 Activity 的原因：页面内嵌高德 [com.amap.api.maps.TextureMapView]，
 * 地图较重且需要独立的生命周期回调，用 Activity 承载比在 Compose 内切页更稳妥。
 */
class AddEditPointActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // pointId = 0 表示新增
        val pointId = intent?.getLongExtra(EXTRA_POINT_ID, 0L) ?: 0L

        setContent {
            CheckinTheme {
                val vm: PointEditViewModel = viewModel()

                // 仅在首次进入时加载一次，避免重组反复查库
                androidx.compose.runtime.LaunchedEffect(pointId) {
                    vm.load(pointId)
                }

                PointEditScreen(
                    viewModel = vm,
                    onBack = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onSaved = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        /** 传入打卡点 id，0 或省略表示新增 */
        const val EXTRA_POINT_ID = "extra_point_id"

        /** 构造「新增」意图 */
        fun newIntentForCreate(context: Context): Intent =
            Intent(context, AddEditPointActivity::class.java)

        /** 构造「编辑」意图 */
        fun newIntentForEdit(context: Context, pointId: Long): Intent =
            Intent(context, AddEditPointActivity::class.java).apply {
                putExtra(EXTRA_POINT_ID, pointId)
            }
    }
}
