# Contributing

## Branches

| Branch | Purpose |
|---|---|
| `dev` | Integration; feature branches merge here |
| `master` | Production, promoted from `dev`; each promotion can publish a release |

1. Branch from `dev`, for example `feat/rest-timer`.
2. Open a PR into `dev` and squash-merge it.
3. To release, open a PR from `dev` into `master` and use a **merge commit**.

The promotion needs a merge commit so semantic-release can still see the individual commits.

## Commit messages

PR titles and commits follow [Conventional Commits](https://www.conventionalcommits.org). The type
decides the next version:

| Commit | Example | Version |
|---|---|---|
| `fix:` | `fix(sync): retry after a dropped connection` | Patch |
| `feat:` | `feat(log): copy the last session's sets` | Minor |
| `feat!:` or a `BREAKING CHANGE:` footer | `feat!: require iOS 19` | Major |
| `chore:`, `docs:`, `refactor:`, `test:`, `ci:` | `docs: explain sync` | No release |

## Before opening a PR

```sh
./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug
./gradlew :shared:iosSimulatorArm64Test              # macOS only
```

The Xcode project is generated: run `cd iosApp && xcodegen` after cloning, and edit
`iosApp/project.yml` rather than the project itself. CI uses XcodeGen 2.46.0
(`scripts/install-xcodegen.sh`); a local install of the same version generates the same project.
