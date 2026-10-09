package iompar.mpts.ie

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A drag shorter than this in either direction is a stray tap, not a box. */
private const val MIN_BOX_PX = 24f

/** How close to a corner counts as grabbing its resize handle. */
private val HANDLE_GRAB = 28.dp

/** The corner a resize drag has hold of. */
private enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Labels address a box from prose, so they have to survive being pasted into a sentence: no spaces
 * to swallow the next word, and no `#` to start a second reference inside the first.
 */
private fun sanitizeLabel(raw: String): String =
    raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(12)

/**
 * Rewrites every `#old` in [text] to `#new`.
 *
 * The lookahead is what stops `#1` from eating the start of `#10` — without it, renaming box 1
 * would quietly corrupt every other reference that happens to share its prefix.
 */
private fun renameRefs(text: String, old: String, new: String): String =
    Regex("#" + Regex.escape(old) + "(?![A-Za-z0-9_-])").replace(text, "#$new")

/**
 * Files a feature request against the screen the user was just looking at.
 *
 * The screenshot is annotatable because "the button here is wrong" is unanswerable without knowing
 * which button: drag out a box, and a numbered chip appears that drops a "#1" into the description.
 * Alongside it goes [FeatureRequestDraft.context] — the screen's own arguments and the payload it
 * was rendering — so the request can be acted on without a second round of "which stop was that?".
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Filing feedback: the screenshot to annotate, what should change, and the screen's context.
 *
 * Saving keeps a copy on the phone, as it always has (the share sheet and the on-device API read
 * it), and sends it to the backend. A copy that couldn't be sent stays put and the screen stays
 * open, so trying again doesn't save it twice.
 *
 * [onAttachScreenshot] is offered when there is no screenshot: it is handed the text typed so far
 * and is expected to take the user out into the app to pick a screen, and to come back here with
 * a draft carrying both.
 */
