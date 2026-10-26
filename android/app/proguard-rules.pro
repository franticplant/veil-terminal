# Engine and protobuf rules come from :engine-client's consumer-rules.pro.

# Kept so scripts/check-release-apk.sh can read DEV_TOOLS from the dex: R8
# would otherwise inline the constant and drop the class.
-keep class io.github.franticplant.veil.BuildConfig { *; }
