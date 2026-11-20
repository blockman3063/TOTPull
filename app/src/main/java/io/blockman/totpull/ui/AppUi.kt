package io.blockman.totpull.ui

import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point called from Java: binds the business-logic host and swaps the legacy
 * View-based layout for the Compose/miuix UI.
 */
fun AppCompatActivity.installUi(host: UiBridge.Host) {
    UiBridge.host = host
    setContent {
        TOTPullTheme { MainScreen() }
    }
}
