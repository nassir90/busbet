package com.example.tfiapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    paletteStore: PaletteStore,
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationAware by settingsStore.locationAware.collectAsState(initial = false)
    val hideFarStops by settingsStore.hideFarStops.collectAsState(initial = false)

    val selectedId by paletteStore.selectedId.collectAsState(initial = DEFAULT_PALETTE.id)
    val customPalettes by paletteStore.customPalettes.collectAsState(initial = emptyList())
    val allPalettes = PRESETS + customPalettes

    // Non-null while the palette editor is open.
    var editing by remember { mutableStateOf<AppPalette?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { settingsStore.setLocationAware(granted) }
    }

    editing?.let { target ->
        PaletteEditorDialog(
            initial = target,
            // Only previously-saved custom palettes can be deleted.
            canDelete = !target.preset && customPalettes.any { it.id == target.id },
            onSave = { scope.launch { paletteStore.upsert(it) }; editing = null },
            onDelete = { scope.launch { paletteStore.delete(target.id) }; editing = null },
            onDismiss = { editing = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
        ) {
            // ── Theme ────────────────────────────────────────────────────────
            SectionHeader("Theme")
            allPalettes.forEach { p ->
                PaletteRow(
                    palette = p,
                    selected = p.id == selectedId,
                    onSelect = { scope.launch { paletteStore.select(p.id) } },
                    onEdit = { editing = p },
                    onDuplicate = { editing = duplicatePalette(p, "${p.name} copy") },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val base = resolvePalette(selectedId, customPalettes)
                    editing = duplicatePalette(base, "My palette")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create palette")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── Location ─────────────────────────────────────────────────────
            SectionHeader("Location")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Location-aware mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sort favourite stops by distance from you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = locationAware,
                    onCheckedChange = { enabled ->
                        if (enabled && !LocationProvider.hasPermission(context)) {
                            permLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        } else {
                            scope.launch { settingsStore.setLocationAware(enabled) }
                        }
                    },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Auto-hide far stops",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (locationAware) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Gray out and collapse favourites more than 5 km away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = hideFarStops && locationAware,
                    enabled = locationAware,
                    onCheckedChange = { enabled -> scope.launch { settingsStore.setHideFarStops(enabled) } },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PaletteRow(
    palette: AppPalette,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        SwatchStrip(palette)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(palette.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                if (palette.preset) "Preset" else "Custom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Editing a preset forks it into an editable copy; custom palettes edit in place.
        IconButton(onClick = if (palette.preset) onDuplicate else onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${palette.name}")
        }
    }
}

@Composable
private fun SwatchStrip(palette: AppPalette) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
    ) {
        val s = palette.light
        listOf(s.primary, s.secondary, s.tertiary, s.surface, s.onSurface).forEach { c ->
            Box(Modifier.size(width = 14.dp, height = 28.dp).background(Color(c)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaletteEditorDialog(
    initial: AppPalette,
    canDelete: Boolean,
    onSave: (AppPalette) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember(initial.id) { mutableStateOf(initial) }
    var editingDark by remember(initial.id) { mutableStateOf(false) }
    // Non-null while a colour role is being picked.
    var pickingRole by remember { mutableStateOf<PaletteRole?>(null) }

    val separate = !working.linkedDark
    // The face currently shown/edited. With linked dark, you only edit light.
    val showDark = separate && editingDark
    val activeSet = if (showDark) working.dark else working.light

    fun applyColor(role: PaletteRole, c: Int) {
        working = if (showDark) {
            working.copy(dark = role.set(working.dark, c))
        } else {
            val newLight = role.set(working.light, c)
            // Keep the auto-generated dark face in sync while it's linked.
            working.copy(light = newLight, dark = if (working.linkedDark) deriveDark(newLight) else working.dark)
        }
    }

    pickingRole?.let { role ->
        ColorPickerDialog(
            title = "${role.label} · ${if (showDark) "Dark" else "Light"}",
            initial = role.get(activeSet),
            onPick = { applyColor(role, it); pickingRole = null },
            onDismiss = { pickingRole = null },
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp,
            // A themed preview: the dialog paints with the face being edited.
            color = Color(activeSet.surface),
            contentColor = Color(activeSet.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(max = 660.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (initial.preset) "New palette" else "Edit palette",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(activeSet.onSurface),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = working.name,
                    onValueChange = { working = working.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Separate dark mode", color = Color(activeSet.onSurface))
                        Text(
                            "Give dark mode its own accents.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(activeSet.onSurfaceVariant),
                        )
                    }
                    Switch(
                        checked = separate,
                        onCheckedChange = { sep ->
                            working = if (sep) working.copy(linkedDark = false, dark = deriveDark(working.light))
                                      else working.copy(linkedDark = true)
                            if (!sep) editingDark = false
                        },
                    )
                }

                if (separate) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !editingDark, onClick = { editingDark = false }, label = { Text("Light") })
                        FilterChip(selected = editingDark, onClick = { editingDark = true }, label = { Text("Dark") })
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Dark mode is generated automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(activeSet.onSurfaceVariant),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color(activeSet.onSurfaceVariant).copy(alpha = 0.3f))

                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    PALETTE_ROLES.forEach { role ->
                        ColorRoleRow(
                            label = role.label,
                            color = role.get(activeSet),
                            textColor = activeSet.onSurface,
                            onClick = { pickingRole = role },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canDelete) {
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color(activeSet.onSurface)) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { onSave(working) },
                        enabled = working.name.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(activeSet.primary),
                            contentColor = Color(activeSet.onPrimary),
                        ),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun ColorRoleRow(label: String, color: Int, textColor: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(textColor).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .background(Color(color)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color(textColor))
            Text(
                hexOf(color),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color(textColor).copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var r by remember { mutableStateOf((initial shr 16 and 0xFF).toFloat()) }
    var g by remember { mutableStateOf((initial shr 8 and 0xFF).toFloat()) }
    var b by remember { mutableStateOf((initial and 0xFF).toFloat()) }
    val current = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(Color(current)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        hexOf(current),
                        color = Color(contrastOn(current)),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                ChannelSlider("R", r, Color(0xFFD32F2F)) { r = it }
                ChannelSlider("G", g, Color(0xFF388E3C)) { g = it }
                ChannelSlider("B", b, Color(0xFF1976D2)) { b = it }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Select") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Float, tint: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(16.dp), color = tint, fontWeight = FontWeight.Bold)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
            modifier = Modifier.weight(1f),
        )
        Text(value.toInt().toString(), Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private fun hexOf(c: Int): String = "#%06X".format(c and 0xFFFFFF)

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
