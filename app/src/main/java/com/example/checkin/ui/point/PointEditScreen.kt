// 职责：新增/编辑打卡点页，内嵌高德地图点选落标记、可视化半径圆并保存
package com.example.checkin.ui.point

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.example.checkin.R
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.geofence.AMapLocationEngine
import com.example.checkin.util.LocationError
import java.util.Locale

/** 中国境内默认视野中心（北京天安门），未选点时使用 */
private const val DEFAULT_LAT = 39.909187
private const val DEFAULT_LNG = 116.397451
private const val DEFAULT_ZOOM = 17f

/** 定位超时兜底时间（毫秒） */
private const val LOCATE_TIMEOUT_MS = 15_000L

/** 日志 TAG */
private const val TAG = "PointEditScreen"

/**
 * 新增/编辑打卡点页面。
 *
 * 地图交互：
 * - **点击地图任意位置**即在该处落下标记，并同步绘制半径圆；
 * - 点击右下「定位」按钮用高德定位回传当前位置；
 * - 半径档位切换后圆会实时重绘，所见即所得。
 *
 * 注意：高德 MapView 必须手动转发生命周期，否则地图不渲染或泄漏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointEditScreen(
    viewModel: PointEditViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 定位失败等提示通过 Snackbar 展示（定位失败绝不能只写日志）
    val snackbarHostState = remember { SnackbarHostState() }
    val locateMessage = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(locateMessage.value) {
        locateMessage.value?.let {
            snackbarHostState.showSnackbar(it)
            locateMessage.value = null
        }
    }

    // 保存成功后关闭页面
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (uiState.isNew) R.string.point_add_title
                            else R.string.point_edit_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.action_save)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---------------- 地图区域 ----------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                PointPickerMap(
                    latitude = uiState.latitude,
                    longitude = uiState.longitude,
                    radiusMeters = uiState.radiusMeters,
                    onPick = { lat, lng -> viewModel.setLocation(lat, lng) },
                    locateMessage = locateMessage,
                    modifier = Modifier.fillMaxSize()
                )

                // 尚未选点时的提示
                if (!uiState.hasLocation) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.point_map_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            // ---------------- 表单区域 ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.point_name_label)) },
                    placeholder = { Text(stringResource(R.string.point_name_placeholder)) },
                    singleLine = true,
                    isError = uiState.error != null && uiState.name.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )

                // 当前坐标展示
                Text(
                    text = if (uiState.hasLocation) {
                        String.format(
                            Locale.CHINA,
                            stringResource(R.string.point_location_format),
                            uiState.latitude,
                            uiState.longitude
                        )
                    } else {
                        stringResource(R.string.point_location_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---------------- 半径档位 ----------------
                Text(
                    text = stringResource(R.string.point_radius_label, uiState.radiusMeters),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckinPoint.RADIUS_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = uiState.radiusMeters == preset,
                            onClick = { viewModel.setRadius(preset) },
                            label = { Text("$preset m") }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.point_radius_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---------------- 开关 ----------------
                SwitchRow(
                    label = stringResource(R.string.point_enabled),
                    checked = uiState.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
                SwitchRow(
                    label = stringResource(R.string.point_notify_enter),
                    checked = uiState.notifyOnEnter,
                    onCheckedChange = viewModel::setNotifyOnEnter
                )
                SwitchRow(
                    label = stringResource(R.string.point_notify_exit),
                    checked = uiState.notifyOnExit,
                    onCheckedChange = viewModel::setNotifyOnExit
                )

                // 错误提示
                uiState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.save() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                    Button(
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

/**
 * 内嵌高德地图：点击落标记 + 半径圆。
 *
 * 通过 [AndroidView] 承载 [TextureMapView]（纹理实现，可与 Compose 共存），
 * 并用 [DisposableEffect] 手动转发生命周期。
 */
