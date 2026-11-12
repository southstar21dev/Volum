package dev.southstar21.volum.data

import dev.southstar21.volum.model.Collection
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.OutboxItem
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.util.nowIso
import dev.southstar21.volum.util.nowMillis
import dev.southstar21.volum.util.parseTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * createDirectories, tolerating another thread creating [dir] or one of its parents at the same
 * moment. kotlinx-io's native implementation checks, then mkdirs each level, and fails with "File
 * exists" if it loses that race (the JVM one doesn't). Losing it on a parent leaves [dir] itself not
 * yet made, so that case is tried again rather than taken as done: it dropped the first prefs write
 * of a new user on iOS, racing the store's own folder.
 */
internal fun ensureDirectory(dir: Path) {
    var attempt = 0
    while (true) {
        try {
            SystemFileSystem.createDirectories(dir)
            return
        } catch (e: IOException) {
            if (SystemFileSystem.metadataOrNull(dir)?.isDirectory == true) return
            if (++attempt >= 3) throw e
        }
    }
}

/** Reads and writes small JSON files atomically (temp file, then move). */
object JsonFiles {
    fun <T> read(path: Path, serializer: KSerializer<T>): T? = try {
        if (!SystemFileSystem.exists(path)) null
        else SystemFileSystem.source(path).buffered().use { VolumJson.decodeFromString(serializer, it.readString()) }
    } catch (_: Throwable) {
        null
    }

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) {
        path.parent?.let(::ensureDirectory)
        val tmp = Path("$path.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(VolumJson.encodeToString(serializer, value)) }
        SystemFileSystem.atomicMove(tmp, path)
    }

    fun delete(path: Path) {
        runCatching { if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path) }
    }
}

/**
 * The local-first database for one signed-in user: the source of truth the UI reads, written
 * immediately on every change and synced to PocketBase in the background (the web app's Dexie DB).
 * [owner] is the signed-in user: only their records are ever queued for the server.
 */
class LocalStore(private val dir: Path, private val scope: CoroutineScope, private val owner: String? = null) {
    private val mutex = Mutex()

    /** Held while files are written, so the writer and a final [flushNow] never write the same file at once. */
    private val fileLock = Mutex()

    private val _exercises = MutableStateFlow<Map<String, Exercise>>(emptyMap())
    private val _routines = MutableStateFlow<Map<String, Routine>>(emptyMap())
    private val _workouts = MutableStateFlow<Map<String, Workout>>(emptyMap())
    private val _outbox = MutableStateFlow<List<OutboxItem>>(emptyList())
    private val _meta = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _loaded = MutableStateFlow(false)
    private val _writeError = MutableStateFlow<String?>(null)

    val exercises: StateFlow<Map<String, Exercise>> = _exercises.asStateFlow()
    val routines: StateFlow<Map<String, Routine>> = _routines.asStateFlow()
    val workouts: StateFlow<Map<String, Workout>> = _workouts.asStateFlow()
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()
    val meta: StateFlow<Map<String, String>> = _meta.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Set while changes can't be written to disk (they stay in memory and are retried). */
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    private val exerciseSer = MapSerializer(String.serializer(), Exercise.serializer())
    private val routineSer = MapSerializer(String.serializer(), Routine.serializer())
    private val workoutSer = MapSerializer(String.serializer(), Workout.serializer())
    private val outboxSer = ListSerializer(OutboxItem.serializer())
    private val metaSer = MapSerializer(String.serializer(), String.serializer())

    private fun file(name: String) = Path(dir, "$name.json")

