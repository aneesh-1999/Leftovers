# Room and Compose ship their own consumer rules.

# Jakarta Mail (email payment detection) finds its IMAP store and helpers by class name, from the
# META-INF/javamail.* and META-INF/services files in its jars, so they must keep their names.
-keep class org.eclipse.angus.mail.imap.** { *; }
-keep class org.eclipse.angus.mail.iap.** { *; }
-keep class org.eclipse.angus.mail.handlers.** { *; }
-keep class org.eclipse.angus.mail.util.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn org.graalvm.**
-dontwarn javax.security.auth.callback.**

# Optimization passes and access modification for faster execution & smaller code size
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ""

# Strip debug and verbose logging overhead in optimized builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

