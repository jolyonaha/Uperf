package app.uperf.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图标缓存与异步加载（对齐 LibChecker 策略：内存缓存键同步命中、占位符先行、无渐变）
 * - LruCache 限容 240 条，避免无限增长
 * - inflight 去重：同包名并发只建一个加载任务
 */
object IconCache {
    private const val MAX_ENTRIES = 240
    private val cache = android.util.LruCache<String, ImageBitmap>(MAX_ENTRIES)
    private val inflight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 同步读缓存（仅 Map 查询，无主线程解码），命中即零成本 */
    fun cached(pkg: String): ImageBitmap? = cache.get(pkg)

    suspend fun load(pkg: String, pm: PackageManager): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        if (!inflight.add(pkg)) {
            // 已有同包名任务在加载：轻量轮询等结果，不重复解码
            repeat(75) {
                kotlinx.coroutines.delay(40)
                cache.get(pkg)?.let { return it }
                if (!inflight.contains(pkg)) return null
            }
            return null
        }
        return try {
            val bmp = runCatching {
                pm.getApplicationIcon(pkg).toBitmap(88, 88).asImageBitmap()
            }.getOrNull()
            if (bmp != null) cache.put(pkg, bmp)
            bmp
        } finally {
            inflight.remove(pkg)
        }
    }
}

@Composable
fun rememberAppIcon(pkg: String): ImageBitmap? {
    val ctx = LocalContext.current
    // 缓存命中 → 初值直接可用，无协程、无占位帧；未命中才启动 IO 加载
    val state = produceState<ImageBitmap?>(IconCache.cached(pkg), pkg) {
        if (value == null) value = IconCache.load(pkg, ctx.packageManager)
    }
    return state.value
}

/** 系统默认强度振动 */
fun haptic(ctx: Context) {
    val vib = ctx.getSystemService(Vibrator::class.java) ?: return
    if (Build.VERSION.SDK_INT >= 26) {
        vib.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION") vib.vibrate(20)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { getSharedPreferences("uperf", MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(prefs.getString("theme", "auto") ?: "auto") }
            AppTheme(themeMode) {
                MainScreen(themeMode) { m ->
                    themeMode = m
                    prefs.edit().putString("theme", m).apply()
                }
            }
        }
    }
}

/* ---------------- 全局状态 ---------------- */
class UiState {
    var rootOk by mutableStateOf(true)
    var loading by mutableStateOf(true)
    var apps = mutableStateListOf<AppEntry>()
    var rules: SnapshotStateMap<String, String> = mutableStateMapOf()
    var curMode by mutableStateOf("auto")
    var offscreen by mutableStateOf("powersave")
    var fallback by mutableStateOf("balance")
    var logLevel by mutableStateOf("info")
    var modName by mutableStateOf("—")
    var modVer by mutableStateOf("—")
    var modAuthor by mutableStateOf("—")
    var running by mutableStateOf(false)
}

suspend fun reloadAll(s: UiState) = withContext(Dispatchers.IO) {
    s.rootOk = UperfRepo.checkRoot()
    val p = UperfRepo.getPerapp()
    withContext(Dispatchers.Main) {
        s.rules.clear(); s.rules.putAll(p.rules)
        s.offscreen = p.offscreen; s.fallback = p.fallback
        s.curMode = UperfRepo.getCurMode()
        s.logLevel = UperfRepo.getLogLevel()
        s.modName = UperfRepo.getModuleProp("name").ifBlank { "未知模块" }
        s.modVer = UperfRepo.getModuleProp("version").ifBlank { "—" }
        s.modAuthor = UperfRepo.getModuleProp("author").ifBlank { "—" }
        s.running = UperfRepo.isRunning()
    }
}

fun saveRules(s: UiState) {
    val p = UperfRepo.Perapp(LinkedHashMap(s.rules), s.offscreen, s.fallback)
    UperfRepo.savePerapp(p)
}