@Composable
private fun PointPickerMap(
    latitude: Double,
    longitude: Double,
    radiusMeters: Int,
    onPick: (Double, Double) -> Unit,
    locateMessage: MutableState<String?>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity

    // 保存地图与图形对象引用
    var mapView by remember { mutableStateOf<TextureMapView?>(null) }
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var circle by remember { mutableStateOf<Circle?>(null) }

    // AndroidView 提供的外层容器：销毁时必须从这里 removeView(TextureMapView)，
    // 否则 Activity 重建时 AndroidView 复用旧容器会再次 addView 一个新的地图。
    var mapContainer by remember { mutableStateOf<FrameLayout?>(null) }

    // 定位引擎（点「定位」按钮时使用）
    val locationEngine = remember { AMapLocationEngine(context) }
    var locating by remember { mutableStateOf(false) }

    /**
     * 定位超时兜底。
     *
     * 高德 SDK 在 Key 无效、隐私未同意、缺少 .so 等情况下可能**永远不回调**，
     * 那样按钮会一直停在「定位中…」，用户看到的就是「点了没反应」。
     * 这里加 15 秒兜底，保证状态一定会恢复并给出可读提示。
     */
    LaunchedEffect(locating) {
        if (!locating) return@LaunchedEffect
        kotlinx.coroutines.delay(LOCATE_TIMEOUT_MS)
        if (locating) {
            locating = false
            locationEngine.stop()
            locateMessage.value = "定位超时（${LOCATE_TIMEOUT_MS / 1000} 秒无结果）：" +
                "请确认已配置高德 Key、手机定位已开启，并到室外或信号较好的位置重试"
        }
    }

    // 实时拿到最新的 onPick，避免 setOnMapClickListener 捕获过期闭包
    val currentOnPick = androidx.compose.runtime.rememberUpdatedState(onPick)

    // 地图生命周期转发
    DisposableEffect(activity) {
        val owner = activity ?: return@DisposableEffect onDispose { }

        /**
         * 彻底释放地图资源。
         *
         * 顺序很重要：先清空图形引用（Marker/Circle 持有已销毁地图上的对象），
         * 再从容器移除 TextureMapView，最后调用 onDestroy 并清空 mapView/aMap。
         * 原实现只置空 mapView/aMap，导致：① 图形对象悬挂；② 容器里仍留着旧地图 View，
         * Activity 重建（旋转 / 深色模式 / 字体大小变更）时会重复 addView。
         */
        fun releaseMap() {
            locationEngine.stop()
            marker = null
            circle = null
            mapView?.let { view ->
                runCatching { mapContainer?.removeView(view) }
                runCatching { view.onDestroy() }
            }
            mapView = null
            aMap = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                Lifecycle.Event.ON_DESTROY -> releaseMap()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            // 兜底：即便没有收到 ON_DESTROY（例如纯重组导致组件被移除）也要释放
            releaseMap()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // 用 FrameLayout 包裹，避免重复创建时高德报“重复添加 View”
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }.also { container ->
                    // 记录容器引用，供 releaseMap() 移除地图 View
                    mapContainer = container
                }
            },
            update = { container ->
                // 地图只创建一次；后续重组只更新标记与半径圆
                val view = mapView ?: run {
                    val created = TextureMapView(container.context).apply { onCreate(Bundle()) }
                    container.addView(
                        created,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    mapView = created

                    created.map?.let { amap ->
                        aMap = amap
                        // UiSettings 是 Java setter，不能使用 Kotlin 属性语法
                        amap.uiSettings.apply {
                            setZoomControlsEnabled(true)
                            setCompassEnabled(true)
                            setScaleControlsEnabled(true)
                            setMyLocationButtonEnabled(false)
                        }
                        // 点击地图即落标记
                        amap.setOnMapClickListener { latLng ->
                            if (latLng != null) {
                                currentOnPick.value(latLng.latitude, latLng.longitude)
                            }
                        }
                    }
                    created
                }

                // 同步标记与半径圆
                val amap = aMap ?: view.map ?: return@AndroidView
                if (latitude == 0.0 && longitude == 0.0) return@AndroidView

                val target = LatLng(latitude, longitude)

                val currentMarker = marker
                if (currentMarker == null) {
                    marker = amap.addMarker(
                        MarkerOptions()
                            .position(target)
                            .title("打卡点")
                            .snippet("半径 $radiusMeters 米")
                            .draggable(false)
                    )
                    // 首次选点后把镜头移过去
                    amap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, DEFAULT_ZOOM))
                } else {
                    currentMarker.position = target
                    currentMarker.snippet = "半径 $radiusMeters 米"
                }

                // 半径圆：实时反映档位变化
                val currentCircle = circle
                if (currentCircle == null) {
                    circle = amap.addCircle(
                        CircleOptions()
                            .center(target)
                            .radius(radiusMeters.toDouble())
                            .strokeWidth(3f)
                            .strokeColor(RING_STROKE_COLOR)
                            .fillColor(RING_FILL_COLOR)
                    )
                } else {
                    currentCircle.center = target
                    currentCircle.radius = radiusMeters.toDouble()
                }
            }
        )

        // 定位到当前位置
        ExtendedFloatingActionButton(
            // 注意：ExtendedFloatingActionButton 没有 enabled 参数，
            // 因此只能靠 onClick 内提前返回做防重复点击保护。
            onClick = locate@{
                // 双保险：即便 enabled 被绕过也不重复启动
                if (locating) return@locate
                locating = true
                locationEngine.onLocation = { snapshot ->
                    locating = false
                    // 把地图镜头移到当前位置，并直接落点到该坐标
                    aMap?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(snapshot.latitude, snapshot.longitude),
                            DEFAULT_ZOOM
                        )
                    )
                    onPick(snapshot.latitude, snapshot.longitude)
                }
                locationEngine.onError = { code, info ->
                    locating = false
                    // 关键：必须把错误暴露到界面，否则用户只会看到“点了没反应”
                    val readable = LocationError.readableMessage(code, info)
                    Log.e(TAG, "定位失败 code=$code info=$info -> $readable")
                    locateMessage.value = "定位失败（错误码 $code）：$readable"
                }
                // onceLocation=true 只请求一次；若本次失败不会自动重试
                locationEngine.start(intervalMs = 2_000L, onceLocation = true)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            icon = { Icon(Icons.Filled.MyLocation, contentDescription = null) },
            text = {
                Text(
                    stringResource(
                        if (locating) R.string.point_locating else R.string.point_locate_me
                    )
                )
            }
        )
    }
}

/** 半径圆边框色（品牌绿，半透明） */
private const val RING_STROKE_COLOR = 0xFF1B6C3A.toInt()

/** 半径圆填充色（品牌绿，约 12% 透明度） */
private const val RING_FILL_COLOR = 0x1F1B6C3A

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
