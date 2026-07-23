package net.uzoukwu.tfiapp

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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import net.uzoukwu.tfiapp.server.BindMode
import net.uzoukwu.tfiapp.server.DEFAULT_SERVER_PORT
import net.uzoukwu.tfiapp.server.OnDeviceServerService
import net.uzoukwu.tfiapp.server.ServerSettingsStore
import net.uzoukwu.tfiapp.server.bindHost
import net.uzoukwu.tfiapp.server.isValidPort
import net.uzoukwu.tfiapp.server.reachableHost
import net.uzoukwu.tfiapp.server.tailscaleIpv4
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    paletteStore: PaletteStore,
    settingsStore: SettingsStore,
    serverSettingsStore: ServerSettingsStore,
    backendConfigStore: BackendConfigStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationAware by settingsStore.locationAware.collectAsState(initial = false)
    val hideFarStops by settingsStore.hideFarStops.collectAsState(initial = false)
    val farStopThresholdM by settingsStore.farStopThresholdM.collectAsState(initial = DEFAULT_FAR_STOP_THRESHOLD_M)
    val busDisplayThresholdMin by settingsStore.busDisplayThresholdMin.collectAsState(initial = DEFAULT_BUS_DISPLAY_THRESHOLD_MIN)

    val selectedId by paletteStore.selectedId.collectAsState(initial = DEFAULT_PALETTE.id)
    val customPalettes by paletteStore.customPalettes.collectAsState(initial = emptyList())
    val allPalettes = PRESETS + customPalettes

    val configBackupStore = remember(paletteStore, settingsStore) {
        ConfigBackupStore(context, paletteStore = paletteStore, settingsStore = settingsStore)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // Holds the action to run once the user confirms an overwrite on import.
    var pendingOverwrite by remember { mutableStateOf<(suspend () -> Unit)?>(null) }

    // Non-null while the palette editor is open.
    var editing by remember { mutableStateOf<AppPalette?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { settingsStore.setLocationAware(granted) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val json = configBackupStore.toJson(configBackupStore.snapshot())
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("Couldn't open file for writing")
            }
            snackbarHostState.showSnackbar(if (result.isSuccess) "Config exported" else "Export failed: ${result.exceptionOrNull()?.message}")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingOverwrite = {
            val result = runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Couldn't open file for reading")
                configBackupStore.restore(configBackupStore.fromJson(json))
            }
            snackbarHostState.showSnackbar(if (result.isSuccess) "Config imported" else "Import failed: ${result.exceptionOrNull()?.message}")
        }
    }

    pendingOverwrite?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingOverwrite = null },
            title = { Text("Replace current config?") },
            text = { Text("This overwrites your saved stops, theme, and settings with the loaded config.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { action() }; pendingOverwrite = null }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingOverwrite = null }) { Text("Cancel") } },
        )
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        "Gray out and collapse favourites more than %.1f km away.".format(farStopThresholdM / 1000f),
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = farStopThresholdM.toFloat(),
                    onValueChange = { scope.launch { settingsStore.setFarStopThresholdM(it.toInt()) } },
                    valueRange = 1000f..20000f,
                    steps = 18,
                    enabled = locationAware && hideFarStops,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.1f km".format(farStopThresholdM / 1000f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(56.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── Map ──────────────────────────────────────────────────────────
            SectionHeader("Map")
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Bus display threshold", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Only show a bus on the map once it's due within this many minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = busDisplayThresholdMin.toFloat(),
                        onValueChange = { scope.launch { settingsStore.setBusDisplayThresholdMin(it.toInt()) } },
                        valueRange = 5f..120f,
                        steps = 22,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$busDisplayThresholdMin min",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(56.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── Backup ───────────────────────────────────────────────────────
            SectionHeader("Backup")
            Text(
                "Export your favourites and settings to a file so an upgrade or reinstall can't lose them, " +
                    "then import it back on the new install.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { exportLauncher.launch(defaultConfigFileName()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import")
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── On-device server ─────────────────────────────────────────────
            ServerSection(serverSettingsStore)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── Backend configuration ────────────────────────────────────────
            BackendSection(backendConfigStore)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ServerSection(store: ServerSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by store.enabled.collectAsState(initial = false)
    val bindMode by store.bindMode.collectAsState(initial = BindMode.LOOPBACK)
    val port by store.port.collectAsState(initial = DEFAULT_SERVER_PORT)

    // Local editable text mirrors the persisted port; we only persist when it's a valid number.
    var portText by remember(port) { mutableStateOf(port.toString()) }
    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && isValidPort(portValue)

    // Tailscale-only bind needs the device to actually be on a tailnet.
    val tailscaleIp = tailscaleIpv4()
    val canBind = bindHost(bindMode) != null

    SectionHeader("On-device server")
    Text(
        "Expose this device's data to local tools via an MCP server (/mcp) and a REST API (/api). " +
            "Runs in the background with a persistent notification while enabled.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Enable server", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            // Don't let the server start with an invalid port or an unsatisfiable bind mode.
            enabled = (portValid && canBind) || enabled,
            onCheckedChange = { on ->
                scope.launch {
                    store.setEnabled(on)
                    if (on) OnDeviceServerService.start(context) else OnDeviceServerService.stop(context)
                }
            },
        )
    }

    Text("Bind to", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        FilterChip(
            selected = bindMode == BindMode.LOOPBACK,
            onClick = { scope.launch { store.setBindMode(BindMode.LOOPBACK) } },
            label = { Text("Loopback") },
        )
        FilterChip(
            selected = bindMode == BindMode.TAILSCALE,
            onClick = { scope.launch { store.setBindMode(BindMode.TAILSCALE) } },
            // Still selectable when offline so the choice sticks; the enable switch guards startup.
            label = { Text("Tailscale") },
        )
        FilterChip(
            selected = bindMode == BindMode.ALL,
            onClick = { scope.launch { store.setBindMode(BindMode.ALL) } },
            label = { Text("All interfaces") },
        )
    }
    Text(
        when (bindMode) {
            BindMode.LOOPBACK -> "Reachable only from this device (127.0.0.1)."
            BindMode.TAILSCALE ->
                if (tailscaleIp != null) "Reachable only over your tailnet ($tailscaleIp) — not on the local Wi-Fi/LAN. No authentication."
                else "Tailscale isn't connected on this device, so there's no address to bind to."
            BindMode.ALL -> "Reachable on every interface (Wi-Fi/LAN, cellular, tailnet). Anyone who can reach the device can read and change your data — no authentication."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (bindMode == BindMode.TAILSCALE && tailscaleIp == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = portText,
        onValueChange = { new ->
            portText = new.filter { it.isDigit() }.take(5)
            portText.toIntOrNull()?.let { if (isValidPort(it)) scope.launch { store.setPort(it) } }
        },
        label = { Text("Port") },
        singleLine = true,
        isError = !portValid,
        supportingText = if (!portValid) {
            { Text("Enter a port between 1024 and 65535") }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    if (enabled) {
        val host = reachableHost(bindMode) ?: "<this device's IP>"
        val base = "http://$host:$port"
        Spacer(Modifier.height(8.dp))
        Text("Reachable at", style = MaterialTheme.typography.bodyMedium)
        Text(
            "$base/api  (REST)\n$base/mcp  (MCP)",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Suggested filename for an exported config, e.g. "tfi-config-2026-06-22.json". */
private fun defaultConfigFileName(): String =
    "tfi-config-${java.time.LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendSection(store: BackendConfigStore) {
    val scope = rememberCoroutineScope()
    val config by store.config.collectAsState(initial = BackendConfig())

    // Local editable mirror; persisted only once the text is a usable base URL, so a half-typed
    // root can't repoint the app at nothing mid-keystroke.
    var rootText by remember(config.root) { mutableStateOf(config.root) }
    val rootValid = isValidBaseUrl(rootText)

    SectionHeader("Backend configuration")

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = rootText,
        onValueChange = { new ->
            rootText = new.trim()
            if (isValidBaseUrl(rootText)) scope.launch { store.setRoot(rootText) }
        },
        label = { Text("Root URL") },
        placeholder = { Text(DEFAULT_BACKEND_ROOT) },
        singleLine = true,
        isError = !rootValid,
        supportingText = {
            Text(if (rootValid) "Services are addressed as <root>/<service>/" else "Enter an absolute http:// or https:// URL")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Derive other services from root",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = config.deriveFromRoot,
            onCheckedChange = { on -> scope.launch { store.setDeriveFromRoot(on) } },
        )
    }

    if (config.deriveFromRoot) {
        // Show what the root actually resolves to, so a misconfiguration is visible here rather
        // than only as a failed request later.
        BackendService.entries.forEach { service ->
            ResolvedUrlRow(service.label, config.urlFor(service))
        }
    } else {
        BackendService.entries.forEach { service ->
            var text by remember(service, config.overrides[service]) {
                mutableStateOf(config.overrides[service] ?: deriveUrl(config.root, service))
            }
            val valid = isValidBaseUrl(text)
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new.trim()
                    if (isValidBaseUrl(text)) scope.launch { store.setOverride(service, text) }
                },
                label = { Text(service.label) },
                singleLine = true,
                isError = !valid,
                supportingText = if (!valid) {
                    { Text("Enter an absolute http:// or https:// URL") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }

    // Cleartext warning. usesCleartextTraffic is a manifest flag and can't be toggled at runtime,
    // so http backends stay permitted (LAN and tailnet deployments need them) and we flag the risk
    // instead of silently allowing it.
    val cleartext = config.cleartextServices()
    if (cleartext.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = "Insecure connection",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Unencrypted (http) — ${cleartext.joinToString { it.label }}. " +
                    "Traffic can be read and modified in transit. Fine on a tailnet or LAN; use https over the internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ResolvedUrlRow(label: String, url: String) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
