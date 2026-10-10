package com.pdfstudio.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.pdfstudio.app.ui.AppTheme
import com.pdfstudio.app.ui.LibraryScreen
import com.pdfstudio.app.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                if (vm.openDoc != null) {
                    BackHandler { vm.closeDoc() }
                    ViewerScreen(vm)
                } else {
                    LibraryScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Handles "Open with" and "Share to" from email, file managers and cloud apps. */
    private fun handleIntent(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { vm.importAndOpen(it) }
    }
}
