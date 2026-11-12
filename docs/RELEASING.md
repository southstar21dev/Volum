# Releasing

GitHub builds, signs and publishes every release. Local builds are for development.

## How a release happens

| Push to | CI (`ci.yml`) | Release (`release.yml`) |
|---|---|---|
| `dev`, PRs | Shared tests, Android R8 build, iOS tests and device build | Nothing |
| `master` | Same | Release, e.g. `v1.2.0` |

CI skips changes that only touch Markdown or `docs/`, and skips the iOS jobs for changes only under
`androidApp/`. A PR from `dev` into `master` gets its own full run, so the PR shows a real result
for the merge rather than a skipped job. No checks are required to merge today. If some are made
required later, note that a PR touching only Markdown or `docs/` starts no CI run at all, so its
required checks would wait forever: add an always-running gate job first.

CI caches the Gradle home (dependencies, build cache, configuration cache) and, for the iOS jobs,
the Kotlin/Native toolchain in `~/.konan`. Pushes to `dev` and `master` save the caches; PRs only
read them. The toolchain cache is keyed on the Kotlin version, so a Kotlin upgrade downloads it once
more.

Before anything is versioned, the release job runs the shared tests (Android host and iOS
simulator), the same ones CI runs, on the pushed commit. If they fail, nothing is tagged or published.
The Release run then shows red even for a push with nothing to release (only `chore:` commits, say),
and the `altstore-source` job below is skipped for it; once the tests pass, rerun the job or run
`gh workflow run release.yml --ref master` to republish the sources.

CI and the release install a pinned XcodeGen (2.46.0) with `scripts/install-xcodegen.sh`, which checks
the download's SHA-256. To upgrade it, change the version and the `xcodegen.zip` digest there.

semantic-release reads the Conventional Commits since the last tag. If any warrant a release
(`fix:`, `perf:`, `feat:`, breaking changes), it runs `scripts/build-release.sh`, which writes the
version into `gradle.properties` and builds:

| File | For |
|---|---|
| `volum-<version>.apk` | Android, signed with the release key |
| `volum-<version>.ipa` | iOS, **unsigned**: AltStore and SideStore sign it with the user's own Apple ID |

It then tags the commit and publishes a GitHub Release with the changelog and both files. The
release job runs on macOS, since the IPA needs Xcode.

Once a release is published, the `testflight` job archives a signed App Store build of the same
commit and version and uploads it to TestFlight (see [TestFlight](#ios-testflight) below).

Afterwards the `altstore-source` job rebuilds the AltStore/SideStore sources from the GitHub
Releases and publishes them to GitHub Pages. Run the **Release** workflow by hand
(`gh workflow run release.yml --ref master`) to republish them without releasing, e.g. after
editing a release's notes.

## Installing and updating

### Android: Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps from GitHub Releases and updates
them automatically. Add `https://github.com/southstar21dev/Volum` as an app.

### iOS: AltStore or SideStore

Open <https://southstar21dev.github.io/Volum/> on the iPhone and tap *Add to AltStore* or *Add to
SideStore*, or add a source by hand:

`https://southstar21dev.github.io/Volum/altstore.json`.

Both apps then offer each new release as an update. With a free Apple ID, the app has to be
refreshed every 7 days (AltStore/SideStore do this in the background).

### iOS: TestFlight

Every master release is also uploaded to TestFlight, under the paid team `3HNYQ2F575`. Testers in
the app's internal group get each build once App Store Connect finishes processing it (usually
5 to 15 minutes); external testers need a Beta App Review the first time.

The job signs with cloud-managed certificates through an App Store Connect API key, so no
certificate or profile is kept anywhere. It needs:

- An app record in App Store Connect for the bundle ID `dev.southstar21.volum`.
- An API key with the **Admin** role (Users and Access, Integrations, App Store Connect API). The
  key is team-wide, so Vectra's key works here too.
- Three repository secrets:

  ```sh
  gh secret set ASC_KEY_ID --body <key id>
  gh secret set ASC_ISSUER_ID --body <issuer id>
  gh secret set ASC_KEY_P8 < AuthKey_<key id>.p8
  ```

Without them the job is skipped with a note in the run summary, and the release is unaffected. The
team is passed to `xcodebuild` in the workflow; `iosApp/project.yml` keeps `DEVELOPMENT_TEAM`
empty.

## Versions

`versionCode` (Android) and `CFBundleVersion` (iOS) order releases after their candidates:
`1.2.3-rc.4` is 1020304 and `1.2.3` is 1020399, so every published build installs as an update
over the previous one. `CFBundleShortVersionString` is the plain `1.2.3`.

Local builds use `volum.versionName` from `gradle.properties` as it is.

## Building locally

```sh
./gradlew :androidApp:assembleRelease                      # APK
VOLUM_KEYSTORE_PATH=~/.config/volum/volum-release.jks \
  scripts/build-release.sh 1.2.3                           # both, as the release does (macOS)
```

`build-release.sh` changes `volum.versionName` in `gradle.properties`; don't commit that.

## Signing (Android)

Releases are signed with the Volum release key. Android only installs an update signed with the
same key as the installed app, so **losing the key means every user has to uninstall**.

- The key: `~/.config/volum/volum-release.jks` on the maintainer's Mac (alias `volum`). Its
  password is in the git-ignored `keystore.properties` in the project root. Keep a copy of both in
  a password manager.
- In GitHub: the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
  The release workflow fails rather than publish a build signed with another key.
- Certificate SHA-256: `AE:86:69:3B:05:F6:EF:FD:40:D0:E4:08:5F:2C:74:A7:E9:67:ED:16:47:3E:F6:6F:20:2B:25:44:05:42:B7:18`

Local release builds use `keystore.properties` when it exists, and the debug key otherwise:

```properties
storeFile=/absolute/path/to/volum-release.jks
storePassword=...
keyAlias=volum
keyPassword=...
```

To set up the secrets again (e.g. after rotating the key):

```sh
base64 -i volum-release.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body volum
gh secret set KEY_PASSWORD
```

The AltStore/SideStore IPA isn't signed. The TestFlight build uses cloud-managed signing, so there
is no Apple certificate to keep either.
