<div align="center">

<img src="iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png" width="112" alt="Volum app icon" />

# Volum

**A strength training log for iOS and Android.**

Build a routine once, then follow it session by session with the previous session's weights and
reps filled in, so logging is mostly confirming and adjusting numbers.

[![CI](https://github.com/southstar21dev/Volum/actions/workflows/ci.yml/badge.svg?branch=dev)](https://github.com/southstar21dev/Volum/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/southstar21dev/Volum?sort=semver&label=release&color=1F4D33)](https://github.com/southstar21dev/Volum/releases/latest)
![iOS 18.5+](https://img.shields.io/badge/iOS-18.5%2B-1F4D33?logo=apple&logoColor=white)
![Android 8+](https://img.shields.io/badge/Android-8%2B-1F4D33?logo=android&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-1F4D33?logo=jetpackcompose&logoColor=white)

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/southstar21dev/Volum"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="64" alt="Get it on Obtainium" /></a>
<a href="https://github.com/southstar21dev/Volum/releases/latest"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" height="64" alt="Get it on GitHub" /></a>

</div>

## Install

| Platform | How |
|---|---|
| **Android** | Tap the Obtainium badge above (or add `https://github.com/southstar21dev/Volum` in [Obtainium](https://github.com/ImranR98/Obtainium)). It installs Volum and keeps it updated. Or download the APK from the [latest release](https://github.com/southstar21dev/Volum/releases/latest). |
| **iPhone** | Open **[southstar21dev.github.io/Volum](https://southstar21dev.github.io/Volum/)** on the iPhone and tap *Add to AltStore* or *Add to SideStore*. Or add the source `https://southstar21dev.github.io/Volum/altstore.json` by hand. |

Release candidates from `staging` are published as pre-releases. To test them, enable *Include
prereleases* in Obtainium, or use the `altstore-beta.json` source on iOS.

## Development

```sh
./gradlew :androidApp:installDebug          # Android
cd iosApp && xcodegen && open iosApp.xcodeproj   # iOS
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for branches and commit messages, and
[docs/RELEASING.md](docs/RELEASING.md) for how releases are built and signed.

## Credits

- Fonts: Jost and Plus Jakarta Sans, under the SIL Open Font License ([licenses/](licenses/)).
- [Get it on Obtainium](https://github.com/ImranR98/Obtainium) and [Get it on GitHub](https://github.com/Kunzisoft/Github-badge) badges by their respective projects.
