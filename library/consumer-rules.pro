# Consumer ProGuard rules for the ART library.
#
# These rules are bundled into the AAR and applied automatically by any
# downstream consumer that runs R8 / ProGuard. Keep them minimal and
# focused on reflection-touched code.

# --- Gson uses reflection to (de)serialize models ---
-keep class com.example.artlibrary.crdt.** { *; }
-keep class com.example.artlibrary.types.** { *; }

# Preserve sealed-class subtypes used by CRDTOperationAdapter.
-keep class com.example.artlibrary.crdt.CRDTOperation$* { *; }

# --- libsodium-jni uses JNI bindings ---
-keep class org.libsodium.jni.** { *; }
-dontwarn org.libsodium.jni.**

# --- Coroutines internals (defensive) ---
-dontwarn kotlinx.coroutines.**
