# Leftovers — Architecture & Technical Decisions

## 1. Firebase Integration Architecture

### Decision: Programmatic Initialization vs `google-services` Gradle Plugin
- **Chosen**: Programmatic initialization using `FirebaseApp.initializeApp(...)` and `FirebaseFirestore.getInstance(app, databaseId)` in Kotlin.
- **Rejected Alternative**: Applying the `com.google.gms.google-services` Gradle plugin with a root `google-services.json`.
- **Rationale**:
  1. The Leftovers app is designed to be F-Droid compliant, reproducible, and free of proprietary Google Play Services plugins that inject encrypted dependencies.
  2. The target database is a **named** Firestore database (`ai-studio-7a495509-3c2e-4c54-aafa-b8beac816b93`). Programmatic initialization allows passing the exact named database ID directly without risking accidental connection to `(default)`.

### Decision: Named Database Selection
- **Chosen**: Connect strictly to `ai-studio-7a495509-3c2e-4c54-aafa-b8beac816b93`.
- **Finding**: REST queries confirmed that the `(default)` database does **not** exist for Google Cloud project `ai-studio-applet-webapp-de5cb`. Attempting to query `(default)` returns HTTP 404. All collection data resides inside this named database instance.

---

## 2. Privacy, Authorization & Security Decisions

### Decision: Separate Auth & Distinct Firebase Project
- **Chosen**: Keep Leftovers personal finance and Room Expense Tracker in completely separate authentication domains and projects.
- **Rationale**:
  1. Leftovers personal finance is 100% offline-first and private to the device owner.
  2. The Room Expense Tracker is a shared co-living ledger for two specific roommates (`Ozric` and `Pawan`).
  3. **Household-Level Authorization**: Automatic access is strictly forbidden for general family or external users. Only authorized household members participate in this ledger.
  4. **No Service Accounts**: Service-account keys or Admin SDK credentials are never included in the client APK. Leftovers operates in **Read-Only** mode using client-side Firestore SDK with offline persistence.

---

## 3. Financial Separation & Data Integrity Decisions

### Decision: Separate Household Ledger from Personal Transactions
- **Chosen**: Shared expenses are stored in a dedicated Room table (`room_expenses`).
- **Rationale**:
  1. Household expenses often total hundreds or thousands of euros (e.g. rent, groceries, electricity coins). Counting the total household expenditure against an individual's personal Leftovers budget would distort personal cash flow and budget alerts.
  2. An explicit user toggle (`roomExpenseIncludeInPersonal`) allows importing **only** the active user's allocated share (`splitDetails[roommate]`).
  3. Stable tag references `[Room Expense] [Doc: <firestoreDocId>]` prevent duplicate imports on repeated syncs.
  4. Currencies are strictly preserved (EUR remains distinct from NPR / USD).

### Decision: Coin Meter Isolation
- **Chosen**: Electricity Coin contributions (`isCoin == true`) are tracked in a dedicated Coin Meter view and excluded from standard split expense totals.
- **Rationale**:
  1. Ported directly from `Dashboard.tsx` in ROOM-EXPANCE.
  2. Coins are physical meter insertions and do not represent ordinary split purchases. Treating them as ordinary purchases would distort monthly spend metrics.

---

## 4. Room Database Migration Strategy (v21 ➔ v23)

### Decision: Safe Incremental Migration
- **Chosen**: Implemented explicit migrations (`MIGRATION_21_22`, `MIGRATION_22_23`) preserving existing personal finance, credit cards, investments, and remittances.
- **Rejected Alternative**: Destructive schema fallback.
- **Rationale**:
  1. Real user financial history must never be lost during an upgrade.
  2. `MigrationTest.kt` verifies that an existing database migrated from version 2 all the way to 23 preserves every table and row without data loss.

---

## 5. Custom Payment Apps Architecture (v1.8.3)

### Decision: Dual Selection (Installed App Picker + Custom Package Entry)
- **Chosen**: Provide an asynchronous `PackageManager` query listing non-system applications with real app icons, alongside a manual package name entry tab with validation.
- **Rejected Alternative**: Hardcoding fixed banking apps into source code.
- **Rationale**:
  1. Users in different countries use regional banking and payment apps (e.g., Revolut, eSewa, Khalti, Monzo, Wise, Nabil SmartBank). A fixed preset list cannot anticipate every banking app.
  2. Direct installed app picking eliminates typing errors in Android package identifiers.
  3. Manual entry provides a fallback for testing or apps installed outside Google Play.

---

## 6. Settings Screen Organization (v1.8.3)

### Decision: Separate Security, Reminders, and Payment Detection
- **Chosen**: Split the cluttered "Security & reminders" block into 3 dedicated sections: **Security**, **Reminders**, and **Payment detection**.
- **Rationale**:
  1. All notification reminders (daily expenses, bills, credit card payment due dates, and work shift advance notices) belong together for intuitive user management.
  2. Security controls (biometric, PIN, auto-lock timeouts) require clear, focused prominence.
  3. Payment detection (apps, SMS, email, keywords) represents a distinct operational workflow.

---

## 7. In-App Updater Asset Matching & Integrity (v1.8.3)

### Decision: Variant Matching & Explicit Checksum Association
- **Chosen**: In `AppUpdateRepository`, inspect all APK assets and prioritize the running build variant (production `release` vs `dev`). Match the `.sha256` asset specifically by filename (`${apkName}.sha256`).
- **Rationale**:
  1. Prevents checksum mismatch errors when releases contain both release and dev build variants.
  2. Protects user devices by guaranteeing that downloaded APKs match their expected SHA-256 integrity hash before invoking the package installer.
