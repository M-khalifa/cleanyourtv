# Started by name: app_process runs HelperMain, Shizuku instantiates HelperService in its own process.
-keep class io.github.mkhalifa.miracast.helper.** { *; }
# Hidden framework classes reached by reflection in the helper.
-dontwarn android.app.ActivityThread
