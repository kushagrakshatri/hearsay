# Our own code is small; keep it whole (JNI bindings, accessibility/IME services, reflection-free).
-keep class dev.offlineflow.** { *; }
# sherpa-onnx's native code looks up its Java classes, fields and constructors by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-dontwarn com.k2fsa.**
# LiteRT-LM: its JNI calls back into these classes by name, and it maps messages with Gson/kotlin-reflect.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-dontwarn kotlin.reflect.**
