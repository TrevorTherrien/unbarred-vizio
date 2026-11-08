package io.github.trevortherrien.unbarred

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * First-run legal gate. The accepted flag is a one-time acknowledgement, not cached device state
 * (app preferences live in [AppSettings]). Bump [KEY] if the text changes materially so users
 * re-confirm. SharedPreferences keeps it dependency-free.
 */
object Disclaimer {
    private const val PREFS = "unbarred_prefs"
    const val KEY = "disclaimer_accepted_v1"

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setAccepted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }

    const val TITLE = "Unbarred — unofficial controller for VIZIO® sound bars"

    val PARAGRAPHS = listOf(
        "Independent & unofficial" to
            "This is an independent, open-source controller. It is not made, endorsed, supported, or " +
            "affiliated with VIZIO. \"VIZIO\", \"Elevate\", model names and related marks are trademarks " +
            "of their respective owners, used here only to identify compatible hardware.",
        "Interoperability / right to repair" to
            "The app communicates with your sound bar over its own Bluetooth Low Energy interface, " +
            "reverse engineered by an independent developer, with AI assistance, for interoperability. " +
            "It uses no VIZIO account, " +
            "server, or application code.",
        "No warranty" to
            "This software is provided \"as is\", without warranty of any kind. You use it at your own " +
            "risk. The authors are not liable for any damage, loss of functionality, or other harm to " +
            "your device or data.",
        "Changes your settings" to
            "Commands sent over Bluetooth change settings on your sound bar. Some values and combinations " +
            "are untested, and behaviour varies by model and firmware.",
    )
}

/** The disclaimer text, reused by the first-run gate and the Settings “About” section. */
@Composable
fun DisclaimerBody(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Disclaimer.PARAGRAPHS.forEach { (heading, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisclaimerScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Before you start", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(Disclaimer.TITLE, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            DisclaimerBody(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "By continuing you acknowledge that you understand and accept the above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDecline) { Text("Decline") }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("I understand & agree") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
