package com.example.filesync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

enum class SyncMode(val label: String, val description: String) {
    TWO_WAY(
        "Two-way ↔",
        "Newer file wins on both sides. Deletions are carried over for files that were synced before."
    ),
    MIRROR(
        "Mirror →",
        "Make the right folder identical to the left one. Extra files on the right are deleted."
    ),
    UPDATE(
        "Update →",
        "Copy new and changed files from left to right. Nothing is ever deleted."
    )
}

enum class Action(val symbol: String, val active: Boolean) {
    EQUAL("=", false),
    TO_RIGHT("→", true),
    TO_LEFT("←", true),
    DELETE_LEFT("✕ L", true),
    DELETE_RIGHT("R ✕", true),
    CONFLICT("⚠", false),
    SKIP("·", false)
}

data class FileEntry(val rel: String, val uri: Uri, val size: Long, val modified: Long)

data class SyncItem(
    val rel: String,
    val left: FileEntry?,
    val right: FileEntry?,
    val action: Action,
    val enabled: Boolean,
    val conflict: Boolean = false
)

data class SyncResult(val copied: Int, val deleted: Int, val bytes: Long, val errors: List<String>)

/** What both sides looked like right after the last successful sync of a file. */
data class Rec(val size: Long, val left: Long, val right: Long)

class Records private constructor(
    private val file: File,
    private val map: MutableMap<String, Rec>
) {
    operator fun get(rel: String): Rec? = map[rel]
    fun put(rel: String, rec: Rec) { map[rel] = rec }
    fun remove(rel: String) { map.remove(rel) }

    fun save() {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.size, v.left, v.right))) }
        file.writeText(o.toString())
    }

    companion object {
        fun load(ctx: Context, left: Uri, right: Uri): Records {
            val name = "rec_" + Integer.toHexString("$left|$right".hashCode()) + ".json"
            val f = File(ctx.filesDir, name)
            val m = HashMap<String, Rec>()
            if (f.exists()) {
                runCatching {
                    val o = JSONObject(f.readText())
                    o.keys().forEach { k ->
                        val a = o.getJSONArray(k)
                        m[k] = Rec(a.getLong(0), a.getLong(1), a.getLong(2))
                    }
                }
            }
            return Records(f, m)
        }
    }
}

object SyncEngine {
    // FAT/exFAT and some providers only keep 2 s precision.
    private const val TOLERANCE_MS = 2000L

    // ---------- scanning ----------

