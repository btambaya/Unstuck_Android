# iOS rebuild spec — Unstuck (1:1 Android replica)

**Purpose.** The existing iOS app is being discarded and rebuilt from scratch in SwiftUI as a **1:1 behavioral replica of the current Android app** (v0.4.29). Android is the reference client (it's ahead of web on native surfaces). Each section below specs one area: behavior + data + business rules + gotchas + the iOS-platform equivalents.

Generated 2026-06-06 from the live Android Kotlin by a 15-agent documentation workflow (`android-ios-rebuild-spec`). The pure-logic `:core` (FocusTimer, VisibleTasks, PickStartNext, Recurrence, Analytics, FreeSlots) carries a JUnit parity suite that the iOS `UnstuckCore` must mirror.

## Sections

- [`00-overview.md`](00-overview.md) — iOS Rebuild Spec — Architecture & App Shell
- [`01-data-model.md`](01-data-model.md) — iOS Rebuild Spec — Data Model & Local Store
- [`02-sync-engine.md`](02-sync-engine.md) — iOS Rebuild SPEC — Sync Engine (offline-first)
- [`03-core-logic.md`](03-core-logic.md) — iOS Rebuild SPEC — Core Business Logic (Pure)
- [`04-auth.md`](04-auth.md) — iOS Rebuild Spec — Auth & Account
- [`05-today.md`](05-today.md) — iOS Rebuild SPEC — Today / Dashboard (`TodayScreen`)
- [`06-tasks.md`](06-tasks.md) — iOS Rebuild Spec — Tasks (CRUD / Detail / Scheduling / Tags)
- [`07-calendar.md`](07-calendar.md) — iOS Rebuild Spec — Calendar & Google Calendar
- [`08-focus.md`](08-focus.md) — iOS Rebuild Spec — Focus Mode (1:1 from Android)
- [`09-collections.md`](09-collections.md) — iOS Rebuild Spec — Collections + Sharing / Accountability
- [`10-notifications.md`](10-notifications.md) — iOS Rebuild Spec — Notifications & Reminders
- [`11-settings.md`](11-settings.md) — iOS Rebuild Spec — Settings & Preferences
- [`12-insights.md`](12-insights.md) — iOS Rebuild Spec — Insights / Analytics
- [`13-onboarding-misc.md`](13-onboarding-misc.md) — iOS Rebuild Spec — Onboarding, Start-Next Widget, Feedback, Capture Inbox, Command Palette, Background Sync
- [`14-backend.md`](14-backend.md) — Unstuck iOS Rebuild Spec — Area: Backend (Supabase)
