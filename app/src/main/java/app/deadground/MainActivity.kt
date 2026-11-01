package app.deadground

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
        setContent { App() }
    }
}

private const val STALE_MS = 30 * 60_000L

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("dg", Context.MODE_PRIVATE) }
    var json by remember { mutableStateOf(prefs.getString("json", null)) }
    var fetchedAt by remember { mutableLongStateOf(prefs.getLong("at", 0L)) }
    var status by remember { mutableStateOf<String?>(null) }
    var hasPerm by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var tab by remember { mutableStateOf("BRIEF") }
    val scope = rememberCoroutineScope()
    val brief = remember(json) { json?.let { runCatching { derive(OpenMeteo.parse(it)) }.getOrNull() } }

    fun refresh() {
        scope.launch {
            status = "ACQUIRING…"
            runCatching {
                val (lat, lon) = locate(ctx) ?: error("no fix")
                withContext(Dispatchers.IO) { OpenMeteo.fetch(lat, lon).also { OpenMeteo.parse(it) } }
            }.onSuccess {
                json = it
                fetchedAt = System.currentTimeMillis()
                prefs.edit().putString("json", it).putLong("at", fetchedAt).apply()
                status = null
            }.onFailure { status = "SIGNAL LOST · TAP TO RETRY" }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPerm = granted
        if (granted) refresh()
    }
    LaunchedEffect(Unit) {
        if (!hasPerm) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        else if (System.currentTimeMillis() - fetchedAt > STALE_MS) refresh()
    }

    Box(Modifier.fillMaxSize().background(Black)) {
        TerrainBackground(brief?.nowPrecip ?: Precip.NONE)
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T("DEAD GROUND", size = 18, spacing = 0.32f)
                T(brief?.stamp ?: "----L", Modifier.clickable { if (hasPerm) refresh() }, color = Dim)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.heightIn(min = 44.dp).border(1.dp, White).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { T(if (hasPerm) "AO ▸ CURRENT POS" else "AO ▸ LOCATION OFF", spacing = 0.12f) }
                T("GRID ±1 KM", color = Dim)
            }
            status?.let {
                T(it, Modifier.clickable { if (hasPerm) refresh() }, color = if (it.startsWith("SIGNAL")) Red else Dim)
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when {
                    tab != "BRIEF" -> T("$tab · NEXT BUILD", color = Dim)
                    brief != null -> BriefBody(brief)
                    !hasPerm -> T("GRANT LOCATION TO RECEIVE A BRIEF", Modifier.clickable {
                        permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }, color = Dim)
                    else -> T("AWAITING FIRST BRIEF", color = Dim)
                }
            }
            Nav(tab) { tab = it }
        }
    }
}
