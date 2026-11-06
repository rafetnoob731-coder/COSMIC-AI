# Local Android Build Environment Guide

**Prepared:** 2026-11-06  
**Scope:** Local-only build investigation for the current COSMIC CODEX checkout. No SDK, proot distribution, QEMU package, or other large package was installed. No source was transferred externally.

## Feasibility summary

The connected Termux host is an Android 12/API 31, `arm64-v8a` phone. The frozen project working tree has 96 entries at base HEAD `bb736c50a95baa83e4b4b1c030685cca468ba809` on `codex/build-stabilization`.

Measured host resources:

| Measurement | Result |
|---|---:|
| CPU/ABI | ARM64 (`aarch64` / `arm64-v8a`) |
| Physical RAM reported by `/proc/meminfo` | 3,862,048 kB (about 3.68 GiB) |
| Available RAM at final recheck | 1,187,860 kB (about 1.13 GiB; fluctuates) |
| Swap total / free at final recheck | 2,124,120 kB / 253,440 kB |
| Shared filesystem free space | 8.5 GB |
| Existing Gradle user cache | 1.1 GB |
| Android SDK tools found | None |
| Installed Gradle / JDK | Gradle wrapper 8.9 / OpenJDK 21.0.12 |

The project config requests `compileSdk = 35`, AGP 8.5.2, Kotlin 2.0.20, KSP `2.0.20-1.0.24`, and Room 2.6.1. The Android CI workflow also requests platform 35 and build-tools 35.0.0.

**Recommendation:** Do not install the Android SDK or an x86_64 emulation stack on this phone. The storage figure alone might fit a command-line SDK, but the available RAM is below the project’s configured Gradle heap (`-Xmx2048m`) before Android tools, Kotlin/KSP, and test workers run. Android’s Linux ARM host is not supported for Android Studio, and Google distributes Linux command-line tools as a Linux package rather than a Linux ARM64 package. Emulating x86_64 adds substantial memory and storage pressure and is not a reproducible, supported setup for this device.

The reproducible path is an x86_64 Linux workstation/VM, JDK 17, Android platform 35, and the versions below. Linux ARM should not be treated as supported for the official host tools based on the available Android downloads and support documentation. These commands are documented for a later local Linux host; they were **not executed** here.

## Tool and version matrix

| Component | Repository/config value | Reproducible host requirement / finding |
|---|---|---|
| Android SDK platform | `compileSdk = 35` | Install `platforms;android-35`. |
| Android build-tools | CI installs `35.0.0` | Install `build-tools;35.0.0` to match CI. |
| Android platform-tools | ADB utilities | Install `platform-tools`; not needed for Kotlin compilation itself, needed for device operations. |
| Android Gradle Plugin | 8.5.2 | **Mismatch:** AGP 8.5 supports up to API 34. API 35 requires at least AGP 8.6.0. Do not silently alter app configuration in this environment task; record this as a separate build-configuration issue requiring an authorized source change. |
| Gradle wrapper | 8.9 | Gradle 8.9 is above AGP 8.6’s minimum Gradle 8.7. |
| JDK | Current phone: 21.0.12; CI workflow: 17 | Use JDK 17 for parity with the repository CI and AGP 8.x baseline. Gradle 8.9 can run on JDK 21, but JDK 21 does not solve the Android host-binary architecture issue. |
| Java/Kotlin bytecode | 17 | App uses Java source/target 17 and Kotlin JVM target 17. This is separate from the JDK that runs Gradle. |
| Kotlin Gradle plugin | 2.0.20 | Keep paired with current KSP plugin. |
| KSP | 2.0.20-1.0.24 | Must match the Kotlin compiler line. The current pair is version-aligned. |
| Room | 2.6.1 | KSP processes `room-compiler:2.6.1`; schema location is configured as `app/schemas`. |
| Historical Room schema files | v1 and v2 checked in | KSP on the current v4 database generates the current v4 schema; it does not recreate historical v3. The v3 migration test fixture is authored separately. |

