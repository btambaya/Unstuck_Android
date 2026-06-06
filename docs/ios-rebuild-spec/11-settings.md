# iOS Rebuild Spec — Settings & Preferences

**Area:** Settings & preferences (account, focus, sound, accessibility, interface/theme/accent/density, backup/export, life areas, tags)
**Reference client:** Unstuck Android
**Primary Android sources:**
- `app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt` (502 lines — all settings UI)
- `app/src/main/kotlin/tech/csalliance/unstuck/SettingsStore.kt` (166 lines — device-local prefs persistence + `SettingsState` + `NotificationLevel`)
- `app/src/main/kotlin/tech/csalliance/unstuck/ui/AppViewModel.kt` (settings/account/area/tag actions + export)
- `sync/src/main/kotlin/tech/csalliance/unstuck/sync/AuthService.kt` (account ops)
- `sync/src/main/kotlin/tech/csalliance/unstuck/sync/Clients.kt` → `PreferencesClient` (server notification mirror)
- `sync/src/main/kotlin/tech/csalliance/unstuck/sync/{WriteThrough,DbRowCodec}.kt` (areas/tags sync)
- `design/src/main/kotlin/tech/csalliance/unstuck/design/theme/Theme.kt` (accent palettes, area-color tokens)
- `app/src/main/kotlin/tech/csalliance/unstuck/surface/ReminderScheduler.kt` (alarm rescheduling on settings change)
- `core/src/main/kotlin/tech/csalliance/unstuck/core/model/{Enums,Models}.kt` (`ThemePref`, `Density`, `FocusTreatment`, `LifeArea`, `TagRow`)

> **Mental model.** Settings splits cleanly into two halves with very different storage:
> 1. **Device-local preferences** (theme, accent, density, accessibility, focus behavior, sound, reminder lead, notification level) — persisted to `SharedPreferences` on Android, **never** synced as a row to Supabase. The *only* server touch is mirroring the notification level into `notification_preferences`. On iOS these go in `UserDefaults` (or a small JSON store) — **not** GRDB and **not** the outbox.
> 2. **Synced domain content** (life areas, tags) — full Supabase rows flowing through the exact same offline write-through + outbox pipeline as tasks. On iOS these go through the same GRDB store + outbox as the rest of the app.
> 3. **Account operations** (display name, password, delete, sign out, export) — Supabase Auth + an Edge Function, no local persistence.

---

## 1. What it does — screens, states, flows, edge cases

### 1.1 Navigation structure

Settings is a **hub + sub-screen** pattern, not one long scroll.

- **`SettingsHub`** — a top-level list (one card, hairline dividers between rows) of 8 entries, each a row with a label and a chevron (`KeyboardArrowRight`). Tapping a row pushes a sub-screen. Order is fixed:
  `Account · Focus · Sound · Accessibility · Interface · Backup · Areas · Tags`
  Header: eyebrow `SETTINGS` (small-caps, primaryDeep color) + serif-italic title **"How Unstuck behaves."**. Top bar: title "Settings", a back (`Leading.BACK`) affordance, no search.
- **`SettingsSubScreen`** — renders one section. Header shows the section's `eyebrow` (small-caps) + serif-italic `title`. Top bar title = the section name title-cased (e.g. "Focus"). Content is section-specific.

Each `SettingsSection` carries an `eyebrow` and `title` string (port these verbatim):

| Section | eyebrow | title |
|---|---|---|
| ACCOUNT | `SETTINGS · ACCOUNT` | `Your account.` |
| FOCUS | `SETTINGS · FOCUS` | `How focus mode behaves.` |
| SOUND | `SETTINGS · SOUND` | `Quiet by default.` |
| A11Y | `SETTINGS · ACCESSIBILITY` | `Adjust to your brain.` |
| INTERFACE | `SETTINGS · INTERFACE` | `How things look.` |
| BACKUP | `SETTINGS · BACKUP` | `Your data is yours.` |
| AREAS | `SETTINGS · AREAS` | `One list. The whole life.` |
| TAGS | `SETTINGS · TAGS` | `Your tag vocabulary.` |

> Note: the hub also has an `onInsights` callback param (an entry point to analytics) but the hub list itself does NOT render an Insights row — it's wired from elsewhere. Don't add an Insights row to Settings.

### 1.2 Reusable row primitives (rebuild these as SwiftUI views)

- **`SettingRow(label, sub?, last, onClick?)`** — bold label + optional gray sub-text below; whole row tappable when `onClick != null`. Hairline divider underneath unless `last`.
- **`ToggleRow(label, value, last, onChange)`** — label on the left, a toggle (`MdToggle`) on the right. → SwiftUI `Toggle`.
- **`SegRow(label, options, selected, last, onSelect)`** — label on the left, a segmented control (`MdSegment`) on the right. → SwiftUI `Picker(.segmented)` or a custom pill segment. Selection is matched by **string equality** against the option list.
- **`SettingsCard { … }`** — rounded (18dp) surface card with a 1px line border; wraps a column of rows.

### 1.3 FOCUS section

A single `SettingsCard` containing, top to bottom:

