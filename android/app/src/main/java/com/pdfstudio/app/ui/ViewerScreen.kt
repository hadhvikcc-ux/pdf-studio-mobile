package com.pdfstudio.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pdfstudio.app.AppViewModel
import com.pdfstudio.app.R
import com.pdfstudio.app.ToolOp
import com.pdfstudio.app.data.Annot
import com.pdfstudio.app.data.Tool
import com.pdfstudio.app.pdf.PdfSession
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class Dlg { None, Search, Navigate, Tools, Versions, Sync }

private const val INK = 0xC62828
private const val HIGHLIGHT = 0xFFEB3B

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(vm: AppViewModel) {
    val doc = vm.openDoc ?: return
    val session = vm.session
    val snack = remember { SnackbarHostState() }
    MessageEffect(vm, snack)

    var tool by remember { mutableStateOf(Tool.None) }
    var dlg by remember { mutableStateOf(Dlg.None) }
    var input by remember { mutableStateOf<ToolOp?>(null) }
    var menu by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.mergeWith(it) }
    }
    val syncPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.setSyncFolder(it) }
    }

    fun goTo(p: Int) { scope.launch { listState.scrollToItem(p) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeDoc() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { dlg = Dlg.Search }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.tools))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val marked = page in vm.bookmarks
                        DropdownMenuItem(
                            text = { Text(stringResource(if (marked) R.string.remove_bookmark else R.string.add_bookmark)) },
                            onClick = { menu = false; vm.toggleBookmark(page) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.thumbnails) + " / " + stringResource(R.string.bookmarks)) },
                            onClick = { menu = false; dlg = Dlg.Navigate }
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.tools)) }, onClick = { menu = false; dlg = Dlg.Tools })
                        DropdownMenuItem(text = { Text(stringResource(R.string.versions)) }, onClick = { menu = false; dlg = Dlg.Versions })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sync)) }, onClick = { menu = false; dlg = Dlg.Sync })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.night_mode)) },
                            onClick = { menu = false; night = !night }
                        )
                    }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(tool == Tool.None, { tool = Tool.None }, { Text(stringResource(R.string.tool_none)) })
                FilterChip(tool == Tool.Ink, { tool = Tool.Ink }, { Text(stringResource(R.string.tool_ink)) })
                FilterChip(tool == Tool.Highlight, { tool = Tool.Highlight }, { Text(stringResource(R.string.tool_highlight)) })
                if (vm.annots.isNotEmpty()) {
                    TextButton(onClick = { vm.undoAnnot() }) { Text(stringResource(R.string.undo)) }
                    TextButton(onClick = { vm.saveAnnotations() }) { Text(stringResource(R.string.save_annotations)) }
                }
                Box(Modifier.weight(1f))
                if (session != null) {
                    Text(stringResource(R.string.page_of, page + 1, session.pageCount), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().clipToBounds()) {
            val baseWpx = with(LocalDensity.current) { maxWidth.toPx() }
            val zoomState = rememberTransformableState { zoom, pan, _ ->
                val old = scale
                scale = (scale * zoom).coerceIn(1f, 5f)
                val ratio = scale / old
                offX = ((offX - baseWpx / 2) * ratio + baseWpx / 2 + pan.x).coerceIn(-(baseWpx * (scale - 1f)), 0f)
            }
            if (vm.busy || (session == null && !vm.needsPassword)) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    vm.progress?.let { (done, total) ->
                        Text(stringResource(R.string.progress_pages, minOf(done + 1, total), total), Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (session != null) {
                // Re-render only at coarse zoom steps so pinching stays smooth.
                val step = ceil(scale * 2f) / 2f
                val renderW = (baseWpx * step).roundToInt().coerceIn(200, 3200)
                LazyColumn(
                    state = listState,
                    userScrollEnabled = tool == Tool.None,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = zoomState, canPan = { scale > 1f }, enabled = tool == Tool.None)
                        .pointerInput(tool) {
                            if (tool == Tool.None) detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1.2f) 1f else 2f
                                offX = if (scale == 1f) 0f else -(baseWpx / 2)
                            })
                        }
                ) {
                    itemsIndexed(List(session.pageCount) { it }, key = { _, i -> "${vm.version}-$i" }) { _, i ->
                        PageView(
                            session = session, index = i, renderWidthPx = renderW,
                            widthPx = baseWpx * scale, offsetX = offX, tool = tool, vm = vm, night = night,
                            aspect = session.aspect
                        )
                    }
                }
            }
        }
    }

    if (vm.needsPassword) PasswordDialog(onUnlock = { vm.unlock(it) }, onCancel = { vm.closeDoc() })

    when (dlg) {
        Dlg.None -> Unit
        Dlg.Search -> SearchDialog(vm, onGo = { goTo(it); dlg = Dlg.None }, onClose = { dlg = Dlg.None })
        Dlg.Navigate -> if (session != null) NavigateDialog(vm, session, onGo = { goTo(it); dlg = Dlg.None }, onClose = { dlg = Dlg.None })
        Dlg.Tools -> AlertDialog(
            onDismissRequest = { dlg = Dlg.None },
            confirmButton = { TextButton(onClick = { dlg = Dlg.None }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.tools)) },
            text = {
                Column {
                    TextButton(onClick = { dlg = Dlg.None; mergePicker.launch(arrayOf("application/pdf")) }) { Text(stringResource(R.string.merge)) }
                    TextButton(onClick = { dlg = Dlg.None; input = ToolOp.Split }) { Text(stringResource(R.string.split)) }
                    TextButton(onClick = { dlg = Dlg.None; input = ToolOp.Reorder }) { Text(stringResource(R.string.reorder)) }
                    TextButton(onClick = { dlg = Dlg.None; input = ToolOp.Watermark }) { Text(stringResource(R.string.watermark)) }
                    TextButton(onClick = { dlg = Dlg.None; input = ToolOp.Protect }) { Text(stringResource(R.string.protect)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.runOcr() }) { Text(stringResource(R.string.ocr)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.exportPdfA(true) }) { Text(stringResource(R.string.export_pdfa)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.sharePdf() }) { Text(stringResource(R.string.share_pdf)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.sharePagePng(page) }) { Text(stringResource(R.string.share_png)) }
                }
            }
        )
        Dlg.Versions -> AlertDialog(
            onDismissRequest = { dlg = Dlg.None },
            confirmButton = { TextButton(onClick = { dlg = Dlg.None }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.versions)) },
            text = {
                val all = vm.versionList()
                LazyColumn {
                    itemsIndexed(all.reversed()) { idx, v ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.version_n, v) + if (idx == 0) " (" + stringResource(R.string.current) + ")" else "",
                                Modifier.weight(1f)
                            )
                            if (idx != 0) TextButton(onClick = { dlg = Dlg.None; vm.restore(v) }) { Text(stringResource(R.string.restore)) }
                        }
                    }
                }
            }
        )
        Dlg.Sync -> AlertDialog(
            onDismissRequest = { dlg = Dlg.None },
            confirmButton = { TextButton(onClick = { dlg = Dlg.None }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.sync)) },
            text = {
                Column {
                    if (!vm.hasSyncFolder()) Text(stringResource(R.string.sync_none))
                    TextButton(onClick = { syncPicker.launch(null) }) { Text(stringResource(R.string.sync_choose_folder)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.syncPush() }) { Text(stringResource(R.string.sync_push)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.syncPull() }) { Text(stringResource(R.string.sync_pull)) }
                }
            }
        )
    }

    input?.let { op ->
        TextInputDialog(
            op = op,
            onApply = { text -> input = null; vm.runTool(op, text) },
            onCancel = { input = null }
        )
    }
}

