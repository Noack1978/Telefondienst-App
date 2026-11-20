package de.mirko.telefondienst

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

object BackupManager {
    const val BACKUP_FILENAME = "telefondienst-backup.json"
    const val BACKUP_VERSION = 1

    private const val PREFS = "telefondienst"
    private const val KEY_AUTO_BACKUP_URI = "auto_backup_uri"
    private const val KEY_AUTO_BACKUP_FOLDER_NAME = "auto_backup_folder_name"
    private const val KEY_AUTO_BACKUP_FAILED = "auto_backup_failed"
    private const val KEY_LAST_BACKUP_TIME = "last_backup_time"

    private val _autoBackupFailed = MutableStateFlow(false)
    val autoBackupFailed: StateFlow<Boolean> = _autoBackupFailed.asStateFlow()

    private val _autoBackupFolderName = MutableStateFlow<String?>(null)
    val autoBackupFolderName: StateFlow<String?> = _autoBackupFolderName.asStateFlow()

    private val _lastBackupTime = MutableStateFlow<Long?>(null)
    val lastBackupTime: StateFlow<Long?> = _lastBackupTime.asStateFlow()

    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private val debounceMutex = Mutex()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = prefs(ctx)
        _autoBackupFailed.value = p.getBoolean(KEY_AUTO_BACKUP_FAILED, false)
        _autoBackupFolderName.value = p.getString(KEY_AUTO_BACKUP_FOLDER_NAME, null)
        val time = p.getLong(KEY_LAST_BACKUP_TIME, 0L)
        _lastBackupTime.value = if (time > 0L) time else null
    }

    fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "Noch keine"
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'", Locale.GERMAN)
        return ldt.format(formatter)
    }

    data class BackupData(
        val version: Int,
        val exportedAt: String,
        val leadMinutes: Int,
        val slots: Set<String>,
    )

    fun createBackupJson(slots: Set<String>, leadMinutes: Int): String {
        val json = JSONObject()
        json.put("version", BACKUP_VERSION)
        json.put("exportedAt", Instant.now().toString())
        json.put("lead_minutes", leadMinutes)
        val array = JSONArray()
        slots.sorted().forEach { array.put(it) }
        json.put("slots", array)
        return json.toString(2)
    }

    fun parseAndValidateBackup(jsonString: String): Result<BackupData> {
        if (jsonString.isBlank()) {
            return Result.failure(IllegalArgumentException("Die Datei ist leer."))
        }
        val json = try {
            JSONObject(jsonString)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Die Datei enthält kein gültiges JSON."))
        }

        if (!json.has("version")) {
            return Result.failure(IllegalArgumentException("Feld 'version' fehlt."))
        }
        val version = json.optInt("version", -1)
        if (version < 1) {
            return Result.failure(IllegalArgumentException("Ungültige Version: $version."))
        }

        if (!json.has("lead_minutes")) {
            return Result.failure(IllegalArgumentException("Feld 'lead_minutes' fehlt."))
        }
        val leadMinutes = json.optInt("lead_minutes", -1)
        if (leadMinutes !in 1..240) {
            return Result.failure(IllegalArgumentException("lead_minutes muss zwischen 1 und 240 liegen (angegeben: $leadMinutes)."))
        }

        if (!json.has("slots")) {
            return Result.failure(IllegalArgumentException("Feld 'slots' fehlt."))
        }
        val slotsArray = json.optJSONArray("slots")
            ?: return Result.failure(IllegalArgumentException("'slots' muss eine Liste sein."))

        val slotRegex = Regex("""^(\d{4}-\d{2}-\d{2})#(\d+)$""")
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)
        val validatedSlots = mutableSetOf<String>()

        for (i in 0 until slotsArray.length()) {
            val item = slotsArray.opt(i)
            if (item !is String) {
                return Result.failure(IllegalArgumentException("Slot an Position $i ist kein Text."))
            }
            val match = slotRegex.matchEntire(item)
                ?: return Result.failure(IllegalArgumentException("Slot '$item' hat ein ungültiges Format (erwartet: yyyy-MM-dd#Stunde)."))

            val datePart = match.groupValues[1]
            val hourPart = match.groupValues[2]

            try {
                LocalDate.parse(datePart, dateFormatter)
            } catch (_: Exception) {
                return Result.failure(IllegalArgumentException("Slot '$item' enthält kein echtes Datum."))
            }

            val hour = hourPart.toIntOrNull()
            if (hour == null || hour !in Store.FIRST_HOUR until Store.LAST_HOUR) {
                return Result.failure(IllegalArgumentException("Slot '$item' enthält eine unzulässige Stunde (erlaubt sind ${Store.FIRST_HOUR} bis ${Store.LAST_HOUR - 1})."))
            }
            validatedSlots.add(item)
        }

        val exportedAt = json.optString("exportedAt", "")
        return Result.success(BackupData(version, exportedAt, leadMinutes, validatedSlots))
    }

    fun exportToUri(context: Context, uri: Uri): Result<Unit> {
        return try {
            val slots = Store.rawSlots(context)
            val lead = Store.leadMinutes(context)
            val json = createBackupJson(slots, lead)
            val os = context.contentResolver.openOutputStream(uri, "wt")
                ?: return Result.failure(IOException("Ausgabestream konnte nicht geöffnet werden."))
            os.use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            val now = System.currentTimeMillis()
            recordManualBackupSuccess(context, now)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun readBackupFromUri(context: Context, uri: Uri): Result<BackupData> {
        return try {
            val isStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IOException("Eingabestream konnte nicht geöffnet werden."))
            val text = isStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
            parseAndValidateBackup(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun setupAutoBackup(context: Context, treeUri: Uri): Result<String> {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)

            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Result.failure(IOException("Ordner konnte nicht geöffnet werden."))
            val folderName = dir.name ?: "Ausgewählter Ordner"

            val p = prefs(context)
            p.edit {
                putString(KEY_AUTO_BACKUP_URI, treeUri.toString())
                putString(KEY_AUTO_BACKUP_FOLDER_NAME, folderName)
                putBoolean(KEY_AUTO_BACKUP_FAILED, false)
            }
            _autoBackupFolderName.value = folderName
            _autoBackupFailed.value = false

            // Prüfen, ob bereits eine Sicherungsdatei im Ordner vorhanden ist
            val existingFile = dir.findFile(BACKUP_FILENAME)
            if (existingFile != null && existingFile.exists() && existingFile.length() > 0) {
                // Vorhandene Sicherungsdatei erhalten (schützt vor Überschreiben bei Neuinstallation)
                val lastMod = existingFile.lastModified()
                if (lastMod > 0L) {
                    recordManualBackupSuccess(context, lastMod)
                }
            } else {
                // Noch keine Sicherungsdatei vorhanden -> direkt sichern (Punkt 5)
                performAutoBackupImmediate(context)
            }

            Result.success(folderName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disableAutoBackup(context: Context) {
        val uriStr = prefs(context).getString(KEY_AUTO_BACKUP_URI, null)
        if (uriStr != null) {
            try {
                val uri = uriStr.toUri()
                val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, releaseFlags)
            } catch (_: Exception) {}
        }
        prefs(context).edit {
            remove(KEY_AUTO_BACKUP_URI)
            remove(KEY_AUTO_BACKUP_FOLDER_NAME)
            putBoolean(KEY_AUTO_BACKUP_FAILED, false)
        }
        _autoBackupFolderName.value = null
        _autoBackupFailed.value = false
    }

    fun triggerAutoBackup(context: Context) {
        if (prefs(context).getString(KEY_AUTO_BACKUP_URI, null) == null) return
        backupScope.launch {
            debounceMutex.withLock {
                debounceJob?.cancel()
                debounceJob = backupScope.launch {
                    delay(2000L)
                    performAutoBackup(context)
                }
            }
        }
    }

    fun performAutoBackupImmediate(context: Context) {
        backupScope.launch {
            debounceMutex.withLock {
                debounceJob?.cancel()
                debounceJob = null
            }
            performAutoBackup(context)
        }
    }

    suspend fun performAutoBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        val uriStr = prefs(context).getString(KEY_AUTO_BACKUP_URI, null) ?: return@withContext false
        val uri = try {
            uriStr.toUri()
        } catch (_: Exception) {
            markAutoBackupFailed(context, true)
            return@withContext false
        }

        try {
            val dir = DocumentFile.fromTreeUri(context, uri)
            if (dir == null || !dir.exists() || !dir.isDirectory || !dir.canWrite()) {
                markAutoBackupFailed(context, true)
                return@withContext false
            }

            val existingFile = dir.findFile(BACKUP_FILENAME)
            val targetFile = existingFile ?: dir.createFile("application/json", BACKUP_FILENAME)
            if (targetFile == null) {
                markAutoBackupFailed(context, true)
                return@withContext false
            }

            val slots = Store.rawSlots(context)
            val lead = Store.leadMinutes(context)
            val json = createBackupJson(slots, lead)

            val os = context.contentResolver.openOutputStream(targetFile.uri, "wt")
                ?: run {
                    markAutoBackupFailed(context, true)
                    return@withContext false
                }

            os.use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
            }

            val now = System.currentTimeMillis()
            markAutoBackupSuccess(context, now)
            true
        } catch (_: Exception) {
            markAutoBackupFailed(context, true)
            false
        }
    }

    fun readBackupFromFolder(context: Context): Result<BackupData> {
        val uriStr = prefs(context).getString(KEY_AUTO_BACKUP_URI, null)
            ?: return Result.failure(IllegalStateException("Kein Sicherungsordner ausgewählt."))
        val uri = uriStr.toUri()

        return try {
            val dir = DocumentFile.fromTreeUri(context, uri)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                return Result.failure(IOException("Sicherungsordner ist nicht erreichbar."))
            }
            val file = dir.findFile(BACKUP_FILENAME)
                ?: return Result.failure(FileNotFoundException("Datei '$BACKUP_FILENAME' wurde im Sicherungsordner nicht gefunden."))

            val inputStream = context.contentResolver.openInputStream(file.uri)
                ?: return Result.failure(IOException("Datei konnte nicht geöffnet werden."))
            val content = inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
            parseAndValidateBackup(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun applyRestore(context: Context, backupData: BackupData) {
        backupScope.launch {
            debounceMutex.withLock {
                debounceJob?.cancel()
                debounceJob = null
            }
        }
        Store.restoreData(context, backupData.slots, backupData.leadMinutes)
        Scheduler.scheduleNext(context)
    }

    private fun markAutoBackupFailed(context: Context, failed: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_BACKUP_FAILED, failed) }
        _autoBackupFailed.value = failed
    }

    private fun markAutoBackupSuccess(context: Context, timestamp: Long) {
        prefs(context).edit {
            putBoolean(KEY_AUTO_BACKUP_FAILED, false)
            putLong(KEY_LAST_BACKUP_TIME, timestamp)
        }
        _autoBackupFailed.value = false
        _lastBackupTime.value = timestamp
    }

    private fun recordManualBackupSuccess(context: Context, timestamp: Long) {
        prefs(context).edit {
            putLong(KEY_LAST_BACKUP_TIME, timestamp)
        }
        _lastBackupTime.value = timestamp
    }
}
