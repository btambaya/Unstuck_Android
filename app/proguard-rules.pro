# =============================================================================
# Unstuck — R8 keep rules for the RELEASE build (isMinifyEnabled = true).
#
# Goal: shrink/obfuscate aggressively WITHOUT breaking the load-bearing runtime
# machinery — kotlinx-serialization (JSON (de)serialization for BOTH the local
# Room `records` blob store AND Supabase sync; broken keep rules here = silent
# data corruption), Room, supabase-kt + ktor + okhttp, Glance app widget,
# Firebase Cloud Messaging, Compose, WorkManager, DataStore.
#
# Used WITH proguard-android-optimize.txt (the AGP default optimize config).
# =============================================================================

# -----------------------------------------------------------------------------
# kotlinx-serialization — OFFICIAL R8 rules (verbatim from the kotlinx.serialization
# README / AndroidX consumer rules). These are load-bearing: they preserve the
# generated $$serializer + the Companion.serializer() entry points the runtime
# resolves reflectively. DO NOT EDIT.
# -----------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named).
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
}

# -----------------------------------------------------------------------------
# App-owned serializable models — defensive belt-and-braces keeps so field /
# enum shapes (and the generated $$serializer descriptors) can never be stripped
# or renamed. Renaming a serialized field name silently corrupts the JSONB the
# server + local blob store expect.
#
# NOTE: @Serializable classes live in MORE than just core.model — there are also
# DTOs in tech.csalliance.unstuck.sync.* (DbRowCodec rows, the edge-function
# request/response DTOs, calendar/collection/feedback/assistant clients incl.
# nested private data classes) and a couple in app (NotificationLog, AppViewModel).
# The two `$$serializer` + `Companion` rules below are package-wide
# (tech.csalliance.unstuck.**) and cover ALL of them. core.model is additionally
# kept whole.
# -----------------------------------------------------------------------------

# Keep every member (fields, the @SerialName values, the custom RecurrenceSerializer
# object, the Recurrence sealed hierarchy) of the core domain models intact.
-keep,includedescriptorclasses class tech.csalliance.unstuck.core.model.** { *; }

# Keep ALL generated synthetic serializers app-wide (core, data, sync, app).
-keep,includedescriptorclasses class tech.csalliance.unstuck.**$$serializer { *; }

# Keep the Companion field of every app class (the serializer() resolution path).
-keepclassmembers class tech.csalliance.unstuck.** {
    *** Companion;
}

# Enums serialized BY NAME via @SerialName (Priority, FocusState, FocusTreatment,
# CalBlockKind, ReasonAction, CaptureTag, CalendarProvider, ThemePref, Density).
# core.model.** above already keeps these whole, but make the intent explicit and
# cover any serialized enum that might live elsewhere.
-keepclassmembers enum tech.csalliance.unstuck.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# kotlinx-serialization keeps its own metadata under @kotlinx.serialization
# annotations; suppress notes for its optional internals.
-dontwarn kotlinx.serialization.**

# -----------------------------------------------------------------------------
# Room — keep entities, DAOs, the database impl + the generated _Impl classes.
# Room generates an `<Database>_Impl` at compile time that it loads by name.
# -----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
# Room's TypeConverters / generated impls reference these; quiet the warnings.
-dontwarn androidx.room.paging.**

# -----------------------------------------------------------------------------
# Jetpack Glance app widget (StartNextWidget / StartNextWidgetReceiver) — the
# receiver is instantiated by the system from the manifest; Glance also uses
# reflection internally for its action/state machinery.
# -----------------------------------------------------------------------------
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class tech.csalliance.unstuck.surface.StartNextWidget { *; }
-keep class tech.csalliance.unstuck.surface.StartNextWidgetReceiver { *; }
-dontwarn androidx.glance.**

# -----------------------------------------------------------------------------
# Manifest-referenced components. The <application> (UnstuckApp) + <activity>
# (MainActivity) are auto-kept by AGP from the merged manifest, but keep the
# services/receivers/workers explicitly to be safe (some are instantiated by the
# framework with no-arg constructors / by class name).
# -----------------------------------------------------------------------------
-keep class tech.csalliance.unstuck.UnstuckApp { *; }
-keep class tech.csalliance.unstuck.MainActivity { *; }
-keep class tech.csalliance.unstuck.surface.FocusTimerService { *; }
-keep class tech.csalliance.unstuck.surface.ReminderReceiver { *; }
-keep class tech.csalliance.unstuck.surface.BootReceiver { *; }
-keep class tech.csalliance.unstuck.surface.NotificationActionReceiver { *; }
-keep class tech.csalliance.unstuck.surface.UnstuckMessagingService { *; }

# WorkManager workers are reflectively instantiated by their class name.
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class tech.csalliance.unstuck.surface.SyncWorker { *; }

# -----------------------------------------------------------------------------
# Firebase Cloud Messaging — the messaging service is reflectively dispatched.
# Firebase ships its own consumer ProGuard rules, but keep our service + quiet
# warnings for optional Firebase/Google transitive deps.
# -----------------------------------------------------------------------------
-keep class com.google.firebase.messaging.FirebaseMessagingService { *; }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# -----------------------------------------------------------------------------
# supabase-kt + ktor + okhttp — networking for sync/auth/realtime + the voice
# WebSocket. These libraries ship consumer rules, but ktor/okhttp pull in many
# OPTIONAL/reflective transitive deps (conscrypt, bouncycastle, slf4j, graal,
# coroutines debug) that R8 flags as missing. Suppress those + keep the
# serializable DTOs the ktor content-negotiation (de)serializes.
# -----------------------------------------------------------------------------
-dontwarn io.github.jan.supabase.**
-dontwarn io.ktor.**
-keep class io.ktor.client.engine.okhttp.** { *; }

# okhttp / okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Common optional/reflective deps pulled by ktor/coroutines that have no runtime
# presence on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn reactor.blockhound.**
-dontwarn kotlinx.coroutines.debug.**

# kotlinx-coroutines ships its own rules; keep the ServiceLoader-loaded main
# dispatcher factory used on Android.
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.**

# -----------------------------------------------------------------------------
# Compose ships its own consumer rules. Nothing extra needed normally; keep
# @Composable-related metadata implicitly via -keepattributes above. No-op here,
# documented for the next reader.
# -----------------------------------------------------------------------------
