// 职责：后台权限设置引导页，按厂商给出可照做的步骤并尽量直达系统设置页
package com.example.checkin.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import com.example.checkin.R
import com.example.checkin.util.RomVendor

/**
 * 后台权限设置引导页。
 *
 * 设计原则（重要）：
 * 国产 ROM 的「自启动」「后台运行」「锁任务卡片」都是**厂商私有开关**，
 * 没有公开 API 可以查询或一键开启，且各家设置页的 Activity 名称会随版本变化。
 * 因此本页：
 * 1. 以**文字步骤**为主（用户一定能照做）；
 * 2. 提供「打开设置」按钮尽力直达对应页面，失败会降级到应用信息页；
 * 3. 明确提示跳转可能不精准，不误导用户。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundGuideScreen(
    initialVendor: RomVendor,
    onOpenAutoStart: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onBack: () -> Unit
) {
    var vendor by remember { mutableStateOf(initialVendor) }
    val guide = remember(vendor) { GuideContent.of(vendor) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guide_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------- 机型选择 ----------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.guide_vendor_detected, vendor.displayName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.guide_vendor_manual_switch),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    // 横向可滚动的机型选择
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RomVendor.entries.forEach { entry ->
                            FilterChip(
                                selected = entry == vendor,
                                onClick = { vendor = entry },
                                label = { Text(entry.displayName.substringBefore("（")) }
                            )
                        }
                    }
                }
            }

            // ---------------- 三个关键开关的步骤 ----------------
            guide.steps.forEachIndexed { index, step ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (index) {
                                    0 -> Icons.Filled.PlayArrow
                                    1 -> Icons.Filled.BatteryChargingFull
                                    else -> Icons.Filled.Lock
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = step.path,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------- 直达设置页按钮 ----------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "快捷跳转",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenAutoStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.guide_open_autostart))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenBatterySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.guide_open_battery))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenAppInfo,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.guide_open_appinfo))
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.guide_jump_unreliable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // ---------------- 通用步骤 ----------------
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.guide_common_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    val common = GuideContent.COMMON_STEPS
                    common.forEachIndexed { i, text ->
                        Text(
                            text = "${i + 1}. $text",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // ---------------- 验证方法 ----------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.guide_verify_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.guide_verify_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 单个设置步骤 */
internal data class GuideStep(
    val title: String,
    /** 设置路径（照抄能找到） */
    val path: String,
    /** 补充说明 */
    val detail: String
)

