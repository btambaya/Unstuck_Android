# iOS Rebuild Spec — Collections + Sharing / Accountability

**Reference client:** Unstuck Android (`/Users/ahmadtambaya/Desktop/projects/unstuck_android`). The existing iOS app is discarded; build this area fresh in SwiftUI as a 1:1 behavioral replica of Android. Where Android and the Supabase backend disagree, the **backend is canonical** (Android is itself a port of the web client; this spec cites both).

---

## 0. What this feature is

Collections are calm "memory containers" — lists of small things the user wants to remember **that are explicitly NOT tasks** (groceries, books, quotes, watch-later). The product copy is load-bearing: *"Things you don't need to remember. A calm shelf. Nothing here is a task."*

Three layers, in increasing complexity:
1. **Solo collections** — local, offline-first, whole-row sync via the outbox.
2. **Shared collections** — co-managed with another user by email; live realtime sync; concurrency-safe item edits via atomic server RPCs; owner vs editor vs viewer roles.
3. **Accountability ("Move to task")** — promote a collection item into a real task. On a shared list, "keep everyone in the loop" links the task back to the item so completion/lateness flows back to all members (status chips, push notifications, a late-nudge cron).

There are exactly four screens/surfaces, all under `ui/collections/`:
- **CollectionsScreen** — the grid overview + search + new + archived filter.
- **CollectionDetailScreen** — one collection's items, add/edit/pin/done/remove, recolor/rename/archive/delete, move-to-task, role-gated.
- **ShareCollectionSheet** — invite by email, role toggle, member + pending-invite list.
- **NewCollectionSheet** — name + color, create.

---

## 1. Behavior — every screen, state, flow, edge case

### 1.1 CollectionsScreen (grid overview)

**File:** `app/.../ui/collections/CollectionsScreen.kt`

**Layout:** A 2-column `LazyVerticalGrid` (SwiftUI: `LazyVGrid` with 2 flexible columns, 10pt spacing, 18pt horizontal page padding). A full-width header spanning both columns sits at the top; cards follow; a full-width empty-state / bottom-spacer span at the end.

**Header (full-width span):**
- Serif-italic title `"Things you don't need to remember."` (26pt) + subtitle `"A calm shelf. Nothing here is a task."` (13pt, muted).
- A pill-shaped search field (rounded 999, `bg2` fill, search glyph + inline placeholder `"Search collections"`). Local state `query`, no debounce — filters live as you type.
- A coral **`+ New`** pill (white text, semibold) — **hidden while viewing archived** (`if (!showArchived)`). Tapping opens `NewCollectionSheet`.
- **Archived filter toggle** — rendered only when `archivedCount > 0 || showArchived`. Pill below the search row. Label is `"Archived (N)"` when active-view, and `"← Back to active"` when in archived-view. Toggling flips `showArchived`. When active: amber-soft background + amber ink; otherwise `bg2` + muted ink.

**Filtering + sort (exact):**
```
shown = collections
    .sortedBy { sortOrder }                         // ascending sortOrder
    .filter {
        (archived == true) == showArchived          // archived-view shows ONLY archived, active-view shows ONLY non-archived
        && (query.isBlank()
            || name.contains(query, ignoreCase=true)
            || items.any { it.body.contains(query, ignoreCase=true) })   // name OR any item body matches
    }
```
Note: `archived` is `Bool?`; `archived == true` treats `nil` as false. Search is case-insensitive substring; it matches the collection name **or any item body**.

