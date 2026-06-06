# iOS Rebuild Spec — Auth & Account

**Area:** Authentication, password recovery, and the Settings → Account surface
**Reference client:** Unstuck Android (`/Users/ahmadtambaya/Desktop/projects/unstuck_android`)
**Target:** New SwiftUI app, 1:1 behavioral replica. The old iOS app is discarded.

> Note on lineage: the Android `AuthService.kt` header literally says "Port of the iOS AuthService.swift," and `AuthErrorsTest.kt` says "Ported from AuthErrorsTests.swift / lib/auth-helpers.test.ts." Treat **Android as the reference** for current behavior, but the pure logic already existed on iOS — reproduce it exactly, do not invent new copy or rules.

---

## 1. What it does — behavior, screens, states, flows, edge cases

The auth area is gated by a single top-level routing decision in `AppRoot.kt`. Three signals drive it: `configured` (anon key present), `authed` (`Bool?` — null until session resolves), and `pendingPasswordRecovery` (`Bool`).

### 1.1 Root routing (`AppRoot.kt` lines 40-52)

```
configured == false                          → SetupScreen   (dev-only; see §1.6)
configured == true:
    authed == null                           → LoadingScreen (spinner only)
    authed == false                          → AuthScreen
    authed == true && recovery == true        → SetNewPasswordScreen
    authed == true && recovery == false       → MainScaffold (the app)
```

- `authed` is `nil` until the Supabase session status first resolves. **Show a spinner, not the auth form**, during this window — otherwise a returning user flashes the sign-in screen on every cold start. (Android: `SharingStarted.Eagerly`, initial value `null`.)
- `authed` is derived purely from session status: `it is SessionStatus.Authenticated → true`, anything else → `false`. supabase-swift exposes this via `AuthClient.authStateChanges` / `client.auth.currentSession`.

### 1.2 AuthScreen — the sign-in / sign-up form (`AuthScreen.kt` lines 51-151)

A single scrolling, keyboard-padded (`imePadding` → SwiftUI `.ignoresSafeArea(.keyboard)` inverse / scroll-to-field) centered column. One boolean `signUp` toggles the whole screen between two modes. Local `@State` for: `signUp`, `email`, `password`, `name`, `busy`, `message: String?`, `messageOk: Bool`.

**Header copy (mode-dependent — reproduce verbatim):**

| Element | Sign-in mode | Sign-up mode |
|---|---|---|
| Section label | `WELCOME BACK` | `BEGIN AGAIN` |
| Serif-italic title (40pt) | `Pick up where\nyou left off.` (literal newline) | `You don't need more discipline.` |
| Sub (13pt sans) | `Quiet clarity, with momentum.` | `Unstuck reduces friction at the moment behavior breaks.` |
| Footer (11pt sans) | `The anchor stays steady. You move around it.` | `Quiet clarity, with momentum.` |

**Fields:** Name field (`Name (optional)`) shown **only** in sign-up mode, above email. Email field (keyboard type email). Password field (secure). All three use the design `MdField` component.

**Primary button** (`UButton`, dark, full-width pill): label `Sign in` / `Create account`, or `…` while `busy`; disabled while `busy`.

On primary tap, validation runs **in this exact order** (lines 105-111):
1. `email.trim().isBlank()` → set error message `Enter your email first.` (no network)
2. `password.isBlank()` → error `Enter your password.`
3. If `signUp` → call `signUp(email, password, name)` with the success banner `Check your email to confirm your account, then sign in.`
4. Else → call `signIn(email, password)` (no success banner — success transitions the whole screen).