1. **`SegRow "Default focus length"`** — options `["15","25","45"]`, selected = `focusDefaultMin.toString()`. On select → `focusDefaultMin = v.toIntOrNull() ?: 25`. (Consumed by NewTaskSheet as the default estimate, and seeds the focus timer.)
2. **`SegRow "Soft overrun"`** — options `["Off","5","10"]`. Selected = `"Off"` when `focusOverrunMin == 0`, else the number. On select → `focusOverrunMin = v.toIntOrNull() ?: 0` (so "Off" → 0). (Consumed by FocusScreen: `graceSec = if focusOverrunMin <= 0 then ∞ else focusOverrunMin*60`.)
3. **`SegRow "Remind me before tasks"`** — options `["Off","5","10","15"]`. Selected = `"Off"` when `reminderLeadMin == 0`. On select → set `reminderLeadMin`, **then re-arm reminders** (`ReminderScheduler.reschedule`). This is the *global* default lead; per-task overrides exist separately (§3.7).
4. **`SegRow "Notifications"`** — options `["Calm","Balanced","Coach"]`, selected = `notificationLevel.label`. On select → `notificationLevel = NotificationLevel.fromLabel(v)`, **then re-arm reminders** AND mirror to server (§3.5). Below the segment, a 12pt gray blurb showing `notificationLevel.blurb` (the descriptive text changes with the level).
5. **`ToggleRow "Hide right rail while focusing"`** → `focusCollapseRail`.
6. **`ToggleRow "Soft exit"`** → `focusSoftExit`. (Consumed by FocusScreen: when on + running + not paused, closing prompts a confirm-exit instead of leaving immediately.)
7. **`ToggleRow "Pause reasons" (last)`** → `focusPauseReasons`. (Consumed by FocusScreen: when on, pausing/exiting shows the pause-reasons sheet.)

**Edge case:** changing the reminder lead OR notification level must immediately reschedule on-device alarms; otherwise already-armed reminders keep firing at the old cadence.

### 1.4 SOUND section

