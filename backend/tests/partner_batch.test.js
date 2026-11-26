// Run with: node --test backend/tests
const test = require("node:test");
const assert = require("node:assert/strict");
const { applyMutation } = require("../pb_hooks/workout_merge.js");
const { mutationsOf, applyMutations, validMutation, BATCH_MAX } = require("../pb_hooks/partner_util.js");

const now = "2026-01-01T10:00:30.000Z";
const stored = () => [
  { id: "e1", exerciseId: "x", name: "Bench", sets: [{ id: "s1", reps: 8, updatedAt: "2026-01-01T10:00:05.000Z" }, { id: "s2", reps: 6, updatedAt: "2026-01-01T10:00:05.000Z" }], updatedAt: "2026-01-01T10:00:05.000Z" },
  { id: "e2", exerciseId: "y", name: "Row", sets: [], updatedAt: "2026-01-01T10:00:05.000Z" },
];
const relink = [
  { kind: "updateEntry", entryId: "e1", patch: { name: "Plank", exerciseId: "p", timed: true } },
  { kind: "set", entryId: "e1", setId: "s1", patch: { seconds: 30, reps: 0 } },
  { kind: "set", entryId: "e1", setId: "s2", patch: { seconds: 30, reps: 0 } },
];

// What the old client did: one request per mutation, each with the stored copy of the one before.
function sequential(mutations) {
  let entries = stored();
  let removed = [];
  for (const m of mutations) {
    const result = applyMutations(entries, removed, [m], now);
    entries = result.entries;
    if (result.removedEntryIds) removed = result.removedEntryIds;
  }
  return { entries, removed };
}

test("a batch equals applying its mutations one by one", () => {
  const batches = [relink, [...relink, { kind: "removeSet", entryId: "e1", setId: "s2" }, { kind: "removeEntry", entryId: "e2" }, { kind: "removeEntry", entryId: "e2" }]];
  for (const batch of batches) {
    const result = applyMutations(stored(), [], batch, now);
    const seq = sequential(batch);
    // Stamps differ by design (each step moves its clock on), so compare without them.
    const strip = (entries) => JSON.parse(JSON.stringify(entries, (k, v) => (k === "updatedAt" ? undefined : v)));
    assert.deepEqual(strip(result.entries), strip(seq.entries));
    assert.deepEqual(result.removedEntryIds || [], seq.removed);
    assert.equal(result.changed, true);
  }
});

test("a batch stamps what it touches after the stored copy", () => {
  const result = applyMutations(stored(), [], relink, now);
  assert.ok(result.entries[0].sets.every((set) => set.updatedAt >= now));
  assert.equal(result.entries[0].name, "Plank");
});

test("removed entry ids are remembered once, after the existing ones", () => {
  const result = applyMutations(stored(), ["old"], [{ kind: "removeEntry", entryId: "e2" }, { kind: "removeEntry", entryId: "e2" }], now);
  assert.deepEqual(result.removedEntryIds, ["old", "e2"]);
  assert.deepEqual(result.entries.map((entry) => entry.id), ["e1"]);
});

test("missing targets are no-ops and change nothing", () => {
  const batch = [{ kind: "removeEntry", entryId: "gone" }, { kind: "set", entryId: "gone", setId: "s1", patch: { reps: 1 } }, { kind: "removeSet", entryId: "e1", setId: "nope" }, { kind: "addSet", entryId: "e1", set: { id: "s1" } }];
  const result = applyMutations(stored(), [], batch, now);
  assert.equal(result.changed, false);
  assert.equal(result.removedEntryIds, null);
  assert.deepEqual(result.entries, stored());
});

test("the single form is unchanged", () => {
  const m = relink[1];
  assert.deepEqual(mutationsOf({ mutation: m }), [m]);
  assert.deepEqual(applyMutations(stored(), [], [m], now).entries, applyMutation(stored(), m, now));
  assert.equal(mutationsOf({ mutation: { kind: "nope" } }), null);
  assert.equal(mutationsOf({}), null);
  assert.equal(mutationsOf(null), null);
});

test("a batch with one invalid item is rejected whole", () => {
  assert.deepEqual(mutationsOf({ mutations: relink }), relink);
  assert.equal(mutationsOf({ mutations: [...relink, { kind: "set", entryId: "e1" }] }), null);
  assert.equal(mutationsOf({ mutations: [...relink, { kind: "set", entryId: "e1", setId: "s1", patch: { reps: 1.5 } }] }), null);
});

test("empty, oversized and non-array batches are rejected", () => {
  const one = { kind: "removeEntry", entryId: "e1" };
  assert.ok(validMutation(one));
  assert.equal(mutationsOf({ mutations: [] }), null);
  assert.equal(mutationsOf({ mutations: "x" }), null);
  assert.equal(mutationsOf({ mutations: Array(BATCH_MAX + 1).fill(one) }), null);
  assert.equal(mutationsOf({ mutations: Array(BATCH_MAX).fill(one) }).length, BATCH_MAX);
});

test("a malformed batch item that passes validation but is refused rejects the whole batch", () => {
  const result = applyMutations(stored(), [], [relink[0], { kind: "addEntry", entry: { id: "e1", exerciseId: "x", name: "dup", sets: [] } }], now);
  assert.equal(result, null);
});
