package app.deadground

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/** Any uncaught crash is written to a file first, so the next launch can show it. */
class DeadGroundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            recordFault(this, e)
            previous?.uncaughtException(t, e)
        }
    }
}

private fun faultFile(ctx: Context) = File(ctx.filesDir, "fault.txt")

fun recordFault(ctx: Context, e: Throwable) {
    runCatching { faultFile(ctx).writeText(Log.getStackTraceString(e)) }
}

fun readFault(ctx: Context): String? = faultFile(ctx).takeIf { it.exists() }?.readText()

fun clearFault(ctx: Context) {
    faultFile(ctx).delete()
}

@Composable
fun FaultScreen(trace: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Black).windowInsetsPadding(WindowInsets.systemBars).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        T("SYSTEM FAULT", size = 18, color = Red, spacing = 0.32f)
        T("LAST RUN CRASHED. COPY THIS AND SEND IT.", color = Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.height(44.dp).background(White).clickable {
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Dead Ground fault", trace))
                }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { T("COPY", color = Black) }
            Box(
                Modifier.height(44.dp).border(1.dp, White).clickable(onClick = onDismiss).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { T("DISMISS") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            T(trace, size = 11, color = Dim, spacing = 0f)
        }
    }
}
