package de.mirko.telefondienst

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureChannel(this)
        Scheduler.scheduleNext(this)
        setContent {
            MaterialTheme { App() }
        }
    }
}

private fun LocalDate.toWorkDay(): LocalDate = when (dayOfWeek) {
    DayOfWeek.SATURDAY -> plusDays(2)
    DayOfWeek.SUNDAY -> plusDays(1)
    else -> this
}

private fun LocalDate.nextWorkDay(): LocalDate = when (dayOfWeek) {
    DayOfWeek.FRIDAY -> plusDays(3)
    DayOfWeek.SATURDAY -> plusDays(2)
    else -> plusDays(1)
}

private fun LocalDate.previousWorkDay(): LocalDate = when (dayOfWeek) {
    DayOfWeek.MONDAY -> minusDays(3)
    DayOfWeek.SUNDAY -> minusDays(2)
    else -> minusDays(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var selectedState by remember { mutableStateOf(Store.bundesland(ctx)) }
    var stateDropdownExpanded by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now().toWorkDay()) }
    var version by remember { mutableIntStateOf(0) } // erzwingt Neuladen nach Änderungen
    var lead by remember { mutableIntStateOf(Store.leadMinutes(ctx)) }
    val hours = remember(date, version) { Store.hours(ctx, date) }
    val holidayName = remember(date, selectedState) { Holidays.getHolidayName(date, selectedState) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        BackupManager.init(ctx)
    }

    val autoBackupFailed by BackupManager.autoBackupFailed.collectAsState()
    val autoBackupFolderName by BackupManager.autoBackupFolderName.collectAsState()
    val lastBackupTime by BackupManager.lastBackupTime.collectAsState()

    var pendingRestoreData by remember { mutableStateOf<BackupManager.BackupData?>(null) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val res = BackupManager.exportToUri(ctx, uri)
            coroutineScope.launch {
                if (res.isSuccess) {
                    snackbarHostState.showSnackbar("Backup erfolgreich exportiert.")
                } else {
                    snackbarHostState.showSnackbar("Export fehlgeschlagen: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val res = BackupManager.readBackupFromUri(ctx, uri)
            if (res.isSuccess) {
                pendingRestoreData = res.getOrNull()
                showRestoreConfirmDialog = true
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Fehler beim Import: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val res = BackupManager.setupAutoBackup(ctx, uri)
            coroutineScope.launch {
                if (res.isSuccess) {
                    snackbarHostState.showSnackbar("Automatische Sicherung eingerichtet.")
                } else {
                    snackbarHostState.showSnackbar("Fehler beim Einrichten: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun save(newHours: Set<Int>) {
        Store.setHours(ctx, date, newHours)
        Scheduler.scheduleNext(ctx)
        version++
    }

    val dateFmt = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale.GERMAN)

    if (showRestoreConfirmDialog && pendingRestoreData != null) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirmDialog = false
                pendingRestoreData = null
            },
            title = { Text("Backup wiederherstellen") },
            text = { Text("Vorhandene Daten werden ersetzt. Fortfahren?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val data = pendingRestoreData
                        showRestoreConfirmDialog = false
                        pendingRestoreData = null
                        if (data != null) {
                            BackupManager.applyRestore(ctx, data)
                            version++
                            lead = Store.leadMinutes(ctx)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Daten erfolgreich wiederhergestellt.")
                            }
                        }
                    },
                ) {
                    Text("Fortfahren")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmDialog = false
                        pendingRestoreData = null
                    },
                ) {
                    Text("Abbrechen")
                }
            },
        )
    }

    if (showSettings) {
        // EINSTELLUNGEN ANSICHT
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("Einstellungen") },
                    navigationIcon = {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    },
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Feiertage & Bundesland
                Text("Feiertage & Bundesland", style = MaterialTheme.typography.titleMedium)
                ExposedDropdownMenuBox(
                    expanded = stateDropdownExpanded,
                    onExpandedChange = { stateDropdownExpanded = !stateDropdownExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedState.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Bundesland für Feiertage") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stateDropdownExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = stateDropdownExpanded,
                        onDismissRequest = { stateDropdownExpanded = false },
                    ) {
                        GermanState.entries.forEach { st ->
                            DropdownMenuItem(
                                text = { Text(st.displayName) },
                                onClick = {
                                    selectedState = st
                                    Store.setBundesland(ctx, st)
                                    Scheduler.scheduleNext(ctx)
                                    version++
                                    stateDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Bestimmt die gesetzlichen Feiertage, an denen kein Dienst stattfindet.",
                    style = MaterialTheme.typography.bodySmall,
                )

                HorizontalDivider()

                // Zeitpunkt der Erinnerung
                Text("Erinnerung vor Dienstbeginn", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { m ->
                        FilterChip(
                            selected = lead == m,
                            onClick = {
                                lead = m
                                Store.setLeadMinutes(ctx, m)
                                Scheduler.scheduleNext(ctx)
                            },
                            label = { Text(if (m == 60) "1 Std." else "$m Min.") },
                        )
                    }
                }
                Text(
                    "Du wirst zu Beginn jedes zusammenhängenden Dienstblocks erinnert.",
                    style = MaterialTheme.typography.bodySmall,
                )

                HorizontalDivider()

                // Berechtigungen & Akku
                Text("Berechtigungen & Akku", style = MaterialTheme.typography.titleMedium)

                // Benachrichtigungen (Android 13+)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    Text(
                        "Benachrichtigungen sind zurzeit nicht erlaubt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Benachrichtigung erlauben")
                    }
                }

                // Exakte Alarme
                if (!Scheduler.canScheduleExact(ctx)) {
                    Text(
                        "Für pünktliche Erinnerungen muss „Alarme & Erinnerungen“ erlaubt sein.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    "package:${ctx.packageName}".toUri(),
                                ),
                            )
                        },
                    ) { Text("Berechtigung erteilen") }
                }

                // Akku-Optimierung
                val pm = remember { ctx.getSystemService(PowerManager::class.java) }
                if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Text(
                        "Für Erinnerungen bei gesperrtem Bildschirm bitte die Akku-Optimierung ausschalten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        "package:${ctx.packageName}".toUri(),
                                    ),
                                )
                            } catch (_: Exception) {
                                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                    ) { Text("Akku-Optimierung ausschalten") }
                }

                HorizontalDivider()

                // Datensicherung
                Text("Datensicherung", style = MaterialTheme.typography.titleMedium)

                if (autoBackupFailed) {
                    Text(
                        "Automatische Sicherung fehlgeschlagen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    "Letzte Sicherung: ${BackupManager.formatTimestamp(lastBackupTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            exportLauncher.launch("telefondienst-backup-${LocalDate.now()}.json")
                        },
                    ) {
                        Text("Backup exportieren")
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                    ) {
                        Text("Backup importieren")
                    }
                }

                if (autoBackupFolderName == null) {
                    Button(onClick = { folderLauncher.launch(null) }) {
                        Text("Automatische Sicherung einrichten")
                    }
                    Text(
                        "Sichert bei jeder Änderung an Stunden oder Vorlaufzeit automatisch in den gewählten Ordner.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Sicherungsordner: $autoBackupFolderName",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val res = BackupManager.readBackupFromFolder(ctx)
                                if (res.isSuccess) {
                                    pendingRestoreData = res.getOrNull()
                                    showRestoreConfirmDialog = true
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Fehler: ${res.exceptionOrNull()?.message}")
                                    }
                                }
                            },
                        ) {
                            Text("Aus Sicherungsordner wiederherstellen")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            BackupManager.disableAutoBackup(ctx)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Automatische Sicherung deaktiviert.")
                            }
                        },
                    ) {
                        Text("Automatische Sicherung aus")
                    }
                }

                HorizontalDivider()

                // Version
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Telefondienst", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Version 1.3.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        // HAUPTANSICHT
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("Telefondienst") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                        }
                    },
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (autoBackupFailed) {
                    Text(
                        text = "Automatische Sicherung fehlgeschlagen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Tag wählen
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButton(onClick = { date = date.previousWorkDay() }) { Text("◀") }
                    Text(date.format(dateFmt), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { date = date.nextWorkDay() }) { Text("▶") }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { date = date.minusWeeks(1) }) { Text("Vorherige Woche") }
                    TextButton(onClick = { date = LocalDate.now().toWorkDay() }) { Text("Heute") }
                    TextButton(onClick = { date = date.plusWeeks(1) }) { Text("Nächste Woche") }
                }

                // Feiertag-Anzeige oder Stunden-Slots
                if (holidayName != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Feiertag in ${selectedState.displayName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                            )
                            Text(
                                text = holidayName,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = "An gesetzlichen Feiertagen steht keine Stundenauswahl zur Verfügung.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    // Stunden-Slots
                    for (h in Store.FIRST_HOUR until Store.LAST_HOUR) {
                        val checked = h in hours
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { save(if (checked) hours - h else hours + h) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { save(if (checked) hours - h else hours + h) })
                            Text("%02d:00 – %02d:00 Uhr".format(h, h + 1), style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { save((Store.FIRST_HOUR until Store.LAST_HOUR).toSet()) }) {
                            Text("Ganzer Tag")
                        }
                        OutlinedButton(onClick = { save(emptySet()) }) { Text("Leeren") }
                    }
                    Text("${hours.size} Stunde(n) eingetragen", style = MaterialTheme.typography.bodyMedium)
                }

                HorizontalDivider()

                // Statistik (bezogen auf den gewählten Tag)
                val weekStart = date.with(DayOfWeek.MONDAY)
                val weekNo = date[WeekFields.ISO.weekOfWeekBasedYear()]
                val monthStart = date.withDayOfMonth(1)
                val stats = listOf(
                    "Woche $weekNo" to Store.hoursBetween(ctx, weekStart, weekStart.plusDays(4)),
                    date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)) to
                        Store.hoursBetween(ctx, monthStart, monthStart.plusMonths(1).minusDays(1)),
                    "Jahr ${date.year}" to
                        Store.hoursBetween(ctx, LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, 12, 31)),
                )
                Text("Statistik", style = MaterialTheme.typography.titleMedium)
                stats.forEach { (label, h) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text("$h Std.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
