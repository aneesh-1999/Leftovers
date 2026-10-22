# AGENTS.md — Leftovers Coding & Collaboration Rules

This file defines the permanent operational, architectural, and safety guidelines for any agent working on the Leftovers codebase.

---

## 1. Core Principles

1. **Never Rebuild from Scratch**:
   - Never replace, wipe, or recreate the existing project, its build scripts, or its architecture.
   - Work incrementally within the established patterns of the repository.

2. **Preserve User Data & Backwards Compatibility**:
   - Leftovers is an offline-first personal finance app holding real user transaction history.
   - **Never run destructive database statements** (e.g. `clearAllTables()`, `fallbackToDestructiveMigration()`) in production or default migration flows.
   - Every schema change must be accompanied by a safe, explicit Room `Migration(from, to)` step added to `ALL_MIGRATIONS` in `AppDatabase.kt` and verified with automated migration unit tests in `MigrationTest.kt`.
   - Never let a failed remote sync or network error delete or corrupt local records.

3. **Follow Established Architecture & Design Patterns**:
   - **Architecture**: MVVM with unidirectional data flow (Kotlin Coroutines `StateFlow`, Room Database `Flow`, manual dependency injection via `AppContainer`).
   - **Design System**: Material 3 with Leftovers frosted glassmorphic UI (`Glass`, `GlassScreen`, `GlassHeader`, `LocalAppColors`, `RoundButton`, `PrimaryButton`, `DockClearance`).
   - **Icons**: Use `com.leftovers.app.ui.icons.Lucide` vector definitions.
   - **Money Math**: Monies are stored in minor units (`amountMinor: Long`, cents / paise) to prevent floating-point rounding errors. Float/Double is reserved strictly for display percentages or external Firestore schemas.
   - **Currency Isolation**: Keep separate currencies (EUR, NPR, USD) strictly isolated in aggregates and UI reports.

4. **Security, Privacy & F-Droid Compliance**:
   - **No Secrets in Source**: Do not hardcode service-account private keys, Firebase Admin credentials, or private credentials into git or client APKs.
   - **F-Droid Cleanliness**: No proprietary tracking or encrypted Play dependency lists. Firebase is configured programmatically (`FirebaseApp.initializeApp`) without the proprietary `google-services` gradle plugin.
   - **Separate Auth & Projects**: Leftovers personal finance and Room Expense Tracker must maintain strictly separated authentication, isolated databases, and household-level boundaries. Do not grant automatic household ledger access to generic family accounts.
   - **Read-Only First**: Default external integrations to read-only until explicit mutation workflows are reviewed.

5. **Testing & Verification**:
   - Run `./gradlew testDebugUnitTest` after any schema, math, or repository changes.
   - Ensure all Robolectric database upgrade tests pass.
   - Verify changes compile with `./gradlew compileDebugKotlin` before declaring work done.