    /**
     * Reads every table. A file that doesn't decode is not read as empty: the records that still
     * decode are kept, the collection's pull marker is dropped so the next sync refetches it, and a
     * damaged outbox re-queues every local change the server may not have. The repair reaches disk
     * before this returns, and the damaged original is kept next to it (`.corrupt-<ms>`), so a
     * process killed at any point finds either the damaged file again or the repaired state.
     *
     * A file that exists but can't be read (an I/O error, not bad content) fails the load and is
     * left untouched, so the next attempt can read it.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        ensureDirectory(dir)
        val exercises = readTable("exercises", Exercise.serializer())
        val routines = readTable("routines", Routine.serializer())
        val workouts = readTable("workouts", Workout.serializer())
        val meta = readTable("meta", String.serializer())
        val outbox = readFile<List<OutboxItem>>("outbox", emptyList(), { VolumJson.decodeFromString(outboxSer, it) }) { json ->
            (json as JsonArray).mapNotNull { item -> runCatching { VolumJson.decodeFromJsonElement(OutboxItem.serializer(), item) }.getOrNull() }
        }

        var rewrite = 0
        val metaValue = LinkedHashMap(meta.value)
        if (meta.damaged != null) rewrite = rewrite or META
        for ((collection, table, bit) in listOf(
            Triple(Collection.Exercises, exercises, EXERCISES),
            Triple(Collection.Routines, routines, ROUTINES),
            Triple(Collection.Workouts, workouts, WORKOUTS),
        )) {
            val marker = "lastPull.${collection.wire}"
            if (table.damaged != null) {
                metaValue.remove(marker)
                rewrite = rewrite or bit or META
            } else if (table.missing && marker in metaValue && quarantinedBefore(collection.wire)) {
                // Pulled before, yet the table is gone and a damaged copy lies next to it: an earlier
                // build moved it aside and was killed before writing the repair. Pull it again in full
                // rather than trust the marker, and write the (empty) table so this happens once.
                metaValue.remove(marker)
                rewrite = rewrite or bit or META
            }
        }
        val outboxValue = if (outbox.damaged != null) {
            rewrite = rewrite or OUTBOX
            requeueUnsynced(outbox.value, exercises.value, routines.value, workouts.value)
        } else {
            outbox.value
        }

        // Repair on disk now: first a copy of each damaged original, then meta without the dropped
        // markers, then the salvaged tables. What can't be written is left to the writer to retry.
        val stamp = nowMillis()
        for ((name, loaded) in listOf("exercises" to exercises, "routines" to routines, "workouts" to workouts, "meta" to meta, "outbox" to outbox)) {
            val original = loaded.damaged ?: continue
            try {
                writeText(Path(dir, "$name.json.corrupt-$stamp"), original)
            } catch (_: Exception) {
                // Keeping the damaged copy is a courtesy; the repair goes ahead without it.
            }
        }
        var failed = 0
        fileLock.withLock {
            if (rewrite and META != 0 && !tryWrite("meta", metaSer, metaValue)) failed = failed or META
            if (rewrite and EXERCISES != 0 && !tryWrite("exercises", exerciseSer, exercises.value)) failed = failed or EXERCISES
            if (rewrite and ROUTINES != 0 && !tryWrite("routines", routineSer, routines.value)) failed = failed or ROUTINES
            if (rewrite and WORKOUTS != 0 && !tryWrite("workouts", workoutSer, workouts.value)) failed = failed or WORKOUTS
            if (rewrite and OUTBOX != 0 && !tryWrite("outbox", outboxSer, outboxValue)) failed = failed or OUTBOX
        }

        mutex.withLock {
            _exercises.value = exercises.value
            _routines.value = routines.value
            _workouts.value = workouts.value
            _outbox.value = outboxValue
            _meta.value = metaValue
            if (failed != 0) markDirty(failed)
        }
        _loaded.value = true
    }

    /** A file's contents; [damaged] holds the original text when it didn't decode whole, [missing] when there was no file. */
    private class Loaded<T>(val value: T, val damaged: String? = null, val missing: Boolean = false)

    private suspend fun <T> readTable(name: String, serializer: KSerializer<T>): Loaded<Map<String, T>> =
        readFile(name, emptyMap(), { VolumJson.decodeFromString(MapSerializer(String.serializer(), serializer), it) }) { json ->
            val records = LinkedHashMap<String, T>()
            for ((id, value) in json.jsonObject) {
                runCatching { VolumJson.decodeFromJsonElement(serializer, value) }.onSuccess { records[id] = it }
            }
            records
        }

