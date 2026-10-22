# The JNI library binds to these by name: JniNatives through its native methods, and JniHost
# through FindClass and GetStaticMethodID when the library loads, so R8 may neither rename them
# nor drop what the library calls.
-keep class io.github.yuroyami.kitejs.quickjs.bridge.JniNatives { *; }
-keep class io.github.yuroyami.kitejs.quickjs.bridge.JniHost { *; }
