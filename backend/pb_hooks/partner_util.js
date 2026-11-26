// Shared by partner.pb.js through require(). Hook handlers can't see each other's top-level
// functions, so anything they share lives in a module.

const ID_MAX = 100;
const SETS_MAX = 200;
const REMOVED_MAX = 5000;

const { applyMutation, isObject, pickChecked, SET_FIELD_CHECKS, ENTRY_FIELD_CHECKS } = require("./workout_merge.js");

const BATCH_MAX = 50;

/** A JSON column's text as an array: anything else ("null", an object, junk, empty) is []. */
function parseList(text) {
  try {
    const parsed = JSON.parse(text || "[]");
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

/** The users record with this id, or null when it is gone. */
function findUser(app, id) {
  if (!id) return null;
  try {
    return app.findRecordById("users", id);
  } catch (_) {
    return null;
  }
}

/** The first record matching the filter, or null. */
function findOne(app, collection, filter, params) {
  try {
    return app.findFirstRecordByFilter(collection, filter, params);
  } catch (_) {
    return null;
  }
}

const isString = (value, max) => typeof value === "string" && value.length > 0 && value.length <= max;

/** Whether source is an object whose present, allowed fields all pass the checks (see workout_merge.js). */
function fieldsValid(source, checks) {
  return isObject(source) && pickChecked(source, checks) !== null;
}

/**
 * Whether a /mutate payload has the right shape and ids. The values themselves (types, ranges,
 * lengths) are checked by the same tables applyMutation uses, so the two can't disagree.
 */
function validMutation(mutation) {
  if (!isObject(mutation)) return false;
  switch (mutation.kind) {
    case "addEntry": {
      const entry = mutation.entry;
      return (
        fieldsValid(entry, ENTRY_FIELD_CHECKS) &&
        isString(entry.id, ID_MAX) &&
        "exerciseId" in entry &&
        "name" in entry &&
        Array.isArray(entry.sets) &&
        entry.sets.length <= SETS_MAX &&
        entry.sets.every((set) => fieldsValid(set, SET_FIELD_CHECKS) && isString(set.id, ID_MAX))
      );
    }
    case "set":
      return isString(mutation.entryId, ID_MAX) && isString(mutation.setId, ID_MAX) && fieldsValid(mutation.patch, SET_FIELD_CHECKS);
    case "addSet":
      return isString(mutation.entryId, ID_MAX) && fieldsValid(mutation.set, SET_FIELD_CHECKS) && isString(mutation.set.id, ID_MAX);
    case "removeSet":
      return isString(mutation.entryId, ID_MAX) && isString(mutation.setId, ID_MAX);
    case "updateEntry":
      return isString(mutation.entryId, ID_MAX) && fieldsValid(mutation.patch, ENTRY_FIELD_CHECKS);
    case "removeEntry":
      return isString(mutation.entryId, ID_MAX);
    default:
      return false;
  }
}

/** The list with `id` added once; the oldest ids fall off past a bound. */
function withId(list, id) {
  const next = list.indexOf(id) >= 0 ? list : list.concat([id]);
  return next.length > REMOVED_MAX ? next.slice(next.length - REMOVED_MAX) : next;
}

/**
 * The mutations a /mutate body carries: [body.mutation] as before, or the array [body.mutations]
 * (at most BATCH_MAX). Null when the body is neither, or any item is invalid: a batch is all or nothing.
 */
function mutationsOf(body) {
  if (!isObject(body)) return null;
  if (body.mutations !== undefined) {
    const list = body.mutations;
    if (!Array.isArray(list) || list.length === 0 || list.length > BATCH_MAX || !list.every(validMutation)) return null;
    return list;
  }
  return validMutation(body.mutation) ? [body.mutation] : null;
}

/**
 * Applies validated mutations to the stored entries in order, exactly as if they were sent one by
 * one: one whose exercise (or set) is already gone, or whose set is already added, changes nothing.
 * Returns { entries, removedEntryIds (null when none was removed), changed }, or null when one of them is refused (the caller
 * rejects the whole batch).
 */
function applyMutations(stored, removedEntryIds, mutations, now) {
  let entries = stored;
  let removed = null;
  let changed = false;
  for (const mutation of mutations) {
    if (mutation.kind !== "addEntry") {
      const target = entries.find((entry) => entry && entry.id === mutation.entryId);
      // A retry or a race with the owner: nothing to change and nothing to record.
      const sets = target && Array.isArray(target.sets) ? target.sets : null;
      const hasSet = (setId) => sets.some((set) => set && set.id === setId);
      if (
        !target ||
        (mutation.kind === "addSet" && (!sets || hasSet(mutation.set.id))) ||
        (mutation.kind === "removeSet" && (!sets || !hasSet(mutation.setId)))
      ) {
        continue;
      }
    }
    const next = applyMutation(entries, mutation, now);
    if (!next) return null;
    entries = next;
    if (mutation.kind === "removeEntry") removed = withId(removed || removedEntryIds, mutation.entryId);
    changed = true;
  }
  return { entries, removedEntryIds: removed, changed };
}

module.exports = { parseList, findUser, findOne, validMutation, mutationsOf, applyMutations, withId, REMOVED_MAX, BATCH_MAX };