    /**
     * Decodes a file whole ([decode]); when that fails, keeps what [salvage] recovers record by record
     * from the parsed JSON. A missing file is simply [empty]. A file that can't be read is tried a few
     * times, then fails the load with an [IOException]: bad luck reading isn't bad content.
     */
    private suspend fun <T> readFile(name: String, empty: T, decode: (String) -> T, salvage: (JsonElement) -> T): Loaded<T> {
        val path = file(name)
        var attempt = 0
        var read: String? = null
        while (read == null) {
            try {
                if (!SystemFileSystem.exists(path)) return Loaded(empty, missing = true)
                read = SystemFileSystem.source(path).buffered().use { it.readString() }
            } catch (e: Exception) {
                if (++attempt >= READ_ATTEMPTS) throw IOException("Couldn't read $name.json", e)
                delay(READ_RETRY_MS)
            }
        }
        val text: String = read
        try {
            return Loaded(decode(text))
        } catch (_: Exception) {
        }
        val salvaged = runCatching { salvage(VolumJson.parseToJsonElement(text)) }.getOrNull() ?: empty
        return Loaded(salvaged, damaged = text)
    }

    /** Whether a damaged copy of [name]'s table was set aside here before. */
    private fun quarantinedBefore(name: String): Boolean =
        runCatching { SystemFileSystem.list(dir).any { it.name.startsWith("$name.json.corrupt-") } }.getOrDefault(false)