@Composable
private fun PageView(
    session: PdfSession, index: Int, renderWidthPx: Int, widthPx: Float, offsetX: Float,
    tool: Tool, vm: AppViewModel, night: Boolean, aspect: Float
) {
    val density = LocalDensity.current
    var bmp by remember(session, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, index, renderWidthPx) {
        runCatching { session.render(index, renderWidthPx) }.getOrNull()?.let { bmp = it }
    }
    var live by remember { mutableStateOf<List<Float>>(emptyList()) }
    val invert = ColorFilter.colorMatrix(
        ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    )
    val pageDesc = stringResource(R.string.page_of, index + 1, session.pageCount)

    Box(
        Modifier
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .width(with(density) { widthPx.toDp() })
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .padding(vertical = 2.dp)
            .semantics { contentDescription = pageDesc }
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(), contentDescription = pageDesc,
                modifier = Modifier.fillMaxWidth(), colorFilter = if (night) invert else null
            )
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = (widthPx * aspect / 2 / density.density).dp))
        }
        val mine = vm.annots.filter { it.page == index }
        Canvas(
            Modifier.matchParentSize().pointerInput(tool, index) {
                if (tool == Tool.None) return@pointerInput
                detectDragGestures(
                    onDragStart = { o -> live = listOf(o.x / size.width, o.y / size.height) },
                    onDrag = { change, _ ->
                        change.consume()
                        live = live + listOf(change.position.x / size.width, change.position.y / size.height)
                    },
                    onDragEnd = {
                        if (live.size >= 4) {
                            val pts = if (tool == Tool.Highlight) listOf(live[0], live[1], live[live.size - 2], live[live.size - 1]) else live
                            vm.addAnnot(Annot(index, tool, if (tool == Tool.Highlight) HIGHLIGHT else INK, pts))
                        }
                        live = emptyList()
                    },
                    onDragCancel = { live = emptyList() }
                )
            }
        ) {
            fun draw(t: Tool, color: Int, pts: List<Float>) {
                val c = Color(0xFF000000.toInt() or color)
                if (pts.size < 4) return
                if (t == Tool.Highlight) {
                    val a = Offset(pts[0] * size.width, pts[1] * size.height)
                    val z = Offset(pts[2] * size.width, pts[3] * size.height)
                    drawRect(
                        c.copy(alpha = 0.35f),
                        topLeft = Offset(minOf(a.x, z.x), minOf(a.y, z.y)),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(z.x - a.x), kotlin.math.abs(z.y - a.y))
                    )
                } else {
                    val path = Path().apply {
                        moveTo(pts[0] * size.width, pts[1] * size.height)
                        var i = 2
                        while (i + 1 < pts.size) { lineTo(pts[i] * size.width, pts[i + 1] * size.height); i += 2 }
                    }
                    drawPath(path, c, style = Stroke(width = 3f))
                }
            }
            mine.forEach { draw(it.tool, it.color, it.pts) }
            if (live.isNotEmpty()) {
                val pts = if (tool == Tool.Highlight && live.size >= 4) listOf(live[0], live[1], live[live.size - 2], live[live.size - 1]) else live
                draw(tool, if (tool == Tool.Highlight) HIGHLIGHT else INK, pts)
            }
        }
    }
}