@Composable
fun FeatureRequestScreen(
    draft: FeatureRequestDraft,
    onBack: () -> Unit,
    onAttachScreenshot: ((description: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { FeatureRequestStore(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    var description by remember {
        mutableStateOf(TextFieldValue(draft.description, TextRange(draft.description.length)))
    }
    var boxes by remember { mutableStateOf(listOf<FeatureRequestBox>()) }
    var showContext by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // The local copy, once made. Kept so a failed send can be retried without saving it again.
    var saved by remember { mutableStateOf<FeatureRequest?>(null) }
    var sendFailed by remember { mutableStateOf(false) }

    // Which box is in move mode, by its stable index. Null is the ordinary drawing state.
    var selected by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<FeatureRequestBox?>(null) }
    // Where the finger is while dragging a box, and where the bin sits — both in window
    // coordinates, because the bin lives outside the annotator and the two have to be compared.
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var binBounds by remember { mutableStateOf<Rect?>(null) }
    val overBin = dragPointer?.let { p -> binBounds?.contains(p) } ?: false

    /** Drops a reference to [name] in at the cursor, which is the point of the chips. */
    fun insertRef(name: String) {
        val text = description.text
        val at = description.selection.end.coerceIn(0, text.length)
        val ref = if (at > 0 && !text[at - 1].isWhitespace()) " #$name " else "#$name "
        description = TextFieldValue(
            text = text.substring(0, at) + ref + text.substring(at),
            selection = TextRange(at + ref.length),
        )
    }

    /**
     * Saves on the phone (once), sends to the backend, and with [share] also hands it to whatever
     * the user picks: mail, chat, a note to self.
     */
    fun saveAndSend(share: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val local = saved ?: withContext(Dispatchers.IO) {
                runCatching {
                    store.save(
                        screen = draft.screen,
                        context = draft.context,
                        description = description.text,
                        boxes = boxes,
                        screenshot = draft.screenshot,
                    )
                }
            }.getOrElse { e ->
                saving = false
                snackbarHostState.showSnackbar("Couldn't save: ${e.message ?: "error"}")
                return@launch
            }
            saved = local

            val sent = withContext(Dispatchers.IO) {
                runCatching { Api.tenant().feedback(store.uploadOf(local)) }
            }
            saving = false

            if (share) {
                val png = store.screenshotFile(local.id)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (png != null) "image/png" else "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "${draft.title} — ${local.screen}")
                    putExtra(Intent.EXTRA_TEXT, shareBody(local))
                    if (png != null) {
                        putExtra(
                            Intent.EXTRA_STREAM,
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", png),
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                context.startActivity(Intent.createChooser(intent, "Send ${draft.title.lowercase()}"))
                onBack()
                return@launch
            }

            if (sent.isSuccess) {
                Toast.makeText(context, "Feedback sent", Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                sendFailed = true
                snackbarHostState.showSnackbar("Saved on this phone, but couldn't send it. Check your connection and try again.")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(draft.title) },
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
                    IconButton(onClick = { saveAndSend(share = true) }, enabled = !saving) {
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
                onAttach = onAttachScreenshot?.let { attach -> { attach(description.text) } },
                boxes = boxes,
                selected = selected,
                // Indices come from a counter, not the list length: after a deletion, size + 1
                // would hand out an index that an existing "#n" in the description already means.
                onAddBox = { box ->
                    boxes = boxes + box.copy(index = (boxes.maxOfOrNull { it.index } ?: 0) + 1)
                },
                onUpdateBox = { box -> boxes = boxes.map { if (it.index == box.index) box else it } },
                onSelect = { selected = it },
                onReference = { insertRef(it) },
                onRename = { renaming = it },
                onDragPointer = { dragPointer = it },
                onDropped = {
                    if (overBin) {
                        // The reference text is left alone. A dangling "#2" is a visible loose end
                        // the user can delete; silently editing their prose is worse than the mess.
                        boxes = boxes.filterNot { it.index == selected }
                        selected = null
                    }
                    dragPointer = null
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // The hint row doubles as the bin. Showing the bin only in move mode is right, but
            // growing the column to make room for it would shove the screenshot up mid-drag and
            // slide the box out from under the finger — so it takes over a row that always exists.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .onGloballyPositioned { binBounds = it.boundsInWindow() },
                contentAlignment = Alignment.Center,
            ) {
                if (selected != null) {
                    Bin(active = overBin)
                } else if (draft.screenshot != null) {
                    Text(
                        if (boxes.isEmpty()) "Drag on the screenshot to box a detail."
                        else "Tap a number to reference it. Hold a box to move it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
                onClick = { saveAndSend(share = false) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(52.dp),
            ) {
                Text(
                    when {
                        saving -> "Sending…"
                        sendFailed -> "Try sending again"
                        else -> "Send feedback"
                    }
                )
            }
        }
    }

    renaming?.let { box ->
        RenameDialog(
            box = box,
            onDismiss = { renaming = null },
            onRename = { newName ->
                val old = box.name
                if (newName != old) {
                    boxes = boxes.map { if (it.index == box.index) it.copy(label = newName) else it }
                    // The whole point of a name is that the description already refers to it.
                    description = description.copy(text = renameRefs(description.text, old, newName))
                }
                renaming = null
            },
        )
    }
}

/** Drop target for the box being dragged. Grows and turns to the error colour once it will catch. */
@Composable
private fun Bin(active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Drag a box here to delete it",
            tint = color,
            modifier = Modifier.size(if (active) 32.dp else 24.dp),
        )
        Text(
            if (active) "Release to delete" else "Drag here to delete",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/** Renames a box, and with it every `#name` already typed into the description. */
@Composable
private fun RenameDialog(
    box: FeatureRequestBox,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var value by remember(box.index) { mutableStateOf(box.name) }
    val cleaned = sanitizeLabel(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename #${box.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = sanitizeLabel(it) },
                    singleLine = true,
                    label = { Text("Name") },
                )
                Text(
                    "References in the description are updated too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(cleaned) }, enabled = cleaned.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    /** Offered when there's no screenshot: starts picking one from elsewhere in the app. */
    onAttach: (() -> Unit)?,
    boxes: List<FeatureRequestBox>,
    selected: Int?,
    onAddBox: (FeatureRequestBox) -> Unit,
    onUpdateBox: (FeatureRequestBox) -> Unit,
    onSelect: (Int?) -> Unit,
    onReference: (String) -> Unit,
    onRename: (FeatureRequestBox) -> Unit,
    onDragPointer: (Offset?) -> Unit,
    onDropped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = draft.screenshot
    if (bitmap == null) {
        if (onAttach == null) {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text("No screenshot was captured", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Column(
                modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onAttach)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("Tap to attach a screenshot", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Go to any screen in the app, then tap the loudspeaker button to attach it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        return
    }

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    val drawn = remember(containerSize, bitmap) { drawnRect(containerSize, bitmap.width, bitmap.height) }
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current

    // Move mode needs a tell, and a box is a static outline with nothing else to animate — so it
    // wobbles, the way a long-pressed launcher icon does.
    //
    // Kept small on purpose. The angle is applied about the centre, so the corners swing further
    // the bigger the box: at 1.6° a box spanning most of the screenshot throws its corners about
    // 14px, which reads as a wobbling picture rather than a picked-up object.
    val wobble by rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = -0.7f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(180), RepeatMode.Reverse),
        label = "wobble",
    )

    // The gesture outlives the composition that started it: pointerInput only restarts when its
    // key changes, so anything it captured directly would be frozen at that moment. In particular
    // onDropped closes over whether the finger is over the bin, which is false when the drag
    // begins and true by the time it ends — captured directly, a drop on the bin never deletes.
    val current = rememberUpdatedState(boxes to selected)
    val addBox by rememberUpdatedState(onAddBox)
    val updateBox by rememberUpdatedState(onUpdateBox)
    val select by rememberUpdatedState(onSelect)
    val dragPointer by rememberUpdatedState(onDragPointer)
    val dropped by rememberUpdatedState(onDropped)

    Box(
        modifier
            .onSizeChanged { containerSize = it }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(drawn) {
                val area = drawn ?: return@pointerInput
                val grab = HANDLE_GRAB.toPx()

                awaitEachGesture {
                    val (allBoxes, sel) = current.value
                    val active = allBoxes.firstOrNull { it.index == sel }
                    val down = awaitFirstDown()
                    val start = down.position

                    val handle = active?.let { handleAt(start, it.toPixels(area), grab) }
                    if (active != null && handle != null) {
                        var live: FeatureRequestBox = active
                        drag(down.id) { change ->
                            change.consume()
                            live = live.resized(handle, change.position, area)
                            updateBox(live)
                        }
                        return@awaitEachGesture
                    }
                    if (active != null && active.toPixels(area).contains(start)) {
                        moveBox(down.id, active, start, area, origin, updateBox, dragPointer)
                        dropped()
                        return@awaitEachGesture
                    }

                    // Otherwise this is a press on the picture: a hold selects whatever is under it,
                    // a drag draws a new box, and a plain tap dismisses move mode.
                    var lifted = false
                    val slop = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            .also { if (it == null) lifted = true }
                    }
                    when {
                        slop != null -> {
                            dragStart = start
                            dragEnd = slop.position
                            drag(down.id) { change ->
                                change.consume()
                                dragEnd = change.position
                            }
                            val end = dragEnd
                            if (end != null &&
                                abs(end.x - dragStart.x) >= MIN_BOX_PX &&
                                abs(end.y - dragStart.y) >= MIN_BOX_PX
                            ) {
                                onAddBox(toFractions(dragStart, end, area))
                            }
                            dragEnd = null
                        }
                        lifted -> onSelect(null)
                        else -> {
                            // Topmost first: later boxes are drawn over earlier ones, so when they
                            // overlap the one you can see is the one you meant to grab.
                            val hit = allBoxes.lastOrNull { it.toPixels(area).contains(start) }
                            if (hit == null) {
                                onSelect(null)
                            } else {
                                onSelect(hit.index)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                moveBox(down.id, hit, start, area, origin, onUpdateBox, onDragPointer)
                                onDropped()
                            }
                        }
                    }
                }
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
                    if (box.index == selected) {
                        rotate(wobble, pivot = rect.center) {
                            drawRect(accent, topLeft = rect.topLeft, size = rect.size, style = stroke)
                            // Filled corners, so it is obvious the corners are the grabbable part.
                            val r = 6.dp.toPx()
                            listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight)
                                .forEach { drawCircle(accent, radius = r, center = it) }
                        }
                    } else {
                        drawRect(accent, topLeft = rect.topLeft, size = rect.size, style = stroke)
                    }
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
                val isSelected = box.index == selected
                Surface(
                    color = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .offset { IntOffset(rect.left.roundToInt(), max(0f, rect.top - 22.dp.toPx()).roundToInt()) }
                        // In move mode the chip is the way to rename; otherwise it is the way to
                        // cite the box. One control, and which job it is doing is on screen.
                        .clickable { if (isSelected) onRename(box) else onReference(box.name) },
                ) {
                    Text(
                        if (isSelected) "#${box.name}  ✎" else "#${box.name}",
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

/**
 * Follows the finger with [box] until it lifts, reporting the pointer in window coordinates.
 *
 * Top-level rather than local to the gesture because [AwaitPointerEventScope] restricts suspension:
 * only member and extension functions of the scope itself may be called from inside it.
 */
private suspend fun AwaitPointerEventScope.moveBox(
    pointerId: PointerId,
    box: FeatureRequestBox,
    start: Offset,
    area: Rect,
    origin: Offset,
    onUpdateBox: (FeatureRequestBox) -> Unit,
    onDragPointer: (Offset?) -> Unit,
) {
    var live = box
    var last = start
    onDragPointer(origin + start)
    drag(pointerId) { change ->
        change.consume()
        live = live.translated(change.position - last, area)
        last = change.position
        onUpdateBox(live)
        onDragPointer(origin + change.position)
    }
}

/** Which corner of [rect] [point] is grabbing, or null if it is not near one. */
private fun handleAt(point: Offset, rect: Rect, grab: Float): Handle? {
    val corners = listOf(
        Handle.TOP_LEFT to rect.topLeft,
        Handle.TOP_RIGHT to rect.topRight,
        Handle.BOTTOM_LEFT to rect.bottomLeft,
        Handle.BOTTOM_RIGHT to rect.bottomRight,
    )
    return corners.minByOrNull { (_, c) -> (c - point).getDistance() }
        ?.takeIf { (_, c) -> (c - point).getDistance() <= grab }
        ?.first
}

/**
 * The box shifted by [delta] container pixels, clamped so it cannot be pushed off the picture.
 *
 * The whole rectangle is clamped rather than each edge, so sliding into an edge stops the box
 * instead of squashing it.
 */
private fun FeatureRequestBox.translated(delta: Offset, drawn: Rect): FeatureRequestBox {
    val dx = (delta.x / drawn.width).coerceIn(-left, 1f - right)
    val dy = (delta.y / drawn.height).coerceIn(-top, 1f - bottom)
    return copy(left = left + dx, right = right + dx, top = top + dy, bottom = bottom + dy)
}

/** The box with [handle]'s corner dragged to [point]. Edges may cross; they are re-sorted after. */
private fun FeatureRequestBox.resized(handle: Handle, point: Offset, drawn: Rect): FeatureRequestBox {
    val fx = ((point.x - drawn.left) / drawn.width).coerceIn(0f, 1f)
    val fy = ((point.y - drawn.top) / drawn.height).coerceIn(0f, 1f)
    val moved = when (handle) {
        Handle.TOP_LEFT -> copy(left = fx, top = fy)
        Handle.TOP_RIGHT -> copy(right = fx, top = fy)
        Handle.BOTTOM_LEFT -> copy(left = fx, bottom = fy)
        Handle.BOTTOM_RIGHT -> copy(right = fx, bottom = fy)
    }
    // Dragging a corner past its opposite is a normal thing to do with a mouse or a thumb; the
    // rectangle should flip rather than invert into something that draws as nothing.
    return moved.copy(
        left = min(moved.left, moved.right),
        right = max(moved.left, moved.right),
        top = min(moved.top, moved.bottom),
        bottom = max(moved.top, moved.bottom),
    )
}

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
                "#${box.name}: %.3f, %.3f, %.3f, %.3f".format(box.left, box.top, box.right, box.bottom),
            )
        }
    }
    appendLine()
    appendLine("Screen context:")
    appendLine(request.context)
}
