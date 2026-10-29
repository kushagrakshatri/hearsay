# Our own code is small; keep it whole (JNI bindings, accessibility/IME services, reflection-free).
-keep class dev.offlineflow.** { *; }
# sherpa-onnx's native code looks up its Java classes, fields and constructors by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-dontwarn com.k2fsa.**
