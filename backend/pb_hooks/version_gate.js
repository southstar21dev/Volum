// Decides whether an app build may use the API. Kept apart from version_gate.pb.js so it can
// be run on its own (node) and required from the hook.

/**
 * "1.4.2", "v1.4.2" or "1.4.2-beta.1" as [1, 4, 2]; null for anything that isn't a version. A
 * pre-release ("-beta.1", "-rc.1") is flagged on the array as `prerelease`.
 */
function parseVersion(text) {
  const match = /^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(-[0-9A-Za-z.-]+)?/.exec(String(text || "").trim());
  if (!match) return null;
  const parts = [Number(match[1]), Number(match[2] || 0), Number(match[3] || 0)];
  parts.prerelease = Boolean(match[4]);
  return parts;
}

// A pre-release sorts below the release it precedes: 1.6.0-rc.1 < 1.6.0.
function isOlder(version, minimum) {
  for (let i = 0; i < 3; i++) {
    if (version[i] !== minimum[i]) return version[i] < minimum[i];
  }
  return Boolean(version.prerelease) && !minimum.prerelease;
}

/**
 * The minimum version a request must be turned away for, or null to let it through.
 *
 * `request`: { version, platform, userAgent } from the X-App-Version, X-App-Platform and
 * User-Agent headers. `config`: { minIos, minAndroid, blockLegacy }.
 *
 * Builds from before the gate send no version and can only be told by Ktor's default
 * User-Agent, so they are refused as a group (`blockLegacy`); the answer for them is "".
 * Everything else without a version (the dashboard, a browser, curl, the health check) passes,
 * and so does anything unreadable: a broken setting must not lock every app out.
 */
function requiredVersion(request, config) {
  if (!request.version) {
    const legacy = String(request.userAgent || "").indexOf("ktor-client") === 0;
    return legacy && config.blockLegacy ? "" : null;
  }
  const platform = String(request.platform || "").trim().toLowerCase();
  const minimumText = platform === "ios" ? config.minIos : platform === "android" ? config.minAndroid : "";
  const minimum = parseVersion(minimumText);
  const version = parseVersion(request.version);
  if (!minimum || !version) return null;
  return isOlder(version, minimum) ? String(minimumText).trim().replace(/^v/, "") : null;
}

module.exports = { parseVersion, requiredVersion };
