// Shared by partner.pb.js through require(). Hook handlers can't see each other's top-level
// functions, so anything they share lives in a module.

const ID_MAX = 100;
const NAME_MAX = 200;
const NOTE_MAX = 2000;
const SETS_MAX = 200;
const REMOVED_MAX = 5000;
const SET_TAGS = ["warmup", "drop"];
const UNITS = ["kg", "lb"];

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
const isNumber = (value, min, max) => typeof value === "number" && Number.isFinite(value) && value >= min && value <= max;
const optional = (value, check) => value === undefined || value === null || check(value);

// Each field a partner may send, with what a valid value looks like. Absent fields are fine (a
// patch carries only what changed); null clears the ones the app can clear.
const SET_CHECKS = {
  weight: (v) => isNumber(v, 0, 100000),
  unit: (v) => UNITS.indexOf(v) >= 0,
  reps: (v) => isNumber(v, 0, 100000),
  seconds: (v) => optional(v, (x) => isNumber(x, 0, 86400)),
  tag: (v) => optional(v, (x) => SET_TAGS.indexOf(x) >= 0),
  note: (v) => optional(v, (x) => typeof x === "string" && x.length <= NOTE_MAX),
  done: (v) => typeof v === "boolean",
};
const ENTRY_CHECKS = {
  exerciseId: (v) => isString(v, ID_MAX),
  name: (v) => typeof v === "string" && v.trim().length > 0 && v.length <= NAME_MAX,
  timed: (v) => optional(v, (x) => typeof x === "boolean"),
  skipped: (v) => optional(v, (x) => typeof x === "boolean"),
};

function fieldsValid(source, checks) {
  if (source === null || typeof source !== "object" || Array.isArray(source)) return false;
  return Object.keys(checks).every((key) => !(key in source) || checks[key](source[key]));
}

/**
 * Whether a /mutate payload has the right shape and values: exact types, enum members, finite
 * bounded numbers and bounded strings. Fields the server ignores are not looked at.
 */
function validMutation(mutation) {
  if (mutation === null || typeof mutation !== "object") return false;
  switch (mutation.kind) {
    case "addEntry": {
      const entry = mutation.entry;
      return (
        fieldsValid(entry, ENTRY_CHECKS) &&
        isString(entry.id, ID_MAX) &&
        isString(entry.exerciseId, ID_MAX) &&
        typeof entry.name === "string" &&
        Array.isArray(entry.sets) &&
        entry.sets.length <= SETS_MAX &&
        entry.sets.every((set) => fieldsValid(set, SET_CHECKS) && isString(set.id, ID_MAX))
      );
    }
    case "set":
      return isString(mutation.entryId, ID_MAX) && isString(mutation.setId, ID_MAX) && fieldsValid(mutation.patch, SET_CHECKS);
    case "addSet":
      return isString(mutation.entryId, ID_MAX) && fieldsValid(mutation.set, SET_CHECKS) && isString(mutation.set.id, ID_MAX);
    case "removeSet":
      return isString(mutation.entryId, ID_MAX) && isString(mutation.setId, ID_MAX);
    case "updateEntry":
      return isString(mutation.entryId, ID_MAX) && fieldsValid(mutation.patch, ENTRY_CHECKS);
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

module.exports = { parseList, findUser, findOne, validMutation, withId, REMOVED_MAX };