**Card (per collection, keyed by `id`):**
- Fixed height 150pt, rounded 18, `surface` fill, 1pt `line` border, tappable → `onOpen(col.id)`.
- Top row: a `ColorChip` (box 26, dot 8) on the left; right side shows a **`SHARED`** badge (8pt bold, `primaryDeep`) when shared, plus the item count (`"${items.size}"`).
  - **Shared test (for the badge):** `members.isNotEmpty() || (myRole != null && myRole != "owner")`. (This is the card's own inline definition; the detail screen computes "shared" slightly differently — see §1.2.)
- Collection name (14pt semibold).
- Up to **2** item bodies as a preview, each prefixed `"· "`, single line, ellipsized.

**Empty state (full-width span, shown when `shown.isEmpty()`):**
- `"No archived lists."` when in archived-view, else `"No lists yet. Tap + to start one."`

**Bottom:** a 28pt spacer span (so the last row clears any bottom nav).

**AppBar:** title `"Collections"`, no leading, with search/notifications/avatar actions. (These belong to the shell — wire to whatever the iOS nav shell exposes. `notifUnread` and `avatarInitials` are passed in.)

### 1.2 CollectionDetailScreen

**File:** `app/.../ui/collections/CollectionDetailScreen.kt` (the richest file — read it in full).

**Resolution + lifecycle:**
- The collection is resolved live by id from the `collections` stream: `col = collections.firstOrNull { it.id == collectionId }`.
- **Critical: if `col == nil`, navigate back — but do it as a lifecycle/`onAppear`-driven effect, NOT inline during body evaluation.** Android uses `LaunchedEffect(col == null) { if (col == null) onBack() }` then `if (col == null) return`. This handles the collection being deleted locally or via realtime *while the screen is open* (another member deletes it, you leave it, you delete it). In SwiftUI: drive `dismiss()` from `.onChange(of: col == nil)` / `.task`, and render an empty placeholder when nil — never call dismiss synchronously inside `body`.

**Derived state:**
- `pinned = items.filter { pinned == true }`, `rest = items.filter { pinned != true }`.
- `owner = isOwner(col)`, `canEdit = canEdit(col)`, `memberCount = members.size`.
- `shared = memberCount > 0 || !owner`.
- `archived = (archived == true)`.

**Pinned header block** (stays put; items scroll beneath — opaque `bg` so scrolled rows don't bleed through):
- `ColorChip` (box 30, dot 9) + the title.
- **Title / rename:** serif-italic 26pt. **Owner only** can rename: tapping the title sets `titleDraft = name; editingTitle = true`, swapping in an inline `TextField` + a green `✓` that calls `renameCollection(col, titleDraft)` and exits edit mode.
  - **Gotcha — `titleDraft` is keyed on `col.id`, NOT `col.name`.** Android: `var titleDraft by remember(col?.id) { mutableStateOf(col?.name ?: "") }`. A realtime rename (another member, or the server echo of your own write) must NOT re-seed the draft mid-edit and wipe what's being typed. In SwiftUI, only seed `titleDraft` from `col.name` when entering edit mode (on the button tap) — do not bind it to `col.name` reactively.
- **Trailing actions (same line, right-aligned):**
  - **Owner:** Archive/Unarchive icon (toggles `archiveCollection(col.id, !archived)` then `onBack()`), Delete icon (opens confirm dialog), Share icon (opens `ShareCollectionSheet`).
  - **Member (non-owner):** a `"Leave"` text button → `leaveCollection(col.id)` then `onBack()`.
- **Shared-with line** (only `if (shared)`): copy depends on role —
  - owner → `"Shared with $memberCount"`
  - editor → `"Shared with you · you can edit"`
  - viewer → `"Shared with you · view only"`
  - 12pt semibold, `primaryDeep`.

**Scroll body (with `imePadding` / keyboard-avoidance):**
- **Recolor swatches (owner only):** a row of 6 circular swatches from `PALETTE = ["indigo","coral","green","amber","blue","violet"]`. The active one gets a 2pt ink border. Tap → `recolorCollection(col, token)`.
- **Empty items state:** a soft rounded card `bg2`: `"Keep small things here."` (serif italic 19) + `"Type below. Hit return. Done."` (12pt muted).
- **Items list** when non-empty:
  - `"Pinned"` section label + pinned rows (only if any pinned).
  - `"All"` section label + the rest.
  - Each row is a `CollItemRow` (see below).
- **Add-item field** (bottom-anchored, **hidden for view-only members** — `if (canEdit)`): a rounded-28 pill with a `+` glyph and a `TextField` placeholder `"Add to this collection…"`. Autofocused on open (Android requests focus in `LaunchedEffect(collectionId)`; iOS: `@FocusState` set true in `.onAppear`). IME action = Done → `add()`. `add()` trims the draft, returns if empty, calls `addCollectionItem(col, body)`, clears the draft, and **re-requests focus so the user can keep adding**.

**`CollItemRow` — the per-item row (the most intricate piece):**

Per-item derived state:
- `done = (item.done == true)`, `isPinned = (item.pinned == true)`, `promoted = (item.promoted == true)`, `promotedDone = (item.promotedDone == true)`.
- `dueMs = parseInstant(item.dueAt)` (nullable).
- `overdue = promoted && !promotedDone && dueMs != nil && now > dueMs`.
- `struck = done || promoted` — a promoted item reads as "handled / in flight" (struck through).

Interaction model — **tap = edit, long-press = reveal actions**, gated by read-only and editing:
- The whole row (except the checkbox + revealed action icons) responds to a combined tap/long-press gesture **only when `!readOnly && !editing`**.
  - **Long-press → reveal** the action bar (`onReveal` toggles `revealedId`).
  - **Tap:** if the action bar is currently revealed, a tap **dismisses it** (`onReveal()`); otherwise a tap enters inline edit (`draft = item.body; editing = true`). (This guards against a tap falling through to the editor while actions are shown.)
- **Done checkbox** (always visible, even for read-only it's shown but only tappable when `!readOnly`): an 18pt circle, coral-filled with a white check when done, otherwise an outlined ring. Tap → `toggleCollectionItemDone(col, item.id)`.
- **Body / inline edit:**
  - When editing: a `TextField` bound to a per-item `draft` + a green `✓` → `updateCollectionItemBody(col, item.id, draft)` then `editing = false`.
  - Otherwise: the body text, `ink3` + **strikethrough when `struck`**, else normal `ink`.
- **Promotion status chip** (only `if (promoted)`), with this exact precedence:
  1. `promotedDone` → `"done by ${assignee ?? "someone"} ✓"` (color `greenInk`).
  2. `overdue` → `"⚠ overdue · due ${fmtTime(dueAt)}"` (color `red`).
  3. `assignee != nil && dueMs != nil` → `"${assignee}'s on it · by ${fmtTime(dueAt)}"` (color `primaryDeep`). **Guard on the PARSED `dueMs`, not the raw string** — an unparseable `dueAt` must not render a dangling `"…'s on it · by "`.
  4. `assignee != nil` → `"${assignee}'s on it"`.
  5. else → `"Promoted"`.
- **Action bar** (`!readOnly`, animated visibility on `revealed`):
  - **Pin** icon — coral when pinned, else `ink4`. Tap → `toggleCollectionItemPin(col, item.id)`.
  - **Move-to-task** icon (`AddTask`) — **hidden while a promotion is in flight** (`if (!promoted || promotedDone)`), to avoid creating a duplicate task. Tap → `startPromote(row)`.
  - **Remove** icon (`Close`). Tap → `removeCollectionItem(col, item.id)`.

Per-item `editing`/`draft` state must be **keyed on `item.id`** (Android: `remember(item.id)`), so realtime updates to other items don't reset the one being edited.

**Move-to-task flow (`startPromote` → chooser → time picker):**
- `startPromote(item)`: clears `revealedId`; if `isShared(col)` set `promoteTarget = item` (opens the chooser dialog); else immediately `moveItemToTask(col, item, .SELF)`.
- **Chooser dialog** (shared lists only) — title `"Move to task"`, body `"\"${item.body}\" becomes a task in your list. Keep everyone in the loop and the others can see when it's done — you'll pick a "by" time."`:
  - Confirm `"Keep everyone in the loop"` (`primaryDeep`) → dismiss, then **time picker** → `moveItemToTask(col, item, .LOOP, iso)`.
  - Dismiss `"Just me"` (`ink2`) → `moveItemToTask(col, item, .SELF)`.
- **Time picker (`pickByTimeThen`)**: a platform time picker seeded to `now`. On confirm, build today's `LocalDate.atTime(h,m)`; **if that instant is before now, add one day** (a "by" time earlier than now means tomorrow — otherwise the task is born already-overdue and fires a late nudge on the next cron tick). Convert to an ISO-8601 instant in the device timezone → `moveItemToTask(col, item, .LOOP, iso)`.
  - **iOS:** use a `DatePicker(.hourAndMinute)` in a sheet/dialog (there is no UIKit `TimePickerDialog`); replicate the "earlier-than-now ⇒ tomorrow" rule exactly.

**Delete confirm dialog:** title `Delete "${name}"?`, body `"This collection and its ${items.size} item(s) are removed."`, confirm `"Delete"` (red) → `deleteCollection(col.id)` + `onBack()`; cancel `"Cancel"`.

### 1.3 ShareCollectionSheet

**File:** `app/.../ui/collections/ShareCollectionSheet.kt`. A modal bottom sheet (SwiftUI `.sheet` with a drag handle / `.presentationDetents`).

**State:** `email`, `role` (default `"editor"`), `busy`, `message: (ok: Bool, text: String)?`, `members: [CollectionMemberInfo]`, `loading` (true until first `refresh()`).

**On open:** `refresh()` → `members = listCollectionMembers(collectionId)`, then `loading = false`.

**Body:**
- Section label `"Share · ${collectionName}"`.
- Explainer: *"Invite anyone by email. If they don't have an Unstuck account yet, they'll get access the moment they sign up. Changes sync live between you."*
- Email field (placeholder `"partner@email.com"`), IME Done → `submit()`.
- Role toggle: two pills `"Can edit"` (`editor`) / `"Can view"` (`viewer`); active pill = ink fill, bg text. + a **Share** button (label `"Sharing…"` while busy; disabled when busy or email blank).
- **`submit()`:** trims email; returns if empty or busy. Sets `busy=true`, clears message. Calls `shareCollection(collectionId, email, role)` and maps the `ShareOutcome`:
  - `.OK` → clear email, `message = (true, "Shared with $e (${role=="viewer" ? "can view" : "can edit"}).")`, `refresh()`.
  - `.INVITED` → clear email, `message = (true, "Invited $e — they'll get access when they sign up.")`, `refresh()`.
  - `.SELF` → `message = (false, "That's you.")`.
  - `.NOT_FOUND` → `message = (false, "No Unstuck account for that email yet.")`.
  - `.ERROR` → `message = (false, "Could not share. Try again.")`.
- Message line: green (`greenInk`) when ok, coral (`coralDeep`) when not.
- **Members section label** — computed: if `accepted>0` → `"Shared with $accepted"` (+ `" · $invited invited"` if any pending); else if `invited>0` → `"$invited invited"`; else `"Not shared yet"`.
- **Member rows** (while `loading` show `"Loading…"`): each row shows `email`, a `"PENDING"` badge (amber) if pending, a `"VIEW"`/`"EDIT"` role badge, and a remove `×`.
  - **`removeMember(m)`:** optimistically removes from the local list, then if `m.pending` → `cancelCollectionInvite(collectionId, m.email)` else `unshareCollection(collectionId, m.userId)`, then `refresh()` to reconcile (a failed removal reappears).
  - **Identity gotcha:** Android removes optimistically via reference identity (`filterNot { it === m }`). Swift has no struct reference identity — make `CollectionMemberInfo` `Identifiable` with a stable id (e.g. `pending ? "invite:\(email)" : "member:\(userId)"`) and filter by that.
- `"Done"` ghost button → dismiss.

### 1.4 NewCollectionSheet

**File:** `app/.../ui/collections/NewCollectionSheet.kt`. Modal bottom sheet.
- Name field placeholder `"What would you like to remember?"`.
- Color section: the 6-swatch `PALETTE`, default `"indigo"`, active swatch gets a 2pt ink border.
- **Create** button (disabled when name blank): builds
  ```
  ItemCollection(
    id = newUuid(),
    name = name.trim(),
    color = color,
    items = [],
    sortOrder = (collections.maxOf { sortOrder } ?? -1) + 1   // append to the end
  )
  ```
  then `upsertCollection(col)` and `onCreated(col.id)` — which closes the sheet and **navigates into the new collection's detail screen**.

---

## 2. Data — models + Supabase tables/columns

### 2.1 Client models

**`CollectionItem`** (`core/.../model/Models.kt`) — lives inline as JSONB inside the collection's `items` array; never queried independently:
| field | type | notes |
|---|---|---|
| `id` | String | client UUID |
| `body` | String | the text |
| `pinned` | Bool? | nil = false |
| `done` | Bool? | nil = false |
| `at` | String | ISO timestamp the item was added |
| `promoted` | Bool? | true once moved to a task |
| `assignee` | String? | display name of the promoter |
| `promotedDone` | Bool? | true once the linked task is completed |
| `dueAt` | String? | ISO "by" time (keep-in-loop); drives overdue |

**`ItemCollection`:**
| field | type | notes |
|---|---|---|
| `id` | String | UUID |
| `name` | String | |
| `color` | String | one of `indigo/coral/green/amber/blue/violet` (free text in DB) |
| `subtitle` | String? | empty ⇄ nil at the DB boundary (see §4) |
| `items` | [CollectionItem] | inline JSONB |
| `sortOrder` | Int | ascending |
| **`ownerId`** | String? | **client-only.** From `collections.user_id`. Never written back. nil = local/demo row. |
| **`members`** | [String] | **client-only.** Shared-with user ids (excludes owner). Empty = not shared. |
| **`myRole`** | String? | **client-only.** `"owner"` / `"editor"` / `"viewer"`; nil = local/own. |
| **`archived`** | Bool? | DB column `archived` (default false). |

The three client-only fields (`ownerId`, `members`, `myRole`) are populated by the hydrator/realtime/codec from `collections.user_id` + `collection_members`, and **must be stripped from any write payload** — the row codec (`CollectionRow`) only serializes `id, name, color, subtitle, items, sort_order, archived`.

**`CollectionMemberInfo`** (`sync/CollectionShareClient.kt`) — for the share sheet only:
`userId: String` (`""` for a pending invite), `email: String`, `role: String` (`"editor"|"viewer"`), `pending: Bool`.

**`ShareOutcome`** enum: `OK, INVITED, NOT_FOUND, SELF, ERROR`.

### 2.2 Supabase tables

**`public.collections`** (migration 012, 020, 026):
`id uuid pk`, `user_id uuid not null` (the **owner**, immutable), `name text`, `color text default 'indigo'`, `subtitle text not null default ''`, `items jsonb not null default '[]'`, `sort_order int default 0`, `archived boolean not null default false`, `created_at`, `updated_at`. In the realtime publication.
- **Owner is pinned by a BEFORE UPDATE trigger** (`lock_collection_owner`): `new.user_id := old.user_id`. A member's whole-row upsert injecting their own `user_id` can never steal/orphan ownership.
- `touch_updated_at` trigger bumps `updated_at`.

**`public.collection_members`** (migration 020, 022): PK `(collection_id, user_id)`, `role text not null default 'editor'` check `in ('editor','viewer')`, `created_at`. In the realtime publication.

**`public.collection_invites`** (migration 023): `id`, `collection_id`, `email text`, `role` (`editor|viewer`), `invited_by`, `created_at`, unique `(collection_id, email)`. **RLS on, NO client policies** — only service-role (the edge function) and the SECURITY-DEFINER claim function touch it. Clients never read/write it directly.

**`public.tasks`** accountability columns (migration 025): `source_collection_id uuid`, `source_item_id text`, `due_at timestamptz`, `late_nudged boolean not null default false`. Partial index on `due_at where source_collection_id is not null and done = false`.

### 2.3 RLS (the iOS client must rely on these exactly as Android does)

- **collections SELECT/UPDATE:** owner OR member; UPDATE additionally requires the member be an **editor** (viewers can read, not write). **DELETE: owner only.** INSERT: `user_id = auth.uid()`.
- A viewer's atomic-item RPC is a no-op because the inner `UPDATE` is RLS-gated (the RPCs are NOT `SECURITY DEFINER`).
- `collection_members`: visible to the member or the owner; insert = owner only; delete = self (leave) or owner (unshare).

### 2.4 Server RPCs + edge functions (the iOS client calls these — names + params are the contract)

Atomic item RPCs (PostgREST `rpc`), used **only for shared lists**:
- `collection_add_item(p_collection_id uuid, p_id text, p_body text, p_at text)` — appends `{id, body, at}`.
- `collection_update_item(p_collection_id, p_item_id text, p_body text)` — sets `body` on the matching item.
- `collection_remove_item(p_collection_id, p_item_id)` — drops the item.
- `collection_set_item_flag(p_collection_id, p_item_id, p_flag text, p_value boolean)` — `p_flag ∈ {'pinned','done'}` only.
- `collection_set_item_promotion(p_collection_id, p_item_id, p_assignee text, p_done boolean?, p_due_at text?)` — merges `{promoted:true, assignee, promotedDone:p_done, dueAt:p_due_at}` (**camelCase keys** in the JSONB, to match the client `CollectionItem` shape).
- `collection_set_item_promoted_done(...)` — **service-role only**; called by the `collection-task-done` edge fn, not the client.

Metadata-only update for shared lists — a **PostgREST `UPDATE`** (NOT an RPC, NOT a whole-row upsert): `collections.update({name, color, subtitle, archived}).eq(id)`. RLS gates it to owner/editor. This avoids shipping the `items` JSONB (which would clobber a concurrent member item edit).

Edge functions:
- **`share-collection`** (POST, JWT): `{action, collectionId, email?, userId?, role?}`. Actions `add | remove | leave | list`. Resolves email→id server-side; existing user → member row; no account → `collection_invites` row + best-effort invite email → returns `{invited:true}`. `list` returns `{members:[{user_id,email,role}], pending:[{email,role}], isOwner}`. Returns `{error:"self"}` / `{error:"not_found"}` / `{ok, userId, role}` / `{ok, invited, ...}`.
- **`collection-task-done`** (POST, JWT): `{collectionId, itemId, taskName, by}`. Flips the shared item `promotedDone=true` (service-role RPC) + pushes the OTHER members. Best-effort — client fires and ignores the result.
- **`check-collection-late`** (cron, x-cron-secret) — server-only; no iOS work. It nudges other members when a keep-in-loop task is `due_at + 5min` past with `total_focused = 0` and not done; fires once via `late_nudged`. iOS just needs to (a) set `due_at` correctly when promoting, and (b) leave `total_focused`/`late_nudged` behavior to the existing task/focus subsystem.

---

## 3. Business rules / logic (the AppViewModel layer)

**File:** `app/.../ui/AppViewModel.kt`. These are the pure decision functions the UI calls; replicate them verbatim in an iOS view-model / store layer. (There is no dedicated `core/` test suite for collections; the logic lives in the view-model. The one codec test, `DbRowCodecTest.collectionSubtitleEmptyBecomesNull`, is cited in §4.)

**Classification (gates everything):**
```
currentUid = auth.currentUserId   // may be transiently nil

isShared(c) = c.members.isNotEmpty()
            || (c.ownerId != nil && currentUid != nil && c.ownerId != currentUid)

isOwner(c)  = c.ownerId == nil || c.ownerId == currentUid

canEdit(c)  = c.myRole != "viewer"
```
**Critical correctness rule (cited verbatim from the source comment):** `isShared` guards on a **known** `currentUid`. *"A transiently-null uid must not mis-classify your OWN list as shared (that routes edits down the RPC-only path with no outbox → silent loss)."* Do not treat a nil uid as "someone else owns this."

**Two write paths, chosen per-mutation by `isShared(latest)`:**

1. **Solo / unshared list → whole-row outbox upsert.** `write.upsertCollection(next)` writes the whole row to the local store and enqueues a `collections` outbox upsert. Handles brand-new rows + offline.
2. **Shared list → optimistic local write + atomic RPC (no outbox).** The local store is updated optimistically; the RPC is the server write. For **metadata** (rename/recolor/archive) it's the partial `collections.update(...)` (not the item-bearing RPC).

**Serialization + re-resolve (the LWW guard):** every collection mutation runs **inside a mutex** and **re-reads the latest collection from the local store first**, then applies a functional `transform`. Android: `collectionMutex.withLock { val latest = store.collections().first().first { id }; ... }`. This mirrors the web's functional-update guard so rapid successive edits (e.g. add three items fast) compose against fresh state instead of a stale captured copy. **iOS must serialize collection mutations** (an `actor`, or a serial queue / `Task` chain) and re-fetch latest-from-store inside the critical section.

**Item operations** (all go through the mutex + re-resolve; each provides a local `transform` and a shared-path `rpc`):
- `addCollectionItem(col, body)`: trim, skip if empty; new `CollectionItem(newUuid(), text, at=isoNow())`; transform appends; rpc = `addItem`.
- `updateCollectionItemBody(col, id, body)`: trim; transform maps body; rpc = `updateItem`.
- `toggleCollectionItemPin(col, id)`: `nextVal = !(pinned ?? false)`; transform sets it; rpc = `setItemFlag(...,"pinned",nextVal)`.
- `toggleCollectionItemDone(col, id)`: same with `"done"`.
- `removeCollectionItem(col, id)`: transform filters out; rpc = `removeItem`.

**Metadata operations** (mutex + re-resolve; shared → partial `updateCollectionFields`, solo → whole-row upsert):
- `renameCollection(col, name)`: trim, skip if empty.
- `recolorCollection(col, color)`.
- `archiveCollection(id, archived)`.
- `upsertCollection(c)` / `deleteCollection(id)` go straight through `write` (create/delete are always whole-row; delete is owner-only by RLS).

**Move-to-task (`moveItemToTask(col, item, mode, dueAtIso?)`)** — the accountability core:
```
1. Guard: if item.promoted == true && item.promotedDone != true → return   // don't duplicate an in-flight task
2. loop = (mode == .LOOP) && isShared(col)
3. task = addTask(
        name = item.body, estimateMin = 25, tags = ["from-collection"],
        sourceCollectionId = loop ? col.id : nil,
        sourceItemId       = loop ? item.id : nil,
        dueAt              = loop ? dueAtIso : nil)
4. if loop && dueAtIso != nil:
        parse dueAtIso → local date + "HH:mm" → scheduleTask(task, date, time)   // shows on calendar
5. if loop || !isShared(col):
        markItemPromoted(col, item.id,
            assignee = currentName ?? "Someone",
            done     = loop ? false : nil,     // false → "on it" chip; nil → static "Promoted"
            dueAt    = loop ? dueAtIso : nil)
```
**Rule (cited):** *"'Just me' on a SHARED list must NOT announce to the others"* — step 5 is skipped for `.SELF` on a shared list (it would mark the shared item "<you>'s on it" for everyone with no way to clear). It only marks when keeping-in-loop, or on a solo list (where the chip is local-only "Promoted").

`markItemPromoted` writes through the same shared/solo split: shared → `setItemPromotion` RPC + optimistic local; solo → local-only chip via whole-row.

**Task completion → flow back to the collection item.** In both `toggleDone` and the focus-completion path, when a completed task has `sourceCollectionId != nil && sourceItemId != nil`:
```
share.taskDone(sourceCollectionId, sourceItemId, task.name, currentName ?? "Someone")
```
The `collection-task-done` edge fn flips `promotedDone=true` on the shared item (realtime shows "done by <name> ✓" to all members) and pushes the others. This must be wired into **whatever task/focus subsystem the iOS rebuild uses** — the Collections feature owns the `share.taskDone(...)` call site, but completion is triggered from the tasks/focus area.

**Sharing operations:**
- `shareCollection(id, email, role)` → `share.share(...)`; on `.OK`, `coordinator.refreshCollections()` (re-hydrate so the new member appears).
- `unshareCollection(id, userId)` → `share.unshare(...)` + refresh.
- `cancelCollectionInvite(id, email)` → `share.cancelInvite(...)`.
- **`leaveCollection(id)`** — *"Fire-and-forget on the view-model scope (NOT the screen's): the caller pops the screen immediately, which would cancel a screen-scoped coroutine before the leave RPC + local drop committed."* It calls `share.leave(id)` then **deletes the row from the local store** (lose access → drop locally). **iOS rule: run leave on a detached/app-level `Task`, not one tied to the view's lifecycle**, so dismissing the screen doesn't cancel it mid-flight.
- `listCollectionMembers(id)` → `share.listMembers(...)`.

**`CollectionShareClient` (`sync/CollectionShareClient.kt`)** is the thin RPC/edge-fn client — port it 1:1 to a `CollectionShareClient` over supabase-swift. Each method wraps in `runCatching`/best-effort (`try?`), and `share`/`updateItem`/etc. swallow errors (the optimistic local write already happened; the next hydrate reconciles).

**`share(...)` outcome mapping (verbatim):**
```
error == "not_found"        → NOT_FOUND
error == "self"             → SELF
invited == true             → INVITED
ok == true && userId != nil → OK
else                        → ERROR
(any throw)                 → ERROR
```

---

## 4. Gotchas (do not skip)

1. **kotlinx default-omission / explicit nulls (the #1 recurring bug class in this codebase).** Android's `rowJson` is configured `explicitNulls = true, encodeDefaults = true, ignoreUnknownKeys = true`. That means optional/null fields **are emitted as explicit JSON `null`** in write payloads, and unknown server columns are ignored on decode. Swift's `JSONEncoder`/`Codable` **omits nil by default** and `JSONDecoder` **throws on unknown keys** — the opposite on both counts.
   - **iOS must:** (a) encode `Bool?`/`String?` fields as explicit `null` where Android does (custom `encode(to:)` or `encodeIfPresent` deliberately avoided), so an upsert doesn't drop a field; (b) tolerate unknown keys on decode (don't fail the whole row when the server adds a column). **But** mirror the *intentional* omissions too — Android only emits the columns in `CollectionRow` (`id, name, color, subtitle, items, sort_order, archived`); the client-only `ownerId/members/myRole` are NEVER in the payload. And for the **item JSONB**, the promotion keys are camelCase (`promoted`, `assignee`, `promotedDone`, `dueAt`) — the server RPC writes them camelCase deliberately; keep client encode/decode camelCase for items even though top-level row columns are snake_case.
2. **`subtitle` empty ⇄ nil round-trip.** `CollectionRow` encodes `subtitle ?? ""` and decodes `subtitle.ifEmpty { nil }`. The test `collectionSubtitleEmptyBecomesNull` asserts: a model `subtitle = nil` encodes to `""` and decodes back to `nil`. The DB column is `not null default ''`. Replicate this both ways or you'll get spurious diffs.
3. **`ownerId` rides on the raw row's `user_id`, not a model column.** Decode: read `user_id` off the raw JSON object → `ItemCollection.ownerId`. It must **never** round-trip back into an upsert payload (the trigger pins ownership anyway, but shipping it is wrong). `members`/`myRole` are populated only by the hydrator/realtime-merge, never decoded from the collection row.
4. **UTC / ISO dates.** `at`/`dueAt` are ISO-8601 instants. `isoNow()` = `ISO.format(Instant.now())` (UTC `Z`). Parsing `dueAt` must accept **both** `Instant.parse` form and offset form — Android: `Instant.parse(iso)` else `OffsetDateTime.parse(iso)` (recoverCatching). `fmtTime` renders in the **device local zone** as `"h:mm a"`. The "by-time earlier than now ⇒ tomorrow" rule (§1.2) is computed in **local time** then converted to a UTC instant. iOS: use `ISO8601DateFormatter` with a fractional-seconds fallback; format with a local `DateFormatter`/`Date.FormatStyle`.
5. **LWW (last-writer-wins) and why shared lists use atomic RPCs.** A whole-row upsert ships the entire `items` array; two members editing concurrently would clobber each other. Shared-list item edits therefore go through the per-item RPCs (one server-side `jsonb_agg` statement). **Solo lists keep the whole-row outbox** (no concurrency, offline-friendly). The mutex + re-resolve-latest is the client-side half of avoiding self-clobber across rapid local edits. Get the `isShared` classification wrong (especially with a transiently-nil uid) and you either lose offline writes or clobber a partner.
6. **Mutation serialization is mandatory.** Without the mutex + re-read, fast successive edits compose against a stale snapshot and lose intermediate changes. Use a Swift `actor` (preferred) so each mutation reads latest-from-store and applies its transform atomically.
7. **Realtime merge must preserve client-only fields.** When a `collections` realtime upsert arrives, it carries `items/name/color/...` but **not** `members/myRole`. Android's `mergeKeep` preserves the existing row's `members`/`myRole` (or `"owner"` if `ownerId == userId`), then upserts. A `collection_members` realtime event triggers a full `hydrateCollections` re-fetch (RLS decides which rows return → freshly-shared list appears / revoked one drops). Replicate both. Also: subscribe to `collections` **without** a `user_id` filter (shared rows are owned by someone else — rely on RLS for delivery); subscribe to `collection_members` **with** `user_id = me` filter.
8. **Realtime per-event guard.** Each realtime event is wrapped so one un-decodable row (a new column, a null in a required field) is **skipped**, not allowed to throw out and permanently kill the table's live mirror. iOS does the same — `do/catch` per event, log + continue.
9. **Navigate-out-of-composition on disappearance.** The detail screen must dismiss when `col` becomes nil (deleted/left/revoked while open) **via an effect, not synchronously in `body`** (§1.2). A synchronous dismiss inside `body` is a SwiftUI anti-pattern (state mutation during view update).
10. **Draft state keyed on id.** `titleDraft` keyed on `col.id` (not name); per-item `editing`/`draft` keyed on `item.id`. Otherwise a realtime echo wipes in-progress typing. In SwiftUI bind these to local `@State` seeded only on edit-entry, not reactively to model values.
11. **Move-to-task duplicate guard + chip hiding.** The `if (item.promoted && !item.promotedDone) return` guard, and hiding the Move-to-task action when `promoted && !promotedDone`, together prevent creating two tasks for one item. A *completed* promotion (`promotedDone == true`) may be re-promoted for a fresh cycle — so the action reappears.
12. **"Just me" on a shared list is silent** (no `markItemPromoted`) — see §3. Easy to get wrong.
13. **`leaveCollection` lifecycle** — must outlive the screen pop (§3). On iOS this is `Task.detached`/app-scoped, never `.task`-on-the-view.
14. **Exact-alarm / cron dependency.** The late-nudge escalation is a **server cron** (`check-collection-late`), not a client alarm — so there is **no iOS exact-alarm concern for this feature.** The only client responsibility is setting `due_at` correctly and scheduling the promoted task on the calendar. (Contrast with other Android areas that use `AlarmManager` exact alarms — collections do not.)
15. **kotlinx enum/role coercion.** `role` from the server is coerced: anything not `"viewer"` becomes `"editor"` (`if (role == "viewer") "viewer" else "editor"`). Mirror this lenient mapping; don't crash on an unexpected role string.
16. **Dependency ordering on the outbox (solo path only).** Solo collection upserts ride the generic outbox. A brand-new collection's create must flush before any later edit. The mutex + the existing outbox ordering handle this; don't reorder collection writes ahead of their create.

---

## 5. iOS equivalents (Android → SwiftUI/Swift)

| Android / Kotlin | iOS / Swift |
|---|---|
| Jetpack Compose (`CollectionsScreen`, `CollectionDetailScreen`, sheets) | SwiftUI views; `LazyVGrid` (2 cols), `ScrollView`, `.sheet`/`.presentationDetents` for the two bottom sheets |
| `AppViewModel` (StateFlow) | `@MainActor ObservableObject` store / `@Observable`; collections as a published array. Mutation serialization via an `actor` |
| Room / `LocalStore` (`store.collections()` flow) | **GRDB** (a `collections` table with the JSONB columns) **or** the project's JSON document store, whichever the rebuild standardizes on. Items stay inline (JSON `items` column), matching the DB 1:1 — do NOT normalize items into a child table |
| `collectAsStateWithLifecycle()` | `@Published` / `@Observable` binding; `.onReceive`/property observation |
| `kotlinx.serialization` (`rowJson`, `explicitNulls=true`, `encodeDefaults=true`, `ignoreUnknownKeys=true`) | `Codable` with **custom `encode(to:)` that writes explicit `null`** for the fields Android emits, a decoder that **ignores unknown keys** (default-tolerant container), camelCase item keys, snake_case row keys via `CodingKeys` |
| `supabase-kt` (`postgrest.rpc`, `functions.invoke`, `from(...).update`) | **supabase-swift**: `client.rpc("collection_add_item", params:)`, `client.functions.invoke("share-collection", options:)`, `client.from("collections").update(...).eq("id", value:)` |
| `RealtimeMirror` (`postgresChangeFlow`) | supabase-swift Realtime v2 channels: subscribe `collections` (no filter, rely on RLS) + `collection_members` (filter `user_id=eq.me`); per-event `do/catch` guard; `mergeKeep` preserving `members/myRole` |
| `Hydrator.hydrateCollections` | An async hydrate: fetch all `collections`, fetch all `collection_members`, build `collectionId → [(userId, role)]`, set `members` + `myRole` (`"owner"` if owner else the member role), `replace` the local store |
| `WorkManager` (outbox flush) | **BGTaskScheduler** (`BGAppRefreshTask`/processing task) for the solo-list outbox flush; foreground flush on app-active. (Shared-list edits are synchronous RPCs, not outbox.) |
| `AlarmManager` exact alarms | **N/A for this feature** — the late nudge is a server cron. No `UNUserNotificationCenter` local scheduling needed here |
| FCM data push (`collection_share`, `collection_task_done`, `collection_late`) | **APNs** — these arrive as alert/data pushes routed to `unstuck://collections`; handle the deep link to open the Collections tab. (The server already routes APNs for `platform == 'ios'` via `pickPrimaryDevice`/`alertPayload`.) |
| Glance widget | **WidgetKit** — not part of this feature (no collections widget in Android); skip |
| `TimePickerDialog` (`pickByTimeThen`) | SwiftUI `DatePicker(.hourAndMinute)` in a confirmation sheet; replicate the "earlier-than-now ⇒ +1 day" rule, then `ISO8601` in the device zone |
| `AlertDialog` (chooser, delete confirm) | `.confirmationDialog` / `.alert` with the same titles/copy/button colors |
| `ModalBottomSheet` + `SheetHandle` | `.sheet` with `.presentationDetents([.large])` + a drag-handle affordance; `imePadding` → SwiftUI keyboard avoidance is automatic, but anchor the add-field at the bottom |
| `UUID.randomUUID().toString()` (`newUuid`) | `UUID().uuidString.lowercased()` (Postgres uuid columns expect lowercase; match Android's lowercase) |
| `Instant.now()` ISO (`isoNow`) | `ISO8601DateFormatter` (UTC, `.withInternetDateTime`) |
| `combinedClickable` (tap=edit / long=reveal) | `.onTapGesture` + `.onLongPressGesture`, gated by `!readOnly && !editing`; tap dismisses the revealed bar if shown |
| `currentName` (`auth.currentName`) | the signed-in user's display name from the auth/session store (used as `assignee` and the `by` label) |
| Foreground service | **N/A** — there is no long-running foreground work in this feature. (If the rebuild needs background sync, it's BGTaskScheduler, not a foreground service — iOS has no equivalent.) |

**Recommended Swift surface (mirrors the Android split):**
- `CollectionsStore` (`actor` or `@MainActor` + internal serial actor) — owns the published `[ItemCollection]`, the classification helpers (`isShared/isOwner/canEdit`), and the mutate-with-mutex-and-re-resolve item/metadata ops.
- `CollectionShareClient` — 1:1 port of the Kotlin client (share/unshare/leave/cancelInvite/listMembers + the atomic item RPCs + `setItemPromotion` + `updateCollectionFields` + `taskDone`).
- `CollectionsHydrator` + realtime subscriptions — port of `Hydrator.hydrateCollections` and the two realtime channels.
- Views: `CollectionsView`, `CollectionDetailView` (+ `CollectionItemRow`), `ShareCollectionSheet`, `NewCollectionSheet`.

**Acceptance — the rebuild is correct when:** a solo list works fully offline (outbox); sharing by email of an existing user adds a member instantly on both devices (realtime); sharing a non-account email shows "Invited …" and the invite is claimed on that person's signup; a viewer sees the list read-only (no add field, no edit, no actions); concurrent item adds from two members both survive (no clobber); "keep everyone in the loop" promotes link the task, set a future `due_at`, show "<name>'s on it · by <time>", flip to "done by <name> ✓" for everyone on completion, and "⚠ overdue" past due; "just me" on a shared list is silent; archiving hides + restores; leaving drops the list locally and survives an immediate screen pop; and a deletion/revocation while the detail screen is open dismisses it cleanly.