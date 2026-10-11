package com.pdfstudio.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.pdfstudio.app.AppViewModel
import com.pdfstudio.app.R
import com.pdfstudio.app.ToolOp
import com.pdfstudio.app.data.Annot
import com.pdfstudio.app.edit.EditOp
import com.pdfstudio.app.edit.Family
import com.pdfstudio.app.edit.RunStyle
import com.pdfstudio.app.edit.ImageBox
import com.pdfstudio.app.edit.InfoFields
import com.pdfstudio.app.edit.PageContent
import com.pdfstudio.app.edit.TextRun
import com.pdfstudio.app.data.Tool
import com.pdfstudio.app.pdf.PdfSession
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class Dlg { None, Search, Navigate, Tools, Versions, Sync, Meta }

/** What the user tapped in Edit mode. Coordinates are normalised page positions. */
private sealed interface EditTarget {
    data class Text(val page: Int, val run: TextRun) : EditTarget
    data class Image(val page: Int, val box: ImageBox) : EditTarget
    data class Empty(val page: Int, val x: Float, val y: Float, val suggested: RunStyle, val pageHasText: Boolean) : EditTarget
}

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

    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var pickFor by remember { mutableStateOf<EditTarget?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val t = pickFor
        pickFor = null
        if (uri != null) when (t) {
            is EditTarget.Empty -> vm.placeImage(uri, t.page, t.x, t.y, null)
            is EditTarget.Image -> vm.placeImage(uri, t.page, 0f, 0f, t.box)
            else -> Unit
        }
    }
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
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(tool == Tool.None, { tool = Tool.None }, { Text(stringResource(R.string.tool_none)) })
                FilterChip(tool == Tool.Ink, { tool = Tool.Ink }, { Text(stringResource(R.string.tool_ink)) })
                FilterChip(tool == Tool.Highlight, { tool = Tool.Highlight }, { Text(stringResource(R.string.tool_highlight)) })
                FilterChip(tool == Tool.Edit, { tool = Tool.Edit }, { Text(stringResource(R.string.tool_edit)) })
                if (vm.edits.isNotEmpty()) {
                    TextButton(onClick = { vm.saveEdits() }) { Text(stringResource(R.string.edit_save, vm.edits.size)) }
                    TextButton(onClick = { vm.discardEdits() }) { Text(stringResource(R.string.edit_discard)) }
                }
                if (vm.annots.isNotEmpty()) {
                    TextButton(onClick = { vm.undoAnnot() }) { Text(stringResource(R.string.undo)) }
                    TextButton(onClick = { vm.saveAnnotations() }) { Text(stringResource(R.string.save_annotations)) }
                }
                Spacer(Modifier.width(8.dp))
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
            if (tool == Tool.Edit) {
                Text(
                    stringResource(R.string.edit_hint),
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
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
                    userScrollEnabled = tool == Tool.None || tool == Tool.Edit,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = zoomState, canPan = { scale > 1f }, enabled = tool == Tool.None || tool == Tool.Edit)
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
                            aspect = session.aspect,
                            onEditTap = { p, x, y, run, img, content ->
                                editTarget = when {
                                    run != null -> EditTarget.Text(p, run)
                                    img != null -> EditTarget.Image(p, img)
                                    else -> EditTarget.Empty(
                                        p, x, y,
                                        // Start from the style of the nearest line so new text blends in.
                                        suggested = content?.runs?.minByOrNull { r -> kotlin.math.abs((r.top + r.bottom) / 2 - y) }?.style ?: RunStyle(),
                                        pageHasText = content?.runs?.isNotEmpty() == true
                                    )
                                }
                            }
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
                    TextButton(onClick = { dlg = Dlg.Meta }) { Text(stringResource(R.string.edit_properties)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.runOcr() }) { Text(stringResource(R.string.ocr)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.exportPdfA(true) }) { Text(stringResource(R.string.export_pdfa)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.sharePdf() }) { Text(stringResource(R.string.share_pdf)) }
                    TextButton(onClick = { dlg = Dlg.None; vm.sharePagePng(page) }) { Text(stringResource(R.string.share_png)) }
                }
            }
        )
        Dlg.Meta -> {
            // Parsing the file can take a moment on large documents, so load off the main thread.
            val info by produceState<InfoFields?>(null) { value = vm.readInfo() }
            info?.let { MetaDialog(it, onSave = { f -> dlg = Dlg.None; vm.saveInfo(f) }, onCancel = { dlg = Dlg.None }) }
        }
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

    when (val t = editTarget) {
        null -> Unit
        is EditTarget.Text -> EditTextDialog(
            title = R.string.edit_text, initial = t.run.text, initialStyle = t.run.style, note = null,
            onApply = { text, style ->
                editTarget = null
                if (text != t.run.text || style != t.run.style) vm.applyEdit(EditOp.ReplaceText(t.page, t.run, text, style))
            },
            onCancel = { editTarget = null }
        )
        is EditTarget.Image -> AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.edit_image)) },
            text = {
                Column {
                    TextButton(onClick = { editTarget = null; vm.applyEdit(EditOp.DeleteImage(t.page, t.box)) }) { Text(stringResource(R.string.edit_image_delete)) }
                    TextButton(onClick = { pickFor = t; editTarget = null; imagePicker.launch("image/*") }) { Text(stringResource(R.string.edit_image_replace)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
        is EditTarget.Empty -> EditTextDialog(
            title = R.string.edit_add_text, initial = "", initialStyle = t.suggested,
            note = if (t.pageHasText) null else stringResource(R.string.edit_no_text_note),
            onApply = { text, style -> editTarget = null; vm.applyEdit(EditOp.AddText(t.page, t.x, t.y, text, style)) },
            onCancel = { editTarget = null },
            extra = {
                TextButton(onClick = { pickFor = t; editTarget = null; imagePicker.launch("image/*") }) { Text(stringResource(R.string.edit_add_image)) }
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
    tool: Tool, vm: AppViewModel, night: Boolean, aspect: Float,
    onEditTap: (page: Int, x: Float, y: Float, run: TextRun?, image: ImageBox?, content: PageContent?) -> Unit
) {
    val content by produceState<PageContent?>(null, vm.version, index, tool == Tool.Edit) {
        value = if (tool == Tool.Edit) vm.pageContent(index) else null
    }
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
            Modifier.matchParentSize().pointerInput(tool, index, content) {
                when (tool) {
                    Tool.None -> return@pointerInput
                    Tool.Edit -> detectTapGestures { o ->
                        val nx = o.x / size.width
                        val ny = o.y / size.height
                        val run = content?.runs?.firstOrNull { nx in it.left..it.right && ny in it.top..it.bottom }
                        val img = if (run == null) content?.images?.firstOrNull { nx in it.left..it.right && ny in it.top..it.bottom } else null
                        onEditTap(index, nx, ny, run, img, content)
                    }
                    else -> detectDragGestures(
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
            if (tool == Tool.Edit) {
                val w = size.width
                val h = size.height
                content?.runs?.forEach {
                    drawRect(Color(0xFF1E88E5), Offset(it.left * w, it.top * h), androidx.compose.ui.geometry.Size((it.right - it.left) * w, (it.bottom - it.top) * h), style = Stroke(1.5f))
                }
                content?.images?.forEach {
                    drawRect(Color(0xFFFB8C00), Offset(it.left * w, it.top * h), androidx.compose.ui.geometry.Size((it.right - it.left) * w, (it.bottom - it.top) * h), style = Stroke(3f))
                }
                // Preview of queued edits; the real result appears once saved.
                vm.edits.filter { it.page == index }.forEach { op ->
                    when (op) {
                        is EditOp.ReplaceText -> {
                            val r = op.run
                            drawRect(Color(0xFF000000.toInt() or op.cover), Offset(r.left * w, r.top * h), androidx.compose.ui.geometry.Size((r.right - r.left) * w, (r.bottom - r.top) * h))
                            drawRect(Color(0xFF43A047), Offset(r.left * w, r.top * h), androidx.compose.ui.geometry.Size((r.right - r.left) * w, (r.bottom - r.top) * h), style = Stroke(2f))
                            drawContext.canvas.nativeCanvas.drawText(op.newText, r.left * w, r.baseline * h, previewPaint(op.style, w))
                        }
                        is EditOp.AddText -> {
                            val paint = previewPaint(op.style, w)
                            drawContext.canvas.nativeCanvas.drawText(op.text, op.x * w, op.y * h + paint.textSize, paint)
                        }
                        is EditOp.DeleteImage -> drawRect(Color(0x66E53935), Offset(op.image.left * w, op.image.top * h), androidx.compose.ui.geometry.Size((op.image.right - op.image.left) * w, (op.image.bottom - op.image.top) * h))
                        is EditOp.PlaceImage -> drawRect(Color(0xFF1E88E5), Offset(op.left * w, op.top * h), androidx.compose.ui.geometry.Size((op.right - op.left) * w, (op.bottom - op.top) * h), style = Stroke(4f))
                    }
                }
            }
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

/** Rough on-screen preview of queued text; assumes an A4-wide page for the point-to-pixel scale. */
private fun previewPaint(style: RunStyle, pageWidthPx: Float): android.graphics.Paint = android.graphics.Paint().apply {
    isAntiAlias = true
    color = 0xFF000000.toInt() or style.rgb
    textSize = style.sizePt / 595f * pageWidthPx
    typeface = android.graphics.Typeface.create(
        when (style.family) {
            Family.Sans -> android.graphics.Typeface.SANS_SERIF
            Family.Serif -> android.graphics.Typeface.SERIF
            Family.Mono -> android.graphics.Typeface.MONOSPACE
        },
        when {
            style.bold && style.italic -> android.graphics.Typeface.BOLD_ITALIC
            style.bold -> android.graphics.Typeface.BOLD
            style.italic -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
    )
}

/**
 * Text entry with font controls. The controls start at the style detected in the document (or at the nearest
 * line's style when adding), so untouched text keeps the document's look; the field itself previews the choice.
 */
@Composable
private fun EditTextDialog(
    title: Int, initial: String, initialStyle: RunStyle, note: String?,
    onApply: (text: String, style: RunStyle) -> Unit, onCancel: () -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    var text by remember { mutableStateOf(initial) }
    var family by remember { mutableStateOf(initialStyle.family) }
    var bold by remember { mutableStateOf(initialStyle.bold) }
    var italic by remember { mutableStateOf(initialStyle.italic) }
    val initialSize = remember { initialStyle.sizePt.roundToInt().coerceAtLeast(1).toString() }
    var size by remember { mutableStateOf(initialSize) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                OutlinedTextField(
                    text, { text = it }, label = { Text(stringResource(R.string.edit_text_hint)) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = when (family) { Family.Sans -> FontFamily.SansSerif; Family.Serif -> FontFamily.Serif; Family.Mono -> FontFamily.Monospace },
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                        fontSize = 16.sp
                    )
                )
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(family == Family.Sans, { family = Family.Sans }, { Text(stringResource(R.string.font_sans)) })
                    FilterChip(family == Family.Serif, { family = Family.Serif }, { Text(stringResource(R.string.font_serif)) })
                    FilterChip(family == Family.Mono, { family = Family.Mono }, { Text(stringResource(R.string.font_mono)) })
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(bold, { bold = !bold }, { Text(stringResource(R.string.font_bold)) })
                    FilterChip(italic, { italic = !italic }, { Text(stringResource(R.string.font_italic)) })
                    OutlinedTextField(
                        size, { size = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.edit_font_size)) }, singleLine = true,
                        modifier = Modifier.width(110.dp)
                    )
                }
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                // Keep the exact detected size unless the user typed a different one.
                val sizePt = if (size == initialSize) initialStyle.sizePt else (size.toFloatOrNull() ?: initialStyle.sizePt).coerceIn(4f, 200f)
                onApply(text, RunStyle(family, bold, italic, sizePt, initialStyle.rgb))
            }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun MetaDialog(initial: InfoFields, onSave: (InfoFields) -> Unit, onCancel: () -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var author by remember { mutableStateOf(initial.author) }
    var subject by remember { mutableStateOf(initial.subject) }
    var keywords by remember { mutableStateOf(initial.keywords) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.edit_properties)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.meta_title)) }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.meta_author)) }, singleLine = true)
                OutlinedTextField(subject, { subject = it }, label = { Text(stringResource(R.string.meta_subject)) }, singleLine = true)
                OutlinedTextField(keywords, { keywords = it }, label = { Text(stringResource(R.string.meta_keywords)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(InfoFields(title, author, subject, keywords)) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    )
}
