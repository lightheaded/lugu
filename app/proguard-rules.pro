# Keep rules for the release build.
#
# The principle here is that every rule names something specific and says why it exists.
# A blanket `-keep class ** { *; }` would make the build pass and R8 pointless, and would
# also hide the next reflective dependency someone adds. Most of the libraries in this
# app already ship consumer rules inside their own artifacts — Media3, Room, Hilt, Coil,
# OkHttp, Sentry, the coroutines and serialization runtimes all do — so what follows is
# only what those do not cover, plus this app's own reflective surface.

# -- Stack traces ------------------------------------------------------------------
# R8 renames everything, and a crash report without a file and a line number cannot be
# acted on. These attributes are what makes an uploaded mapping file usable; uploading
# it is a separate problem, recorded in the backlog.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -- Kotlin ------------------------------------------------------------------------
# Generic signatures and runtime annotations survive because kotlinx.serialization, Room
# and Hilt all read them back; R8 treats unread attributes as dead weight otherwise.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# -- kotlinx.serialization ---------------------------------------------------------
# The classic casualty. A `@Serializable` class's serializer is generated as a nested
# `$serializer` object and resolved by name at runtime, so nothing in the code refers to
# it and R8 removes it — the symptom is a SerializationException about a missing
# serializer, only in release, only for whichever type happened to be shrunk. These are
# the rules from the library's own documentation.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The app's own serialized types, named rather than left to the rules above: the API DTOs
# in core:api, the download manifest, and the progress payloads in core:sync. Named
# because these are the ones whose loss shows up as an empty library rather than as a
# crash, which is the failure that takes longest to trace back to R8.
-keep,includedescriptorclasses class io.github.lightheaded.lugu.core.api.**$$serializer { *; }
-keep,includedescriptorclasses class io.github.lightheaded.lugu.core.download.**$$serializer { *; }
-keep,includedescriptorclasses class io.github.lightheaded.lugu.core.sync.**$$serializer { *; }
-keepclassmembers class io.github.lightheaded.lugu.core.api.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.lightheaded.lugu.core.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# -- Ktor --------------------------------------------------------------------------
# AbsClient builds `HttpClient { }` with no engine named, so Ktor finds one through
# java.util.ServiceLoader: the OkHttp engine is referred to only from a META-INF/services
# file and by nothing in the code, which is exactly the shape R8 removes. ktor-client-core
# ships no consumer rules, so this has to be written here.
-keep class io.ktor.client.HttpClientEngineContainer
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# Ktor's internal state is held in volatile fields updated through atomic field updaters,
# which resolve by name.
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}

# Ktor's JVM artifacts reference server-side and logging classes that were never on
# Android. Warnings only; nothing here is reachable from this app.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn kotlinx.coroutines.debug.**

# -- OkHttp ------------------------------------------------------------------------
# OkHttp probes for optional TLS providers by name and warns about each one it cannot
# find. None of them are shipped here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -- Room --------------------------------------------------------------------------
# Room instantiates the generated `_Impl` of the database by name. room-runtime's own
# rules cover the generated classes; the database class itself needs its constructor.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# -- WorkManager and Hilt ----------------------------------------------------------
# Workers are constructed reflectively from a class name stored in WorkManager's own
# database, so a worker that exists only in a scheduled row and nowhere in the code looks
# unreachable to R8. Hilt's worker factory does not change that.
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# -- Media3 ------------------------------------------------------------------------
# The playback service is named in the merged manifest and in a ComponentName, and it is
# the entry point for the notification, Android Auto and the media button receiver — a
# rename here is silent until the car will not connect.
-keep public class * extends androidx.media3.session.MediaSessionService {
    <init>();
}
-keep public class * extends androidx.media3.session.MediaLibraryService {
    <init>();
}

# -- Socket.IO ---------------------------------------------------------------------
# Live updates from the server. engine.io picks its transports by class name and both
# libraries hand callbacks across a Java listener interface, so the classes are reachable
# only through strings and reflection. The failure mode is the quiet one: the socket never
# connects, the poll-and-sweep sync covers for it, and nobody notices until they wonder
# why an edit made on the web took ten minutes to appear.
-keep class io.socket.** { *; }
-keep class io.socket.engineio.client.transports.** { *; }
-dontwarn io.socket.**

# org.json ships with Android, but engine.io's own copy is referenced at compile time.
-dontwarn org.json.**