Single card:
1. `ToggleRow "Start chime"` → `soundStartChime` (default ON)
2. `ToggleRow "Overrun bell"` → `soundOverrunBell` (default ON)
3. `ToggleRow "Completion sound"` → `soundCompletion` (default OFF)
4. `SegRow "Ambient" (last)` — options `["off","brown","pink"]`, selected = `ambient`. → `ambient = v`. (Consumed by the focus screen's ambient-audio player; off = silence, brown/pink = noise loops.)

### 1.5 ACCESSIBILITY section

Single card of toggles:
1. `ToggleRow "Reduce motion"` → `reduceMotion`
2. `ToggleRow "Larger type"` → `largerType`
3. `ToggleRow "High contrast"` → `highContrast`
4. `ToggleRow "Keyboard hints" (last)` → `keyboardHints` (default ON)

> **Important fidelity note / known gap:** on Android only **`largerType`** is actually wired into behavior (it feeds `fontScale`). `reduceMotion`, `highContrast`, and `keyboardHints` are **stored but not consumed anywhere** in the current Android build (grep confirms zero non-Settings consumers). For a 1:1 behavioral replica, **persist all four with the same defaults**, wire `largerType` into the type scale (§1.7), and you may treat the other three as no-ops to match Android — but the cleaner iOS move is to honor `reduceMotion` for animations and `highContrast` for contrast (this would *exceed* Android parity; flag it as a deliberate improvement rather than silently diverging). Default behavior to match Android: store-only.

### 1.6 INTERFACE section

Single card:
1. `SegRow "Theme"` — options `["system","light","dark"]`, selected = `theme.name.lowercase()`. → `theme = ThemePref.valueOf(v.uppercase())`.
2. `SegRow "Accent"` — options `["indigo","rose","forest"]`, selected = `accentKey(accent)`. → `accent = accentFromKey(v)`.
   - `accentKey`: `INDIGO_CORAL→"indigo"`, `PERIWINKLE_ROSE→"rose"`, `FOREST_AMBER→"forest"`.
   - `accentFromKey`: `"rose"→PERIWINKLE_ROSE`, `"forest"→FOREST_AMBER`, else `INDIGO_CORAL`.
3. `SegRow "Density" (last)` — options `["compact","regular","comfy"]`, selected = `density.name.lowercase()`. → `density = Density.valueOf(v.uppercase())`.

### 1.7 How theme/accent/density apply (app-root reactivity)

This is **not** a Settings-screen concern — it's wired at the app root and must re-theme the *whole* app live. From `AppRoot.kt`:
- `dark = theme == DARK || (theme == SYSTEM && systemDark)`.
- The root theme container takes `(dark, accent, fontScale)`.
- `fontScale` is a single multiplier folding **density + largerType** (see formula in §2.4).
- iOS: keep settings in an `@Observable`/`ObservableObject` injected as an `EnvironmentObject` at the app root. Theme (color scheme), accent palette, and a `Font` scaling modifier all derive reactively. A change in Settings must propagate to every screen immediately (Android does this via a `StateFlow` collected at root).

### 1.8 ACCOUNT section

A single card with rows:
1. **`SettingRow "Display name"`**, sub = `currentName ?: "Set a name"`, tap → opens `FieldDialog`. On save → `updateDisplayName(name)`; success toast "Name updated.", failure shows the error message in red.
2. **`SettingRow "Signed in"`**, sub = `currentEmail ?: "—"` — **static, not tappable** (no onClick).
3. **`SettingRow`** label = `hasPassword ? "Change password" : "Add a password"`, sub "Update your sign-in password", tap → `PasswordDialog`.
4. **`SettingRow "Export everything"`**, sub "One-shot JSON snapshot", tap → file-save flow writing `exportJson()` (§3.6).
5. **`SettingRow "Delete my account"`**, sub "Permanently removes your data", tap → delete confirm dialog.
6. **`SettingRow "Sign out" (last)`**, sub "End this session", tap → `signOut()`.

A status message line (`msg`) renders below the card: green on success, red on failure (`msgErr`).

**FieldDialog (display name)** — single text field pre-filled with the current name, "Save" enabled only when non-blank, trims on save. Cancel dismisses.

**PasswordDialog** — title is "Change password" or "Add a password" based on `hasPassword`. Fields:
- "Current password" (shown **only** if `hasPassword`) — secure entry.
- "New password" — secure.
- "Confirm password" — secure.
- Inline validation: `"At least 8 characters."` when new pw non-empty and `< 8`; `"Passwords don't match."` when confirm non-empty and `≠` new pw.
- Save enabled only when: `newPw.length >= 8 && newPw == confirm && (!hasPassword || current.isNotBlank())`.
- **On save flow (critical):** if `hasPassword`, the app **re-authenticates first** by calling `signIn(email, current)`:
  - If **no email** on the account → abort with red message: `"Can't verify your current password — no email is set on this account."` (Don't sign in with empty string.)
  - If re-auth fails → red `"Current password incorrect."`
  - On success (or if `!hasPassword`) → `changePassword(newPw)`; toast `"Password updated."` or the error.

**Delete account dialog:**
- Title "Delete your account?". Body: `"This permanently removes everything and cannot be undone. Type {your email|DELETE} to confirm."`
- A text field. **Confirm word = the account email, or the literal `"DELETE"` when there is no email** (otherwise an email-less account could never enable the button — trap avoided).
- Confirm button "Delete forever" (red) enabled only when typed text **case-insensitively equals** the confirm word (trimmed). On confirm → `deleteAccount()`; on error show red message.
- Cancel dismisses.

### 1.9 BACKUP section

A single card with **one** row: `SettingRow "Export everything"`, sub "A full JSON snapshot of your data.", tap → file-save writing `exportJson()`. Below the card: gray caption `"Your data is yours — export a complete copy any time."` and a colored status line.

> **Design note (port verbatim):** the old Backup card had an inert "Auto-export every Sunday" toggle + "Export now" that were no-ops. **Do not** rebuild those. There is no scheduled-backup backend. Only the on-demand JSON export is real. (Export also appears in the Account section — both invoke the same `exportJson()`.)

### 1.10 AREAS section (life areas — synced)

Top caption: `"Areas filter the same list — flat on purpose."` Then a vertical list of area rows (sorted by `sortOrder`), then an "add" row.

Each **area row** (rounded 14dp surface card):
- **Color chip** (left) — tappable; opens a small palette popover (a horizontal row of 8 color chips). Tapping a color → `recolorLifeArea(area, color)` and close. Palette: `["indigo","coral","green","amber","teal","blue","violet","red"]`.
- **Name + open count** — `name` (semibold) over `"{open} open"` where `open = count of tasks whose lifeArea == area.name && !done`. When **editing**, the name becomes an inline text field with a green ✓ confirm that calls `renameLifeArea(area, draft)`.
- **Overflow menu** (`MoreVert`) → dropdown with **Rename** (enters inline edit) and **Delete area** (red; opens a confirm dialog).
- **Delete confirm dialog:** title `Delete "{name}"?`, body `"Tasks keep their data — they just lose this area label."`, Delete (red) → `deleteLifeArea(area.id)`; Cancel.

**Add row** (bottom): a "New area" text field + a dark "Add" button. On Add:
- `name = draft.trim()`.
- **Only add if** name non-blank **and** no existing area has that name (case-insensitive). (Duplicate names break the name-keyed filter; silently no-op + keep the typed text on a dup.)
- New color = **first palette color not already used** by any area, falling back to `palette[areas.size % palette.size]`.
- New `sortOrder = (max existing sortOrder ?? -1) + 1`.
- Create `LifeArea(newUuid(), name, color, order)` → `upsertLifeArea`. Clear the draft only on a real add.

### 1.11 TAGS section (synced)

Top caption: `"Tags cut across areas — apply as many as you like."` Same row/add structure as Areas, with these differences:
- Tag display = `"#{name}"`. Tapping the name (not just the menu) enters inline edit.
- Right-side count = `uses = count of tasks whose tags contains tag.name` (case-sensitive `contains` on the list).
- Color chip is smaller (box 26 vs 30). Same 8-color palette + recolor popover → `recolorTag(tag, color)`.
- Overflow menu: **Rename** + **Delete** (red).
- Delete confirm: title `Delete #{name}?`, body `"It's removed from every task that uses it. This can't be undone."` → `deleteTag(tag.id)`.
- Add row "New tag" + "Add": same anti-dup/anti-collision logic → `upsertTag(TagRow(newUuid(), name, color, order))`. Note `TagRow.color` is **nullable** (`color: String?`), but the Settings add path always assigns a palette color.

---

## 2. Data — models, persistence, Supabase tables

### 2.1 `SettingsState` (device-local; the entire prefs payload)

Port this struct field-for-field, with these **exact defaults**:

| Field | Type | Default | SharedPreferences key |
|---|---|---|---|
| `theme` | `ThemePref` | `SYSTEM` | `theme` (stored as enum `.name`) |
| `accent` | `AccentPalette` | `INDIGO_CORAL` | `accent` |
| `density` | `Density` | `REGULAR` | `density` |
| `largerType` | Bool | `false` | `largerType` |
| `reduceMotion` | Bool | `false` | `reduceMotion` |
| `highContrast` | Bool | `false` | `highContrast` |
| `keyboardHints` | Bool | `true` | `keyboardHints` |
| `focusDefaultMin` | Int | `25` | `focusDefaultMin` |
| `focusOverrunMin` | Int | `5` (0 = Never) | `focusOverrunMin` |
| `focusCollapseRail` | Bool | `true` | `focusCollapseRail` |
| `focusSoftExit` | Bool | `true` | `focusSoftExit` |
| `focusPauseReasons` | Bool | `true` | `focusPauseReasons` |
| `soundStartChime` | Bool | `true` | `soundStartChime` |
| `soundOverrunBell` | Bool | `true` | `soundOverrunBell` |
| `soundCompletion` | Bool | `false` | `soundCompletion` |
| `ambient` | String | `"off"` (off\|brown\|pink) | `ambient` |
| `treatment` | `FocusTreatment` | `AMBIENT` | `treatment` |
| `reminderLeadMin` | Int | `10` (0 = Off) | `reminderLeadMin` |
| `notificationLevel` | `NotificationLevel` | `BALANCED` | `notificationLevel` |

Enums stored as their `.name` string; on load, parse and **fall back to the default** if the stored string doesn't match a known case (Android's `enumOf` swallows bad values). On iOS: store the enum's `rawValue`/case name in `UserDefaults`; decode with a fallback, never crash.

### 2.2 `NotificationLevel` (enum with computed gates)

Three cases, each with a `label` and `blurb` (port the copy verbatim):
- `CALM` — "Calm" / "Only the essentials — pre-task reminders and your session recap."
- `BALANCED` — "Balanced" / "Reminders, a start-now nudge with Start/Reschedule, paused check-ins, the morning brief, and quiet in-app nudges."
- `COACH` — "Coach" / "Everything in Balanced, plus a nudge if you haven't started on time and more proactive prompts."

Computed gate properties (these are the **single source of truth** consumed by the reminder scheduler, paused-check-in scheduler, in-app nudges, and the server morning brief):
- `atStart = self != CALM` — fire a "starts now" (Start/Reschedule) notification at block start.
- `drifted = self == COACH` — a ~10-min-after-start follow-up if still not started.
- `pausedCheckin = self != CALM`.
- `morningBrief = self != CALM`.
- `nudges = self != CALM` — quiet in-app Today nudges (no push).
- `fromLabel(l)` — match a label string, default `BALANCED`.

### 2.3 `LifeArea` and `TagRow` (synced domain rows)

```
LifeArea(id: String, name: String, color: String, sortOrder: Int)
TagRow(id: String, name: String, color: String?, sortOrder: Int)
```
`color` is **non-null** on `LifeArea`, **nullable** on `TagRow`.

### 2.4 `fontScale` derived value

```
fontScale = densityFactor * (largerType ? 1.15 : 1.0)
densityFactor: COMPACT → 0.94, REGULAR → 1.0, COMFY → 1.08
```
Mirror exactly (web parity). On iOS, apply as a multiplier on your base font sizes / via a custom Dynamic-Type-like scaler at the root.

### 2.5 Supabase tables touched

**`life_areas`** — columns: `id` (uuid PK), `user_id` (uuid, injected by the gateway — NOT in the client model), `name` (text), `color` (text), `sort_order` (int). camelCase→snake_case mapping is `sortOrder ↔ sort_order` only.

**`tags`** — `id`, `user_id`, `name` (text), `color` (text **nullable**), `sort_order` (int).

**`notification_preferences`** — upserted on conflict `user_id`. The client writes only `{ user_id, morning_brief_enabled, paused_checkin_enabled }` (both Bool, derived from the level). Other columns retain their values. **This is the only server write that the device-local settings trigger.**

**`user_preferences`** — `{ user_id, adhd_struggles: text[] }`, upserted on conflict `user_id` (set during onboarding, not from Settings, but same client). Mentioned for completeness.

**Account/Auth (no table writes from the client):**
- Display name → Supabase Auth `updateUser` writing user metadata `{ full_name, display_name }`.
- Password → Auth `updateUser { password }`.
- Delete → invoke Edge Function **`account-delete`**, then `signOut()`.
- `hasPassword` = the current user has an identity with `provider == "email"` (else Google-only); defaults to `true` when unknown.
- `currentName` resolution: `display_name` metadata → `full_name` metadata → email local-part (before `@`).

> **No `settings`/`preferences` row for the scalar device prefs.** Theme/density/accent/focus/sound/a11y do **not** round-trip to any table and do **not** sync across devices. Only the notification level is mirrored. Match this — do not invent a settings-sync table.

---

## 3. Business rules / logic

### 3.1 Update-and-persist (`updateSettings`)

`updateSettings { transform }`:
1. `next = transform(prev)`.
2. Push `next` into the observable state flow (UI re-themes immediately).
3. Persist `next` synchronously to `SharedPreferences`/`UserDefaults`.
4. **If `next.notificationLevel != prev.notificationLevel`** and a user is signed in → best-effort async upsert to `notification_preferences` with `morningBrief`/`pausedCheckin` derived from the new level. Failures are swallowed (`runCatching`).

iOS: a single `update(_ transform:)` on the settings store, writing `UserDefaults`, plus the conditional server mirror via supabase-swift.

### 3.2 Life-area add anti-collision

(`SettingsScreen.AreasContent` add handler, mirrored in `AppViewModel`)
- Reject blank or case-insensitive-duplicate names (silent no-op, keep typed text).
- `color` = first palette color not used by any existing area; fallback `palette[areas.size % palette.size]`. (Using `areas.size` directly would re-collide after a delete shrinks the list — hence "first unused".)
- `sortOrder` = `(max existing ?? -1) + 1`. (Not `areas.size`, for the same post-delete reason.)

### 3.3 Tag add anti-collision

Identical to areas (`tags.maxOf sortOrder + 1`, first-unused color, dup-name guard). Clear the draft **only on a real add**.

### 3.4 Rename / delete cascades (`AppViewModel`)

These run as multi-write transactions through the same write-through pipeline:

- **`renameLifeArea(area, newName)`**: trim; no-op if empty or unchanged; **bail if another area already has that name** (case-insensitive) — areas key tasks by *name string*, so duplicates make the filter ambiguous. Then `upsertLifeArea(renamed)` **and** rewrite every task with `lifeArea == old name` to the new name. (Note: area→task link is by **name**, not id.)
- **`deleteLifeArea(id)`**: look up name, delete the area row, then **null out `lifeArea` on every task** that referenced that name (no dangling labels). UI body text says "Tasks keep their data — they just lose this area label."
- **`recolorLifeArea(area, color)`**: `upsertLifeArea(area.copy(color))`.
- **`renameTag(tag, newName)`**: trim; no-op if empty/unchanged; **bail on case-insensitive dup**. Then upsert the renamed tag, and on every task that uses the old name (case-insensitive), **map** the old name → new name in the tags list and **`.distinct()`** — so renaming `A→B` on a task tagged `[A,B]` yields `[B]`, not `[B,B]`.
- **`deleteTag(id)`**: look up name, delete the tag row, then strip the name (case-insensitive) from every task's tags list; collapse an emptied list to `null` (`ifEmpty { null }`).
- **`recolorTag(tag, color)`**: `upsertTag(tag.copy(color))`.

> All task rewrites set `updatedAt = isoNow()` (LWW timestamp — see §4.3).

### 3.5 Notification-level → server mirror

Already covered in §3.1 step 4 and §2.5. The mapping: only `morning_brief_enabled = level.morningBrief` and `paused_checkin_enabled = level.pausedCheckin` are sent. Re-arming local alarms (§3.7) is a **separate** side-effect triggered from the FOCUS segment handler, not from `updateSettings`.

### 3.6 Export bundle (`exportJson`)

Serializes one pretty-printed JSON object (`ExportBundle`) with **`encodeDefaults = true`**:
```
{ exportedAt, email, tasks[], sessions[], calBlocks[], captures[],
  reasonLogs[], collections[], tags[], lifeAreas[] }
```
`exportedAt = isoNow()` (UTC ISO, see §4.2), `email = currentEmail`. This is the in-memory model snapshot (camelCase keys, NOT the snake_case DB shape). On iOS use `JSONEncoder` over the same model structs; surface via a share/save sheet. The Android file flow writes UTF-8 bytes to a user-picked `application/json` destination; iOS equivalent is a `UIActivityViewController`/`fileExporter` writing `unstuck-export.json`.

### 3.7 Reminder rescheduling on settings change

Changing **reminder lead** or **notification level** calls `ReminderScheduler.reschedule`, which rebuilds all on-device alarms from the current store. The scheduler (per upcoming task/external block within a 48h horizon) arms up to three notifications, gated by the level:
- **LEAD (A1)** at `start − lead·60s` — all levels, when `lead > 0`. Tasks use the per-task override if set, else the global lead; external calendar events use the global lead.
- **ATSTART (A2)** at `start` — task blocks only, `level.atStart` (Balanced+). Carries Start/Reschedule actions.
- **DRIFTED (A4)** at `start + 10min` — task blocks only, `level.drifted` (Coach only).
Done tasks are skipped. Past/over-horizon fire times are skipped. Stale alarms (in prev set, not in new set) are cancelled. The set of armed keys is persisted so a later diff can cancel removed ones.

> See §5 for the iOS scheduling equivalent and constraints.

### 3.8 Per-task reminder override + device-local content (sign-out cleanup)

`SettingsStore` also owns device-local, per-user content that must be **cleared on sign-out** so a different account on the same device starts clean (`clearUserContent`):
- **Per-task reminder override**: `reminder.override.{taskId}` → Int minutes, or absent = use global default. `reminderOverride(taskId)` returns null if absent or stored value `< 0`. `setReminderOverride(taskId, leadMin?)` writes or removes.
- **Dismissed in-app nudge ids** (`dismissedNudges`, a string set) — so a dismissed nudge stays dismissed across relaunch.
- **Archived capture ids** (`archivedCaptureIds`, string set) — Inbox triage state.
`clearUserContent()` removes all `reminder.override.*` keys + `dismissedNudges` + `archivedCaptureIds`. (It does **not** clear theme/focus/sound prefs — those are device-level, not per-user.)

> These three aren't rendered on the Settings screen, but they live in `SettingsStore` and are part of this area's persistence contract. Replicate them in the iOS settings store and clear them on sign-out.

### 3.9 Relevant `core/` pure-logic + tests

The Settings UI itself has no dedicated `core/` test file, but these are the cited pure-logic touchpoints with tests:
- **`core/logic/newUuid()`** — id minting for new areas/tags. → iOS `UUID().uuidString` (lowercase to match; verify casing expectations against `isUuid`).
- **`CoreModelsTest.kt`** — round-trips the domain models (`LifeArea`, `TagRow`, enums) through serialization; mirror these as Swift `Codable` round-trip tests. Verify `@SerialName` values (§4.1) survive encode/decode.
- **`DbRowCodecTest.kt`** (in `:sync`) — verifies the snake_case row mapping (`sort_order`, nullable tag color, `user_id` injection). Port equivalent tests for your `life_areas`/`tags` row codecs.
- Enum `@SerialName` contract from `Enums.kt`: `ThemePref{system,light,dark}`, `Density{compact,regular,comfy}`, `FocusTreatment{ambient,cockpit,monk}` — but note **device-local prefs store the enum `.name` (UPPERCASE)** in SharedPreferences, while the **server/JSON wire format uses the `@SerialName` lowercase**. Don't conflate the two (see §4.1).

---

## 4. Gotchas

### 4.1 Two distinct serialization formats for the same enums

This is the easiest mistake to make. For `ThemePref`/`Density`/`FocusTreatment`:
- **Device-local prefs (`SettingsStore`)** store/read the **Kotlin enum `.name`** = UPPERCASE (`"SYSTEM"`, `"REGULAR"`, `"AMBIENT"`). The Settings UI also converts to/from lowercase **for display only** (e.g. `theme.name.lowercase()` for the segment, `ThemePref.valueOf(v.uppercase())` back).
- **Server/export JSON** uses the `@SerialName` value = lowercase (`"system"`, `"regular"`, `"ambient"`).
On iOS, pick one storage encoding per surface and be consistent. For `UserDefaults` you can store either, but the **export bundle and any server JSON must emit lowercase**. Don't let a single shared `rawValue` accidentally write UPPERCASE into exported JSON.

### 4.2 UTC ISO timestamps

`isoNow()` uses pattern `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` at **`ZoneOffset.UTC`** — millisecond precision, literal trailing `Z`. All `updatedAt`/`completedAt`/`exportedAt` use this. On iOS, use an `ISO8601`/`DateFormatter` configured to UTC with `.SSS` fractional millis and a literal `Z` — **not** the default `ISO8601DateFormatter` (which may omit millis or format the offset differently). The cascade rewrites in §3.4 stamp `updatedAt` with this; a mismatched format will break LWW ordering and string comparisons.

### 4.3 LWW (last-write-wins) and the area→task rename cascade

The local store keeps a denormalized `updatedAt` per record for ordering, and the realtime/hydrate path resolves conflicts last-write-wins on that timestamp. **Areas and tags themselves have no `updatedAt`** (the row models carry only id/name/color/sortOrder), so their conflict resolution is plain whole-row replace — concurrent edits from two devices clobber. The **tasks** rewritten by a rename/delete cascade *do* carry `updatedAt = isoNow()`, so they win LWW against stale copies. Gotcha: if you skip stamping `updatedAt` on the cascaded task rewrites, a stale realtime echo can revert the area-label change. Always stamp.

### 4.4 kotlinx default-omission vs. `encodeDefaults`

kotlinx-serialization **omits properties equal to their default** by default. The general DB row JSON relies on this. But the **export bundle explicitly sets `encodeDefaults = true`** so a full snapshot includes every field even at default values. On iOS, `JSONEncoder` always encodes all stored properties — so your export already matches the `encodeDefaults = true` behavior, but be careful that your **DB-write codec** for areas/tags doesn't emit fields the server doesn't expect (the gateway injects `user_id`; the client model must not carry it). When decoding server rows, treat missing optional fields as their defaults (e.g. `TagRow.color` nullable, missing → `null`).

### 4.5 Areas/tags key tasks by **name**, not id

A task's `lifeArea` is the area's **name string**; a task's `tags` are **name strings**. This is why every rename/delete cascades across tasks, and why duplicate names are forbidden (ambiguous filter). When porting, do **not** "fix" this to id-based links — the web app, server, and Android all use name-keying; changing it would desync. The case-insensitivity in matching (`equals(..., ignoreCase = true)`) and the `.distinct()` de-dup on tag rename are load-bearing.

### 4.6 Exact-alarm permission (Android) — N/A on iOS, but behavior must be preserved

Android falls back from exact (`setExactAndAllowWhileIdle`) to inexact alarms when exact-alarm permission is denied (`canScheduleExactAlarms()`), and the LEAD/ATSTART/DRIFTED gating is preserved either way. iOS has no exact-alarm permission, but it **does** require notification authorization and has its own scheduling constraints (§5.4). The gotcha to carry over: a settings change to lead/level must **rebuild** the whole schedule (cancel stale + arm new), not just add new alarms.

### 4.7 Dependency ordering for areas/tags writes

Unlike cal_blocks (which depend on their parent task flushing first), **area and tag upserts have no `dependsOn`** — they enqueue with `dependsOn = null` and flush FIFO. But the **task rewrites** in a rename/delete cascade are separate outbox ops; they don't strictly depend on the area/tag op, but they should be enqueued *after* it so the server sees the area gone/renamed before the task references change. Keep the AppViewModel ordering (mutate the area/tag row first, then the tasks).

### 4.8 Display-name / password edge cases (re-auth trap)

The PasswordDialog re-auth (§1.8) is subtle: an email-less account (Google-only that somehow has a password, or an OTP-only account) cannot re-authenticate by email+password — the code special-cases this with a clear message instead of attempting `signIn("", current)`. Likewise the delete dialog falls back to typing `"DELETE"` for email-less accounts. Preserve both fallbacks or you'll trap users.

### 4.9 Settings do not sync across devices (except notification level)

Don't surprise users by syncing theme/density. A fresh install starts at defaults. Only `notification_preferences` is server-side. Match this.

---

## 5. iOS equivalents

| Android | iOS |
|---|---|
| `SettingsScreen.kt` Compose (`SettingsHub`, `SettingsSubScreen`, `SettingsCard`, `SettingRow`, `ToggleRow`, `SegRow`, dialogs) | SwiftUI: a `SettingsHubView` with `NavigationStack` pushing a `SettingsSubScreen(section:)`. Reusable views: `SettingsCard`, `SettingRow`, `ToggleRow` (`Toggle`), `SegRow` (`Picker(.segmented)` or custom pill segment). Dialogs → `.alert` / `.sheet` / `.confirmationDialog`. |
| `MdToggle` | `Toggle` styled to the design system tint. |
| `MdSegment` (string-matched) | Segmented `Picker` bound to a `String`, or a custom segmented control; selection compared by string. |
| `AlertDialog` (rename/delete/password/delete-account) | `.alert(...)` with `TextField`/`SecureField` for the dialogs that take input; or a `.sheet` for the multi-field PasswordDialog. |
| `DropdownMenu` (overflow + color popover) | `Menu` / `contextMenu` for overflow; a small popover or inline row of color chips for the palette. |
| `SharedPreferences` (`SettingsStore`) | `UserDefaults` (or a tiny JSON-on-disk store). **Not** GRDB. Mirror the keys & defaults from §2.1. Per-task overrides as `reminder.override.{taskId}`; nudge/archive id sets as arrays. |
| `StateFlow<SettingsState>` collected at `AppRoot` | `@Observable` settings store as an app-root `@Environment`/`EnvironmentObject`; views observe and re-theme reactively. |
| Theme/accent/density at root (`UnstuckTheme(dark, accent, fontScale)`) | Root view applies `.preferredColorScheme`, an accent palette environment value, and a font-scale modifier derived from `fontScale`. |
| Room (`records`/`outbox`) for `life_areas`/`tags` | **GRDB** (or your JSON store) using the same generic `records` + `outbox` design the rest of the iOS app uses. Areas/tags flow through the same write-through + outbox as tasks. |
| `WorkManager` (outbox flusher) | `BGTaskScheduler` (`BGProcessingTask`/`BGAppRefreshTask`) for background drain, plus an in-foreground flush on app active. |
| `AlarmManager` exact alarms (`ReminderScheduler`) | `UNUserNotificationCenter` with `UNCalendarNotificationTrigger`/`UNTimeIntervalNotificationTrigger`. Rebuild on settings change: remove pending (`removePendingNotificationRequests`) + re-add. Categories with actions for the "starts now" Start/Reschedule (A2). |
| `Glance` "Start Next" widget | `WidgetKit` (App Group shared store; reload timelines). Out of this area's scope but the settings/notification level can affect what the widget surfaces. |
| FCM (push for morning brief / server nudges) | **APNs** (`platform = "ios"` on the token registration). The notification-level mirror to `notification_preferences` gates the server-sent morning brief + paused check-in regardless of platform. |
| Android foreground service (focus session) | **iOS has no equivalent** — no long-running foreground service. The focus timer must rely on local notifications + recompute-on-foreground (store `sessionStart` epoch and derive elapsed when the app returns). This is a focus-area concern, but note it: any settings that assume an always-running service (e.g. sound loops, overrun bell) must be re-implemented with `AVAudioSession` background audio + scheduled local notifications, not a service. |
| `supabase-kt` (Auth, Functions, Postgrest upsert) | **supabase-swift**: `auth.update(user:)` for name/password, `functions.invoke("account-delete")` for delete, `from("notification_preferences").upsert(..., onConflict: "user_id")`, `from("life_areas"/"tags")` for area/tag rows. `hasPassword` = current user identities contain `provider == "email"`. |
| `runCatching {}.fold(...)` for auth outcomes | A Swift `enum AuthOutcome { case ok; case error(String) }` (already mirrored in the existing iOS `AuthService.swift` per the file header) with `Result`/`do-catch`. Reuse `humanizeAuthError`. |
| File save via `ActivityResultContracts.CreateDocument` | `fileExporter` / `UIActivityViewController` writing `unstuck-export.json`; encode the `ExportBundle` with `JSONEncoder` (pretty-printed, UTC dates). |

### 5.1 Account ops (supabase-swift)
- **Display name:** `auth.update(user: UserAttributes(data: ["full_name": .string(name), "display_name": .string(name)]))`.
- **Password:** `auth.update(user: UserAttributes(password: newPw))`, preceded by the re-auth `signIn(email, current)` when `hasPassword` (§1.8).
- **Delete:** `functions.invoke("account-delete")` then `auth.signOut()`.
- **Sign out:** unregister this device's push token while the JWT is valid, then `signOut` (prevents the previous user's pushes reaching the next user). Then clear device-local per-user content (§3.8).

