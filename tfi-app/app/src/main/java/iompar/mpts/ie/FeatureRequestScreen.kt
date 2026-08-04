package iompar.mpts.ie

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A drag shorter than this in either direction is a stray tap, not a box. */
private const val MIN_BOX_PX = 24f

/**
 * Files a feature request against the screen the user was just looking at.
 *
 * The screenshot is annotatable because "the button here is wrong" is unanswerable without knowing
 * which button: drag out a box, and a numbered chip appears that drops a "#1" into the description.
 * Alongside it goes [FeatureRequestDraft.context] — the screen's own arguments and the payload it
 * was rendering — so the request can be acted on without a second round of "which stop was that?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureRequestScreen(draft: FeatureRequestDraft, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { FeatureRequestStore(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    var description by remember { mutableStateOf(TextFieldValue("")) }
    var boxes by remember { mutableStateOf(listOf<FeatureRequestBox>()) }
    var showContext by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    /** Drops a reference to box [index] in at the cursor, which is the point of the chips. */
    fun insertRef(index: Int) {
        val text = description.text
        val at = description.selection.end.coerceIn(0, text.length)
        val ref = if (at > 0 && !text[at - 1].isWhitespace()) " #$index " else "#$index "
        description = TextFieldValue(
            text = text.substring(0, at) + ref + text.substring(at),
            selection = TextRange(at + ref.length),
        )
    }

    /** Saves, then hands [FeatureRequest] to whatever the user picks: mail, chat, a note to self. */
    fun saveThenShare(share: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.save(
                        screen = draft.screen,
                        context = draft.context,
                        description = description.text,
                        boxes = boxes,
                        screenshot = draft.screenshot,
                    )
                }
            }
            saving = false
            val saved = result.getOrNull()
            if (saved == null) {
                snackbarHostState.showSnackbar("Couldn't save: ${result.exceptionOrNull()?.message ?: "error"}")
                return@launch
            }
            if (share) {
                val png = store.screenshotFile(saved.id)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (png != null) "image/png" else "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Feature request — ${saved.screen}")
                    putExtra(Intent.EXTRA_TEXT, shareBody(saved))
                    if (png != null) {
                        putExtra(
                            Intent.EXTRA_STREAM,
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", png),
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                context.startActivity(Intent.createChooser(intent, "Send feature request"))
            }
            onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Feature request") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Discard",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                actions = {
                    if (boxes.isNotEmpty()) {
                        IconButton(onClick = { boxes = boxes.dropLast(1) }) {
                            Icon(
                                Icons.Filled.Undo,
                                contentDescription = "Undo last box",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    IconButton(onClick = { saveThenShare(share = true) }, enabled = !saving) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "Save and share",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                draft.screen,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Annotator(
                draft = draft,
                boxes = boxes,
                onAddBox = { boxes = boxes + it.copy(index = boxes.size + 1) },
                onReference = { insertRef(it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            Text(
                if (boxes.isEmpty()) "Drag on the screenshot to box a detail."
                else "Tap a number to reference it in the description.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("What should change?") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            TextButton(onClick = { showContext = !showContext }) {
                Text(if (showContext) "Hide screen context" else "Screen context for the agent")
            }
            if (showContext) {
                Text(
                    draft.context,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }

            Button(
                onClick = { saveThenShare(share = false) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(52.dp),
            ) { Text("Save request") }
        }
    }
}

/**
 * The screenshot with its boxes on top.
 *
 * Boxes are held as fractions of the image, not pixels, so the maths here is only about where the
 * image landed inside [modifier]'s bounds — the picture is letterboxed to preserve its aspect, and a
 * drag has to be mapped against the drawn rectangle rather than the container.
 */
@Composable
private fun Annotator(
    draft: FeatureRequestDraft,
    boxes: List<FeatureRequestBox>,
    onAddBox: (FeatureRequestBox) -> Unit,
    onReference: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = draft.screenshot
    if (bitmap == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("No screenshot was captured", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    val drawn = remember(containerSize, bitmap) { drawnRect(containerSize, bitmap.width, bitmap.height) }
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .onSizeChanged { containerSize = it }
            .pointerInput(drawn) {
                val area = drawn ?: return@pointerInput
                detectDragGestures(
                    onDragStart = { dragStart = it; dragEnd = it },
                    onDrag = { change, delta ->
                        change.consume()
                        dragEnd = (dragEnd ?: dragStart) + delta
                    },
                    onDragEnd = {
                        val end = dragEnd
                        if (end != null && abs(end.x - dragStart.x) >= MIN_BOX_PX && abs(end.y - dragStart.y) >= MIN_BOX_PX) {
                            onAddBox(toFractions(dragStart, end, area))
                        }
                        dragEnd = null
                    },
                    onDragCancel = { dragEnd = null },
                )
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = "Screenshot of the screen this request is about",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        drawn?.let { area ->
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 3.dp.toPx())
                boxes.forEach { box ->
                    val rect = box.toPixels(area)
                    drawRect(accent, topLeft = rect.topLeft, size = rect.size, style = stroke)
                }
                dragEnd?.let { end ->
                    val rect = Rect(
                        min(dragStart.x, end.x),
                        min(dragStart.y, end.y),
                        max(dragStart.x, end.x),
                        max(dragStart.y, end.y),
                    )
                    drawRect(accent, topLeft = rect.topLeft, size = rect.size, alpha = 0.5f, style = stroke)
                }
            }

            // The chips sit on the boxes rather than in a row below: with half a dozen annotations
            // on a busy board, a detached list of numbers tells you nothing about which is which.
            boxes.forEach { box ->
                val rect = box.toPixels(area)
                Surface(
                    color = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .offset { IntOffset(rect.left.roundToInt(), max(0f, rect.top - 22.dp.toPx()).roundToInt()) }
                        .clickable { onReference(box.index) },
                ) {
                    Text(
                        "#${box.index}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Where an image of [imageW] x [imageH] lands when drawn to fit inside [container]. */
private fun drawnRect(container: IntSize, imageW: Int, imageH: Int): Rect? {
    if (container.width == 0 || container.height == 0 || imageW == 0 || imageH == 0) return null
    val scale = min(container.width.toFloat() / imageW, container.height.toFloat() / imageH)
    val width = imageW * scale
    val height = imageH * scale
    return Rect(
        Offset((container.width - width) / 2f, (container.height - height) / 2f),
        Size(width, height),
    )
}

/** A drag in container pixels, as fractions of the drawn image. Index is filled in by the caller. */
private fun toFractions(start: Offset, end: Offset, drawn: Rect): FeatureRequestBox {
    fun fx(x: Float) = ((x - drawn.left) / drawn.width).coerceIn(0f, 1f)
    fun fy(y: Float) = ((y - drawn.top) / drawn.height).coerceIn(0f, 1f)
    return FeatureRequestBox(
        index = 0,
        left = fx(min(start.x, end.x)),
        top = fy(min(start.y, end.y)),
        right = fx(max(start.x, end.x)),
        bottom = fy(max(start.y, end.y)),
    )
}

private fun FeatureRequestBox.toPixels(drawn: Rect) = Rect(
    drawn.left + left * drawn.width,
    drawn.top + top * drawn.height,
    drawn.left + right * drawn.width,
    drawn.top + bottom * drawn.height,
)

/** The shared message: the description, what each box refers to, and the machine-readable context. */
private fun shareBody(request: FeatureRequest): String = buildString {
    appendLine("Feature request — ${request.screen}")
    appendLine()
    appendLine(request.description.ifBlank { "(no description)" })
    if (request.boxes.isNotEmpty()) {
        appendLine()
        appendLine("Annotations (fractions of the screenshot, left/top/right/bottom):")
        request.boxes.forEach { box ->
            appendLine(
                "#${box.index}: %.3f, %.3f, %.3f, %.3f".format(box.left, box.top, box.right, box.bottom),
            )
        }
    }
    appendLine()
    appendLine("Screen context:")
    appendLine(request.context)
}
