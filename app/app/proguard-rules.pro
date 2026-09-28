-keepattributes SourceFile,LineNumberTable
-dontobfuscate
-dontoptimize
-keep public class de.mm20.launcher2.ui.launcher.search.common.SearchableItemVM {
    public <init>();
}


# Release builds carry no debug or verbose logging (#15). Debug lines name
# apps and packages, and an app inventory in logcat is what a launcher must
# not emit. Measured, not assumed: under -dontoptimize above, R8 still
# dropped 100 of 102 calls, and kept two that built their message from a
# package name. check-release-logs.py counts the calls left in the built
# APK's dex, and both release jobs require zero. Warnings and errors stay;
# they are kept free of app identity at their call sites.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