**Secondary actions:**
- **Google button** — outlined pill, white surface, 1px border, official multicolor "G" logo (18dp) + `Continue with Google`. Disabled while busy. Calls `googleSignIn()` with no success banner.
- **Mode toggle text** — `New here? Create an account` / `Already have an account? Sign in`. Tapping flips `signUp` AND clears `message`/`messageOk` (so a stale error/success banner from the other mode doesn't linger). ≥44dp touch target (`padding(vertical = 10.dp)`).
- **Magic link** — `Email me a magic link instead`. If email blank → `Enter your email first.`; else `magicLink(email)` with success banner `Check your email for a one-tap sign-in link.`
- **Forgot password** — `Forgot your password?` shown **only in sign-in mode** (`if (!signUp)`). If email blank → `Enter your email first.`; else `resetPassword(email)` with success banner `Check your email for a password reset link.`

**The `run()` helper (lines 66-75) — replicate its semantics exactly:**
- Sets `busy = true`, clears `message`.
- On `AuthOutcome.Ok`: if a `success` string was passed, set `messageOk = true` and show it; **if no success string, show nothing** (sign-in success leaves no banner because the screen is about to be replaced). This is why every email-only flow (signup/magic/reset) passes a success string — an empty Ok branch previously "looked like nothing happened."
- On `AuthOutcome.Error`: `messageOk = false`, show `r.message`.
- Always sets `busy = false` at the end.

**Message banner:** rendered below the password field; green (`primaryDeep`) when `messageOk`, coral/red (`coralDeep`) otherwise. Centered, 13pt.

**Edge cases to preserve:**
- Email is `.trim()`-ed everywhere before use; password is NOT trimmed.
- All three email-only flows (signup, magic, reset) are deliberately calm in tone and never reveal whether the email exists (anti-enumeration), except the explicit already-exists detection in signup (§3).
- Busy disables the primary button and Google row, but the text toggles and email-only links also guard with `enabled = !busy`.

### 1.3 SetNewPasswordScreen — recovery flow (`AuthScreen.kt` lines 155-193)

Shown **only** when `authed == true && pendingPasswordRecovery == true` (reached via a `type=recovery` deep link; the recovery session is already authenticated). State: `pw`, `confirm`, `busy`, `message: String?`.

- Header: section label `SET A NEW PASSWORD`, serif title (34pt) `Choose a new password.`
- Two secure fields: `New password`, `Confirm password`.
- **Validity:** `pw.length >= 8 && pw == confirm`. Button disabled until valid (and not busy).
- **Inline hint logic** (lines 176-180), priority order:
  - `pw` non-empty and `< 8` chars → `At least 8 characters.`
  - else `confirm` non-empty and `!= pw` → `Passwords don't match.`
  - else → the `message` from a failed save.
- On **Save password** (lines 183-191): set busy, call `setNewPassword(pw)`. On `Ok` → `consumeRecovery()` (sets `pendingPasswordRecovery = false`), which drops the user straight into the app. On `Error` → show `r.message`, clear busy. **Note:** `setNewPassword` is just `auth.changePassword(newPassword)` under the hood (`AppViewModel.kt` line 252) — same call as the account-settings change.

### 1.4 Settings → Account (`SettingsScreen.kt` lines 195-266)

A `SettingsCard` of rows plus three dialogs. Local state: `showName`, `showPassword`, `showDelete`, `msg: String?`, `msgErr: Bool` (failures render red, success green).

**Rows (in order):**
1. **Display name** — subtitle = `currentName ?? "Set a name"`. Tap → name dialog.
2. **Signed in** — subtitle = `currentEmail ?? "—"`. **Static, not tappable.**
3. **Change password / Add a password** — title is `Change password` if `hasPassword` else `Add a password`; subtitle `Update your sign-in password`. Tap → password dialog.
4. **Export everything** — subtitle `One-shot JSON snapshot`. Tap → document export (§1.5).
5. **Delete my account** — subtitle `Permanently removes your data`. Tap → delete confirm dialog.
6. **Sign out** — subtitle `End this session`, last row. Tap → `signOut()` immediately (no confirm).

**Name dialog** (`FieldDialog`): single text field, initial = current name. Save button enabled only when non-blank, value is `.trim()`-ed. On save: `updateDisplayName(it)`; banner `Name updated.` or the error message.

**Password dialog** (`PasswordDialog`, lines 269-295):
- If `hasPassword`: shows a `Current password` secure field first.
- Then `New password` + `Confirm password` secure fields.
- Inline error: `< 8` → `At least 8 characters.`; mismatch → `Passwords don't match.`
- Save enabled when: `pw.length >= 8 && pw == confirm && (!hasPassword || current.isNotBlank())`.
- **Re-auth logic (lines 227-238) — critical, replicate exactly:**
  - If `hasPassword`: read `currentEmail`. If it's null/blank → show `Can't verify your current password — no email is set on this account.` and bail. Otherwise re-authenticate by calling `signIn(email, current)`. If that errors → `Current password incorrect.` and bail.
  - Then call `changePassword(newPw)`. Banner `Password updated.` or error.
  - If `!hasPassword` (Google-only): skip re-auth, go straight to `changePassword` (this "adds" a password to the account).

**Delete account dialog** (lines 242-265):
- Title `Delete your account?`, body `This permanently removes everything and cannot be undone. Type {your email | DELETE} to confirm.`
- Confirm word = the current email, OR the literal string `DELETE` when email is blank (so an email-less account isn't permanently trapped with an un-clickable button).
- Text field label = `Email` (or `Confirm` when email-less).
- Confirm button (`Delete forever`, red) enabled only when `typed.trim().equals(confirmWord, ignoreCase = true)`.
- On confirm: `deleteAccount()`. On error, surface `r.message`. On success the account-delete + sign-out happens server+client-side and the session drops to `authed == false` → AuthScreen.

### 1.5 Data export (§ also in `AppViewModel.exportJson`, lines 738-746)

- Android uses `CreateDocument("application/json")` with default filename `unstuck-export.json`, writes `exportJson()` bytes to the chosen URI. Banner `Exported.` / `Export failed.`
- `exportJson()` serializes an `ExportBundle` (pretty-printed, `encodeDefaults = true`) containing: `exportedAt` (ISO now), `email` (currentEmail), and the full arrays of `tasks, sessions, calBlocks, captures, reasonLogs, collections, tags, lifeAreas`. **Matches the web `exportAll`.**

### 1.6 SetupScreen (`AppRoot.kt` lines 66-89)

Dev-only screen shown when the Supabase anon key is missing at build time. Copy: `Add SUPABASE_ANON_KEY to secrets.properties, then rebuild.` On iOS, the equivalent is a missing `Info.plist`/build-config Supabase URL+key. Keep an equivalent guard but the copy should reference the iOS config mechanism. Low priority — this never ships to users.

---

## 2. Data — models, Supabase tables/columns

This area is **almost entirely auth, not app tables.** No Room/GRDB table is owned by auth itself. The relevant data:

### 2.1 `AuthOutcome` (sealed) — `AuthService.kt` lines 21-24
```kotlin
sealed class AuthOutcome { object Ok; data class Error(val message: String) }
```
Swift: `enum AuthOutcome { case ok; case error(String) }`. Every auth VM method returns this.

### 2.2 `AuthErrorInfo` — `AuthErrors.kt` line 11
```kotlin
data class AuthErrorInfo(val code: String? = null, val message: String? = null, val status: Int? = null)
```
The minimal shape used by `humanizeAuthError`. Swift: `struct AuthErrorInfo { var code: String?; var message: String?; var status: Int? }`.

### 2.3 Supabase auth.users (managed by Supabase, not your schema)
You read these off the current user:
- `id` (uuid) → `currentUserId`
- `email` → `currentEmail`
- `user_metadata.display_name` / `user_metadata.full_name` → `currentName` (with fallback, §3.4)
- `identities[]` — each has a `provider` field; `"email"` presence ⇒ `hasPassword`. Google-only accounts have no `"email"` identity.
- On sign-up: `identities` (count), `email_confirmed_at`, `last_sign_in_at` — used by `detectSignupAlreadyExists` (§3.3).

### 2.4 `ExportBundle` — `AppViewModel.kt` lines 756-768
Serializable bundle of all user-owned collections (see §1.5). Mirror field names exactly (snake-case via the JSON encoder config that the rest of the app uses) so exports are cross-platform identical.

### 2.5 Server: `account-delete` Edge Function
`supabase/functions/account-delete/index.ts`. POST only; `verify_jwt = true`. Decodes the JWT `sub` server-side (never from the body) and calls `admin.deleteUser(sub)`. All user tables cascade via `ON DELETE CASCADE` on `user_id`. Returns `{ deleted: true }` or `{ error }`. **The client just invokes it then signs out** (`AuthService.deleteAccount`, lines 85-89).

### 2.6 `push_tokens` (touched on sign-out, not by auth UI)
`signOutAndUnregister` deletes this device's push-token row *before* signing out (RLS requires a valid JWT). See §5.7.

---

## 3. Business rules / pure logic (and tests)

All pure logic lives in `core/logic/AuthErrors.kt`, tested in `core/test/.../AuthErrorsTest.kt`. **Port these verbatim into a Swift `AuthErrors.swift` with a matching XCTest file.** These are the load-bearing, testable rules.

### 3.1 `humanizeAuthError(err: AuthErrorInfo?) -> String` (lines 14-48)
Maps Supabase's technical errors to actionable copy. Match conditions are checked **in this order** (first match wins); each checks a `code` equality OR a `message.lowercased().contains(...)`:

| Condition (code OR message-substring OR status) | Returned copy |
|---|---|
| `null` err | `Something went wrong. Try again in a moment.` |
| `over_email_send_rate_limit` / msg `rate limit` | `We can only send a few sign-up emails per hour. Wait ~30 min and try again — or use a different email.` |
| `invalid_credentials` / `invalid login credentials` / `invalid_credentials` | `That email and password don't match. Try again, or use Forgot password.` |
| `user_already_exists` / `already registered` / `user already` | `An account with that email already exists. Try signing in instead.` |
| `email_not_confirmed` / `email not confirmed` | `Your email isn't confirmed yet. Check your inbox for the verification link.` |
| `weak_password` / `password should be` | `Password needs at least 8 characters.` |
| `over_request_rate_limit` / status `429` | `You hit a rate limit. Slow down for a minute and try again.` |
| msg `network` / `failed to fetch` / `timed out` | `Couldn't reach the server. Check your connection and try again.` |
| msg `invalid email` / `validation_failed` | `That email address looks off. Double-check it.` |
| fallback | capitalize first letter of raw message, keep the rest (`"something weird happened"` → `"Something weird happened"`) |

`message` is lowercased once up front for the `.contains` checks; the fallback uses the **original-case** raw message. Empty raw message returns empty string.

### 3.2 `nextSafePath(raw: String?, fallback = "/dashboard") -> String` (lines 52-58)
Open-redirect guard for `?next=`. URL-decodes `raw`; returns `fallback` if: nil/empty, decode throws, doesn't start with `/`, or starts with `//`. Otherwise returns the decoded path (query strings preserved). Tests cover encoded paths (`%2Ftasks` → `/tasks`), `//evil.com` rejection, absolute URLs, `javascript:`, relative `../tasks`, and a custom fallback. **Port it even though the mobile deep-link flow doesn't currently consume `?next=`** — it's tested and cheap, and keeps parity with web/iOS originals.

### 3.3 `detectSignupAlreadyExists(identitiesCount: Int?, emailConfirmedAt: String?, lastSignInAt: String?, hasSession: Bool) -> Bool` (lines 62-72)
Supabase's anti-enumeration returns a "successful" obfuscated user for an already-registered email. Any one of these ⇒ already exists:
- `identitiesCount == 0` (empty identities), OR
- `emailConfirmedAt != nil && !hasSession`, OR
- `lastSignInAt != nil && !hasSession`.

Note: `identitiesCount == nil` ⇒ NOT exists (genuine new user / unknown). Confirmed **with** a session ⇒ a newly verified genuine user, NOT "exists." Tests assert all five rows.

**Where it's used (`AuthService.signUp`, lines 49-56):** after a "successful" signUp, compute `detectSignupAlreadyExists(user.identities.size, user.emailConfirmedAt, user.lastSignInAt, currentSession != nil)`. If true → return `AuthOutcome.Error(humanizeAuthError(AuthErrorInfo(code = "user_already_exists")))` instead of the misleading "check your email." This is what stops a returning user from being silently stuck.

### 3.4 `currentName` fallback (`AuthService.kt` lines 105-111)
`display_name` ?? `full_name` from user metadata, each taken only if non-blank; else the email's local part (`email.substringBefore('@')`). Null when signed out.

### 3.5 `hasPassword` (`AuthService.kt` lines 92-93)
`currentUser?.identities?.any { provider == "email" } ?? true`. **Defaults to `true`** when the user/identities are unknown — so we don't wrongly offer "Add a password" to a normal email user during a transient null.

### 3.6 Display-name metadata on write (`signUp` lines 41-42, `updateDisplayName` line 81)
When a non-empty trimmed name is provided, write BOTH `full_name` AND `display_name` into user metadata (`buildJsonObject { put("full_name", name); put("display_name", name) }`). On sign-up, omit the metadata entirely if the name is blank.

---

## 4. Gotchas

1. **`authed` is tri-state (`Bool?`), not `Bool`.** The `nil` window must render a spinner. Mapping it to a plain `Bool` reintroduces the sign-in-flash bug on every cold start. Use an `enum AuthState { case unknown, signedOut, signedIn }`.

2. **kotlinx default-omission / metadata shape.** Supabase user_metadata is free-form JSON. Android writes both `full_name` and `display_name`. On read, parse defensively (`jsonPrimitive.contentOrNull`, blank-check). Don't assume the key exists or is a string. supabase-swift decodes metadata as `[String: AnyJSON]` — guard the type.

3. **Re-auth before change-password is a real sign-in.** The "current password" check (§1.4) is implemented by calling `signIn(email, current)` and inspecting the outcome — there is no dedicated verify endpoint. A failed re-auth must NOT show the raw Supabase error; it shows the fixed copy `Current password incorrect.` This also means a successful re-auth refreshes the session as a side effect — harmless, but be aware.

4. **Recovery `changePassword` == account `changePassword`.** `SetNewPasswordScreen` and the settings password dialog both funnel into the same `auth.updateUser { password = ... }`. The only difference is recovery skips re-auth (the recovery session is already authenticated and has no "current password" to confirm).

5. **Anti-enumeration is deliberate.** Sign-up, magic-link, and reset all return calm "check your email" messaging even for emails that may or may not exist. Do NOT add "this email doesn't exist" feedback. The ONE exception is the `detectSignupAlreadyExists` path (§3.3), which is an explicit, tested behavior.

6. **Delete-account confirm word.** Uses the email, but falls back to literal `DELETE` when email is blank — case-insensitive compare on the trimmed input. Don't hardcode email-only or you trap email-less (Google-only with no email? edge) accounts.

7. **UTC ISO timestamps.** `isoNow()` uses `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` at UTC (`AppViewModel` lines 749-750). The export bundle's `exportedAt` and all timestamp comparisons (e.g. `detectSignupAlreadyExists` reads `emailConfirmedAt`/`lastSignInAt` as strings) assume UTC ISO. Use a fixed `ISO8601`/`DateFormatter` with `TimeZone(identifier: "UTC")`, milliseconds, trailing `Z`. Never use the device-local formatter.

8. **Dependency ordering on sign-out.** `signOutAndUnregister` (§5.7) MUST: (a) flush the offline outbox while the JWT is valid, then (b) unregister the push token while the JWT is valid, then (c) sign out. The NotAuthenticated handler wipes local content — so anything needing a valid JWT or un-flushed data must happen first. Bound each pre-sign-out step with a timeout (Android uses 5s on the flush) so a flaky network can't hang sign-out.

9. **Sign-out must clear per-user device-local state.** On `isSignOut` (`MainActivity` lines 56-61), Android clears the notification log and `settings.clearUserContent()` so the next user on the device doesn't inherit the previous user's notification history / dismissed nudges / archived captures. Replicate: on sign-out, clear all device-local user-scoped caches.

10. **LWW / sync interplay (not auth-specific but adjacent).** Deleting the account triggers `ON DELETE CASCADE` server-side; the client just invokes the function and signs out. Don't try to also locally delete rows one by one — let the cascade + the sign-out content-clear handle it.

11. **Google-only accounts.** `hasPassword == false` flips the password row to "Add a password" and skips re-auth. Confirm this path against a real Google-OAuth test account.

---

## 5. iOS equivalents

| Android / web mechanism | iOS replacement |
|---|---|
| Jetpack Compose `AuthScreen`/`SetNewPasswordScreen` | SwiftUI `View`s with `@State` for `signUp/email/password/name/busy/message/messageOk` |
| `viewModel { AppViewModel }`, `StateFlow` | `@Observable` (Observation) view model or `ObservableObject` with `@Published`; `authed` as `@Published var authState: AuthState` |
| supabase-kt (`io.github.jan.supabase`) | **supabase-swift** (`Supabase`, `Auth`) — same API surface (`auth.signIn`, `signUp`, `signInWithOTP`, OAuth, `updateUser`, `resetPasswordForEmail`, `signOut`, `functions.invoke`) |
| `client.auth.sessionStatus: Flow<SessionStatus>` | `for await state in supabase.auth.authStateChanges` (`AsyncStream`); map `.signedIn`/`.signedOut`/`.userUpdated`/`.passwordRecovery` |
| `signInWith(Email/OTP/Google)` | `auth.signIn(email:password:)`, `auth.signInWithOTP(email:)`, `auth.signInWithOAuth(provider: .google)` (ASWebAuthenticationSession) |
| PKCE flow + `unstuck://auth-callback` (`SupabaseClientProvider`) | supabase-swift `flowType: .pkce`, `redirectToURL: URL("unstuck://auth-callback")`; register the URL scheme in `Info.plist` (`CFBundleURLTypes`) |
| `handleDeeplinks(intent)` in `MainActivity` | `.onOpenURL { url in supabase.auth.session(from: url) }` on the root scene |
| `data.contains("type=recovery")` → `pendingPasswordRecovery = true` | In `authStateChanges`, the `.passwordRecovery` event sets `pendingPasswordRecovery = true`; or detect `type=recovery` in `onOpenURL` before handing to supabase-swift |
| `CreateDocument("application/json")` + `contentResolver.openOutputStream` | `.fileExporter` with a `FileDocument`/`Transferable`, default name `unstuck-export.json`, UTType `.json`; write `exportJson()` UTF-8 bytes |
| `runCatching { ... }.fold(...)` | `do { try await ... } catch { ... }` returning `AuthOutcome` |
| `functions.invoke("account-delete")` | `try await supabase.functions.invoke("account-delete")` (POST, JWT auto-attached), then `auth.signOut()` |
| FCM token register/unregister (`registerFcmToken` / `push.unregister`) | **APNs**: register `UNUserNotificationCenter` + `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`; on sign-out, delete the `push_tokens` row for this device (`platform = "ios"`) while the JWT is still valid, then sign out |
| Room/`LocalStore` (export reads cached collections) | GRDB or JSON store — `exportJson()` reads the in-memory/cached arrays, no auth-specific table |
| `SharedPreferences` (`unstuck.app`, login-ping throttle, `exactAlarmPrompted`) | `UserDefaults` / Keychain (Keychain for session if not using supabase-swift's storage) |
| `humanizeAuthError`, `nextSafePath`, `detectSignupAlreadyExists` (`core/logic`) | Pure Swift `AuthErrors.swift` + XCTest port of `AuthErrorsTest.kt` (these tests *already existed* as `AuthErrorsTests.swift` — re-derive them) |
| `Settings.Secure.ANDROID_ID` device id | `UIDevice.current.identifierForVendor?.uuidString` (or a stored UUID in Keychain) for the push-token row key |
| `AppGraph.configured` (BuildConfig anon key) | Build-config / `Info.plist` Supabase URL + anon key guard; show the setup screen if absent |

### 5.1 supabase-swift method mapping (one-to-one)
- `signIn(email, password)` → `try await auth.signIn(email:password:)`
- `signUp(email, password, name)` → `try await auth.signUp(email:password:data:)` where `data` is `["full_name": .string(name), "display_name": .string(name)]` only if name non-blank. Then run `detectSignupAlreadyExists` on the returned `User`'s `identities.count`, `emailConfirmedAt`, `lastSignInAt`, and `auth.currentSession != nil`.
- `sendMagicLink(email)` → `try await auth.signInWithOTP(email:)`
- `signInWithGoogle()` → `try await auth.signInWithOAuth(provider: .google)` (presents `ASWebAuthenticationSession`; the PKCE callback returns via `unstuck://auth-callback`)
- `resetPassword(email)` → `try await auth.resetPasswordForEmail(_:)`
- `changePassword(newPassword)` → `try await auth.update(user: UserAttributes(password: newPassword))`
- `updateDisplayName(name)` → `try await auth.update(user: UserAttributes(data: ["full_name": .string(name), "display_name": .string(name)]))`
- `deleteAccount()` → `try await functions.invoke("account-delete"); try await auth.signOut()`
- `currentUserId/currentEmail/currentName/hasPassword` → computed off `auth.currentUser`

### 5.2 No foreground service / exact-alarm concerns here
The auth area itself schedules no alarms and runs no foreground service, so the Android exact-alarm / WorkManager / Glance gotchas don't apply to *this* spec — except the **one cross-cutting touch**: do not bounce a brand-new user into any system permission prompt (notifications) before they've finished onboarding (Android gates the exact-alarm prompt on `onboarded`; apply the same restraint to the iOS notification-permission prompt — request it post-onboarding, not on the auth screen).

### 5.3 OAuth presentation
Google sign-in and the Google Calendar consent both bounce through `unstuck://`. Use `ASWebAuthenticationSession` with a presentation context provider; ensure the callback URL scheme is the *same* one registered for Supabase PKCE. The `onOpenURL`/session-from-url handler must run **only on a fresh launch for the launch URL** — Android guards re-firing on config changes (`if (savedInstanceState == null)`); iOS naturally avoids this, but be careful not to re-exchange a code if the URL is delivered twice.

---

## 6. Acceptance checklist (behavioral parity)

- [ ] Cold start while signed in shows a spinner, never the auth form, until session resolves.
- [ ] Sign-in/sign-up mode toggle swaps all header/footer copy and the name field, and clears any banner.
- [ ] Validation order: blank email → "Enter your email first."; blank password → "Enter your password."
- [ ] Sign-in success shows NO banner; signup/magic/reset show their exact calm success strings.
- [ ] Already-registered email on sign-up surfaces "An account with that email already exists…" (via `detectSignupAlreadyExists`).
- [ ] Magic link & forgot-password links require a non-blank email; forgot-password hidden in sign-up mode.
- [ ] Recovery deep link (`type=recovery`) routes to SetNewPasswordScreen; valid only when `pw≥8 && pw==confirm`; success drops into the app.
- [ ] Account: change-password re-auths via sign-in for password accounts; "Add a password" skips re-auth for Google-only; "no email" guard message present.
- [ ] Delete dialog confirm word = email (or `DELETE` if no email), case-insensitive; invokes `account-delete` then signs out.
- [ ] Export writes a pretty `unstuck-export.json` matching the web `ExportBundle` field-for-field.
- [ ] Sign-out flushes outbox + unregisters push token before signing out, then clears per-user device-local caches.
- [ ] `humanizeAuthError`, `nextSafePath`, `detectSignupAlreadyExists` ported with the full XCTest suite green (21 cases from `AuthErrorsTest.kt`).

**Key source files (Android):** `app/.../ui/auth/AuthScreen.kt`, `app/.../ui/AppRoot.kt`, `app/.../ui/AppViewModel.kt` (auth methods L699-746), `app/.../MainActivity.kt` (deep links L101-135), `app/.../ui/settings/SettingsScreen.kt` (AccountContent L195-295), `sync/.../AuthService.kt`, `sync/.../SupabaseClientProvider.kt`, `sync/.../SyncCoordinator.kt` (`signOutAndUnregister` L61-71), `core/.../logic/AuthErrors.kt` + test `core/test/.../AuthErrorsTest.kt`, `supabase/functions/account-delete/index.ts`.