Android officially lists API 35 as requiring AGP 8.6.0 and AGP 8.6.0 lists Gradle 8.7/JDK 17 as its baseline. The current AGP 8.5.2 configuration is therefore not the supported pairing for `compileSdk 35`. Sources are linked under [References](#references).

## Termux and local Linux-userland options

### Native Termux

- Termux itself is ARM64 and has Java 21 and ADB, but this checkout has no `sdkmanager`, SDK platform, or build-tools.
- The official Android Studio documentation says Linux ARM machines are unsupported. The official downloads page lists Linux command-line tools separately from macOS ARM command-line tools; no Linux ARM64 command-line-tools archive is listed.
- `sdkmanager` is a Java entry point, but an Android build also invokes host-side tools such as AAPT2, D8/R8, zipalign, and packaging/signing utilities. Finding a Java launcher alone would not establish that these host binaries can run on Android ARM64.
- Termux’s Android SDK setup script is part of its package-build infrastructure. Its existence is not a supported end-user Android Studio/Gradle SDK installation route for an ARM phone.

### PRoot Linux userland

Termux PRoot-Distro can install a Linux userland without root. An ARM64 Ubuntu/Debian userland still runs ARM64 binaries; it does not make Google’s Linux x86_64 SDK host tools ARM64-compatible. PRoot-Distro documents QEMU user-mode for cross-architecture execution, but that is emulation rather than native support.

**Commands to inspect only (not run during this mission):**

```sh
# Check repository availability before installing anything.
pkg search proot-distro
pkg search qemu-user

# If an ARM64 Linux shell is wanted for unrelated Linux tooling:
pkg install proot-distro
proot-distro search ubuntu
proot-distro install ubuntu:24.04 --architecture aarch64
proot-distro login ubuntu
```

A Linux x86_64 guest requires a QEMU user-mode package compatible with the installed Termux repository and the PRoot-Distro QEMU integration. This device has no `proot-distro` or QEMU executable installed, and the exact QEMU package availability was not established here. The x86_64 guest route was not attempted because its emulator, guest rootfs, SDK, and build caches would consume additional scarce storage and memory. Do not run the install commands on this device as a COSMIC build strategy without first checking current free resources and accepting the experimental/emulated nature of the setup.

## Storage and memory budget

These are measured values, not estimates:

- Available shared storage: **8.5 GB**.
- Existing Gradle cache: **1.1 GB**.
- Available RAM at the final recheck: **about 1.13 GiB** (it was 0.78 GiB at the earlier snapshot).
- Swap free during inspection: **about 0.26 GiB**.
- Project Gradle JVM maximum heap: **2 GiB** (`org.gradle.jvmargs=-Xmx2048m`).

The current official Linux command-line-tools archive is listed at about **181.8 MB**. Exact platform/build-tools download sizes are repository metadata that can change; no `sdkmanager` was available to query installed package sizes. Android Studio’s official Linux minimum is 8 GB free disk and 8 GB RAM, and Linux ARM is unsupported. The phone has slightly more than the IDE disk minimum but less than half the listed minimum RAM, and existing Gradle caches already use 1.1 GB.

For a CLI-only supported Linux build host, reserve at least **8 GB free disk after checkout** for the SDK, Gradle/Kotlin caches, build outputs, and test reports; more is preferable. Treat this as a planning reserve, not a measured SDK package total. After installation, measure actual usage with:

```sh
du -sh "$ANDROID_HOME" "$HOME/.gradle" app/build
```

The phone’s RAM/swap snapshot is the decisive local constraint. The configured 2 GiB Gradle heap plus SDK tools, Kotlin daemon, KSP and tests is likely to cause severe reclaim/OOM with only 1.13 GiB currently available and about 0.25 GiB swap free. No heap-setting override is recommended as a way to mask this hardware limit.

## Reproducible setup on a supported Linux host

These commands target a Debian/Ubuntu x86_64 host with JDK 17. They are instructions only; they were not run on the Termux phone.

```sh
uname -m                 # expected: x86_64
df -h "$HOME"           # reserve at least 8 GB free after checkout
free -h
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk curl unzip

export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
mkdir -p "$ANDROID_HOME/cmdline-tools" "$HOME/.cache/android-cmdline-tools"
cd "$HOME/.cache/android-cmdline-tools"

# Pin the command-line tools archive and verify its published SHA-256.
CMDLINE_TOOLS_REV=15859902
curl -fLO "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_REV}_latest.zip"
echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  commandlinetools-linux-15859902_latest.zip' | sha256sum -c -
unzip -q "commandlinetools-linux-${CMDLINE_TOOLS_REV}_latest.zip" -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  'platform-tools' \
  'platforms;android-35' \
  'build-tools;35.0.0'
```

The pinned archive revision and hash above are from the official Android download page at the time this guide was prepared. Re-check that page if the archive is no longer available. This setup installs the workflow’s SDK packages but does not resolve the AGP 8.5.2/API 35 compatibility mismatch noted above.

The current project normally discovers the SDK from `ANDROID_HOME` or `ANDROID_SDK_ROOT`. Do not commit `local.properties`; if a local Gradle invocation specifically requires it, create it as a local ignored file and verify it is ignored before building:

```sh
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
git check-ignore local.properties
```

## Reproducible verification procedure

Run from the repository root on the supported host, with network access for dependencies on the first run:

```sh
java -version
./gradlew --version
sdkmanager --list_installed
./gradlew --no-daemon help

# Kotlin and KSP (Room schema generation happens here)
./gradlew --no-daemon :app:compileDebugKotlin :app:kspDebugKotlin

# Unit tests, including Room migration tests
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests 'com.cosmic.ai.data.RoomMigrationV1ToV2Test' \
  --tests 'com.cosmic.ai.data.RoomMigrationV3ToV4Test'

# Android test sources, lint, and APK
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleDebug
```

Confirm the exported current Room schema and APK:

```sh
find app/schemas -maxdepth 3 -type f -print
test -s app/schemas/com.cosmic.ai.data.local.db.CosmicDatabase/4.json
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

The `test -s` checks fail if KSP did not export the schema or APK assembly did not produce an artifact. For a warm, fully cached Linux host, try `--offline` only after a successful online dependency resolution. It will not download a missing SDK package, Gradle plugin, Maven dependency, or platform artifact.

### Room schema and migration expectations

- Current Room version: **4**.
- `room.schemaLocation` is configured to `app/schemas`.
- KSP should write `app/schemas/com.cosmic.ai.data.local.db.CosmicDatabase/4.json` during the app KSP task.
- The checked-in historical schemas currently include v1 and v2. KSP running the v4 source does not synthesize schema v3; the v3 migration test builds a v3 fixture using the historical v2 schema plus v3 tables. Treat a missing v3 snapshot as a documentation/schema-history gap, distinct from successful execution of the v3→v4 migration test.
- Migration verification is not complete until both Room tests execute and pass with the generated schema validated by Room.

## Offline/local-only feasibility

An offline build is **not currently possible** on the phone: there is no Android SDK, and an offline Gradle cache cannot supply the missing platform/build-tools. The existing 1.1 GB Gradle cache may contain some Maven dependencies, but completeness was not established. After a supported host has completed an online build and installed all SDK packages, offline repeat builds may work if every dependency and tool artifact remains cached. Verify that with `./gradlew --offline ...`; a successful `--offline` run must be recorded separately from a normal build.

## Explicit limitations

- No package was installed and no Android build was attempted while preparing this guide.
- Android Studio on Linux ARM is officially unsupported. PRoot ARM64 alone is not a supported substitute for x86_64 SDK host tools; QEMU is an unverified/emulated option and is not recommended on this phone’s measured memory budget.
- The repository configuration declares AGP 8.5.2 with `compileSdk 35`, while official Android compatibility guidance requires AGP 8.6.0 or newer for API 35. This guide does not modify production build configuration. Resolve that mismatch in a separately reviewed source change before claiming a supported API 35 build.
- JDK 21 can run Gradle 8.9, but JDK 17 is the reproducible choice matching the repository CI and AGP 8.x baseline.
- A physical-device test is separate from compilation/unit tests. Do not install a newly built APK over existing app data without explicit authorization.

## References

- [Android Gradle Plugin 8.5 release notes and supported API ceiling](https://developer.android.com/build/releases/past-releases/agp-8-5-0-release-notes)
- [Android Gradle Plugin 8.6 compatibility (API 35, Gradle 8.7, JDK 17)](https://developer.android.com/build/releases/agp-8-6-0-release-notes)
- [AGP/API minimum compatibility table](https://developer.android.com/build/releases/about-agp)
- [Android Studio installation/system requirements (Linux ARM unsupported)](https://developer.android.com/studio/install)
- [Official Android SDK command-line tools downloads](https://developer.android.com/studio#command-line-tools-only)
- [sdkmanager install and license commands](https://developer.android.com/tools/sdkmanager)
- [Gradle Java runtime compatibility](https://docs.gradle.org/8.9/userguide/compatibility.html)
- [Gradle 8.5 Java 21 runtime support](https://docs.gradle.org/8.5/release-notes.html)
- [Termux PRoot-Distro capabilities and cross-architecture QEMU](https://github.com/termux/proot-distro)
- [Termux proot-distro package definition](https://github.com/termux/termux-packages/blob/master/packages/proot-distro/build.sh)
- [Termux Android SDK setup script (package-build infrastructure)](https://github.com/termux/termux-packages/blob/master/scripts/setup-android-sdk.sh)
