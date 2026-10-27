# AIDL stubs and the Binder interface are referenced by name across packages.
-keep class io.github.franticplant.veil.dev.api.IDevHooks { *; }
-keep class io.github.franticplant.veil.dev.api.IDevHooks$* { *; }

# The native server calls these by name.
-keep class io.github.franticplant.veil.devd.DevdNative { *; }
-keep interface io.github.franticplant.veil.devd.DevdNative$Callback { *; }
-keep class * implements io.github.franticplant.veil.devd.DevdNative$Callback { *; }