    fun scan(ctx: Context, treeUri: Uri): Map<String, FileEntry> {
        val out = HashMap<String, FileEntry>()
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
        val stack = ArrayList<Pair<String, String>>()
        stack.add(DocumentsContract.getTreeDocumentId(treeUri) to "")
        while (stack.isNotEmpty()) {
            val (docId, prefix) = stack.removeAt(stack.lastIndex)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        stack.add(id to rel)
                    } else {
                        out[rel] = FileEntry(
                            rel,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            if (c.isNull(3)) 0L else c.getLong(3),
                            if (c.isNull(4)) 0L else c.getLong(4)
                        )
                    }
                }
            }
        }
        return out
    }

    // ---------- filter ----------

    /** Comma/semicolon/newline separated globs; `*` and `?` are supported. */
    fun parseExcludes(text: String): List<Regex> =
        text.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { glob ->
                val pattern = glob.map {
                    when (it) {
                        '*' -> ".*"
                        '?' -> "."
                        else -> Regex.escape(it.toString())
                    }
                }.joinToString("")
                Regex(pattern, RegexOption.IGNORE_CASE)
            }

    private fun isExcluded(rel: String, excludes: List<Regex>): Boolean =
        excludes.any { re -> re.matches(rel) || rel.split('/').any { re.matches(it) } }

    // ---------- comparison ----------

    fun compare(
        ctx: Context,
        left: Uri,
        right: Uri,
        mode: SyncMode,
        excludes: List<Regex>
    ): List<SyncItem> {
        val l = scan(ctx, left)
        val r = scan(ctx, right)
        val records = Records.load(ctx, left, right)
        val items = ArrayList<SyncItem>()
        for (rel in (l.keys + r.keys).toSet().sorted()) {
            if (isExcluded(rel, excludes)) continue
            val action = decide(l[rel], r[rel], records[rel], mode)
            items += SyncItem(
                rel = rel,
                left = l[rel],
                right = r[rel],
                action = action,
                enabled = action.active,
                conflict = action == Action.CONFLICT
            )
        }
        return items
    }

    private fun decide(a: FileEntry?, b: FileEntry?, rc: Rec?, mode: SyncMode): Action {
        if (a != null && b != null) {
            val same = a.size == b.size && (
                abs(a.modified - b.modified) <= TOLERANCE_MS ||
                    (rc != null && rc.size == a.size && rc.left == a.modified && rc.right == b.modified)
                )
            if (same) return Action.EQUAL
            return when (mode) {
                SyncMode.MIRROR -> Action.TO_RIGHT
                SyncMode.UPDATE -> if (a.modified > b.modified) Action.TO_RIGHT else Action.SKIP
                SyncMode.TWO_WAY ->
                    if (rc == null) {
                        if (a.modified >= b.modified) Action.TO_RIGHT else Action.TO_LEFT
                    } else {
                        val leftChanged = a.size != rc.size || a.modified != rc.left
                        val rightChanged = b.size != rc.size || b.modified != rc.right
                        when {
                            leftChanged && rightChanged -> Action.CONFLICT
                            leftChanged -> Action.TO_RIGHT
                            rightChanged -> Action.TO_LEFT
                            else -> Action.EQUAL
                        }
                    }
            }
        }
        if (a != null) { // only on the left
            return if (mode == SyncMode.TWO_WAY && rc != null && a.size == rc.size && a.modified == rc.left)
                Action.DELETE_LEFT // was synced before, right side deleted it, left unchanged
            else
                Action.TO_RIGHT
        }
        if (b != null) { // only on the right
            return when (mode) {
                SyncMode.MIRROR -> Action.DELETE_RIGHT
                SyncMode.UPDATE -> Action.SKIP
                SyncMode.TWO_WAY ->
                    if (rc != null && b.size == rc.size && b.modified == rc.right) Action.DELETE_RIGHT
                    else Action.TO_LEFT
            }
        }
        return Action.EQUAL
    }

    // ---------- synchronisation ----------

    fun synchronize(
        ctx: Context,
        left: Uri,
        right: Uri,
        items: List<SyncItem>,
        onProgress: (done: Int, total: Int, name: String) -> Unit
    ): SyncResult {
        val leftRoot = DocumentFile.fromTreeUri(ctx, left) ?: error("Left folder is not accessible")
        val rightRoot = DocumentFile.fromTreeUri(ctx, right) ?: error("Right folder is not accessible")
        val leftDirs = HashMap<String, DocumentFile>()
        val rightDirs = HashMap<String, DocumentFile>()
        val records = Records.load(ctx, left, right)

        // copies first, deletions last
        val todo = items
            .filter { it.enabled && it.action.active }
            .sortedBy { if (it.action == Action.DELETE_LEFT || it.action == Action.DELETE_RIGHT) 1 else 0 }

        var copied = 0
        var deleted = 0
        var bytes = 0L
        val errors = ArrayList<String>()

        todo.forEachIndexed { i, item ->
            onProgress(i, todo.size, item.rel)
            try {
                when (item.action) {
                    Action.TO_RIGHT -> {
                        val src = item.left!!
                        val (size, mod) = copy(ctx, src, rightRoot, item.right, rightDirs)
                        records.put(item.rel, Rec(size, src.modified, mod))
                        copied++; bytes += size
                    }
                    Action.TO_LEFT -> {
                        val src = item.right!!
                        val (size, mod) = copy(ctx, src, leftRoot, item.left, leftDirs)
                        records.put(item.rel, Rec(size, mod, src.modified))
                        copied++; bytes += size
                    }
                    Action.DELETE_LEFT -> {
                        delete(ctx, item.left!!.uri); records.remove(item.rel); deleted++
                    }
                    Action.DELETE_RIGHT -> {
                        delete(ctx, item.right!!.uri); records.remove(item.rel); deleted++
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                errors += "${item.rel}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // remember identical pairs too, so later deletions can be detected
        items.filter { it.action == Action.EQUAL && it.left != null && it.right != null }
            .forEach { records.put(it.rel, Rec(it.left!!.size, it.left.modified, it.right!!.modified)) }
        records.save()

        return SyncResult(copied, deleted, bytes, errors)
    }

    private fun copy(
        ctx: Context,
        src: FileEntry,
        destRoot: DocumentFile,
        existing: FileEntry?,
        dirCache: MutableMap<String, DocumentFile>
    ): Pair<Long, Long> {
        val destUri: Uri = existing?.uri ?: run {
            val parent = ensureDir(destRoot, src.rel.substringBeforeLast('/', ""), dirCache)
            val name = src.rel.substringAfterLast('/')
            parent.findFile(name)?.uri
                ?: parent.createFile(mimeFor(name), name)?.uri
                ?: error("Cannot create file")
        }
        val resolver = ctx.contentResolver
        val input = resolver.openInputStream(src.uri) ?: error("Cannot read source")
        input.use { i ->
            val output = resolver.openOutputStream(destUri, "wt") ?: error("Cannot write target")
            output.use { o -> i.copyTo(o, 128 * 1024) }
        }
        val doc = DocumentFile.fromSingleUri(ctx, destUri)
        return (doc?.length() ?: src.size) to (doc?.lastModified() ?: 0L)
    }

    private fun ensureDir(
        root: DocumentFile,
        rel: String,
        cache: MutableMap<String, DocumentFile>
    ): DocumentFile {
        if (rel.isEmpty()) return root
        cache[rel]?.let { return it }
        val parent = ensureDir(root, rel.substringBeforeLast('/', ""), cache)
        val name = rel.substringAfterLast('/')
        val dir = parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: error("Cannot create folder $rel")
        cache[rel] = dir
        return dir
    }

    private fun delete(ctx: Context, uri: Uri) {
        if (!DocumentsContract.deleteDocument(ctx.contentResolver, uri)) error("Delete failed")
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
