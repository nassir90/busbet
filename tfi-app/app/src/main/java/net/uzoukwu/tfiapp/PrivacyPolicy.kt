package net.uzoukwu.tfiapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Google Play requires the privacy policy to be reachable both from the Play listing and from
 * inside the app. This is the in-app copy; keep it in step with the hosted version.
 */
const val PRIVACY_POLICY_LAST_UPDATED = "23 July 2026"

/**
 * Written against what the app actually does, verified in the source rather than assumed. If any
 * of the following changes, this text and the Play Data safety declaration both need updating:
 *  - location leaving the device (today it never does)
 *  - anything identifying being attached to a report
 *  - crash reporting being enabled in release builds
 */
// Paragraphs are single logical lines. Hard-wrapping the source would render as literal line
// breaks mid-sentence — the text must reflow to whatever width the device gives it.
val PRIVACY_POLICY_BODY = listOf(
    "Iompar shows live Dublin bus departures using open transport data. This policy describes what the app does with your information.",

    "WHAT THIS APP DOES NOT DO",
    "The app has no accounts and no sign-in. It does not ask for your name, email or phone number. It contains no advertising and no analytics or tracking SDKs.",

    "LOCATION",
    "If you enable location sorting, the app reads your device location to order nearby stops by distance and to show the closest favourite in the home screen widget.",
    "Your location is used entirely on your device. It is never transmitted anywhere. Turning off location sorting in Settings stops the app reading it.",
    "The home screen widget reads your location while the app is not open, so that it can show the nearest stop without you launching the app. This only happens if location sorting is enabled.",

    "INFORMATION STORED ON YOUR DEVICE",
    "Your favourite stops, theme, notification schedules, recent searches and settings are stored on your device only. Uninstalling the app removes them.",

    "SERVERS AND NETWORK",
    "The app talks to the backend servers, which supply timetable and live departure data. They are hosted in Germany and reached over an encrypted connection through Cloudflare, which acts as a network provider for the connection.",
    "The backend servers do not log the IP addresses of requests.",
    "Advanced users can point the app at a different backend server in Settings. If you do that, the data described above goes to whoever operates that server, and this policy no longer governs it.",

    "ON-DEVICE SERVER",
    "The app can optionally run a server on your device so that tools on your own network can read and change its data. It is off by default. When enabled it has no authentication, so anyone who can reach your device on the chosen network can use it. Only enable it on networks you trust.",

    "CHILDREN",
    "The app is not directed at children and does not knowingly collect information from them.",

    "CHANGES",
    "If this policy changes, the updated version will appear here and in the app listing.",

    "CONTACT",
    "Questions about this policy: uzoukwuc@tcd.ie",
).joinToString("\n\n")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy policy") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Last updated $PRIVACY_POLICY_LAST_UPDATED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(PRIVACY_POLICY_BODY, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
        }
    }
}