/* ---------------- 主框架 ---------------- */
@Composable
fun MainScreen(themeMode: String, onTheme: (String) -> Unit) {
    val u = LocalU.current
    val s = remember { UiState() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var subPage by rememberSaveable { mutableStateOf("") } // "", "log", "about"
    val titles = listOf("调度", "应用", "系统应用", "版本")

    suspend fun refreshApps() {
        val list = loadInstalledApps(ctx.packageManager)
        // 合并为一次快照写入，避免列表观察者被无效化两次
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            s.apps.clear(); s.apps.addAll(list)
        }
    }

    LaunchedEffect(Unit) {
        reloadAll(s)
        refreshApps()
        s.loading = false
    }

    // ROM「获取应用列表」授权后需重新查询；ON_RESUME 重载修复首启列表为空
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) scope.launch { refreshApps() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    Scaffold(containerColor = u.bg, bottomBar = {
        Surface(color = u.bg) {
            Column {
                HorizontalDivider(color = u.outline, thickness = 1.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    val items = listOf(
                        Icons.Outlined.FlashOn to "调度",
                        Icons.Outlined.Apps to "应用",
                        Icons.Outlined.Android to "系统应用",
                        Icons.Outlined.Settings to "版本"
                    )
                    items.forEachIndexed { i, (icon, label) ->
                        val on = tab == i && subPage.isEmpty()
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { haptic(ctx); tab = i; subPage = "" }
                                .padding(vertical = 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(icon, label, tint = if (on) u.text else u.text3,
                                modifier = Modifier.size(21.dp))
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                color = if (on) u.text else u.text3,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = pad.calculateBottomPadding())
        ) {
            // 顶栏：左上角日志入口；子页面显示返回
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 6.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    subPage.isNotEmpty() -> IconButton(onClick = { haptic(ctx); subPage = "" }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = u.text)
                    }
                    else -> IconButton(onClick = { haptic(ctx); subPage = "log" }) {
                        Icon(Icons.AutoMirrored.Outlined.Article, "日志", tint = u.text)
                    }
                }
                Text(
                    when (subPage) {
                        "log" -> "日志"
                        "about" -> "关于 Uperf"
                        else -> titles[tab]
                    },
                    fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp
                )
            }

            if (!s.rootOk) {
                Surface(
                    color = u.surface, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        "未获取 Root 权限，配置修改将无法生效",
                        fontSize = 12.sp, color = u.text2, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                    )
                }
            }

            when {
                subPage == "log" -> LogScreen(s, ::toast)
                subPage == "about" -> AboutScreen(::toast)
                tab == 0 -> PerfScreen(s, ::toast)
                tab == 1 -> AppsScreen(s, systemTab = false, ::toast)
                tab == 2 -> AppsScreen(s, systemTab = true, ::toast)
                else -> SettingsScreen(s, themeMode, onTheme, ::toast) { subPage = "about" }
            }
        }
    }
}

/* ---------------- 通用组件 ---------------- */
@Composable
fun ModeSegment(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val u = LocalU.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(u.surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) u.bg else Color.Transparent)
                    .clickable { onSelect(v) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (on) u.text else u.text2)
            }
        }
    }
}

