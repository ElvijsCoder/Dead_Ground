package app.deadground

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Alerts are never worth a crash on launch: if scheduling fails, record it and open anyway.
        runCatching { AlertJob.schedule(this) }.onFailure { recordFault(this, it) }
        val start = intent.getStringExtra("tab") ?: "BRIEF" // notification actions open a tab
        setContent {
            var fault by remember { mutableStateOf(readFault(this)) }
            val trace = fault
            if (trace != null) {
                FaultScreen(trace) {
                    clearFault(this)
                    fault = null
                }
            } else App(start)
        }
    }
}

private const val STALE_MS = 30 * 60_000L

@Composable
fun App(startTab: String = "BRIEF") {
    val ctx = LocalContext.current
    val store = remember { prefs(ctx) }
    var json by remember { mutableStateOf(store.getString("json", null)) }
    var fetchedAt by remember { mutableLongStateOf(store.getLong("at", 0L)) }
    var status by remember { mutableStateOf<String?>(null) }
    var hasPerm by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var tab by remember { mutableStateOf(startTab) }
    var warnings by remember { mutableStateOf(loadWarnings(ctx)) }
    var aos by remember { mutableStateOf(loadAos(ctx)) }
    var active by remember { mutableIntStateOf(store.getInt("active", -1).takeIf { it < aos.size } ?: -1) }
    var roster by remember { mutableStateOf<List<Brief?>>(emptyList()) }
    var radar by remember { mutableStateOf<Scope?>(null) }
    var radarNote by remember { mutableStateOf<String?>(null) }
    var radarAt by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val forecast = remember(json) { json?.let { runCatching { OpenMeteo.parse(it) }.getOrNull() } }
    val brief = remember(forecast, warnings) {
        forecast?.let { runCatching { applyWarnings(derive(it), warnings, System.currentTimeMillis()) }.getOrNull() }
    }
    val days = remember(forecast) { forecast?.let { runCatching { week(it) }.getOrNull() }.orEmpty() }
    var hereBrief by remember { mutableStateOf(if (active == -1) brief else null) }

    fun refresh() {
        scope.launch {
            status = "ACQUIRING…"
            runCatching {
                val (lat, lon) = aos.getOrNull(active)?.let { it.lat to it.lon } ?: locate(ctx) ?: error("no fix")
                // Alerts follow the last CURRENT POS fix, whatever AO the screen shows.
                if (active == -1) store.edit().putString("here_lat", "$lat").putString("here_lon", "$lon").apply()
                withContext(Dispatchers.IO) {
                    runCatching { saveWarnings(ctx, OpenMeteo.fetchWarnings()) }
                    fetchAndStore(ctx, lat, lon).also { DeadGroundWidget.renderAll(ctx) }
                }
            }.onSuccess {
                json = it
                warnings = loadWarnings(ctx)
                fetchedAt = store.getLong("at", 0L)
                if (active == -1) hereBrief = runCatching { derive(OpenMeteo.parse(it)) }.getOrNull()
                status = null
            }.onFailure { status = "SIGNAL LOST · TAP TO RETRY" }
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPerm = granted
        if (granted) refresh()
        askNotifications() // one dialog at a time: alerts permission right after location
    }
    LaunchedEffect(Unit) {
        if (!hasPerm && active == -1) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        else {
            askNotifications()
            if (System.currentTimeMillis() - fetchedAt > STALE_MS) refresh()
        }
    }
    // Roster temps for every saved AO, in one request, only while the AO tab is open.
    LaunchedEffect(tab, aos) {
        if (tab == "AO" && aos.isNotEmpty()) {
            roster = withContext(Dispatchers.IO) {
                runCatching { OpenMeteo.fetchMany(aos).map { f -> runCatching { derive(f) }.getOrNull() } }
                    .getOrElse { aos.map { null } }
            }
        }
    }
    // Model scope around the active AO, only while the RADAR tab is open; reused for 15 min.
    LaunchedEffect(tab, active) {
        if (tab != "RADAR") return@LaunchedEffect
        val centre = aos.getOrNull(active)?.let { it.lat to it.lon }
            ?: store.getString("lat", null)?.toDoubleOrNull()?.let { lat ->
                store.getString("lon", null)?.toDoubleOrNull()?.let { lat to it }
            }
        if (centre == null) {
            radarNote = "NO AO FIX YET · OPEN BRIEF FIRST"
            return@LaunchedEffect
        }
        if (radar != null && System.currentTimeMillis() - radarAt < 15 * 60_000L) return@LaunchedEffect
        radarNote = "SCANNING…"
        radar = withContext(Dispatchers.IO) { runCatching { OpenMeteo.fetchScope(centre.first, centre.second) }.getOrNull() }
        radarAt = System.currentTimeMillis()
        radarNote = if (radar == null) "SCOPE OFFLINE · REOPEN TAB TO RETRY" else null
    }
    fun select(i: Int) {
        active = i
        store.edit().putInt("active", i).apply()
        tab = "BRIEF"
        radar = null // new AO, new scope
        if (i == -1 && !hasPerm) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) else refresh()
    }

    Box(Modifier.fillMaxSize().background(Black)) {
        TerrainBackground(brief?.nowPrecip ?: Precip.NONE)
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T("DEAD GROUND", size = 18, spacing = 0.32f)
                T(brief?.stamp ?: "----L", Modifier.clickable { if (hasPerm || active >= 0) refresh() }, color = Dim)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val aoName = aos.getOrNull(active)?.name?.substringBefore(",")
                    ?: if (hasPerm) "CURRENT POS" else "LOCATION OFF"
                Box(
                    Modifier.heightIn(min = 44.dp).border(1.dp, White).clickable { tab = "AO" }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { T("AO ▸ $aoName", spacing = 0.12f) }
                T("GRID ±1 KM", color = Dim)
            }
            status?.let {
                T(it, Modifier.clickable { if (hasPerm || active >= 0) refresh() }, color = if (it.startsWith("SIGNAL")) Red else Dim)
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when {
                    tab == "7 DAY" -> WeekBody(days)
                    tab == "AO" -> AoBody(
                        aos, active, hereBrief, roster,
                        onSelect = ::select,
                        onAdd = { p ->
                            aos = aos + p
                            saveAos(ctx, aos)
                        },
                        onRemove = { i ->
                            aos = aos.filterIndexed { j, _ -> j != i }
                            saveAos(ctx, aos)
                            roster = roster.filterIndexed { j, _ -> j != i }
                            if (active == i) select(-1) else if (active > i) {
                                active -= 1
                                store.edit().putInt("active", active).apply()
                            }
                        },
                        search = { q -> withContext(Dispatchers.IO) { OpenMeteo.search(q) } },
                    )
                    tab == "RADAR" -> RadarBody(radar, radarNote)
                    brief != null -> BriefBody(brief)
                    !hasPerm && active == -1 -> T("GRANT LOCATION TO RECEIVE A BRIEF", Modifier.clickable {
                        permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }, color = Dim)
                    else -> T("AWAITING FIRST BRIEF", color = Dim)
                }
            }
            Nav(tab) { tab = it }
        }
    }
}
