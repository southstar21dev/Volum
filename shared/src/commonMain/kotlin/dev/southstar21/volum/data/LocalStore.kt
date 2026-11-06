package dev.southstar21.volum.data

import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.OutboxItem
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Reads and writes small JSON files atomically (temp file, then move). */
object JsonFiles {
    fun <T> read(path: Path, serializer: KSerializer<T>): T? = try {
        if (!SystemFileSystem.exists(path)) null
        else SystemFileSystem.source(path).buffered().use { VolumJson.decodeFromString(serializer, it.readString()) }
    } catch (_: Throwable) {
        null
    }

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
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
 */
class LocalStore(private val dir: Path, private val scope: CoroutineScope) {
    private val mutex = Mutex()

    private val _exercises = MutableStateFlow<Map<String, Exercise>>(emptyMap())
    private val _routines = MutableStateFlow<Map<String, Routine>>(emptyMap())
    private val _workouts = MutableStateFlow<Map<String, Workout>>(emptyMap())
    private val _outbox = MutableStateFlow<List<OutboxItem>>(emptyList())
    private val _meta = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _loaded = MutableStateFlow(false)

    val exercises: StateFlow<Map<String, Exercise>> = _exercises.asStateFlow()
    val routines: StateFlow<Map<String, Routine>> = _routines.asStateFlow()
    val workouts: StateFlow<Map<String, Workout>> = _workouts.asStateFlow()
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()
    val meta: StateFlow<Map<String, String>> = _meta.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val exerciseSer = MapSerializer(String.serializer(), Exercise.serializer())
    private val routineSer = MapSerializer(String.serializer(), Routine.serializer())
    private val workoutSer = MapSerializer(String.serializer(), Workout.serializer())
    private val outboxSer = ListSerializer(OutboxItem.serializer())
    private val metaSer = MapSerializer(String.serializer(), String.serializer())

    private fun file(name: String) = Path(dir, "$name.json")

    suspend fun load() = withContext(Dispatchers.IO) {
        SystemFileSystem.createDirectories(dir)
        _exercises.value = JsonFiles.read(file("exercises"), exerciseSer) ?: emptyMap()
        _routines.value = JsonFiles.read(file("routines"), routineSer) ?: emptyMap()
        _workouts.value = JsonFiles.read(file("workouts"), workoutSer) ?: emptyMap()
        _outbox.value = JsonFiles.read(file("outbox"), outboxSer) ?: emptyList()
        _meta.value = JsonFiles.read(file("meta"), metaSer) ?: emptyMap()
        _loaded.value = true
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
        if (tx.touched != 0) {
            dirty = dirty or tx.touched
            if (!flushing) {
                flushing = true
                scope.launch { flush() }
            }
        }
        result
    }

    // Tables changed since they were last written, and whether the writer is running; both only
    // touched while holding [mutex]. One writer drains them, so a burst of transactions (a sync
    // pull, quick set edits) costs a write per table rather than one per change.
    private var dirty = 0
    private var flushing = false

    private suspend fun flush() {
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
            withContext(Dispatchers.IO) {
                ex?.let { runCatching { JsonFiles.write(file("exercises"), exerciseSer, it) } }
                ro?.let { runCatching { JsonFiles.write(file("routines"), routineSer, it) } }
                wo?.let { runCatching { JsonFiles.write(file("workouts"), workoutSer, it) } }
                ob?.let { runCatching { JsonFiles.write(file("outbox"), outboxSer, it) } }
                me?.let { runCatching { JsonFiles.write(file("meta"), metaSer, it) } }
            }
        }
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
    }
}
