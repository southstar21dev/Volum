#!/usr/bin/env node
// Writes the AltStore/SideStore sources into the directory given as the first argument, built from
// the repo's GitHub Releases (every release with an .ipa asset):
//   altstore.json       releases from master only
//   altstore-beta.json  also the release candidates from staging
// Rebuilding from the releases each time keeps the sources in step with what's actually published,
// so nothing has to be committed back to the repo. Needs GITHUB_REPOSITORY and GITHUB_TOKEN.
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

const outDir = process.argv[2] ?? "site";
const repo = process.env.GITHUB_REPOSITORY ?? "southstar21dev/Volum";
const token = process.env.GITHUB_TOKEN;

const raw = `https://raw.githubusercontent.com/${repo}/master`;
const tint = "#1F4D33";
const minOSVersion = "18.5"; // iosApp/project.yml deploymentTarget

async function releases() {
  const all = [];
  for (let page = 1; ; page++) {
    const response = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=100&page=${page}`, {
      headers: {
        Accept: "application/vnd.github+json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
    if (!response.ok) throw new Error(`GitHub API: ${response.status} ${await response.text()}`);
    const batch = await response.json();
    all.push(...batch);
    if (batch.length < 100) return all;
  }
}

// Same rule as androidApp's versionCode and the Xcode pre-build script: CFBundleShortVersionString
// is the plain x.y.z, CFBundleVersion is 1.2.3 -> 1020399, 1.2.3-rc.4 -> 1020304.
function bundleVersions(tag) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?$/.exec(tag);
  if (!match) return null;
  const [major, minor, patch, rc] = match.slice(1).map((part) => (part === undefined ? undefined : Number(part)));
  return {
    version: `${major}.${minor}.${patch}`,
    buildVersion: String((major * 10_000 + minor * 100 + patch) * 100 + (rc ?? 99)),
  };
}

function toVersion(release) {
  const ipa = release.assets.find((asset) => asset.name.endsWith(".ipa"));
  const versions = bundleVersions(release.tag_name);
  if (!ipa || !versions || release.draft) return null;
  return {
    ...versions,
    date: release.published_at,
    localizedDescription: release.body || release.name || release.tag_name,
    downloadURL: ipa.browser_download_url,
    size: ipa.size,
    minOSVersion,
    prerelease: release.prerelease,
  };
}

function source({ beta, versions }) {
  return {
    name: beta ? "Volum (beta)" : "Volum",
    identifier: beta ? "dev.southstar21.volum.source.beta" : "dev.southstar21.volum.source",
    subtitle: "A strength training log.",
    description: beta
      ? "Volum releases and release candidates, straight from GitHub."
      : "Volum releases, straight from GitHub.",
    iconURL: `${raw}/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`,
    website: `https://github.com/${repo}`,
    tintColor: tint,
    apps: [
      {
        name: "Volum",
        bundleIdentifier: "dev.southstar21.volum",
        developerName: "southstar21",
        subtitle: "A strength training log.",
        localizedDescription:
          "Build a routine once, then follow it session by session with the previous session's weights and reps filled in.",
        iconURL: `${raw}/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`,
        tintColor: tint,
        category: "lifestyle",
        screenshots: [],
        versions: versions.map(({ prerelease, ...version }) => version),
        appPermissions: { entitlements: [], privacy: {} },
      },
    ],
    news: [],
  };
}

const versions = (await releases())
  .map(toVersion)
  .filter(Boolean)
  .sort((a, b) => b.date.localeCompare(a.date));

await mkdir(outDir, { recursive: true });
for (const beta of [false, true]) {
  const included = beta ? versions : versions.filter((version) => !version.prerelease);
  const file = join(outDir, beta ? "altstore-beta.json" : "altstore.json");
  await writeFile(file, `${JSON.stringify(source({ beta, versions: included }), null, 2)}\n`);
  console.log(`${file}: ${included.length} version(s)`);
}
