// Shared by partner.pb.js through require(). Hook handlers can't see each
// other's top-level functions, so anything they share lives here.
//
// WorkoutMerge.kt mirrors this file. The JSON fixtures in test-fixtures/workout-merge run
// against both (backend/tests/workout_merge.test.js and WorkoutMergeParityTest.kt).

const MAX_NAME = 200;
const MAX_NOTE = 1000;
const MAX_NUMBER = 100000;

// PocketBase dates read "2026-10-20 10:00:00.000Z"; make them ISO before parsing.
function parseTime(value) {
  const parsed = Date.parse(String(value || "").replace(" ", "T"));
  return Number.isNaN(parsed) ? 0 : parsed;
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

// JSON.parse("null") is null, not [], so anything read from a stored field goes through this.
function asList(value) {
  return Array.isArray(value) ? value : [];
}

// Exercises and sets as stored; skips anything that is not an object with a string id.
function asRecords(value) {
  return asList(value).filter((item) => isObject(item) && typeof item.id === "string");
}

function isCount(value) {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 && value <= MAX_NUMBER;
}

const orNull = (check) => (value) => value === null || check(value);
const isFlag = (value) => typeof value === "boolean";

// What a partner may change on a set, and what each value must look like.
const SET_FIELD_CHECKS = {
  weight: (v) => typeof v === "number" && Number.isFinite(v) && v >= 0 && v <= MAX_NUMBER,
  unit: (v) => v === "kg" || v === "lb",
  reps: isCount,
  seconds: orNull(isCount),
  tag: orNull((v) => v === "warmup" || v === "drop"),
  note: orNull((v) => typeof v === "string" && v.length <= MAX_NOTE),
  done: isFlag,
};

// What a partner may change on an exercise; its sets go through the set mutations.
const ENTRY_FIELD_CHECKS = {
  exerciseId: (v) => typeof v === "string" && v.length > 0 && v.length <= MAX_NAME,
  name: (v) => typeof v === "string" && v.trim().length > 0 && v.length <= MAX_NAME,
  timed: orNull(isFlag),
  skipped: orNull(isFlag),
};

// The allowed keys of source that are present, each checked; null when one has a bad type or range.
function pickChecked(source, checks) {
  const out = {};
  for (const key of Object.keys(checks)) {
    if (!isObject(source) || !Object.prototype.hasOwnProperty.call(source, key)) continue;
    if (!checks[key](source[key])) return null;
    out[key] = source[key];
  }
  return out;
}

// The newest time anything in the entries was stamped (ms), 0 for none.
function latestStamp(entries) {
  let latest = 0;
  for (const entry of asRecords(entries)) {
    latest = Math.max(latest, parseTime(entry.updatedAt));
    for (const set of asRecords(entry.sets)) latest = Math.max(latest, parseTime(set.updatedAt));
  }
  return latest;
}

// A stamp for an edit made at `now` that is never older than what is already stamped, so an edit
// made after seeing another one wins the merge even when the two clocks disagree by a few seconds.
// WorkoutMerge.kt (monotonicNow) does the same on the device.
function stampAfter(now, entries) {
  const latest = latestStamp(entries);
  return latest === 0 || parseTime(now) > latest ? now : new Date(latest + 1).toISOString();
}

function stampedSet(source, stamp) {
  const picked = pickChecked(source, SET_FIELD_CHECKS);
  return picked && Object.assign(picked, { id: source.id, updatedAt: stamp });
}

// Applies one partner mutation, stamping what it touches so the owner's next
// whole-record sync keeps it (see mergeEntries). Returns null when the
// mutation is malformed. Mirrors applyWorkoutMutation in WorkoutMerge.kt.
function applyMutation(storedEntries, mutation, requestedNow) {
  const entries = asRecords(storedEntries);
  if (!isObject(mutation)) return null;
  const now = stampAfter(requestedNow, entries);
  if (mutation.kind === "addEntry") {
    const entry = mutation.entry;
    if (
      !isObject(entry) ||
      typeof entry.id !== "string" ||
      !Array.isArray(entry.sets) ||
      entries.some((candidate) => candidate.id === entry.id)
    ) {
      return null;
    }
    const head = pickChecked(entry, ENTRY_FIELD_CHECKS);
    if (!head || !("exerciseId" in head) || !("name" in head)) return null;
    const sets = [];
    for (const set of entry.sets) {
      if (!isObject(set) || typeof set.id !== "string") continue;
      if (sets.some((added) => added.id === set.id)) return null;
      const stamped = stampedSet(set, now);
      if (!stamped) return null;
      sets.push(stamped);
    }
    return entries.concat([Object.assign(head, { id: entry.id, sets: sets, updatedAt: now })]);
  }
  if (typeof mutation.entryId !== "string") return null;
  const target = entries.find((entry) => entry.id === mutation.entryId);
  const onEntry = (fn) =>
    entries.map((entry) => (entry.id === mutation.entryId ? fn(entry, asRecords(entry.sets)) : entry));

  switch (mutation.kind) {
    case "set": {
      if (typeof mutation.setId !== "string") return null;
      const patch = pickChecked(mutation.patch, SET_FIELD_CHECKS);
      if (!patch) return null;
      return onEntry((entry, sets) =>
        Object.assign({}, entry, {
          sets: sets.map((set) =>
            set.id === mutation.setId ? Object.assign({}, set, patch, { updatedAt: now }) : set,
          ),
        }),
      );
    }
    case "addSet": {
      if (!isObject(mutation.set) || typeof mutation.set.id !== "string") return null;
      const added = stampedSet(mutation.set, now);
      if (!added) return null;
      // An id that is already there is a repeat of the same tap, not a second set.
      if (target && asRecords(target.sets).some((set) => set.id === added.id)) return null;
      return onEntry((entry, sets) => Object.assign({}, entry, { sets: sets.concat([added]) }));
    }
    case "removeSet":
      if (typeof mutation.setId !== "string") return null;
      return onEntry((entry, sets) => {
        const known = asList(entry.removedSetIds);
        const out = Object.assign({}, entry, { sets: sets.filter((set) => set.id !== mutation.setId) });
        // Only ids that were really there are remembered, once each, so the list stays bounded.
        if (sets.some((set) => set.id === mutation.setId) && !known.includes(mutation.setId)) {
          out.removedSetIds = known.concat([mutation.setId]);
        }
        return out;
      });
    case "updateEntry": {
      const patch = pickChecked(mutation.patch, ENTRY_FIELD_CHECKS);
      if (!patch) return null;
      return onEntry((entry) => Object.assign({}, entry, patch, { updatedAt: now }));
    }
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

// Mirrors mergeWorkoutEntries in WorkoutMerge.kt; keep both in sync.
function mergeEntries(serverEntries, snapshotEntries, snapshotAt, removedEntryIds) {
  const server = asRecords(serverEntries);
  const snapshot = asRecords(snapshotEntries);
  const snapshotTime = parseTime(snapshotAt);
  const removedEntries = new Set(asList(removedEntryIds));
  const snapshotEntryIds = new Set(snapshot.map((entry) => entry.id));
  const merged = snapshot.filter((entry) => !removedEntries.has(entry.id)).map((entry) => {
    const stored = server.find((candidate) => candidate.id === entry.id);
    if (!stored) return entry;
    const storedSets = asRecords(stored.sets);
    const entrySets = asRecords(entry.sets);
    const removed = new Set(asList(stored.removedSetIds).concat(asList(entry.removedSetIds)));
    const snapshotIds = new Set(entrySets.map((set) => set.id));
    const sets = entrySets
      .filter((set) => !removed.has(set.id))
      .map((set) => {
        const other = storedSets.find((candidate) => candidate.id === set.id);
        return other && parseTime(other.updatedAt) > parseTime(set.updatedAt) ? other : set;
      });
    for (const set of storedSets) {
      if (!snapshotIds.has(set.id) && !removed.has(set.id) && parseTime(set.updatedAt) > snapshotTime) {
        sets.push(set);
      }
    }
    const out = Object.assign({}, newer(stored, entry) ? stored : entry, { sets: sets });
    // A partner can't edit the note, so whichever header won, the owner's note is the real one.
    if (entry.note === undefined || entry.note === null) delete out.note;
    else out.note = entry.note;
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

module.exports = { applyMutation, mergeEntries, stampAfter, asList };
