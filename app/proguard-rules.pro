# JNI: the native bridge looks these up by name.
-keepclasseswithmembernames class dev.mahali.hatch.ghostty.** {
    native <methods>;
}
