# Leftovers — Implementation Status

*Last updated: November 2026 (v1.8.3)*
*Current Git Branch*: `my-features` (in sync with `main` on `aneesh` remote)

---

## 1. Verified Fully Implemented Features

| Feature | Status | Test Coverage |
| :--- | :--- | :--- |
| **Personal Finance Core** | Complete | Transactions, accounts, transfers, budgets, category totals, recurring items, trends, and multi-currency conversions. |
| **Custom Payment Apps Support (v1.8.3)** | Complete | Popular presets (Revolut, eSewa, Khalti, Monzo, Wise, PayPal, Cash App, Venmo) + add any installed app via device picker with native icons or manual package entry. Routing to accounts, read toggles, custom app deletion. Verified in `PaymentAppPrefsTest.kt`. |
| **Settings Reorganization (v1.8.3)** | Complete | Dedicated Security (app lock, timeouts), Reminders (daily reminders + time picker, bills, credit card due dates, roster reminders), and Payment Detection (payment apps counter, SMS, email, keywords). |
| **In-App Updater & Integrity (v1.8.3)** | Complete | GitHub Releases update checking, SHA-256 integrity validation, version comparison, download progress, and variant matching (release vs dev). Verified in `AppUpdateRepositoryTest.kt` and `AppUpdateNotificationDeduplicationTest.kt`. |
| **Firebase Cloud Backup & Auth** | Complete | Google Sign-In with Credential Manager, Email fallback, online/offline Firestore sync (`user_backups/{uid}`), GZIP compression, automatic 24h background sync, password encryption preservation. Verified in `CloudBackupTest.kt`. |
| **Room Database Migrations** | Complete (v1 ➔ v23) | `MigrationTest.kt` passes validating all 22 incremental schema migrations. |
| **Money Owed Account Linking (v23)** | Complete | Optional account connection for debts. Lending money deducts from linked account; borrowing adds to account; settled/deleted debts restore balance; unlinked debts remain independent; account deletion blocked if debts are linked. Verified in `DebtAccountIntegrationTest.kt`. |
| **Shared Room Expenses Integration** | Complete (Read-Only) | Direct named Firestore connection (`ai-studio-7a495509-3c2e-4c54-aafa-b8beac816b93`). Local Room cache table `room_expenses`. Calculation engine verified against live data in `RoomExpenseMathTest.kt`. |
| **Finance Separation & Deduplication** | Complete | "Include my share in personal finance" toggle in Settings; user's allocated share imported using stable tag `"[Room Expense] [Doc: <id>]"`; full household totals never imported; EUR currency isolated. |
| **Roster & Shift Calendar + Interactive Widget** | Complete | Shifts, workplaces, night shifts, leave entries, pay calculations, and interactive home-screen widget with day pills, week navigation, and mark-as-done toggle. |
| **Remittances Tracker** | Complete | Beneficiaries, transfers, exchange rates, and bank transaction linking. |
| **Investment Portfolio** | Complete | NEPSE asset transactions, live price snapshot history, watchlists. |
| **Credit Card Management (v21)** | Complete | Statement cycle tracking, payment allocations, reminders, and interest calculations. |

---

## 2. Test Suite & Build Verification

- **Unit Tests**:
  - Command: `./gradlew testDebugUnitTest`
  - Result: **362 tests executed, 0 failures, 100% passing**.
  - Key test suites:
    - `PaymentAppPrefsTest` (Custom payment apps, presets, routing, removal)
    - `PaymentNotificationParserTest` (Revolut, eSewa, generic notification parsers)
    - `AppUpdateRepositoryTest` & `AppUpdateNotificationDeduplicationTest` (Update checks, cache, rate limits)
    - `DebtAccountIntegrationTest` (Debt-account balance linking)
    - `MigrationTest` (All 22 Room schema migrations v1➔v23)
    - `RoomExpenseMathTest` (Roommate splits, coin meter, net settlement)
    - `CloudBackupTest`, `CreditCardMathTest`, `RemittanceCalculationsTest`, `RosterTest`.
- **Production Build**:
  - Command: `./gradlew assembleRelease`
  - Result: **BUILD SUCCESSFUL**
  - Output: `Leftovers-1.8.3-release.apk` (6.2 MB, R8 minified & optimized).
  - SHA256: `745e407600a1e6162b78a94ef704d78169de607e31b0c673f66168ed5c6a71ed`
  - Live on GitHub: [https://github.com/aneesh-1999/Leftovers/releases/tag/v1.8.3](https://github.com/aneesh-1999/Leftovers/releases/tag/v1.8.3)

---

## 3. Current Git Working State
- **Branch**: `my-features` (clean working directory, synchronized with `main` on `https://github.com/aneesh-1999/Leftovers.git`)
- **Active Release Tag**: `v1.8.3`
