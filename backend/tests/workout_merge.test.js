// Run with: node --test backend/tests
// The fixtures in test-fixtures/workout-merge are also run against WorkoutMerge.kt
// (WorkoutMergeParityTest in shared/src/commonTest), so the two implementations can't drift.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { applyMutation, mergeEntries, stampAfter, asList } = require("../pb_hooks/workout_merge.js");

const fixtures = path.join(__dirname, "..", "..", "test-fixtures", "workout-merge");
const load = (name) => JSON.parse(fs.readFileSync(path.join(fixtures, name), "utf8"));

for (const c of load("merge.json")) {
  test(`merge: ${c.name}`, () => {
    assert.deepEqual(mergeEntries(c.server, c.snapshot, c.snapshotAt, c.removedEntryIds), c.expected);
  });
}

for (const c of load("mutate.json")) {
  test(`mutate: ${c.name}`, () => {
    assert.deepEqual(applyMutation(c.entries, c.mutation, c.now), c.expected);
  });
}

test("stored entries of null or the wrong type are treated as empty", () => {
  assert.deepEqual(asList(JSON.parse("null")), []);
  assert.deepEqual(asList({}), []);
  assert.deepEqual(mergeEntries(null, null, "2026-01-01T10:00:00.000Z", null), []);
  const added = { kind: "addEntry", entry: { id: "e1", exerciseId: "x", name: "Row", sets: [] } };
  const result = applyMutation(null, added, "2026-01-01T10:00:00.000Z");
  assert.equal(result.length, 1);
  assert.equal(result[0].id, "e1");
});

test("malformed entries and sets in a stored copy are skipped, not thrown on", () => {
  const server = [null, 5, { id: "e1", sets: "nope" }, { id: "e2", sets: [null, { id: "s1" }] }];
  const snapshot = [{ id: "e1", exerciseId: "x", name: "A", sets: [{ id: "s1" }] }];
  assert.doesNotThrow(() => mergeEntries(server, snapshot, "2026-01-01T10:00:00.000Z", []));
  assert.doesNotThrow(() => applyMutation(server, { kind: "addSet", entryId: "e1", set: { id: "s2" } }, "2026-01-01T10:00:00.000Z"));
});

test("field types, enums and ranges are checked", () => {
  const entries = [{ id: "e1", exerciseId: "x", name: "A", sets: [{ id: "s1" }] }];
  const now = "2026-01-01T10:00:00.000Z";
  const set = (patch) => applyMutation(entries, { kind: "set", entryId: "e1", setId: "s1", patch }, now);
  const entry = (patch) => applyMutation(entries, { kind: "updateEntry", entryId: "e1", patch }, now);
  for (const bad of [{ unit: "stone" }, { tag: "failure" }, { reps: 1.5 }, { reps: "8" }, { weight: Infinity }, { weight: 1e9 }, { done: "yes" }, { note: 5 }, { note: "x".repeat(2001) }]) {
    assert.equal(set(bad), null, JSON.stringify(bad));
  }
  for (const bad of [{ name: 123 }, { name: "x".repeat(201) }, { skipped: "true" }, { exerciseId: "" }]) {
    assert.equal(entry(bad), null, JSON.stringify(bad));
  }
  assert.notEqual(set({ unit: "lb", tag: "drop", seconds: null }), null);
});

test("a mutation that is not an object is refused", () => {
  for (const bad of [null, undefined, 5, "set", [], {}, { kind: "nope", entryId: "e1" }, { kind: "set", entryId: 5 }]) {
    assert.equal(applyMutation([], bad, "2026-01-01T10:00:00.000Z"), null, JSON.stringify(bad));
  }
});

test("removal lists stay bounded when the same set is removed again", () => {
  let entries = [{ id: "e1", exerciseId: "x", name: "A", sets: [{ id: "s1" }] }];
  const remove = { kind: "removeSet", entryId: "e1", setId: "s1" };
  for (let i = 0; i < 5; i++) entries = applyMutation(entries, remove, "2026-01-01T10:00:00.000Z");
  assert.deepEqual(entries[0].removedSetIds, ["s1"]);
});

test("stampAfter keeps the time when it is later than everything stored", () => {
  const entries = [{ id: "e1", updatedAt: "2026-01-01T10:00:05.000Z", sets: [] }];
  assert.equal(stampAfter("2026-01-01T10:00:09.000Z", entries), "2026-01-01T10:00:09.000Z");
  assert.equal(stampAfter("2026-01-01T10:00:01.000Z", entries), "2026-01-01T10:00:05.001Z");
  assert.equal(stampAfter("2026-01-01T10:00:01.000Z", null), "2026-01-01T10:00:01.000Z");
});

test("validMutation and applyMutation accept and refuse the same values", () => {
  const { validMutation } = require("../pb_hooks/partner_util.js");
  const now = "2026-01-01T10:00:00.000Z";
  const entries = [{ id: "e1", exerciseId: "x", name: "Squat", sets: [{ id: "s1", weight: 0, reps: 0 }] }];
  const note = (n) => ({ kind: "set", entryId: "e1", setId: "s1", patch: { note: "x".repeat(n) } });
  const reps = (n) => ({ kind: "set", entryId: "e1", setId: "s1", patch: { reps: n } });
  const seconds = (n) => ({ kind: "set", entryId: "e1", setId: "s1", patch: { seconds: n } });
  const exercise = (n) => ({ kind: "updateEntry", entryId: "e1", patch: { exerciseId: "x".repeat(n) } });
  const cases = [note(2000), note(2001), reps(8), reps(8.5), seconds(90), seconds(90.5), seconds(86401), seconds(100001), exercise(200), exercise(201)];
  for (const m of cases) {
    assert.equal(validMutation(m), applyMutation(entries, m, now) !== null, JSON.stringify(m).slice(0, 80));
  }
  assert.equal(validMutation(note(2000)), true);
  assert.equal(validMutation(note(2001)), false);
  assert.equal(validMutation(reps(8.5)), false);
  assert.equal(validMutation(seconds(86401)), true);
});
