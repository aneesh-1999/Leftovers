# Leftovers — Session Handoff

*Updated: November 2026 (v1.8.3 / VersionCode 26)*
*Current Git Branch*: `my-features` (synchronized with `main` and pushed to `aneesh`)

---

## 1. What Was Accomplished in This Session

### A. Custom Payment Apps (Revolut, eSewa, Khalti, & Installed App Picker)
- **Built-in Presets Expanded**: Added presets for **Revolut**, **Revolut Business**, **eSewa**, **eSewa Mobile**, **Khalti**, **Siddhartha BankSmart**, **Global Smart Plus**, **Nabil SmartBank**, **Monzo**, **Wise**, **PayPal**, **Cash App**, and **Venmo** in `PaymentApps.names`.
- **Add Any Installed App**: Added `AddPaymentAppSheet` with dual tabs:
  - **Installed on device**: Lists non-system apps with actual application icons loaded asynchronously from `PackageManager` and real-time search filtering. Tapping any app configures it immediately.
  - **Custom package**: Manual entry for app name and package name with validation and guidance text.
- **Management & Custom Deletion**:
  - Displays real app icons or clean fallback vector icons for all configured apps.
  - Custom apps feature a `"Custom"` badge and a delete button (`Lucide.Trash2`) with an alert confirmation dialog.
  - Individual read toggles and account routing (e.g., directing Revolut transactions to a Revolut EUR wallet or eSewa to an NPR wallet).
- **Auto-Parsing Integration**: `PaymentNotificationListener` connects to `PaymentNotificationParserRegistry.parse` for parsing transactions from Revolut, eSewa, and generic banking notifications across multiple currencies (€, £, $, Rs, ₹).
- **Unit Test Coverage**: Added `PaymentAppPrefsTest.kt` verifying preset loading, custom app addition, persistence across instances, routing, and removal.

---

### B. Settings Screen Reorganization
The monolithic, cluttered "Security & reminders" section in `SettingsScreen.kt` was reorganized into 3 clean, dedicated sections:
1. **Security**:
   - App lock switch (PIN / Fingerprint / Face unlock).
   - Lock method selection and auto-lock timeout options.
2. **Reminders** *(All reminder features unified together)*:
   - Daily expense reminder switch with interactive Material 3 time picker.
   - Upcoming bill & subscription due-date reminders.
   - Credit card payment due-date reminders.
   - Shift & roster reminders (advance hours, night-before notification time, morning alarm suggestion).
3. **Payment detection**:
   - Payment apps row with live counter (`"X reading · Y apps configured"`).
   - Detect bank SMS switch.
   - Detect payments in email navigator.
   - Custom detection keywords manager.

---

### C. Release v1.8.3 & In-App Updater Hardening
- **Version Bump**: Bumped to `versionName = "1.8.3"` and `versionCode = 26` in `app/build.gradle.kts`.
- **Updater Checksum Collision Fix**:
  - Previously, releases with multiple APKs (`release.apk` and `dev.apk`) caused the existing in-app updater on user phones to download `dev.apk` (due to alphabetical ordering) while checking it against the `release.apk.sha256` checksum, resulting in `APK integrity check failed! Expected SHA-256...`.
  - Hardened `AppUpdateRepository.kt` to explicitly prioritize release variant APKs over dev APKs and match checksum assets by name.
  - Cleaned GitHub release `v1.8.3` to provide `Leftovers-1.8.3-release.apk` with matching SHA-256 checksum.
- **README Clean Up**:
  - Download links point exclusively to our release page (`https://github.com/aneesh-1999/Leftovers/releases/latest`).
  - Git clone command points to `https://github.com/aneesh-1999/Leftovers.git`.
  - Concise credit given to original creator C. Sai Saharsh without extraneous external links.

---

## 2. Core Architecture & Preservation Principles
1. **Offline-First & Data Preservation**: Never delete or drop user database tables. Schema changes always use explicit Room migrations.
2. **Preserved Custom Modules**:
   - **Roster Module**: Workplaces, Shifts, Leaves, Timesheets, Alarms, and interactive home-screen widget.
   - **Remittance Module**: Beneficiaries, transfers, exchange rates, and bank transaction linking.
   - **Firebase Cloud Backup & Restore**: Google & Email Authentication with automatic daily sync and full user data isolation.
   - **Room Expense Tracker**: Co-living shared household ledger (Ozric & Pawan) with coin-meter tracking.
   - **Investments & Credit Cards**: NEPSE stocks, gold/silver, statement cycles, and payment reminders.
   - **SMS & Payment Notifications**: Real-time parsing with custom app support.
   - **In-App Updater**: GitHub Releases updater with SHA-256 integrity check and version comparison.

---

## 3. Verification & Build Health
Run these verification commands:
```bash
# 1. Compile Kotlin sources
./gradlew compileDebugKotlin

# 2. Run all unit tests (362 tests passing, 0 failures)
./gradlew testDebugUnitTest

# 3. Build production release APK
./gradlew assembleRelease
```

- **All Unit Tests**: **362 tests executed, 0 failures, 100% passing**.
- **Release APK**: `Leftovers-1.8.3-release.apk` (6.2 MB, R8 minified & optimized).
- **Verified SHA-256 Checksum**:
  `745e407600a1e6162b78a94ef704d78169de607e31b0c673f66168ed5c6a71ed`
- **GitHub Release**: [https://github.com/aneesh-1999/Leftovers/releases/tag/v1.8.3](https://github.com/aneesh-1999/Leftovers/releases/tag/v1.8.3)
