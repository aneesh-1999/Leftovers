# Leftovers — Project Context & Architecture

*Version*: **1.8.3** (versionCode 26)  
*Last updated*: November 2026

## 1. Project Purpose & Target Users
**Leftovers** is a private, offline-first personal finance and daily planner application built for Android. It is tailored for international earners, migrant workers, and Nepali diaspora individuals who manage:
- Daily personal cash flow, multi-currency accounts (EUR, NPR, USD, GBP, etc.) with automatic rate conversions.
- Work rosters, variable hourly wages, night shifts, leave tracking, and interactive home-screen widgets.
- Remittances sent to beneficiaries in Nepal with live exchange-rate history.
- Investments including NEPSE stocks, gold, and silver.
- Credit card cycles, minimum payments, and billing reminders.
- **Custom Payment Apps**: Real-time parsing from banking and payment apps (Revolut, eSewa, Khalti, Monzo, Wise, or any user-installed app).
- **Shared Room Expenses**: Co-living household expense ledger with roommates (Ozric & Pawan) tracking split purchases, net settlement balances, and coin-meter electricity contributions.

---

## 2. Technical Stack & Build Setup
- **Language**: Kotlin 2.4.20
- **Android Target**: compileSdk 37, targetSdk 36, minSdk 26
- **Build System**: Android Gradle Plugin 9.4.1, Gradle 9.8.0, Kotlin Symbol Processing (KSP) 2.3.12
- **UI Toolkit**: Jetpack Compose with Compose BOM `2026.09.00`, Material 3 + Liquid Glass design tokens
- **Local Persistence**:
  - **Room Database 2.8.5**: SQLite database with full schema export enabled (`app/schemas/com.leftovers.app.data.AppDatabase/`).
  - **Jetpack DataStore Preferences 1.2.1**: App configuration and feature flags (`settings`).
- **Dependency Injection**: Explicit, lightweight application container pattern via `AppContainer.kt`.
- **Glassmorphic UI**: `dev.chrisbanes.haze:haze:2.0.1` and Leftovers custom glass components (`Glass`, `GlassSheet`, `LocalAppColors`).
- **Icons**: Lucide icons custom vector pack (`com.leftovers.app.ui.icons.Lucide`).
- **Testing**: JUnit 4.13.2, Robolectric 4.17 (JVM Android environment testing on SDK 35), AndroidX Room Testing. 362 passing tests.

---

## 3. Architecture & Key Subsystems

### A. Navigation & MVVM Layer
- **UI Layer**: Jetpack Compose screens consuming `StateFlow` exposed by ViewModels created via `AppViewModelProvider.Factory`.
- **Navigation**: `LeftoversNavHost.kt` with bottom dock tabs (`Home`, `Activity`, `Insights`, `Plan`) and full-screen detail destinations.
  - `Routes.HOME`: Account balances, safe-to-spend allowance, recent activity.
  - `Routes.ACTIVITY`: Filterable transaction timeline and search.
  - `Routes.INSIGHTS`: Monthly spend analytics, categories, and yearly recaps.
  - `Routes.PLAN`: Budget goals, subscriptions, and sub-features:
    - `Routes.ROSTER`: Shift calendar, hours logged, and estimated pay.
    - `Routes.REMITTANCE`: Remittance logs, recipient management, bank reports.
    - `Routes.INVESTMENT`: Portfolio tracking (NEPSE stocks, Gold/Silver).
    - `Routes.CREDIT_CARDS`: Card statement cycles and payment reminders.
    - `Routes.ROOM_EXPENSES`: Shared household roommate ledger.

### B. Custom Payment Apps Subsystem
- `PaymentNotifications.kt`:
  - `PaymentApps`: Expanded preset registry (Revolut, eSewa, Khalti, Monzo, Wise, PayPal, Cash App, Venmo).
  - `PaymentAppPrefs`: SharedPreferences-backed configuration managing read toggles, custom app definitions, and account routing.
  - `PaymentNotificationListener`: Android notification listener service delegating to `PaymentNotificationParserRegistry`.
- `AddPaymentAppSheet` in `SettingsScreen.kt`:
  - Device picker listing non-system apps with icons loaded via `PackageManager`.
  - Manual package name entry tab with validation.

### C. Settings Architecture
Cleaned and separated into dedicated, modular groups:
- **Security**: Biometric / PIN lock, auto-lock timeout.
- **Reminders**: Grouping daily reminder with time picker, bill due dates, credit card due dates, and shift/roster advance notifications.
- **Payment Detection**: Payment apps counter & sheet, bank SMS switch, email connection, and detection keywords.

### D. In-App Updater Subsystem
- `AppUpdateRepository.kt`: Checks GitHub Releases API (`aneesh-1999/Leftovers`), validates SHA-256 checksums, downloads APK with progress tracking, prioritizes matching APK variants (release vs dev), and prompts Android Package Installer.

---

## 4. Database Schema & Migration History
- Current Version: **23** (`AppDatabase.kt`)
- Entities:
  - Core: `Category`, `Transaction`, `Recurring`, `Goal`, `GoalDeposit`, `Account`, `Transfer`, `Debt` (with optional `accountId` in v23), `DeletedTransaction`, `SeenMessage`.
  - Roster: `Shift`, `Workplace`, `LeaveEntry`, `WorkplacePayment`, `DeletedShift`, `DeletedLeaveEntry`.
  - Remittances: `Recipient`, `Remittance`, `RemittanceStatusHistory`.
  - Investments: `InvestmentAsset`, `InvestmentTransaction`, `MarketPriceSnapshot`, `MarketPriceHistory`, `WatchlistItem`.
  - Credit Cards (v21): `CreditCard`, `CreditCardStatement`, `CreditCardTransaction`, `StatementPaymentAllocation`.
  - Shared Household (v22): `RoomExpense` (`room_expenses` table with indices on `timestamp`, `isSettlement`, `isCoin`, `isLendBorrow`, `status`).
- All 22 upgrade steps (`MIGRATION_1_2` through `MIGRATION_22_23`) are strictly covered by automated Robolectric tests in `MigrationTest.kt`.
