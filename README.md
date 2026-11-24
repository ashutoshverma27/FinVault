# FinVault

Native Android, local-first personal finance application.

## Included in this starter

- Kotlin + Jetpack Compose UI
- Room / SQLite local database
- Cash, bank and credit-card account definitions
- Expense and income categories
- Double-entry journal entries and journal lines
- Expense, income and transfer entry
- Account balance calculation from ledger entries
- Recent transaction history
- No backend or cloud database

## Open in Android Studio

1. Extract the ZIP.
2. Open Android Studio.
3. Select **Open** and choose the extracted `FinVault` folder (the folder containing `settings.gradle.kts`).
4. Set **Gradle JDK** to JDK 17 in Settings > Build, Execution, Deployment > Build Tools > Gradle.
5. Install Android SDK Platform 36 from SDK Manager if needed.
6. Allow Gradle to download dependencies and sync.
7. Run the `app` configuration on an emulator or Android device.

The included Gradle bootstrap scripts download Gradle 8.14.4 on first use. Internet access is required for the first Gradle sync and dependency download. See [BUILD_CONFIGURATION.md](BUILD_CONFIGURATION.md) for the pinned compatibility matrix and build notes.

## Build from terminal

Windows:
```bat
gradlew.bat assembleDebug
```

macOS / Linux:
```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Important scope notes

- Financial data is stored in the app's private Room database on this device.
- No backend or cloud sync is configured.
- Seed account balances start at zero. Entering opening balances is a next milestone; do not treat zero as a real bank balance.
- SMS, Gmail, bank statement and broker integrations are not included yet.
- Reversals, audit history, source observations, deduplication, statement reconciliation, investment holdings, encrypted backup and database encryption are planned next.
- Android backup is disabled for this starter so financial data is not silently copied into a consumer cloud backup.

## Versions

- Android Gradle Plugin 8.13.2
- Kotlin 2.3.20
- Compose BOM 2025.11.00
- Room 2.8.5
- KSP 2.3.12
- Gradle 8.14.4
- JDK 17
