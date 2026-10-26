# The native engine calls these by their JNI names.
-keep class io.github.franticplant.veil.engine.NativeEngine {
    native <methods>;
}

# protobuf-lite reads message fields reflectively.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