### 5.2 Accent palettes & area-color tokens
- Three accents (`indigo`/`rose`/`forest`) override the primary+coral ramp on the base light/dark palette (OKLCH values in `Theme.kt` §`withAccent`). Port the exact OKLCH→RGB conversions or precompute the resulting colors. `INDIGO_CORAL` = brand default (no override).
- The **area/tag color tokens** (`indigo, coral, green, amber, teal, blue, violet, red`) resolve through `areaColor(token)` (`Theme.kt`). Note token aliases: `indigo|primary → primary`, `coral|rethink|test → coral`, `green|"new feature"|bug → green`; `teal` is a literal `oklch(0.70,0.10,200)`; unknown → `ink4`. Port the full mapping so seeded server colors render correctly.

### 5.3 Defaults seeding (areas)
Not on the Settings screen, but areas can be empty until onboarding seeds them (`["Work","Personal","Home","Health"]` with palette colors by index, or the user's picked set). The Areas section must render gracefully with zero areas (just the add row). Don't seed from Settings.

### 5.4 Notification scheduling fidelity
Reproduce the LEAD/ATSTART/DRIFTED gating exactly (§3.7) using `UNUserNotificationCenter`. Because iOS caps pending local notifications (64) and has no exact-alarm denial path, keep the 48h horizon to stay well under the cap and re-arm on foreground + on settings change. The ATSTART notification needs a `UNNotificationCategory` with Start/Reschedule actions matching Android's A2.

---

## 6. Acceptance checklist (behavioral parity)

- [ ] Hub lists exactly 8 sections in the given order; each pushes a sub-screen with the correct eyebrow/title.
- [ ] Every toggle/segment writes through to persistent storage and survives relaunch with the §2.1 defaults.
- [ ] Theme/accent/density/largerType re-theme the entire app live (root-level reactivity), with the exact `fontScale` formula.
- [ ] Changing reminder lead OR notification level re-arms local notifications and (for level) mirrors `notification_preferences` server-side when signed in.
- [ ] `NotificationLevel` gates (atStart/drifted/pausedCheckin/morningBrief/nudges) match the table; blurb text updates with the level.
- [ ] Areas: add (anti-dup, first-unused color, max+1 order), inline rename with task cascade, recolor popover, delete with task-label clearing + confirm dialog, live "{open} open" count.
- [ ] Tags: same as areas with `#name` display, "uses" count, nullable color, rename `.distinct()` de-dup, delete strips from all tasks (case-insensitive, empties → null).
- [ ] Account: display-name dialog, password dialog (8-char min, confirm match, re-auth-before-change with the email-less special case), delete dialog (email-or-DELETE confirm, case-insensitive), sign-out (token unregister + clear per-user device content), static "Signed in" row.
- [ ] Export (both Account and Backup) produces a pretty JSON `ExportBundle` with UTC millisecond `Z` timestamps and all 8 collections; no inert auto-export toggle.
- [ ] Per-task reminder overrides + dismissed-nudge ids + archived-capture ids persist device-locally and are cleared on sign-out (theme/focus/sound prefs are NOT cleared).
- [ ] Enum storage uses UPPERCASE names for device-local prefs but lowercase `@SerialName` for export/server JSON.
- [ ] a11y flags persist with correct defaults; `largerType` affects type scale (others store-only to match Android, unless you deliberately exceed parity and document it).