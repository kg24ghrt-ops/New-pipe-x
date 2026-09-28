# newpipex

An Android application built with Kotlin using Jetpack Compose, featuring dual extraction backends for YouTube and other streaming sites.

## Features

- **Modern Android stack**: Kotlin, Jetpack Compose, Material3
- **Dual extraction backends**:
  - **NewPipeExtractor** (native Java/Kotlin) - lightweight, native Android YouTube path
  - **yt-dlp** (Python via Chaquopy) - comprehensive extractor for YouTube + 1000+ sites
- **Automated dependency management** via GitHub Actions
- **Reproducible builds** with pinned dependency versions

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17 or later
- Android SDK API 36
- Gradle 8.14.4 (via wrapper)

## Getting Started

1. Open the project in Android Studio
2. Sync Gradle files
3. Build and run with Android Studio or `./gradlew assembleDebug`

## Extraction Backends

This project uses two extraction backends, both pinned to specific versions for reproducibility:

### NewPipeExtractor (Native)
- Maven coordinate: `com.github.TeamNewPipe:NewPipeExtractor`
- Version: `0.26.5` (pinned in `gradle.properties` and `config/dependencies.toml`)
- Lightweight, native Android implementation
- Published via JitPack

### yt-dlp (Python/Chaquopy)
- PyPI package: `yt-dlp`
- Version: `2026.8.19` (pinned in `app/python-requirements.txt` and `config/dependencies.toml`)
- Full-featured extractor via Chaquopy Python environment
- Covers YouTube plus 1000+ additional sites

## Dependency Management

The project uses a single source of truth for dependency pins: `config/dependencies.toml`.

### Automated Updates
- **Daily scheduled workflow** (`.github/workflows/dependency-update.yml`) checks upstream for new releases
- Opens a PR with version bumps when safe (minor bumps for NewPipeExtractor, any CalVer release for yt-dlp)
- Major version bumps require manual approval via `--allow-major` flag

### Manual Commands
```bash
# Check for available updates (read-only)
./tools/check-dependencies.py

# Check with JSON output for CI
./tools/check-dependencies.py --json

# Apply updates (writes to all pin files)
./tools/update-dependencies.py --write

# Force a major version bump after review
./tools/update-dependencies.py --allow-major --write
```

### Pin Files (kept in sync)
| File | Purpose |
|------|---------|
| `config/dependencies.toml` | **Source of truth** - declares current versions and metadata |
| `gradle.properties` | Mirrors versions for Gradle (`newpipeExtractorVersion`, `ytDlpVersion`) |
| `app/python-requirements.txt` | Pins yt-dlp for Chaquopy (`yt-dlp==<version>`) |

The `:app:checkDependencies` Gradle task validates all three files agree.

## CI/CD Workflows

### Android Build (`.github/workflows/android-build.yml`)
Runs on every push and PR:
1. **Verify dependency pins** - validates all pin files agree
2. **Assemble Debug APK** - builds debug variant
3. **Assemble Release APK** - builds release variant with minification
4. Uploads APKs as artifacts (14-day retention)

### Dependency Update (`.github/workflows/dependency-update.yml`)
Runs daily at 06:30 UTC and on demand:
1. Checks upstream releases for both backends
2. Computes safe bump plan (respects semantic versioning rules)
3. Opens PR with changes if updates available
4. Android Build workflow runs on the PR for validation

## Project Structure

```
newpipex/
├── app/
│   ├── build.gradle.kts          # App module config, dependencies, checkDependencies task
│   ├── python-requirements.txt   # yt-dlp pin for Chaquopy
│   └── src/main/kotlin/...       # Source code
├── config/
│   └── dependencies.toml         # Single source of truth for dependency pins
├── tools/
│   ├── check-dependencies.py     # Check upstream for updates
│   ├── update-dependencies.py    # Apply version bumps to all pin files
│   └── depcommon.py              # Shared logic for dependency management
├── gradle.properties             # Gradle settings + mirrored version pins
├── .github/workflows/
│   ├── android-build.yml         # Build + verify workflow
│   └── dependency-update.yml     # Automated dependency update workflow
└── README.md                     # This file
```

## Building

### Debug APK
```bash
./gradlew assembleDebug
```

### Release APK
```bash
./gradlew assembleRelease
```

### Run Dependency Checks
```bash
./gradlew checkDependencies
```

## License

MIT License