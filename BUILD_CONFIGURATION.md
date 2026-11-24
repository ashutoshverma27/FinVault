# FinVault build configuration

This project is intentionally pinned to a compatible Android build toolchain targeting Android API 36.

| Component | Version |
|---|---:|
| Android Gradle Plugin | 8.13.2 |
| Gradle distribution | 8.14.4 |
| Kotlin Gradle Plugin | 2.3.20 |
| Kotlin Compose compiler plugin | 2.3.20 |
| Kotlin Symbol Processing (KSP) | 2.3.12 |
| Compile SDK | 36 |
| Target SDK | 36 |
| Minimum SDK | 26 |
| Java / Gradle JDK | 17 |
| Compose BOM | 2025.11.00 |
| Lifecycle | 2.9.4 |
| Room | 2.8.5 |

## Why these versions are pinned

- AGP 8.13.x supports API 36 and requires Gradle 8.13 or newer.
- KSP 2.3.12 supports Kotlin 2.3.20–2.4.x, AGP 8.13.0 or newer, and Gradle 8.13 or newer.
- The Compose BOM is pinned to a stable 2025 release rather than the 2026 Compose line that triggered API 37 / AGP 9.1 metadata errors in the original project.
- Lifecycle is pinned to 2.9.4 rather than 2.11.0, which was among the dependencies requiring API 37 / AGP 9.1 in the supplied build log.

## Gradle launcher note

The `gradlew` and `gradlew.bat` files are small bootstrap scripts included with this starter. They download and launch the pinned Gradle distribution on first use. They are not the generated Gradle Wrapper JAR scripts. Internet access is required for the initial Gradle distribution and dependency downloads.

## Build

- Use JDK 17 as the Gradle JDK in Android Studio.
- Install Android SDK Platform 36.
- Open the folder containing `settings.gradle.kts`.
- Sync Gradle, then run `:app:assembleDebug`.

A successful build has not been claimed from this packaging environment because it has no Android SDK and cannot reach Gradle/Maven repositories. The configuration has been statically checked against the published compatibility requirements; Android Studio must perform the actual dependency download and build on your machine.
