const test = require("node:test");
const assert = require("node:assert/strict");
const { parseVersion, requiredVersion } = require("../pb_hooks/version_gate.js");

const config = { minIos: "1.6.0", minAndroid: "v1.4.2", blockLegacy: true };

test("parseVersion reads versions with a prefix or a suffix and rejects the rest", () => {
  assert.deepEqual(parseVersion("1.4.2"), [1, 4, 2]);
  assert.deepEqual(parseVersion("v1.4.2"), [1, 4, 2]);
  assert.deepEqual(parseVersion("1.4.2-beta.1"), [1, 4, 2]);
  assert.deepEqual(parseVersion("2"), [2, 0, 0]);
  assert.equal(parseVersion("beta"), null);
  assert.equal(parseVersion(""), null);
  assert.equal(parseVersion(null), null);
});

test("an older build is turned away with the minimum, a current one passes", () => {
  assert.equal(requiredVersion({ version: "1.5.9", platform: "ios" }, config), "1.6.0");
  assert.equal(requiredVersion({ version: "1.6.0", platform: "ios" }, config), null);
  assert.equal(requiredVersion({ version: "1.4.1", platform: "android" }, config), "1.4.2");
  assert.equal(requiredVersion({ version: "1.10.0", platform: "android" }, config), null);
});

test("builds from before the gate are refused only as a group, and only when asked", () => {
  const legacy = { version: "", platform: "", userAgent: "ktor-client" };
  assert.equal(requiredVersion(legacy, config), "");
  assert.equal(requiredVersion(legacy, { ...config, blockLegacy: false }), null);
  assert.equal(requiredVersion({ version: "", userAgent: "curl/8" }, config), null);
});

test("a broken setting never locks anyone out", () => {
  assert.equal(requiredVersion({ version: "1.0.0", platform: "ios" }, { minIos: "soon" }), null);
  assert.equal(requiredVersion({ version: "weird", platform: "ios" }, config), null);
  assert.equal(requiredVersion({ version: "1.0.0", platform: "web" }, config), null);
});
