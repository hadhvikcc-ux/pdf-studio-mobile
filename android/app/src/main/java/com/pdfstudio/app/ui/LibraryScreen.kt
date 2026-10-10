package com.pdfstudio.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pdfstudio.app.AppViewModel
import com.pdfstudio.app.R
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importAndOpen(it) }
    }
    val syncPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.setSyncFolder(it) }
    }
    val snack = remember { SnackbarHostState() }
    MessageEffect(vm, snack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library)) },
                actions = {
                    TextButton(onClick = { syncPicker.launch(null) }) { Text(stringResource(R.string.sync)) }
                    TextButton(onClick = { vm.syncPull() }) { Text(stringResource(R.string.sync_pull)) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                Text(stringResource(R.string.open_pdf))
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
            if (vm.docs.isEmpty()) {
                Text(
                    stringResource(R.string.empty_library),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(vm.docs, key = { it.id }) { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text(DateFormat.getDateTimeInstance().format(Date(d.opened))) },
                        trailingContent = {
                            TextButton(onClick = { vm.deleteDoc(d.id) }) { Text(stringResource(R.string.delete)) }
                        },
                        modifier = Modifier.clickable { vm.open(d) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Shows [AppViewModel.message] in the snackbar, formatted with the current locale. */
@Composable
fun MessageEffect(vm: AppViewModel, host: SnackbarHostState) {
    val msg = vm.message
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(msg) {
        if (msg != null) {
            val text = if (msg.raw != null) ctx.getString(msg.res, msg.raw) else ctx.getString(msg.res, *msg.args.toTypedArray())
            host.showSnackbar(text)
            vm.message = null
        }
    }
}