/** 各厂商的引导内容 */
internal data class GuideContent(
    val steps: List<GuideStep>
) {
    companion object {
        /** 通用步骤：不依赖 ROM，所有机型都可照做 */
        val COMMON_STEPS = listOf(
            "允许自启动：设置 → 应用管理 → 找到本应用 → 允许「自启动 / 开机启动」",
            "电池不优化：设置 → 电池 → 更多电池设置 → 关闭对本应用的「智能省电 / 后台冻结」",
            "允许后台运行：设置 → 应用管理 → 本应用 → 后台运行 → 选择「允许后台运行」",
            "清除后台时锁住本应用：打开最近任务（多任务）界面，下拉本应用卡片或点击锁图标锁定",
            "关闭「后台高耗电限制」提示：若系统弹出「本应用耗电异常」，选择允许继续运行"
        )

        fun of(vendor: RomVendor): GuideContent = when (vendor) {
            RomVendor.MIUI -> GuideContent(
                listOf(
                    GuideStep(
                        title = "第 1 步：允许自启动（MIUI / HyperOS）",
                        path = "设置 → 应用设置 → 应用管理 → 定位打卡 → 自启动",
                        detail = "打开「自启动」开关；若列表里还能看到「关联启动」，也一并允许。"
                    ),
                    GuideStep(
                        title = "第 2 步：电池设为无限制",
                        path = "设置 → 应用设置 → 应用管理 → 定位打卡 → 省电策略 → 无限制",
                        detail = "省电策略必须选「无限制」，否则锁屏后会被冻结。"
                    ),
                    GuideStep(
                        title = "第 3 步：锁定最近任务",
                        path = "打开最近任务 → 下拉「定位打卡」卡片 → 出现锁图标",
                        detail = "锁定后点击「全部清除」不会杀掉本应用。"
                    )
                )
            )

            RomVendor.EMUI, RomVendor.HONOR -> GuideContent(
                listOf(
                    GuideStep(
                        title = "第 1 步：允许自启动（EMUI / MagicOS）",
                        path = "设置 → 应用 → 应用启动管理 → 定位打卡 → 关闭「自动管理」",
                        detail = "关闭自动管理后，把「自启动」「关联启动」「后台活动」三项全部手动打开。"
                    ),
                    GuideStep(
                        title = "第 2 步：电池不优化",
                        path = "设置 → 电池 → 更多电池设置 → 应用启动管理 / 高耗电提醒",
                        detail = "关闭「高耗电提醒」对本应用的弹窗限制，并在「忽略电池优化」中加入本应用。"
                    ),
                    GuideStep(
                        title = "第 3 步：锁定最近任务",
                        path = "打开最近任务 → 下拉「定位打卡」卡片加锁",
                        detail = "锁屏清理时保留本应用，避免监控中断。"
                    )
                )
            )

            RomVendor.COLOR_OS -> GuideContent(
                listOf(
                    GuideStep(
                        title = "第 1 步：允许自启动（ColorOS）",
                        path = "设置 → 应用管理 → 定位打卡 → 允许自启动 / 允许后台活动",
                        detail = "部分版本路径为「手机管家 → 权限隐私 → 自启动管理」。"
                    ),
                    GuideStep(
                        title = "第 2 步：电池不优化",
                        path = "设置 → 电池 → 更多设置 → 睡眠待机优化 → 关闭；应用耗电管理 → 允许后台运行",
                        detail = "务必在「耗电管理」里选择「允许后台运行」，否则锁屏 5 分钟后会被冻结。"
                    ),
                    GuideStep(
                        title = "第 3 步：锁定最近任务",
                        path = "打开最近任务 → 点击卡片右上角菜单 → 锁定",
                        detail = "锁定后清理后台不会影响监控。"
                    )
                )
            )

            RomVendor.ORIGIN_OS -> GuideContent(
                listOf(
                    GuideStep(
                        title = "第 1 步：允许自启动（OriginOS / Funtouch）",
                        path = "设置 → 应用与权限 → 权限管理 → 自启动 → 定位打卡 → 允许",
                        detail = "部分版本在「i 管家 → 应用管理 → 权限管理 → 自启动」。"
                    ),
                    GuideStep(
                        title = "第 2 步：电池不优化",
                        path = "设置 → 电池 → 后台高耗电 → 定位打卡 → 允许后台高耗电",
                        detail = "同时在「电池优化」中把本应用设为「不优化」。"
                    ),
                    GuideStep(
                        title = "第 3 步：锁定最近任务",
                        path = "打开最近任务 → 下拉本应用卡片 → 锁定",
                        detail = "避免一键清理时被结束。"
                    )
                )
            )

            RomVendor.FLYME -> GuideContent(
                listOf(
                    GuideStep(
                        title = "第 1 步：允许自启动（Flyme）",
                        path = "手机管家 → 权限管理 → 自启动 → 定位打卡 → 允许",
                        detail = "同时开启「后台运行」权限。"
                    ),
                    GuideStep(
                        title = "第 2 步：电池不优化",
                        path = "设置 → 电量管理 → 应用耗电管理 → 定位打卡 → 允许后台运行",
                        detail = "关闭「智能省电」对本应用的限制。"
                    ),
                    GuideStep(
                        title = "第 3 步：锁定最近任务",
                        path = "打开最近任务 → 下拉本应用卡片加锁",
                        detail = "锁定防止被清理。"
                    )
                )
            )

            RomVendor.SAMSUNG -> GuideContent(
                listOf(
                    GuideStep(
                        title = "第 1 步：关闭电池优化",
                        path = "设置 → 电池和设备维护 → 电池 → 后台使用限制 → 从「休眠应用」中移除本应用",
                        detail = "务必确认本应用不在「休眠应用」和「深度休眠应用」列表中。"
                    ),
                    GuideStep(
                        title = "第 2 步：允许后台活动",
                        path = "设置 → 应用 → 定位打卡 → 电池 → 不受限制",
                        detail = "选择「不受限制」，否则后台定位会被限制。"
                    ),
                    GuideStep(
                        title = "第 3 步：关闭自适应电池",
                        path = "设置 → 电池和设备维护 → 电池 → 更多电池设置 → 关闭「自适应电池」",
                        detail = "自适应电池会学习并限制后台应用。"
                    )
                )
            )

            RomVendor.OTHER -> GuideContent(
                listOf(
                    GuideStep(
                        title = "第 1 步：允许后台运行",
                        path = "设置 → 应用 → 定位打卡 → 电池 → 不受限制 / 允许后台活动",
                        detail = "不同品牌路径略有差异，关键是找到「电池」或「后台限制」相关选项。"
                    ),
                    GuideStep(
                        title = "第 2 步：关闭电池优化",
                        path = "设置 → 电池 → 电池优化 → 所有应用 → 定位打卡 → 不优化",
                        detail = "或在应用信息页中查找「电池优化 / 省电策略」。"
                    ),
                    GuideStep(
                        title = "第 3 步：允许自启动（若系统提供）",
                        path = "设置 → 应用管理 → 定位打卡 → 允许自启动",
                        detail = "部分系统无此选项，跳过即可。"
                    )
                )
            )
        }
    }
}
