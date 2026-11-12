// Shared by partner.pb.js through require(). Hook handlers can't see each
// other's top-level functions, so anything they share lives here.

const SET_FIELDS = ["weight", "unit", "reps", "seconds", "tag", "note", "done"];
// What a partner may change on an exercise; its sets go through the set mutations.
const ENTRY_FIELDS = ["exerciseId", "name", "timed", "skipped"];

// PocketBase dates read "2026-10-20 10:00:00.000Z"; make them ISO before parsing.
function parseTime(value) {
  const parsed = Date.parse(String(value || "").replace(" ", "T"));
  return Number.isNaN(parsed) ? 0 : parsed;
}

function pick(source, keys) {
  const out = {};
  for (const key of keys) {
    if (source && Object.prototype.hasOwnProperty.call(source, key)) out[key] = source[key];
  }
  return out;
}

function pickSetFields(source) {
  return pick(source, SET_FIELDS);
}

// Applies one partner mutation, stamping what it touches so the owner's next
// whole-record sync keeps it (see mergeEntries). Returns null when the
// mutation is malformed.
function applyMutation(entries, mutation, now) {
  if (mutation && mutation.kind === "addEntry") {
    const entry = mutation.entry;
    if (
      !entry ||
      typeof entry.id !== "string" ||
      typeof entry.exerciseId !== "string" ||
      typeof entry.name !== "string" ||
      !entry.name.trim() ||
      !Array.isArray(entry.sets) ||
      entries.some((candidate) => candidate.id === entry.id)
    ) {
      return null;
    }
    return entries.concat([
      Object.assign(pick(entry, ENTRY_FIELDS), {
        id: entry.id,
        sets: entry.sets
          .filter((set) => set && typeof set.id === "string")
          .map((set) => Object.assign(pickSetFields(set), { id: set.id, updatedAt: now })),
        updatedAt: now,
      }),
    ]);
  }
  if (!mutation || typeof mutation.entryId !== "string") return null;
  const onEntry = (fn) =>
    entries.map((entry) => (entry.id === mutation.entryId ? fn(entry) : entry));

  switch (mutation.kind) {
    case "set":
      if (typeof mutation.setId !== "string") return null;
      return onEntry((entry) =>
        Object.assign({}, entry, {
          sets: entry.sets.map((set) =>
            set.id === mutation.setId
              ? Object.assign({}, set, pickSetFields(mutation.patch), { updatedAt: now })
              : set,
          ),
        }),
      );
    case "addSet":
      if (!mutation.set || typeof mutation.set.id !== "string") return null;
      return onEntry((entry) =>
        Object.assign({}, entry, {
          sets: entry.sets.concat([
            Object.assign(pickSetFields(mutation.set), { id: mutation.set.id, updatedAt: now }),
          ]),
        }),
      );
    case "removeSet":
      if (typeof mutation.setId !== "string") return null;
      return onEntry((entry) =>
        Object.assign({}, entry, {
          sets: entry.sets.filter((set) => set.id !== mutation.setId),
          removedSetIds: (entry.removedSetIds || []).concat([mutation.setId]),
        }),
      );
    case "updateEntry":
      if (!mutation.patch || typeof mutation.patch !== "object") return null;
      if ("name" in mutation.patch && !String(mutation.patch.name || "").trim()) return null;
      return onEntry((entry) =>
        Object.assign({}, entry, pick(mutation.patch, ENTRY_FIELDS), { updatedAt: now }),
      );
    case "removeEntry":
      // The caller records the id in the workout's removedEntryIds.
      return entries.filter((entry) => entry.id !== mutation.entryId);
    default:
      return null;
  }
}

function newer(a, b) {
  return parseTime(a && a.updatedAt) > parseTime(b && b.updatedAt);
}

// Mirrors mergeWorkoutEntries in web/src/lib/workoutMerge.ts — keep both in sync.
function mergeEntries(server, snapshot, snapshotAt, removedEntryIds) {
  const snapshotTime = parseTime(snapshotAt);
  const removedEntries = new Set(removedEntryIds || []);
  const snapshotEntryIds = new Set(snapshot.map((entry) => entry.id));
  const merged = snapshot.filter((entry) => !removedEntries.has(entry.id)).map((entry) => {
    const stored = server.find((candidate) => candidate.id === entry.id);
    if (!stored) return entry;
    const removed = new Set((stored.removedSetIds || []).concat(entry.removedSetIds || []));
    const snapshotIds = new Set(entry.sets.map((set) => set.id));
    const sets = entry.sets
      .filter((set) => !removed.has(set.id))
      .map((set) => {
        const other = stored.sets.find((candidate) => candidate.id === set.id);
        return other && parseTime(other.updatedAt) > parseTime(set.updatedAt) ? other : set;
      });
    for (const set of stored.sets) {
      if (!snapshotIds.has(set.id) && !removed.has(set.id) && parseTime(set.updatedAt) > snapshotTime) {
        sets.push(set);
      }
    }
    const out = Object.assign({}, newer(stored, entry) ? stored : entry, { sets: sets });
    if (removed.size > 0) out.removedSetIds = Array.from(removed);
    return out;
  });
  for (const stored of server) {
    if (!snapshotEntryIds.has(stored.id) && !removedEntries.has(stored.id) && parseTime(stored.updatedAt) > snapshotTime) {
      merged.push(stored);
    }
  }
  return merged;
}

module.exports = { applyMutation, mergeEntries };
