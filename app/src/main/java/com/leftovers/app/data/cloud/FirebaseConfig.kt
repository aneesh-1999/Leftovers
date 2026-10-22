package com.leftovers.app.data.cloud

/**
 * Programmatic Firebase configuration for Leftovers personal finance.
 * Compliant with AGENTS.md:
 * - Programmatic initialization without proprietary google-services plugin
 * - Kept strictly isolated from Room Expense Tracker
 */
object FirebaseConfig {
    const val FIREBASE_APP_NAME = "LeftoversFirebaseApp"
    val PROJECT_ID: String get() = com.leftovers.app.BuildConfig.FIREBASE_PROJECT_ID.ifBlank { "leaftovers" }
    val API_KEY: String get() = com.leftovers.app.BuildConfig.FIREBASE_API_KEY
    val APP_ID: String get() = com.leftovers.app.BuildConfig.FIREBASE_APP_ID
    val STORAGE_BUCKET: String get() = com.leftovers.app.BuildConfig.FIREBASE_STORAGE_BUCKET
    val DEFAULT_WEB_CLIENT_ID: String get() = com.leftovers.app.BuildConfig.FIREBASE_WEB_CLIENT_ID
    const val DATABASE_ID = "(default)"

    // Storage path for Leftovers backups
    const val COLLECTION_USER_BACKUPS = "user_backups"
    const val DOC_LATEST = "latest"

    fun isConfigured(): Boolean = API_KEY.isNotBlank() && APP_ID.isNotBlank()

    @Synchronized
    fun getOrInitApp(context: android.content.Context): com.google.firebase.FirebaseApp {
        val appContext = context.applicationContext ?: context
        val existing = com.google.firebase.FirebaseApp.getApps(appContext).find { it.name == FIREBASE_APP_NAME }
        return existing ?: run {
            val options = com.google.firebase.FirebaseOptions.Builder()
                .setApplicationId(APP_ID)
                .setApiKey(API_KEY)
                .setProjectId(PROJECT_ID)
                .setStorageBucket(STORAGE_BUCKET)
                .build()
            com.google.firebase.FirebaseApp.initializeApp(appContext, options, FIREBASE_APP_NAME)
        }
    }
}