@Composable
private fun PasswordDialog(onUnlock: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.password_required)) },
        text = {
            OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onUnlock(pw) }) { Text(stringResource(R.string.unlock)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun TextInputDialog(op: ToolOp, onApply: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val title = when (op) {
        ToolOp.Split -> R.string.split
        ToolOp.Reorder -> R.string.reorder
        ToolOp.Watermark -> R.string.watermark
        ToolOp.Protect -> R.string.protect
    }
    val hint = when (op) {
        ToolOp.Split -> R.string.ranges_hint
        ToolOp.Reorder -> R.string.order_hint
        ToolOp.Watermark -> R.string.watermark_hint
        ToolOp.Protect -> R.string.password
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(stringResource(hint)) }, singleLine = true,
                visualTransformation = if (op == ToolOp.Protect) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onApply(text.trim()) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun SearchDialog(vm: AppViewModel, onGo: (Int) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.search)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text(stringResource(R.string.search_hint)) })
                    TextButton(onClick = { vm.search(q) }) { Text(stringResource(R.string.search)) }
                }
                if (vm.hits.isEmpty() && !vm.busy && q.isNotBlank()) Text(stringResource(R.string.no_results))
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    itemsIndexed(vm.hits) { _, h ->
                        Text(
                            "${h.page + 1}: …${h.snippet}…",
                            Modifier.fillMaxWidth().clickable { onGo(h.page) }.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun NavigateDialog(vm: AppViewModel, session: PdfSession, onGo: (Int) -> Unit, onClose: () -> Unit) {
    var thumbs by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row {
                TextButton(onClick = { thumbs = true }) { Text(stringResource(R.string.thumbnails)) }
                TextButton(onClick = { thumbs = false }) { Text(stringResource(R.string.bookmarks)) }
            }
        },
        text = {
            if (thumbs) {
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((0 until session.pageCount).toList()) { i ->
                        var bmp by remember(session, i) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(session, i) { bmp = runCatching { session.render(i, 240) }.getOrNull() }
                        Column(Modifier.clickable { onGo(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            bmp?.let { Image(it.asImageBitmap(), stringResource(R.string.page_of, i + 1, session.pageCount), Modifier.fillMaxWidth()) }
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                val marks = vm.bookmarks.sorted()
                if (marks.isEmpty()) Text(stringResource(R.string.no_bookmarks))
                LazyColumn {
                    itemsIndexed(marks) { _, p ->
                        Text(stringResource(R.string.page_of, p + 1, session.pageCount),
                            Modifier.fillMaxWidth().clickable { onGo(p) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } }
    )
}