    private fun writeText(path: Path, text: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    /**
     * Queues every local record of [owner]'s the server may not have, for an outbox that couldn't be
     * read. A local save or delete drops the server's `updated`, so a record without it changed here
     * since its last pull; a delete by an earlier build kept it, so a deleted record edited after its
     * server copy counts too. Records the server already has are left alone: pushing a stale whole
     * copy could overwrite newer changes. Other users' records (the exercise library, a partner's
     * routines) are never queued: the server would refuse them.
     */
    private fun requeueUnsynced(
        kept: List<OutboxItem>,
        exercises: Map<String, Exercise>,
        routines: Map<String, Routine>,
        workouts: Map<String, Workout>,
    ): List<OutboxItem> {
        val queued = kept.map { it.collection to it.recordId }.toSet()
        val out = ArrayList(kept)
        var next = (kept.maxOfOrNull { it.id } ?: 0L) + 1
        val now = nowIso()
        fun unsynced(recordOwner: String, updated: String?, updatedAt: String, deleted: Boolean) =
            (owner == null || recordOwner == owner) &&
                (updated == null || (deleted && parseTime(updatedAt) > parseTime(updated)))
        fun queue(collection: Collection, id: String, payload: JsonObject) {
            if ((collection.wire to id) in queued) return
            out += OutboxItem(id = next++, collection = collection.wire, recordId = id, payload = payload, createdAt = now)
        }
        for (e in exercises.values) if (unsynced(e.owner, e.updated, e.updatedAt, e.deleted)) {
            queue(Collection.Exercises, e.id, VolumJson.encodeToJsonElement(Exercise.serializer(), e.copy(updated = null)).jsonObject)
        }
        for (r in routines.values) if (unsynced(r.owner, r.updated, r.updatedAt, r.deleted)) {
            queue(Collection.Routines, r.id, VolumJson.encodeToJsonElement(Routine.serializer(), r.copy(updated = null)).jsonObject)
        }
        for (w in workouts.values) if (unsynced(w.owner, w.updated, w.updatedAt, w.deleted)) {
            queue(Collection.Workouts, w.id, VolumJson.encodeToJsonElement(Workout.serializer(), w.copy(updated = null)).jsonObject)
        }
        return out
    }

    /**
     * Runs [block] with exclusive access. Changes are made on working copies and published once at
     * the end (one StateFlow emission per table, however many records the block touched); every
     * table it changed is then written to disk.
     */
    suspend fun <T> transaction(block: Tx.() -> T): T = mutex.withLock {
        val tx = Tx()
        val result = tx.block()
        tx.commit()
        if (tx.touched != 0) markDirty(tx.touched)
        result
    }

    /** Marks tables for writing and starts the writer if it isn't running. Call while holding [mutex]. */
    private fun markDirty(tables: Int) {
        dirty = dirty or tables
        if (!flushing) {
            flushing = true
            scope.launch { flush() }
        }
    }

    // Tables changed since they were last written, and whether the writer is running; both only
    // touched while holding [mutex]. One writer drains them, so a burst of transactions (a sync
    // pull, quick set edits) costs a write per table rather than one per change.
    private var dirty = 0
    private var flushing = false

    private suspend fun flush() {
        var retryMs = WRITE_RETRY_MS
        while (true) {
            var ex: Map<String, Exercise>? = null
            var ro: Map<String, Routine>? = null
            var wo: Map<String, Workout>? = null
            var ob: List<OutboxItem>? = null
            var me: Map<String, String>? = null
            val done = mutex.withLock {
                val d = dirty
                dirty = 0
                if (d == 0) flushing = false
                if (d and EXERCISES != 0) ex = _exercises.value
                if (d and ROUTINES != 0) ro = _routines.value
                if (d and WORKOUTS != 0) wo = _workouts.value
                if (d and OUTBOX != 0) ob = _outbox.value
                if (d and META != 0) me = _meta.value
                d == 0
            }
            if (done) return
            val failed = fileLock.withLock {
                withContext(Dispatchers.IO) { writeTables(ex, ro, wo, ob, me) }
            }
            if (failed == 0) {
                _writeError.value = null
                retryMs = WRITE_RETRY_MS
                continue
            }
            // Disk full or similar: keep the tables dirty and try again later rather than losing the
            // write until the table next changes.
            mutex.withLock { dirty = dirty or failed }
            _writeError.value = "Couldn't save changes on this device"
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(MAX_WRITE_RETRY_MS)
        }
    }

    private fun writeTables(
        ex: Map<String, Exercise>?,
        ro: Map<String, Routine>?,
        wo: Map<String, Workout>?,
        ob: List<OutboxItem>?,
        me: Map<String, String>?,
    ): Int {
        var bits = 0
        ex?.let { if (!tryWrite("exercises", exerciseSer, it)) bits = bits or EXERCISES }
        ro?.let { if (!tryWrite("routines", routineSer, it)) bits = bits or ROUTINES }
        wo?.let { if (!tryWrite("workouts", workoutSer, it)) bits = bits or WORKOUTS }
        ob?.let { if (!tryWrite("outbox", outboxSer, it)) bits = bits or OUTBOX }
        me?.let { if (!tryWrite("meta", metaSer, it)) bits = bits or META }
        return bits
    }

    /**
     * Writes the tables once more, now, for a store being closed: its scope (and so the writer, which
     * may be waiting to retry or not yet started) is about to be cancelled. Every table is written
     * when any write is pending, since a cancelled writer may already have taken the dirty marks.
     * The file lock keeps this from interleaving with a write already under way.
     */
    suspend fun flushNow() {
        if (!_loaded.value) return
        val (ex, ro, wo, ob, me) = mutex.withLock {
            if (dirty == 0 && !flushing) return
            dirty = 0
            Tables(_exercises.value, _routines.value, _workouts.value, _outbox.value, _meta.value)
        }
        val failed = fileLock.withLock { withContext(Dispatchers.IO) { writeTables(ex, ro, wo, ob, me) } }
        if (failed != 0) mutex.withLock { dirty = dirty or failed }
    }

    private data class Tables(
        val exercises: Map<String, Exercise>,
        val routines: Map<String, Routine>,
        val workouts: Map<String, Workout>,
        val outbox: List<OutboxItem>,
        val meta: Map<String, String>,
    )

    private fun <T> tryWrite(name: String, serializer: KSerializer<T>, value: T): Boolean = try {
        JsonFiles.write(file(name), serializer, value)
        true
    } catch (_: Exception) {
        false
    }

    inner class Tx {
        internal var touched = 0
            private set

        private var ex: MutableMap<String, Exercise>? = null
        private var ro: MutableMap<String, Routine>? = null
        private var wo: MutableMap<String, Workout>? = null
        private var ob: MutableList<OutboxItem>? = null
        private var me: MutableMap<String, String>? = null

        val exercises: Map<String, Exercise> get() = ex ?: _exercises.value
        val routines: Map<String, Routine> get() = ro ?: _routines.value
        val workouts: Map<String, Workout> get() = wo ?: _workouts.value
        val outbox: List<OutboxItem> get() = ob ?: _outbox.value
        val meta: Map<String, String> get() = me ?: _meta.value

        private fun exW() = ex ?: LinkedHashMap(_exercises.value).also { ex = it; touched = touched or EXERCISES }
        private fun roW() = ro ?: LinkedHashMap(_routines.value).also { ro = it; touched = touched or ROUTINES }
        private fun woW() = wo ?: LinkedHashMap(_workouts.value).also { wo = it; touched = touched or WORKOUTS }
        private fun obW() = ob ?: ArrayList(_outbox.value).also { ob = it; touched = touched or OUTBOX }
        private fun meW() = me ?: LinkedHashMap(_meta.value).also { me = it; touched = touched or META }

        // Writing a record identical to the stored one (a sync re-pull) changes nothing.
        fun putExercise(e: Exercise) { if (exercises[e.id] != e) exW()[e.id] = e }
        fun putRoutine(r: Routine) { if (routines[r.id] != r) roW()[r.id] = r }
        fun putWorkout(w: Workout) { if (workouts[w.id] != w) woW()[w.id] = w }
        fun removeExercises(ids: Set<String>) { if (ids.any { it in exercises }) exW().keys.removeAll(ids) }
        fun removeRoutines(ids: Set<String>) { if (ids.any { it in routines }) roW().keys.removeAll(ids) }

        fun addOutbox(item: (Long) -> OutboxItem) {
            val list = obW()
            list.add(item((list.maxOfOrNull { it.id } ?: 0L) + 1))
        }

        /**
         * Queues [item] in place of any pending (not rejected) item for the same record: payloads are
         * whole records, so only the newest needs pushing. Its id is above every current one, so an
         * item already being pushed can't share it.
         */
        fun replaceOutbox(collection: String, recordId: String, item: (Long) -> OutboxItem) {
            val list = obW()
            val next = (list.maxOfOrNull { it.id } ?: 0L) + 1
            list.removeAll { !it.failed && it.collection == collection && it.recordId == recordId }
            list.add(item(next))
        }

        fun updateOutbox(transform: (List<OutboxItem>) -> List<OutboxItem>) {
            val next = transform(outbox)
            if (next != outbox) ob = ArrayList(next).also { touched = touched or OUTBOX }
        }

        fun putMeta(key: String, value: String) { if (meta[key] != value) meW()[key] = value }
        fun deleteMeta(key: String) { if (key in meta) meW().remove(key) }

        internal fun commit() {
            ex?.let { _exercises.value = it }
            ro?.let { _routines.value = it }
            wo?.let { _workouts.value = it }
            ob?.let { _outbox.value = it }
            me?.let { _meta.value = it }
        }
    }

    private companion object {
        const val EXERCISES = 1
        const val ROUTINES = 2
        const val WORKOUTS = 4
        const val OUTBOX = 8
        const val META = 16
        const val WRITE_RETRY_MS = 1_000L
        const val MAX_WRITE_RETRY_MS = 60_000L
        const val READ_ATTEMPTS = 3
        const val READ_RETRY_MS = 50L
    }
}