@Composable
fun CardBlock(title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    val u = LocalU.current
    Surface(
        color = u.surface, shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 7.dp)
            .fillMaxWidth()
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = u.text)
            if (desc.isNotEmpty()) {
                Text(desc, fontSize = 12.sp, color = u.text3, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
            } else Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/* ---------------- 应用列表项（独立重组作用域） ---------------- */
@Composable
private fun AppListItem(app: AppEntry, mode: String?, onClick: () -> Unit) {
    val u = LocalU.current
    Surface(
        color = u.surface, shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = rememberAppIcon(app.pkg)
            if (icon != null) {
                Image(icon, null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp)))
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(u.surface2)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = u.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.pkg, fontSize = 10.sp, color = u.text3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                color = if (mode != null) u.text else u.surface2,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    mode?.let { UperfRepo.MODE_CN[it] ?: it } ?: "默认",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = if (mode != null) u.bg else u.text2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/* ---------------- 应用页 ---------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(s: UiState, systemTab: Boolean, toast: (String) -> Unit) {
    val u = LocalU.current
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var sheetPkg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 单次遍历完成 过滤+置顶分组；derivedStateOf 缓存结果，
    // 仅在 s.apps / s.rules / query 变化时重算，无关重组零成本
    val ordered by remember(systemTab) {
        derivedStateOf {
            val q = query
            val pinned = ArrayList<AppEntry>()
            val rest = ArrayList<AppEntry>()
            for (a in s.apps) {
                if (a.isSystem != systemTab) continue
                if (q.isNotBlank() && !a.label.contains(q, true) && !a.pkg.contains(q, true)) continue
                if (s.rules.containsKey(a.pkg)) pinned.add(a) else rest.add(a)
            }
            pinned + rest
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 搜索
        Row(
            Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(u.surface)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Search, null, tint = u.text3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = u.text),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("搜索应用或包名…", fontSize = 14.sp, color = u.text3)
                    inner()
                }
            )
            if (query.isNotEmpty()) {
                Icon(Icons.Outlined.Close, null, tint = u.text3,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { query = "" })
            }
        }

        Row(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 8.dp)
        ) {
            Text(if (systemTab) "系统应用" else "用户应用", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = u.text2)
            Spacer(Modifier.weight(1f))
            Text("${ordered.size} 个", fontSize = 12.sp, color = u.text3)
        }

        when {
            s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = u.text)
            }
            ordered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有匹配的应用", fontSize = 14.sp, color = u.text3)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ordered, key = { it.pkg }, contentType = { "app" }) { app ->
                    // 在 item 作用域读取 SnapshotStateMap：仅该应用的模式变化时只重组本 item
                    AppListItem(
                        app = app,
                        mode = s.rules[app.pkg],
                        onClick = { haptic(ctx); sheetPkg = app.pkg }
                    )
                }
            }
        }
    }

    // 模式选择底部弹层
    sheetPkg?.let { pkg ->
        val app = s.apps.firstOrNull { it.pkg == pkg }
        ModalBottomSheet(
            onDismissRequest = { sheetPkg = null },
            containerColor = u.bg,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 34.dp)
            ) {
                Text(app?.label ?: pkg, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = u.text)
                Text(pkg, fontSize = 11.sp, color = u.text3,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                val cur = s.rules[pkg] ?: ""
                listOf(
                    "" to ("跟随默认" to "使用默认调度规则"),
                    "powersave" to ("省电" to "限制性能，延长续航"),
                    "balance" to ("均衡" to "日常使用推荐"),
                    "performance" to ("性能" to "游戏、重负载场景"),
                    "fast" to ("极速" to "释放全部性能")
                ).forEach { (v, info) ->
                    val (t, d) = info
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                haptic(ctx)
                                if (v.isEmpty()) s.rules.remove(pkg) else s.rules[pkg] = v
                                sheetPkg = null
                                scope.launch(Dispatchers.IO) { saveRules(s) }
                                toast("已保存")
                            }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = cur == v, onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = u.text, unselectedColor = u.outline
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(t, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = u.text)
                        Spacer(Modifier.weight(1f))
                        Text(d, fontSize = 10.sp, color = u.text3)
                    }
                }
            }
        }
    }
}

/* ---------------- 调度页 ---------------- */
@Composable
fun PerfScreen(s: UiState, toast: (String) -> Unit) {
    val u = LocalU.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val modes = listOf("powersave" to "省电", "balance" to "均衡", "performance" to "性能", "fast" to "极速")

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        item {
            Surface(
                color = u.surface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 7.dp)
                    .fillMaxWidth()
            ) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(u.surface2),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.FlashOn, null, tint = u.text,
                            modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text(
                            UperfRepo.MODE_CN[s.curMode] ?: s.curMode,
                            fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = u.text
                        )
                        Text(
                            if (s.running) "服务运行中 · ${UperfRepo.USER_PATH}" else "服务未运行",
                            fontSize = 11.sp, color = u.text3, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
        item {
            CardBlock("全局性能模式", "「动态」按应用规则自动调度；其余模式强制全局生效") {
                ModeSegment(listOf("auto" to "动态") + modes, s.curMode) { v ->
                    if (v == s.curMode) return@ModeSegment
                    haptic(ctx)
                    s.curMode = v
                    scope.launch(Dispatchers.IO) { UperfRepo.setCurMode(v) }
                    toast("全局模式：${UperfRepo.MODE_CN[v]}")
                }
            }
        }
        // 仅「动态」时展开息屏/默认调度
        item {
            AnimatedVisibility(
                visible = s.curMode == "auto",
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    CardBlock("息屏调度", "屏幕关闭时使用的模式（perapp 规则「-」）") {
                        ModeSegment(modes, s.offscreen) { v ->
                            if (v == s.offscreen) return@ModeSegment
                            haptic(ctx)
                            s.offscreen = v
                            scope.launch(Dispatchers.IO) { saveRules(s) }
                            toast("已保存")
                        }
                    }
                    CardBlock("默认调度", "未配置应用的前台默认模式（perapp 规则「*」）") {
                        ModeSegment(modes, s.fallback) { v ->
                            if (v == s.fallback) return@ModeSegment
                            haptic(ctx)
                            s.fallback = v
                            scope.launch(Dispatchers.IO) { saveRules(s) }
                            toast("已保存")
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 日志页 ---------------- */
@Composable
fun LogScreen(s: UiState, toast: (String) -> Unit) {
    val u = LocalU.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf("加载中…") }

    LaunchedEffect(Unit) {
        logText = withContext(Dispatchers.IO) { UperfRepo.getLog() }.ifBlank { "（日志为空）" }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        item {
            CardBlock("日志级别", "DEBUG 会显著增加日志体积，排查问题时再开启") {
                ModeSegment(
                    listOf("info" to "INFO", "debug" to "DEBUG", "error" to "ERROR"),
                    s.logLevel
                ) { v ->
                    if (v == s.logLevel) return@ModeSegment
                    haptic(ctx)
                    s.logLevel = v
                    scope.launch(Dispatchers.IO) { UperfRepo.setLogLevel(v) }
                    toast("日志级别：${v.uppercase()}（重启服务后生效）")
                }
            }
        }
        item {
            CardBlock("运行日志", "") {
                Surface(color = u.surface2, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        logText, fontSize = 10.sp, color = u.text, lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

/* ---------------- 设置页 ---------------- */
@Composable
fun SettingsScreen(
    s: UiState, themeMode: String, onTheme: (String) -> Unit,
    toast: (String) -> Unit, onAbout: () -> Unit
) {
    val u = LocalU.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showUninstall by remember { mutableStateOf(false) }
    var showReboot by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        item {
            CardBlock("主题", "深色模式下界面整体反色") {
                ModeSegment(
                    listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                    themeMode
                ) { v ->
                    if (v == themeMode) return@ModeSegment
                    haptic(ctx)
                    onTheme(v)
                }
            }
        }
        item {
            CardBlock("模块信息", "") {
                InfoRow("模块名称", s.modName)
                InfoRow("版本", s.modVer)
                InfoRow("作者", s.modAuthor)
                InfoRow("运行状态", if (s.running) "运行中" else "未运行")
            }
        }
        item {
            CardBlock("操作", "修改配置后一般即时生效；异常时可重启调度服务") {
                Button(
                    onClick = {
                        haptic(ctx)
                        scope.launch(Dispatchers.IO) { UperfRepo.restart() }
                        toast("已重启 Uperf 服务")
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = u.surface2, contentColor = u.text),
                    elevation = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) { Text("重启 Uperf 服务", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { haptic(ctx); showUninstall = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = u.text),
                    border = BorderStroke(1.5.dp, u.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) { Text("卸载模块（重启后生效）", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { haptic(ctx); showReboot = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = u.text),
                    border = BorderStroke(1.5.dp, u.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) { Text("重启手机", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
        item {
            // 关于入口
            Surface(
                color = u.surface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 7.dp)
                    .fillMaxWidth()
                    .clickable { haptic(ctx); onAbout() }
            ) {
                Row(
                    Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Info, null, tint = u.text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("关于 Uperf", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = u.text, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = u.text3)
                }
            }
        }
    }

    if (showUninstall) {
        AlertDialog(
            onDismissRequest = { showUninstall = false },
            title = { Text("卸载模块", fontWeight = FontWeight.Bold) },
            text = { Text("将标记该调度模块为待卸载，重启手机后移除。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showUninstall = false
                    scope.launch(Dispatchers.IO) { UperfRepo.uninstall() }
                    toast("已标记卸载，重启后生效")
                }) { Text("卸载", color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showUninstall = false }) { Text("取消") }
            },
            containerColor = u.bg,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showReboot) {
        AlertDialog(
            onDismissRequest = { showReboot = false },
            title = { Text("重启手机", fontWeight = FontWeight.Bold) },
            text = { Text("设备将立即重启，确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showReboot = false
                    scope.launch(Dispatchers.IO) { UperfRepo.reboot() }
                }) { Text("重启", color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showReboot = false }) { Text("取消") }
            },
            containerColor = u.bg,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

/* ---------------- 关于页 ---------------- */
private const val PRIVACY_TEXT =
    "Uperf 是一款完全离线的本地调度管理工具。\n\n" +
            "· 不联网：应用未申请任何网络权限，没有任何数据上传行为。\n" +
            "· 不收集：不收集、不存储、不共享任何个人信息或使用数据。\n" +
            "· 本地化：所有配置仅保存在本机 /sdcard/Android/yc/uperf 目录，卸载模块即彻底清除。\n\n" +
            "如您对本政策有任何疑问，可通过作者主页联系。"

private const val LICENSE_TEXT =
    "Uperf 管理器\n以 Apache License 2.0 协议开源。\n\n" +
            "调度核心 uperf 由 Matt Yang 开发，遵循 Apache License 2.0。\n\n" +
            "本应用使用的开源组件：\n" +
            "· libsu（topjohnwu，Apache 2.0）\n" +
            "· Jetpack Compose（Google，Apache 2.0）\n\n" +
            "Apache License 2.0 全文：\nhttps://www.apache.org/licenses/LICENSE-2.0"

@Composable
fun AboutScreen(toast: (String) -> Unit) {
    val u = LocalU.current
    val ctx = LocalContext.current
    var dialogText by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher), null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
                Text("Uperf", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = u.text,
                    modifier = Modifier.padding(top = 14.dp))
                Text("v${BuildConfig.VERSION_NAME} · 本地调度管理", fontSize = 12.sp,
                    color = u.text3, modifier = Modifier.padding(top = 5.dp))
            }
        }
        item {
            Surface(
                color = u.surface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 7.dp)
                    .fillMaxWidth()
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    AboutRow(Icons.Outlined.PrivacyTip, "隐私政策", "完全离线，不收集任何信息") {
                        haptic(ctx); dialogText = "隐私政策" to PRIVACY_TEXT
                    }
                    AboutRow(Icons.Outlined.Gavel, "开源许可", "Apache License 2.0") {
                        haptic(ctx); dialogText = "开源许可" to LICENSE_TEXT
                    }
                    AboutRow(Icons.Outlined.Person, "作者主页", "酷安 @张译") {
                        haptic(ctx)
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("coolmarket://u/1033114"))
                                    .setPackage("com.coolapk.market")
                            )
                        } catch (e: Exception) {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://www.coolapk.com/u/1033114"))
                            )
                        }
                    }
                    AboutRow(Icons.Outlined.Code, "GitHub", "敬请期待", enabled = false) {
                        toast("暂未开放，敬请期待")
                    }
                }
            }
        }
    }

    dialogText?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                Text(text, fontSize = 13.sp, lineHeight = 21.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { dialogText = null }) { Text("知道了") }
            },
            containerColor = u.bg,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun AboutRow(
    icon: ImageVector, title: String, sub: String,
    enabled: Boolean = true, onClick: () -> Unit
) {
    val u = LocalU.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) u.text else u.text3,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) u.text else u.text3)
            Text(sub, fontSize = 11.sp, color = u.text3, modifier = Modifier.padding(top = 2.dp))
        }
        if (enabled) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = u.text3)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    val u = LocalU.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = u.text)
            Text(value, fontSize = 12.sp, color = u.text2, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp))
        }
        HorizontalDivider(color = u.outline, thickness = 1.dp)
    }
}
