package tech.csalliance.unstuck.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Domain enums. Mirror lib/types.ts (web) + UnstuckCore/Enums.swift (iOS).
// @SerialName values match the strings stored server-side.

@Serializable
enum class Priority {
    @SerialName("urgent") URGENT,
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW;
}

@Serializable
enum class FocusState {
    @SerialName("idle") IDLE,
    @SerialName("starting") STARTING,
    @SerialName("running") RUNNING,
    @SerialName("overrun") OVERRUN,
    @SerialName("pause") PAUSE,
    @SerialName("done") DONE,
    @SerialName("resume") RESUME;
}

@Serializable
enum class FocusTreatment {
    @SerialName("ambient") AMBIENT,
    @SerialName("cockpit") COCKPIT,
    @SerialName("monk") MONK;
}

@Serializable
enum class CalBlockKind {
    @SerialName("task") TASK,
    @SerialName("placeholder") PLACEHOLDER,
    @SerialName("external") EXTERNAL;
}

@Serializable
enum class ReasonAction {
    @SerialName("pause") PAUSE,
    @SerialName("switch") SWITCH;
}

@Serializable
enum class CaptureTag {
    @SerialName("follow-up") FOLLOW_UP,
    @SerialName("idea") IDEA,
    @SerialName("edit") EDIT,
    @SerialName("question") QUESTION,
    @SerialName("distraction") DISTRACTION;
}

@Serializable
enum class CalendarProvider {
    @SerialName("google") GOOGLE,
    @SerialName("apple") APPLE,
    @SerialName("microsoft") MICROSOFT;
}

@Serializable
enum class ThemePref { @SerialName("system") SYSTEM, @SerialName("light") LIGHT, @SerialName("dark") DARK }

@Serializable
enum class Density { @SerialName("compact") COMPACT, @SerialName("regular") REGULAR, @SerialName("comfy") COMFY }

enum class TaskListView(val label: String) {
    ALL("All"), BACKLOG("Backlog"), TODAY("Today"), UPCOMING("Upcoming"), LATER("Later"), RECURRING("Recurring"), COMPLETED("Completed");
}
