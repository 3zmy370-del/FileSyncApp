package com.example.filesync

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val left: Uri? = null,
    val right: Uri? = null,
    val mode: SyncMode = SyncMode.TWO_WAY,
    val excludes: String = "",
    val items: List<SyncItem> = emptyList(),
    val compared: Boolean = false,
    val showEqual: Boolean = false,
    val busy: String? = null,
    val progress: Float? = null,
    val message: String? = null,
    val confirmDelete: Boolean = false
)

class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ---------- settings ----------

    private fun restore(): UiState {
        val mode = runCatching { SyncMode.valueOf(prefs.getString("mode", "") ?: "") }
            .getOrDefault(SyncMode.TWO_WAY)
        return UiState(
            left = validUri(prefs.getString("left", null)),
            right = validUri(prefs.getString("right", null)),
            mode = mode,
            excludes = prefs.getString("excludes", "") ?: ""
        )
    }

    private fun validUri(s: String?): Uri? {
        val uri = s?.let { Uri.parse(it) } ?: return null
        val ok = ctx.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return uri.takeIf { ok }
    }

    private fun persist() {
        val s = _state.value
        prefs.edit()
            .putString("left", s.left?.toString())
            .putString("right", s.right?.toString())
            .putString("mode", s.mode.name)
            .putString("excludes", s.excludes)
            .apply()
    }

    fun setFolder(isLeft: Boolean, uri: Uri) {
        _state.update {
            (if (isLeft) it.copy(left = uri) else it.copy(right = uri))
                .copy(items = emptyList(), compared = false)
        }
        persist()
    }

    fun setMode(mode: SyncMode) {
        _state.update { it.copy(mode = mode, items = emptyList(), compared = false) }
        persist()
    }

    fun setExcludes(text: String) {
        _state.update { it.copy(excludes = text, items = emptyList(), compared = false) }
        persist()
    }

    fun setShowEqual(v: Boolean) = _state.update { it.copy(showEqual = v) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun dismissConfirm() = _state.update { it.copy(confirmDelete = false) }

    // ---------- list interaction ----------

    /** Normal rows: include/exclude. Conflicts: cycle  none → left-to-right → right-to-left. */
    fun tap(rel: String) = _state.update { s ->
        s.copy(items = s.items.map { item ->
            if (item.rel != rel || item.action == Action.EQUAL) item
            else if (item.conflict) {
                val next = when (item.action) {
                    Action.CONFLICT -> Action.TO_RIGHT
                    Action.TO_RIGHT -> Action.TO_LEFT
                    else -> Action.CONFLICT
                }
                item.copy(action = next, enabled = next != Action.CONFLICT)
            } else item.copy(enabled = !item.enabled)
        })
    }

    // ---------- compare ----------

    fun compare() {
        val s = _state.value
        val l = s.left ?: return
        val r = s.right ?: return
        if (overlaps(l, r)) {
            _state.update { it.copy(message = "The two folders must not be the same or nested inside each other.") }
            return
        }
        _state.update { it.copy(busy = "Scanning folders…", progress = null, items = emptyList(), compared = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = SyncEngine.compare(ctx, l, r, s.mode, SyncEngine.parseExcludes(s.excludes))
                _state.update { it.copy(items = items, compared = true, busy = null) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = "Scan failed: ${e.message}") }
            }
        }
    }

    // ---------- synchronise ----------

    fun requestSync() {
        val hasDeletes = _state.value.items.any {
            it.enabled && (it.action == Action.DELETE_LEFT || it.action == Action.DELETE_RIGHT)
        }
        if (hasDeletes) _state.update { it.copy(confirmDelete = true) } else sync()
    }

    fun sync() {
        val s = _state.value
        val l = s.left ?: return
        val r = s.right ?: return
        _state.update { it.copy(confirmDelete = false, busy = "Starting…", progress = 0f) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = SyncEngine.synchronize(ctx, l, r, s.items) { done, total, name ->
                    _state.update {
                        it.copy(busy = "Syncing ${done + 1}/$total: $name", progress = done.toFloat() / total)
                    }
                }
                _state.update { it.copy(busy = "Refreshing…", progress = null) }
                val fresh = SyncEngine.compare(ctx, l, r, s.mode, SyncEngine.parseExcludes(s.excludes))
                val text = buildString {
                    append("${result.copied} copied (${formatSize(result.bytes)}), ${result.deleted} deleted")
                    if (result.errors.isNotEmpty()) {
                        append(", ${result.errors.size} failed:\n")
                        append(result.errors.take(5).joinToString("\n"))
                        if (result.errors.size > 5) append("\n…")
                    }
                }
                _state.update { it.copy(items = fresh, compared = true, busy = null, message = text) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = "Sync failed: ${e.message}") }
            }
        }
    }

    // ---------- helpers ----------

    private fun overlaps(a: Uri, b: Uri): Boolean {
        if (a.authority != b.authority) return false
        val x = DocumentsContract.getTreeDocumentId(a).trimEnd('/')
        val y = DocumentsContract.getTreeDocumentId(b).trimEnd('/')
        return x == y || isInside(x, y) || isInside(y, x)
    }

    private fun isInside(child: String, parent: String): Boolean =
        child.startsWith(if (parent.endsWith(":")) parent else "$parent/")
}
