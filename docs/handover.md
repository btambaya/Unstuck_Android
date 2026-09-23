# Unstuck Android — Handover / Live State

Single source of truth for "where is the Android build?". Update as phases land.

> **New engineer? Start with the onboarding handbook: [`handbook/`](handbook/README.md)** (8 deep chapters) + the quick [`APP_GUIDE.md`](APP_GUIDE.md). (All project docs now live under `docs/`.)

## 2026-09-23 (night) — vc104 / 0.5.20: sign-up links that open the app; "account already exists"

- **vc104** (34e7752, Firebase release 1q9mvhh7qgcn8, 2 testers): signUpWith(Email) and signInWith(OTP) pass
  `redirectUrl = "unstuck://auth-confirm"` (AuthService.EMAIL_LINK_REDIRECT); reset + Google keep the client default
  `unstuck://auth-callback`. A new `autoVerify="true"` intent-filter claims https://unstucknow.io/auth/app-confirm (assetlinks.json
  live, Google's DAL API lists io.unstucknow.app); MainActivity verifies with `verifyEmailOtp(type, tokenHash)` once per link.
  Prod templates 01/02 send `/auth/app-confirm/?token_hash=…` only for that redirect (older builds keep Supabase's own link).
- **Play launch TODO:** add the Play App Signing cert's SHA-256 to unstuck/public/.well-known/assetlinks.json, or Play installs
  open the web page instead of the app.
- **Already registered:** a sign-up whose user has `identities: []` now shows "An account with this email already exists. Sign in
  instead." with Sign in instead / Forgot password? (a 422 user_already_exists is treated the same).
- **Device test:** sign up in vc104 with a real inbox → tap the email on the phone → app opens signed in; open the same kind of
  email on a computer → the web page says confirmed / open the app; sign up again with that email → the "already exists" notice.

## 2026-09-23 (evening) — vc102 / 0.5.18 (list-item gestures + reconnect card) and vc103 / 0.5.19 (stage 2: same id for same day)

- **vc102** (c10d689, parity with iOS build 84): collection items use one gesture per job — TAP strikes out, SWIPE LEFT Delete,
  SWIPE RIGHT Pin/Unpin + "To task", HOLD edits (also as accessibility actions); a dead Google connection is a plain
  "Google Calendar stopped syncing" card with Reconnect, never the raw `invalid_grant (400)`.
- **vc103** (2534bc5, Firebase release 560gofaakv1h8, 2 testers only): stage 2 / C21 — every repeating-task occurrence id is
  UUIDv5(task id | date) (`occurrenceId`, the shared vectors in unstuck_ios/audit/parity-2026-09-23/deterministic-occurrence-ids.md
  §1.5), minted insert-if-absent (`on_conflict=id`, `resolution=ignore-duplicates`) and retimed only while open on its date
  (rules G + H); the horizon top-up is tail-only and serialised after a GOOD cal_blocks pull. Shipped the same evening as
  web stage 2 (unstuck main 976b900) — iOS has had it since build 85.
- **Backend live the same evening:** 077 daily voice minutes (Talk + calls share 10 min/local day; the Worker refuses a connect
  with none left with the 429 Android already maps — the minutes-left line / 1-minute warning are iOS-only so far) and 078 push
  tokens tied to the auth session (senders read `live_device_tokens`; a remote sign-out stops pushes/calls).
- **Device checks:** the Google stage-2 path (a repeating task mirrored to Google: no duplicate events after a second device
  fills in the same day; a moved occurrence stays moved).

## 2026-09-23 — vc100 / 0.5.16 (parity with iOS build 81) and vc101 / 0.5.17 (pre-launch audit P0 + P1)

Both went to the 2 Firebase testers only.

**vc100: parity port (cb72888).** Android caught up with iOS builds 75-81 and the 20 iOS pre-launch fixes, in 8 groups, each implemented, adversarially reviewed and fixed up. Gap report: `unstuck_ios/audit/parity-2026-09-23/android-gap.md`.
- Recurrence: the anchor is recurrenceEditStart, not the oldest block. "Start repeating" replaces the invented 09:00, and unschedule_task on a series refuses and asks.
- C4 clamps, including healing already-queued outbox payloads.
- Completion: ticks on a series are today-only, writes use the stored row, and there is no tick on a repeating share.
- Sync: C2 reminders; C8/C9 (membership re-read, chain reconcile).
- Sharing: server-backed Block/Unblock/Report (migration 075).
- Calls: deviceGuard with 06:00-23:00 hours, a quiet call-notes channel, and the call ends on error.
- Voice: busy-retry, plain-words errors, a dial watchdog, one redial after a 401, and runtime tool compaction.
- Settings: Clear Assistant history.

**vc101: audit fixes (b70b593).** Read-only audit: `unstuck_ios/audit/parity-2026-09-23/android-audit/REPORT.md` (98 agents; 1 P0 + 18 P1 fixed; 50 P2 + 33 P3 left as a mostly unverified backlog).
- P0 (A1-A3): supabase-kt 3.0.3 resets the session to Initializing on ON_STOP. As a result, call pushes, background sync and call outcomes acted signed-out. The new `sync/SessionGate.kt` (`SyncCoordinator.session`) never treats Initializing as signed out. It restores the stored session in the background.
- Other fixes:
  - A4: the ring loops for the whole 30 s.
  - A5: End after an accepted call-back no longer cancels it.
  - A6: calls about web/iOS tasks are no longer dropped as stale.
  - A7: a failed auth link no longer crashes.
  - A8/A9: no duplicate life areas; the onboarding gate uses the real uid.
  - A10: transient failures no longer dead-letter writes.
  - A11: the first pull of each launch is a full hydrate, as on iOS.
  - A12: stored and wire dates always use ASCII digits.
  - A13/A14: focus on an occurrence has the right prior, and its captures sync.
  - A15: exact alarms on Android 14+, re-armed when granted.
  - A16: sharing flows load after sign-in.
  - A17: assistant Undo is exact or refuses.
  - A18: Export everything is complete.
  - A19: the Google connect copy is honest.
- DEVICE GATE: before any release beyond the testers, test A1-A3 with the app backgrounded and with it killed: ask for a call, tick something then press Home, and say "call me back in ten" then lock. Use a real call for this, not the Settings test call.
- Decisions I made while Ahmad slept: `unstuck_ios/audit/parity-2026-09-23/DECISIONS.md`.

## 2026-09-20 (night) — vc99 / 0.5.15: the echo verdict narrowed, the corrective forces the tool

Mirror of iOS build 74 (evidence in `assistant_turns`, Ahmad's 15:21–15:37
Talk session on iOS: three real utterances deleted as echo of the question
they answered — "Have you set up the call?", "What is today?", "Book the cool
call now." — and zero true echoes caught since echo cancellation came back).
Ahmad's steer: the solution that cannot make the experience worse — tiered,
not off.

- `core/logic/BargeIn.kt` — a completed transcript is judged by its words
  ONLY when its segment began while the reply's audio was on air (or is a
  later piece of an echo-judged segment); after the drain the words are the
  user's. On air, four words or more (`ECHO_VERBATIM_FROM`) are echo only
  when every content word is the model's and at most one filler is not (the
  transcriber slips a filler into an echo — "Coming up on Friday" — the
  user's framing adds more — "HAVE YOU set up the call?"); three or fewer keep
  the content-word scoring. The live-guess early cut is unchanged. Tests
  19c/21c/22d (pinned pre-AEC iOS device logs) flipped; section 25 added.
- `ui/assistant/VoiceRealtimeClient.kt` — the integrity corrective's
  `response.create` carries `response.tool_choice = required` (measured
  honoured by DashScope); spoken-only, the model answered it with another
  promise and the same question. New corrective text (verbatim with iOS/web).
- Contract: `unstuck/docs/voice-turn-taking.md` §2/§4. The voice-proxy now
  logs the turn-taking as `assistant_turns` role `event` (migration 073).
- Android caveat: the OEM AEC is the only echo removal on the loudspeaker
  now for anything after the drain or longer than three words; if a device
  answers its own words, that is where to look (not word matching).

Tests: core 885, app 591, all green. Shipped to Firebase (2 testers) as vc99.

## 2026-09-20 (evening) — vc98 / 0.5.14: a promised action is a claim; the voice prompt gets the CALLS rule

Ahmad asked Talk (iOS) "call me in one minute and remind me…" and no call came:
the assistant said "I'll set a reminder for one minute from now" and called no
tool (`assistant_turns`, 15:18 UTC). Same holes existed here, fixed in lockstep
with web (commit 40fbc94) and iOS (build 73):

- `core/logic/AssistantGuard.kt` — a new claim pattern: any `I'll / I will /
  I'm going to <tool verb>` (set, call, remind, book, add, move, mark, share…)
  is an action claim, so a promise made INSTEAD of a tool call is bounced into
  the real call by the existing corrective. Offers ("do you want me to call
  you?") and refusals still pass. Test: `AssistantGuardTest.promisesOfAnActionAreClaims`.
- `ui/assistant/AssistantContext.kt` — the voice honesty block now carries the
  CALLS rule (verbatim from web `CALLS_RULE`): "call me at/in …" means
  `request_call` NOW with `when` from `context.today` + `context.now`, reminders
  verbatim as notes, a call exists only on `ok`, never book an unasked call, a
  bare "remind me at 5" is a scheduled task.
- Rules of record: `unstuck/docs/assistant-tooling-rules.md` §2 + §3.

Tests: 1472 (core + app) green. Shipped to Firebase (2 testers) as vc98.

## 2026-09-20 (later) — calls build-out, Android half (parity with iOS) — SHIPPED vc97 / 0.5.13 (commit acb53f2)

The Android section of `unstuck/docs/calls-build-out.md`, against the LIVE server
contract in `unstuck/docs/handbook/07-backend.md` "Calls" (migration 072:
`call_requests.kind/retries`, `notification_preferences.call_*`, `callKind` +
`endTime` on the push, `call-outcome → { ok, status, retry, snoozeUntil? }`).
Reference implementation: `unstuck_ios/App/Calls/*` (its handover top entry).

- **Model + mirror + client** (`core/model/CallRequest.kt`, `sync/CallsClient.kt`):
  `CallKind` (requested | test | morning | evening | after_block; tolerant
  `fromWire` → requested), `CallRequest.kind` (String, default `requested`) +
  `retries` (Int?), `kindEnum` / `isTestCall`. The mirror, catch-up and realtime
  paths decode through the same serializer, so the two columns ride for free;
  `CallsClient.createRow/create` write `kind` explicitly (`requested` default,
  `test` for the button). `AssistantCallStore.book(kind)` threads it.
- **Test call** (`AppViewModel.bookTestCall`): books kind `test` DIRECTLY through
  the call store (iOS shape) after the same guards (server window + past-time via
  `CallToolLogic.timeGuard`, the user's hours, calls-on) and after cancelling
  every live earlier test row (`core TestCallLogic.previousTestCalls`: kind
  `test`, or the old label). Same "ok: call booked …" contract string, so the
  Settings card is unchanged. The old request_call + duplicate-regex dance is gone.
- **Push payload** (`core/logic/IncomingCallPayload.kt`): `callKind` + `endTime`
  decoded and round-tripped (`toData` always writes `callKind`); `resolvedKind`
  (callKind → the push's own `kind` slot if a server wrote the row's kind there →
  requested); `isCallPush(kind)` = "call" or one of the five kinds —
  `CallPushHandler` keys on it (kind:"call" stays the discriminator); `endMs` /
  `spokenEnd` ("11:30" → "11:30am"; ISO → the zone's clock).
- **The call is the full assistant** (`core/logic/CallScript.kt`): `opening` /
  `instructions` / `headline` / `conversationRule` vary by kind exactly like iOS
  (requested unchanged; test; morning → get_schedule + plan; evening →
  get_tasks(completed) + carry_to_tomorrow on request; after_block → "<task> was
  on till <time>. How did it go?" → done / skip / reschedule). `- kind:` and
  `- block ended at:` in the context. NEW **name-once rule**
  (`CallScript.NAME_ONCE_RULE`, line 3 of every kind — a test call said the name
  three times). `CALL_TOOLS` is gone: `callToolNames(voice, call)` = the
  registry's voice surface + call surface + guaranteed `update_call` /
  `snooze_call`; `:app callToolNames()` / `callVoiceToolsJson()` (no-arg) build it
  from `RegistryTools`; `CallVoiceService.callToolSchemas` now emits EVERY voice
  schema it is handed + snooze_call (always ours) + update_call (synthesised when
  the registry lacks it). `finish_interview` is therefore live on a call too.
- **Missed → retry-aware** (`CallOutcomeStore`, `CallRinger.settle(notifyUnlessRetry)`,
  `MissedCallReceiver`, `IncomingCallActivity.onRingTimedOut`, `CallRinger.recover`):
  nobody posts "I called about X" at the 30 s timeout any more. The `missed`
  item carries `notify = MISSED` + the ring payload (persisted with the queue —
  survives a kill), `CallsClient.reportOutcome` returns `Result<CallOutcomeReceipt>`
  (body parsed tolerantly; a pre-072 server = `retry:false`), and the drain posts
  the notice on `retry: false` or on a permanent refusal, swallows it on
  `retry: true` (`CallOutcomeReceipt.shouldNotify`). Declined / busy / outside-
  hours / voice-failed notices are unchanged (never retried server-side).
- **Settings › Calls** (`SettingsScreen.CallsContent`): the existing `enabled` +
  hours + lead stay; NEW "Calls Unstuck can make on its own" — Morning planning
  call (time), Evening wrap-up call (time), Check in after a block, one-line copy
  each verbatim from iOS — read/written through `PreferencesClient.
  fetchCallProactivePrefs / setCallProactivePrefs` (`notification_preferences.
  call_*`), cached per uid in `CallSettingsStore.loadProactive/saveProactive` with
  a `pendingProactivePush` flag (`AppViewModel.setCallProactivePrefs` →
  cache + pending + push; `reconcileCallProactivePrefs` after every pull / prefs
  realtime event: push pending first, then server wins unless pending — pure
  `core CallProactiveSync.resolve`; `refreshCallProactivePrefs` on screen open).
  The full-screen-intent nudge (Android's twin of the iOS VoIP nudge) keeps the
  `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` row, now with an explainer line and
  a **"Not now"** dismissal persisted per uid (`CallSettingsStore.ringNudgeDismissed`,
  `AppViewModel.dismissRingNudge`, pure `core CallRingNudge.shouldShow`); the
  device-status line keeps saying calls degrade. `ToggleRow` gained an optional
  sub-line.
- **Tests**: core `CallScriptTest` (per-kind openings + instructions, callToolNames,
  name-once, unknown kind = requested), `IncomingCallPayloadTest` (callKind /
  endTime / isCallPush / spokenEnd), `CallSettingsTest` (spokenTime,
  CallProactivePrefs + hhmm + sync rule, ring nudge, previousTestCalls),
  `CallOutcomeQueueTest` (receipt decode, notify round-trip), `CallCoordinatorLogicTest`
  (kind/retries decode); sync `CallsClientTest` (kind on create, 072 decode); app
  `CallOutcomeStoreTest` (retry true swallows / false posts / pre-072 posts /
  permanent posts / transient keeps the notice across a relaunch),
  `CallRingerTest` + `IncomingCallActivityTest` + `PushTest` (deferred notice,
  proactive rings, kind-tagged pushes), `CallVoiceServiceTest` (full tool set,
  per-kind compose), `CallSettingsStoreTest` (proactive cache / pending / nudge),
  `CallsSettingsCopyTest`, registry parity tests updated.
- **Left**: on-device validation of each kind's ring + the proactive toggles
  round-trip against prod; the Play "calling app" declaration draft for Ahmad
  (not code); a `get_calls` line showing the kind (cosmetic).

## 2026-09-20 — assistant v2 (vc96 / 0.5.12): one tool registry, tools that report real outcomes, and the new voice turn-taking

Ported from the iOS work of 2026-09-19/20 (see unstuck/docs/voice-turn-taking.md and
unstuck/docs/assistant-tooling-rules.md — the shared contracts).
- **Tools**: the hand-maintained `VoiceToolSchema` specs and `ContractDiffTest` are
  gone. `ToolRegistry.generated.kt` (from `unstuck/scripts/gen-tool-registry.mjs`,
  71 tools, one hash shared with web + iOS) is the schema source; `RegistryTools`
  parses it for the voice session and call mode; `ToolRegistryParityTest` pins the
  executor to it. Every seam method reports an outcome (Unit → Boolean/id), every
  executor returns `ok:` only when the store confirmed it, partial results are
  spelled out, 13 new tools (find_tasks, set_task_reminder, finish_focus,
  recolor_list, leave_list, share_list (staged), pin_list_item, restore_capture,
  get_settings, set_theme, set_focus_defaults, set_ambient_sound, finish_interview
  as an executor case). Guards: the harness's write rule is `ok:`-prefixed and not
  read-only/navigation; the corrective wording is the shared one; the voice
  integrity guard uses the same rule.
- **Voice**: `BargeIn.kt` is the port of iOS `BargeIn.swift` (64 cases): the server's
  own turn detection is OFF (`interrupt_response`/`create_response` false), the client
  asks for every reply from the completed transcript with a 500 ms hold and never
  before a cancelled reply's done, the transcriber's live guess (`stash`) cuts a
  reply mid-segment, echo/no-word items are deleted only when the next segment
  starts, echo scoring v2 is the backstop behind the platform AEC. The loudspeaker
  is full-duplex again (talk-over works; the platform's VOICE_COMMUNICATION AEC/NS/
  AGC were already wired — logged as `voice engine route=… aec=…`). Opening
  watchdog + quiet reconnect when the server fails before any reply.
- Tests: 1443 unit tests green (`:core:test` + `:app:testDebugUnitTest`).
- Ship: vc96 to Firebase (the two testers). On-device validation of loudspeaker
  talk-over pending.

## 2026-09-18 — the Start-Next hero is gone from the home

The lavender "Start next" card (area · task, first-step headline, estimate, Focus, "Pick another") and its all-clear twin ("Nothing to start / You're all clear. / Add one thing") no longer render on Today, on iOS and Android alike. The home is now: top bar → date eyebrow → one-line greeting → "This week · focused" pill → the assistant input pill → the Today list (filters + rows). Focus stays reachable from every task row (detail / context menu) and the editor's Focus button; the Start-Next **home-screen widget** (`surface/StartNextWidget.kt`, `pickStartNext`) is untouched.

- `ui/today/TodayScreen.kt`: `StartNextHero`, `EmptyHero`, `heroBrush`, the `startNext`/`empty` derivations and the `onSearch` parameter are deleted; rows no longer subtract the hero; a plain Today with nothing scheduled shows the iOS note "Nothing scheduled. Tap + to add." instead of the all-clear card.
- `core/logic/PickStartNext.kt`: `pickTodayHero()` (+ its tests/soak benches) deleted — only the hero used it.
- Tour: anchors `START_NEXT` / `FOCUS_BEGIN` removed; the today + finish steps target `TODAY_LIST` directly (no fallback chain); the today step's copy no longer describes Start Next. Its old Cherry clips narrated the hero and were removed the same day — **re-recorded later that day (see the next section); `TOUR_STEPS_AWAITING_NARRATION` is empty again.**

## 2026-09-18 (later) — the `today` tour step narrates again

The hero removal left the `today` tour step with a title and nothing else (Listen hidden, Read collapsed on small screens). Closed, identically on iOS and Android:

- **Copy** (`ui/tour/TourData.kt`, `today` step — the SAME strings as iOS `TourData.swift`, which previously differed): body / narration / more describe the hero-less home — the one-line greeting, the "This week · … focused" pill, the assistant input pill ("ask, plan, or brain-dump; say it or type it, and it does it"), then the Today list with its area filters + Backlog, and that Focus starts from any task row or from inside the task. No Start-Next / hero / "pick something" (`tourCopyNoLongerDescribesTheStartNextHero` still guards both lists).
- **Clips** (`res/raw/tour_today.m4a` 23.2 s, `tour_today_more.m4a` 14.0 s — byte-identical to the iOS bundle): DashScope **qwen3-tts-flash**, voice **"Cherry"**, synthesised server-side (a temporary guard-protected Supabase edge helper read `DASHSCOPE_API_KEY`; the key never left the server; helper deleted — the Supabase secrets API only returns digests, so the key cannot be fetched to a machine), WAV 24 kHz mono → gain-matched to the clips they replace (−22.5 / −24.3 LUFS integrated, exactly the old today / today-more) → `afconvert -f m4af -d aac -b 56000` → AAC-LC 24 kHz mono like every other clip in the set.
- **Wiring**: `TourAudio.kt` maps `"today"` in both `tourAudioRes` and `tourMoreAudioRes`; `TOUR_STEPS_AWAITING_NARRATION = emptySet()`. Tests (`TourLogicTest`): `everyStepHasABundledAudioResource` pins the empty set + both today ids wired, `audioResourceNamesMatchBundledClips` includes `tour_today`, `todayClipsMatchTheStepCopy` pins the exact narration/more strings the clips speak (edit the copy → re-record, or it fails), `todayClipsArePresentInResRaw` checks the files on disk.

## 2026-09-17 — iOS-parity pass: barge-in (a)(b)(c), the home, the interview in the assistant, coral-only colours

Ported from the iOS reference (unstuck_ios head 4bc5110, builds 56–61). Unit tests green (`./gradlew test`); `:app:assembleDebug` compiles.

1. **Barge-in (`core/logic/BargeIn.kt`, `BargeInControllerTest` 19 cases incl. iOS 2/2b/2c/5/5b/16).** (a) A duck is CONFIRMED at the tick only when `serverSpeaking && gateOpen` — the timer alone confirms nothing (speech_stopped can't arrive inside a 300 ms window: 600 ms of silence first), so a server blip with no mic energy **restores + suppressNextResponse**, and a gate-only duck the server never called speech restores without suppression. (b) The transcription accelerator cancels only while the gate is open (deltas stream for the previous turn and for the model's own echo). (c) **The loudspeaker is HALF-DUPLEX for the whole reply**: `BargeInProfile.SPEAKER` is now `assistedDuplex = false` (`halfDuplexWhilePlaying`), `BargeInProfile.gateForcedClosed(profile, modelBusy, pttPressed)` holds the gate shut from `response.created` until the playback has drained — `RmsGate.process(…, forcedClosed)` uploads DIGITAL SILENCE (the server's silence timer keeps running), drops the pre-roll, emits one close event; hold-to-talk's press overrides it; earphones/BT (`LOW_ECHO`) stay full duplex. `SPEAKER_ASSISTED_DUPLEX` is the opt-in for a device whose AEC proves good enough (`VoiceRealtimeClient(speakerHalfDuplex = false)`). `VoiceAudioEngine` computes `forcedClosed` per frame from `profile` + `responseActive` + `playbackQueued()` (the old `dropForEcho` frame-drop is gone). On the loudspeaker, Interrupt (tap / button) is the way to cut a reply.
2. **Home (`ui/today/TodayScreen.kt`).** Greeting on ONE line (`greetingLine()` → "Good evening Maya.", auto-shrinks to 70 % before ellipsising); the gateway card is REPLACED by **one input pill directly under the week pill** (`ui/today/AssistantInputPill.kt`: "✦ Ask, plan, or brain-dump…" + mic → Talk; tap → `AppViewModel.openAssistant(focusComposer = true)` → the Assistant sheet with the keyboard in ITS composer via `AssistantOpenRequest(handoff, focusComposer)`); the "Nothing scheduled today / Pick something to start / N in your backlog" card is GONE (`TourAnchorIds.BACKLOG_POINTER` removed; today/finish fall back to `TODAY_LIST`). Start-next hero, recap, list untouched. `GatewayCard.kt` + `InterviewSheet.kt` deleted (`composeBrief` / `pickMoment` / `GATEWAY_*` stay in `:core` / `GatewayLogic.kt`); the one-shot `InterviewAutoOpenGate` is gone.
3. **The interview lives INSIDE the assistant (`ui/assistant/InterviewFlow.kt`).** Same `InterviewFlowController` / profile-facts store / done flag / resume step. TEXT: `InterviewThreadDriver` arms on the FIRST send of a visit, the reply comes first, then the greeting (once) + one question per LOCAL assistant turn (`appendLocalAssistant` now returns the turn id; `InterviewPromptRow` draws chips + Skip (+ free text) under `promptTurnId`); taps echo as local USER bubbles (`appendLocalUser`, no ✦); a subject change gets its reply then the same question again; the picker marks done, "That's me set up" posts the closing line; a failed save keeps the question. Stand-down = the ≥1-fact rule at send time (`vm.interviewThreadAsking` is the "isOpen" the MainScaffold auto-complete effect respects). VOICE: `buildVoiceOpening` gates on `AssistantApi.interviewPending()` (new seam, default false), lists the seven questions (`core InterviewVoice.spoken`), saves via `save_profile_fact`, allows skips, does the user's requests first, and closes with the new **talk-level `finish_interview` tool** (`VoiceToolSchema.FINISH_INTERVIEW_SPEC`; `talkVoiceToolsJson()` = 57 + 1; answered in `AppViewModel.runVoiceTool` → `markInterviewDone()`; never a contract or call tool — `ContractDiffTest` still pins 57). Tests: `InterviewThreadDriverTest` (15), `InterviewVoiceHostTest` (6), `core InterviewVoiceTest` (3).
4. **Colours (`brand-colour-coral-only`).** The rust `coralDeep` token stays defined in `Theme.kt` (the accent remap needs it) but **no longer appears anywhere in this pass's files**: error text → `c.red`, accents (Undo, mic, "Just now", the loading spinner, the fact-category eyebrow, the drag ghost, the in-call icon) → `c.coral`, chip/label text that was rust → `c.ink` (Tasks "today" tab, capture "distraction" tag, the in-call label), selection = the black-and-white pair everywhere (`RitualChips`, the New Task Off/View/Partner/Assign row, `design Chip`). **`withAccent` dark-mode bug FIXED** (mirror of iOS `Tokens.swift`): the rose / forest ramps are per SCHEME now — dark `primary` / `primaryDeep` / `primarySoft` / `coralSoft` use the web's `.u-dark` values (`coral` + `coralDeep` keep the light values in dark, as on the web); `TokensTest.accentRampsFollowTheSchemeLikeTheWeb` pins it. Still rust (owned by the unified-sharing agent's files, not touched here): `ui/sharing/ShareTaskSheet.kt`, `ui/collections/ShareCollectionSheet.kt`, `ui/settings/ConnectionsContent.kt`.

## CURRENT STATUS — v0.5.0 (versionCode 84), 2026-09-06

**Feature-complete + web-parity, shipping on Firebase App Distribution to 2 testers** (justtesting6363@, zyzkazaure@ — NO beta group by default; `-PappDistGroups=beta` for the full group). Same Supabase backend (`uaxfteluwctrlgwmmfzi`). Release builds signed (dev keystore); backend crons live (morning-brief /15m, collection-late /5m, task-reminder-dispatch /5m). Android is the **native reference for the iOS rebuild**.

**v0.5.0 (versionCode 84, 2026-09-06) — AI GATEWAY PORT, phases A0 · A1 · A1b · A2 · C0 of `unstuck/docs/android-gateway-plan.md` (commits 6794c70 → 3d4894b → fb19233). 1322 tests green: `:core` 693 · `:sync` 250 · `:data` 13 · `:app` 366.** The assistant is now the app's gateway with memory, 1:1 with web + iOS (same server prompt, same tool contract). Release build 84 was in progress when this was written; the Firebase upload to the 2 testers + the adversarial parity sweep are **A3 (next)**.

1. **Memory layer (A0).** `profile_facts` is a first-class synced record: `core/model/ProfileFact.kt` + `core/logic/ProfileFacts.kt` (prepare / normalise / refine, injection guard `isInstructionLike`, `detectStylePreference`, preferred-name rules, `contextLines` cap 15) ported 1:1 from the web `lib/assistant/profile.ts` + iOS `ProfileFacts.swift`; `Tables.PROFILE_FACTS` row codec in `DbRowCodec`; **hydrate keeps pending local upserts** (`replace(keepPendingUpserts=true)` — local-only facts survive, tombstones merge newest-`updated_at`-wins); realtime `upsertIfNewer(updated_at)` (055 replica identity full, so `active=false` UPDATEs arrive); **soft-delete tombstones go through the outbox** (`WriteThrough.upsertProfileFact`, `active=false`); `sync/ProfileFactsService.kt` (save→refine→upsert→enqueue, remove, clear, wipeLocal). `PreferencesClient` gains `pa_rituals` (migration 053) + `setRituals` / `markInterviewDone` / `setUsableMinutes`; `core/logic/PAPrefs.kt` (ritual defaults `true/true/false/false`, missing-key fill, dismissed-moments cap 200) + `InterviewFlag.kt` (server flag → auto-open / auto-complete gates). `AppViewModel`: `profileFacts` flow, the **style preference is saved deterministically before the model sees the message**, server rituals + interview flag are pinned on hydrate **before any auto-open**, **per-uid SharedPreferences keys** (pattern = `captureArchiveMigrated(uid)`), **sign-out scrub** next to `clearAssistant()`.
2. **Engine (A1).** The executor moved out of `AppViewModel` into `ui/assistant/AssistantTools.kt` (+ `AssistantToolsSurface.kt`, `AssistantToolsAppModel.kt`) behind the **`AssistantApi` interface** (`AssistantApi.kt`; AppViewModel implements it, `AssistantCallStore` covers calls) — **all 57 contract tools** with the contract's exact names / args / `ok:`·`error:` strings (unknown recurrence kind is an error, `schedule_task` keeps the time when `startTime` is omitted, `complete_task` returns `id=`, `create_tasks` ≤25, `update_task` refuses schedule args, `block_time` returns the placeholder block id so undo can delete it), the same **cascades** the UI does (delete task → blocks + captures; rename area/tag → tasks follow), `moveCount` bumps, `open_screen` routed for all 12 contract screens. The turn loop is pure, in `:core`: **`AssistantHarness.kt`** (`MAX_ROUNDS=5`, ONE hidden `CORRECTIVE` bounce when the reply claims an action no tool performed, `CUT_OFF_HINT` on `finish_reason=length`, `TRUNCATED_ARGS_RESULT`, `READ_ONLY_TOOLS` never count as "acted", **honest fallbacks** `LOST_THREAD` / `PARTWAY_STAGED` / `PARTWAY_UNRECEIPTED` / `DROPPED_MID_REPLY` — never a synthesised "Done."), `AssistantGuard.kt` (`looksLikeActionClaim`, `stripSelfCorrection`), **`ReplyPolish.kt`**, **`AssistantTime.kt`** (injectable clock, free windows today, past-time / past-date guards, `upcomingDates`, `nowNote` — lifted out of AppViewModel's private helpers), **`AssistantReceipts.kt`** for all 46 write tools with **undo by id** (`ReceiptUndoKind`: DELETE_TASK, UNCOMPLETE_TASK, UNCOMPLETE_TASKS, DELETE_TASKS, FORGET_FACT, DELETE_CAPTURE, COMPLETE_TASK, CANCEL_CALL — bulk undo fixed), `AssistantInsights.kt` + `InsightsRead.kt` (`renderInsights` over `Analytics.kt`). **Contract-shaped context** (`AssistantContext.kt`: profile lines, tone, week, captures, people, focus, preferredName / nameUse, struggle, focusWindow, noticed…). **Send queue** — a message typed while a turn is in flight is queued (`assistantQueued`), shown as a faded pending bubble in `AssistantSheet`, and sent when the turn ends. `AssistantReply.finishReason` is decoded from the edge fn. **Vendored `docs/assistant-tool-contract.md`** (generated in the web repo by `npm run tool-contract` — re-vendor, never hand-edit) + **`ContractDiffTest`** (57 names = registry, every result template is `ok:`/`error:`, required args match, read-only set matches, voice schema carries every tool) — the contract is the port spec, the test is the drift alarm. `VoiceToolSchema.kt` generates the Talk-mode tools from the same registry (chat and voice cannot drift). `sync/CallsClient.kt` (`call_requests` list / get / forTask / create / update / cancel + `call-outcome` invoke; writes go direct, never via the outbox) backs `request_call` / `cancel_call` / `update_call` / `get_calls`.
3. **Moments · patterns · brief (A1b, pure).** `core/logic/Moments.kt` (9 candidates, priority → salience → id, tone copy), `Patterns.kt` (`derivePatterns`, `patternGaps`), `Brief.kt` (`composeBrief`, `probeQuestion`) — tests translated from the web `*.test.ts` + the Swift twins; strict date validation added by the verifier so an impossible date can't crash the card.
4. **Surfaces (A2).** **`ui/today/GatewayCard.kt` replaces the quiet-nudge block on Today** (the recap stays): brief line, ONE moment with actions (`carry_tasks` / `schedule` / `create_task` / `chat` / `dismiss`) that **write through the same paths as the tools**, a composer that hands the message to `AssistantSheet`, chips, mic → Voice mode; `ui/assistant/GatewayLogic.kt` + `GatewayHosts.kt` memoise `composeBrief + pickMoment` on a minute-keyed input (iOS `GatewayMemo`); onboarding struggles reach the context and the moments engine; the **AI kill-switch (Settings → Interface → AI Assistant) hides the whole card**, voice included. **Interview**: `core/logic/Interview.kt` (7 questions verbatim from the web `interview.tsx`, `splitPeople`, step / auto-done / resume / skip rules) + `ui/assistant/InterviewFlow.kt` / `InterviewSheet.kt` — rituals picker, resumable, **auto-opens only after the server flag is applied** (never re-asks a user who finished on web/iOS), auto-completes at one fact. **`ui/assistant/FactsPanel.kt` = Settings › Memory ("What Unstuck knows")**: list with dates, edit-in-place, forget one, forget all (confirmed), rituals toggles, disclosure copy verbatim from iOS. **Voice**: `voiceOpening()` interview branch + profile lines, the **integrity guard** in `VoiceRealtimeClient.kt` (a claimed action with no tool call in that response gets ONE hidden corrective, capped per session, never loops — the bookkeeping is pure and unit-tested), tool-call dedupe by call id, instructions built off the main thread.
5. **Calls status (C0 done, C1 pending).** Server side (web repo: migration 057 + `send-call`) now **dispatches Android an FCM high-priority data push with `kind=call`** (callId, taskId, title, notes JSON, scheduledAt, deepLink `unstuck://call/<callId>`; short TTL so a late ring never arrives after the stale window). `surface/Push.kt` parses it (`CallPush`, pure + tested) and **`CallRing` posts a ring notification** synchronously inside the FCM window — REMINDERS channel (IMPORTANCE_HIGH + sound), `CATEGORY_CALL`, lock-screen-private with "Unlock to read", tap → the deep link, appended to `NotificationLog`. **NOT built yet = phase C1**: full-screen intent + `IncomingCallActivity` (Answer / Decline / Snooze), the `CallCoordinator` state machine (outside hours / focus live / stale anchor / one outcome per call), `CallVoiceService` (FGS microphone) speaking the call script, the durable outcome queue, missed / busy / outside-hours notifications, "Call me about this" in the task editor, Calls settings — plan §3 C1–C3.

**Module ownership rule (the port's one hard constraint):** pure logic lives in **`:core`** with **no Android imports** (JVM-only, JUnit, `-Duser.timezone=UTC`) — `AssistantHarness`, `AssistantTime`, `ReplyPolish`, `Moments`, `Patterns`, `Brief`, `Interview`, `ProfileFacts`, `PAPrefs`, `InterviewFlag` are all pure and mirror the web Vitest + iOS XCTest cases; wire format + supabase-kt live in `:sync`; Compose, services and `SharedPreferences` in `:app`. If a port needs `android.*`, it goes in `:app` behind an interface that `:core` defines (`AssistantAsk` / `ToolRunner` for the harness, `AssistantApi` for the executor).

**v0.4.47 (2026-06-11) — 3 reported-bug fixes from real testing + Insights, shipped to the 2 testers (also ported to web [live on Cloudflare] + iOS code [committed; iOS NOT on TestFlight yet — see the iOS repo handover]):**
1. **Recurring tasks no longer flood "All".** A "every Friday" task used to appear 4–5× in All. Now templates show only under the **Recurring** filter, and each occurrence appears in **Today** on its day (the SINGLE next per series in Upcoming) — All/Backlog/Later/Completed use non-templates only. (`core/logic/VisibleTasks.kt` rewrite; tick/skip a single day without touching the series, as before.)
2. **Cross-device sync fixed.** Completing a task on web didn't reflect on the phone: a stale offline `tasks` upsert op in the outbox re-pushed and clobbered the web's `done=true`, which the next hydrate then pulled back as not-done. Fix: **`Hydrator.pruneStaleTaskOps()`** drops queued task ops the server already supersedes (strictly newer `updatedAt`), run BEFORE flush in `SyncCoordinator.syncNow()` + the sign-in path. (Android's `SyncGateway.upsert` is a whole-row `onConflict=id` with no per-field guard — same clobber class as web; the prune is the load-bearing fix.) Genuine offline edits (op newer than server) still flush.
3. **Start-Next hero card usable again.** `core/logic/PickStartNext.kt` `pickTodayHero()`: next **scheduled** task today (by start time) → else **lowest-friction** (shortest estimate) among today's tasks → else **nil + a tappable backlog pointer** ("Nothing scheduled today / N in your backlog →"), never pulling a random backlog task. `TodayScreen.kt` `BacklogPointerHero`.
4. **Insights shows real numbers from the FIRST session.** The Reflection screen hid everything behind a 5-session threshold (testers saw only dashes). UI gate now `sessions.isNotEmpty()`; estimate-hit % gates on calibration dots; the qualitative "Worth noticing" prose keeps a small floor (`REAL_DATA_THRESHOLD` 5→3). Empty state reworded. (`ui/insights/InsightsScreen.kt` + `core/logic/Analytics.kt`.)

**Major work since v0.4.24 (this block + the v0.5.0 block above are authoritative; the sections below are historical):**
- **Voice realtime (v0.4.36)** — "Talk" mode (Qwen-Omni via a Cloudflare Worker proxy → DashScope); comm-mode + route-aware duplex barge-in.
- **Agentic assistant** — bubble = Assistant chat + Feedback; qwen via the stateless `assistant` edge fn + client-side tool execution; on-device $0 voice. **Superseded in v0.5.0** by the gateway port above (57 tools, memory, harness in `:core`).
- **Notifications fully live + refined.** Per-task reminders, morning brief, session recap, paused check-in — all deployed; secrets moved to **Supabase Vault** (migration 031). **Notification "drill-down" (2026-06-10):** Calm now keeps the essentials (pre-task reminders + session recap are on at every level; Calm only mutes morning brief + paused check-in) — `SettingsStore.NotificationLevel`; **quiet hours removed** (migration 032 dropped the unused columns); recap + paused-checkin **also work on web now**; the in-app bell surfaces server `notification_queue` cards (cross-device recaps + collection events).
- **Forgot-password recovery FIXED (v0.4.42).** PKCE recovery deep links arrive as `unstuck://auth-callback?code=…` with no `type=recovery`, so they were indistinguishable from magic-link/OAuth and just logged the user in. Now MainActivity arms a one-shot probe + AppViewModel classifies the exchanged session by its token `amr` (recovery → SetNewPasswordScreen). The 'check your email' confirmation is a pronounced banner.
- **RECURRING-TASK REWORK (v0.4.43–v0.4.46).** A repeating task is a hidden **template** shown only under a new **"Recurring"** tab; each occurrence (today + upcoming) is an independent one-day task in Today/All/Upcoming that you complete or **"Skip today"** without touching the series. Model: per-occurrence state on `cal_blocks` (migration 033 adds `done`/`skipped`/`completed_at`, additive, **no backfill**). `core/logic/Occurrences.kt` projects occurrence blocks into synthetic one-day rows (id = block id); complete/skip/focus route to the cal_block, never the template (focus accrues on the template via `LiveSession.occurrenceBlockId`). Verified end-to-end on the emulator. Two on-device-caught bugs fixed: occurrence detail wouldn't open (MainScaffold `Route.Detail` → `taskForBlock` fallback), and focusing an occurrence could mint a phantom occurrence-as-task (`finishFocus` now resolves the template robustly + excludes templates from the schedule tray / area counts / slip nudges).
- Earlier bug sweeps: v0.4.40 cold-start login flash; v0.4.41 phantom reminder for a deleted task; large 49-bug (v0.4.23) + 107-bug (v0.4.26–28) sweeps (`audit/`).

**Recurring-task gotchas:** a `cal_block` is now an occurrence carrying its own done/skipped state — surfaces that render blocks must hide `skipped` ones and read done off the block; surfaces that aggregate "open tasks" (schedule tray, area counts, slip nudges, pickStartNext) must exclude templates (`recurrence != null`); never `upsertTask` a row whose id is a block id (would mint a phantom occurrence-as-task — `finishFocus` guards this).

**Remaining to fully close out (verification + launch decision, NOT building):**
1. **Google OAuth app verification** (only for public/non-test Google accounts) — Calendar is a sensitive scope; testing mode caps at 100 users + shows the "unverified app" screen. Redirect already registered.
2. **Google Play launch** (only if going public) — would need an AAB + a Play upload key (we ship APK on the dev keystore today) + Play Console listing. NOT needed for the Firebase beta.
3. **AI gateway A3 + calls C1–C3** — adversarial parity sweep against web/iOS, on-device voice check, Firebase build 84 to the testers; then the client call path (see the v0.5.0 block above + `unstuck/docs/android-gateway-plan.md` §3).

## Calendar + Notifications (v0.3.x–v0.4.x, 2026-05-31)

**Two-way Google Calendar sync — shipped & working (v0.3.5–v0.3.8).** Connect uses the HTTPS bounce page `https://unstuck-602.pages.dev/calendar-callback` (registered on the Google Cloud **Web** OAuth client — custom schemes are rejected). Tasks push to the user's **primary** Google calendar (selectedCalendarIds can be read-only → 403); pull sends **RFC3339** timestamps (bare dates → 0 events). Root fixes along the way: ktor `contentType(application/json)` on every `functions.invoke{setBody}`; kotlinx omits default values (made `provider` explicit); snake_case `/connections` DTO.

**Notifications — shipped (v0.4.0–v0.4.5) + backend deployed.** Pre-task reminders (Settings→Focus default + per-task override on New Task; exact AlarmManager, boot reschedule); live focus notification ("FOCUSING · LIVE" + Pause/Capture ↔ amber "Did you step away?" + Resume/Snooze/End); paused-too-long WorkManager check-in; session-end recap (+ Today card); morning brief (server cron, live); in-app slipping/follow-up nudges; per-purpose channels + lock-screen privacy; notification deep-links + Capture route through `MainActivity`→`AppGraph.pendingDeepLink`→`MainScaffold`. New files under `app/.../surface/`: NotificationChannels, NotificationRenderer, NotificationActionReceiver, FocusCommands, PausedCheckinScheduler, ReminderScheduler, ReminderReceiver (boot), ScheduleCommands.

**Start-now notifications + 3 intensity levels (v0.4.4, git e11f523, on Firebase).** Per user feedback. (1) `ReminderScheduler` now keeps up to THREE exact alarms per task block — LEAD (start−lead, A1, all levels), ATSTART (start, A2, Balanced+), DRIFTED (start+10m, A4, Coach) — each with its own intent tag (`lead:`/`atstart:`/`drifted:$blockId`); verified on-device via `dumpsys alarm`. The ATSTART/DRIFTED notification (`NotificationRenderer.postTaskStarting`) has two shade actions that work app-closed: **Start** (`unstuck://focus/{id}` activity PendingIntent → `MainScaffold` `vm.startFocus` + `focusTask`) and **Reschedule** (broadcast → `NotificationActionReceiver.ACTION_RESCHEDULE` → `ScheduleCommands.rescheduleToNextSlot`: next free slot today via `findFreeSlotsForDate` else +1h, bumpMoveCount, re-arm, shade confirmation — no app open). `ReminderReceiver` re-checks done/already-focusing before posting (goAsync). (2) `NotificationLevel { CALM, BALANCED(default), COACH }` (`SettingsStore`) is the single source of truth (`atStart`/`drifted`/`pausedCheckin`/`morningBrief`/`nudges` booleans), surfaced as a `SegRow` + live blurb in Settings→Focus. Gates `ReminderScheduler` (atstart/drifted), `PausedCheckinScheduler.arm`, `AppViewModel.nudges`, and is synced to the server (`PreferencesClient.setNotificationLevel` → `notification_preferences.morning_brief_enabled`/`paused_checkin_enabled`, owner-self RLS) so the cron brief honours Calm. **NOTE: live alarm-fire + Start/Reschedule taps not yet exercised on-device** (alarm SCHEDULING + level gating are verified via dumpsys; the render reuses the v0.4.3-proven path) — confirm by scheduling a task ~2 min out.

**UI batch (v0.4.5, git 22f20c6, on Firebase).** Five on-device-feedback fixes, all verified on the emulator: (1) **Sticky headers** — `TodayScreen`/`TasksScreen` split into a fixed header (avatar/greeting + filter pills / title + tabs) above a weighted scrolling `LazyColumn`; only the list scrolls now. (2) **Bottom-anchored collection add** — the add field in `CollectionDetailScreen` moved below the items (items already appended to the bottom; web does too — `[...c.items, item]`). (3) **Custom time** — `NewTaskSheet` Time row gains a "Custom…" chip → Material3 `TimePicker` dialog (the prefilled chip is now editable; was previously a dead `{}` onClick). (4) **Account menu** — `AvatarMenu` is now a top-right `Popup` (alignment TopEnd, ~64dp down) anchored by the avatar, not a `ModalBottomSheet`. (5) **Notification center** — a bell in the AppBar + Today header (unread dot) opens `ui/notifications/NotificationCenterScreen` (Upcoming reminders computed live from blocks + Recent from `surface/NotificationLog`, a SharedPreferences-backed `StateFlow` the renderer appends to on every post). `AppBar` gained `onNotifications`/`notifUnread`; `Route.Notifications` + `openNotifs` (markSeen + push) in `MainScaffold`. **Caught + fixed in testing:** opening the center crashed on a duplicate `LazyColumn` key (test data has identical (task,time) blocks) → `distinctBy { taskId to at }`.

**Fix batch (v0.4.6, git 801bc5b, on Firebase — all 3 testers).** Investigated via a 4-agent workflow, all verified on-device: (1) **Nudge dismissal now persists** — `_dismissedNudges` was in-memory only (a retained ViewModel field) so a ✕'d capture nudge reappeared after relaunch; now persisted via `SettingsStore.load/saveDismissedNudges` (StringSet). (2) **Calendar day-grid title clip** — short blocks (height floored to 22dp, 6dp padding, top-aligned) clipped the 12sp title into the rounded clip; fixed with `Alignment.CenterStart` + `vertical = 2.dp` padding + min height 24dp (`DayGrid.kt`). (3) **Tasks hamburger removed** (`leading = Leading.NONE`) — the inline area pills already cover it; Calendar/Collections keep theirs. (4) **Branded launcher icon** — was an off-brand placeholder; now the coral Orbit (`#E89077`) on dark ink (`#1A1C26`) via `ic_launcher_foreground`/`_background` recolor + a single-colour `ic_launcher_foreground_mono` for the themed-icon layer. NOTE: the App Distribution `testers` default now includes all 3 (was owner-only on v0.4.3–v0.4.5; fixed commit 4253a30).

**Today layout + exact-logo icon (v0.4.7–v0.4.9, on Firebase).** Per feedback: (v0.4.7) the v0.4.6 icon recolored the ring coral, which didn't match the logo — reverted to the real Orbit (ink ring + anchor, coral satellite) on the light tile. (v0.4.8) Today: the **Start-Next banner sits above the filter pills**, and the pills are a `LazyColumn` **stickyHeader** (`@OptIn(ExperimentalFoundationApi)`) — banner scrolls, pills stick to the top (the owner preferred this over pinning both). (v0.4.9) **App icon uses the exact `~/Downloads/mark.svg` geometry** scaled into the 108 adaptive viewport (ink ring/anchor `#1A1C26`, coral satellite `#E89077`) + matching mono layer; the same `mark.svg` is the brand mark used for the web email logo. The web prod launch (unstucknow.io) + ZeptoMail email + Supabase auth templates are tracked in the WEB repo (`unstuck/docs/PRE-LAUNCH-CHECKLIST.md`) and the `web-prod-launch` memory.

**Backend (deployed to Unstuck `uaxfteluwctrlgwmmfzi`):** `register-push-token`, `send-session-recap`, `send-paused-checkin`, `send-morning-brief` deployed (data-only FCM payloads + design-voice copy); `_shared/fcm.ts` gained `dataOnly`. **Morning-brief cron live** (`morning-brief-dispatch` + `wake-window-calibration`, every 15 min). `CRON_SECRET` rotated + inlined into `dispatch_morning_briefs` (the manual SQL keeps placeholders — never commit the secret). The local `supabase` CLI is already authed to this project — `functions deploy --project-ref uaxfteluwctrlgwmmfzi` + `db query --linked` (no DB password).

**Review pass (2026-05-31):** a multi-agent adversarial review (bugs + security) raised 18, confirmed 10, all fixed — notably: notification deep-links/Capture now actually route (were dead); morning-brief cron double-dispatch (HH:MM vs HH:MM:SS) → minutes-since-midnight half-open window; `try_consume_push_budget` locked to `service_role` only (was public/anon/authenticated); resumed-chronometer base; heavy/light from full open-count; high-priority data push.

**Self-test → push-registration fix (v0.4.3, git 8272e45).** On-device testing of the v0.4.2 build found that **no server push could ever reach Android** (registration was 100% broken). Two compounding bugs in `sync/Clients.kt` `PushClient`: (1) `register-push-token` was throwing the ktor *"Fail to prepare request body … Content-Type: null"* gotcha (the distributed APK predated the `contentType()` fix now in source) — every register failed silently (`runCatching` swallows it, no log); (2) `RegisterBody.platform` had a default `= "android"`, and **kotlinx omits default-valued fields** → `platform` never serialized → server (`register-push-token` index.ts:47 `body.platform === 'android' ? … : 'ios'`) **mislabeled the row `ios`** → `send-morning-brief` filters `platform === 'android'` for FCM → never routed. Fix: `platform` is now a required field set explicitly at the call site. **Verified live:** the `device_tokens` row flipped `ios → android` (fcm_token intact), and a real data-only FCM push (Firebase Admin SA) rendered correctly on the emulator — `dumpsys` confirmed channel `unstuck_daily`, id 2003, coral, `VISIBILITY_PRIVATE` + the public "Unlock to read" version, deep-link `contentIntent`. (This is the **3rd time** kotlinx default-omission has bitten us — after `provider` and the calendar server fields. **Never give a `@Serializable` field a default if the server depends on it.**) v0.4.3 is committed + pushed to git but **NOT yet on Firebase** — the App Distribution upload was left for the owner (`./gradlew :app:appDistributionUploadRelease`).

**Known refinements (not blocking):** deep-links land on Today but don't yet scroll to the recap/brief detail; the "Capture" action opens the live focus screen (not a standalone capture sheet); per-task reminder lead is device-local (not synced); server `notification_preferences` toggles aren't surfaced in the Android settings UI yet.

**✅ FIXED in v0.4.23 — poison-pill outbox entry (was found 2026-05-31).** An orphaned cal_block referencing a task absent server-side (`cal_blocks_task_id_fkey`) retried every flush cycle forever (log spam + redundant network; sync wasn't stalled thanks to the `progressed` flag). The fix took option (b) without a Room migration: `OutboxFlusher` keeps an in-memory `failCounts` map and **drops a row after `FAIL_CAP=5` failures** (dead-letter), and a `blockedRows` set skips that row's LATER ops once one fails so per-row LWW is preserved. No silent local-change loss in practice (5 attempts across flush cycles before drop).

## UI redesign → Android Mockups (in progress)

The app is being reconciled to the official **Android Mockups** (Claude-Design
bundle `unstuck-v2`; `Android Mockups.html` = 39 screens, source of truth for
look) and to the **web app** for behavior. Plan: `~/.claude/plans/streamed-juggling-book.md`.

**Done + on-device-verified:** the **design system** — exact mockup tokens (soft
coral `#E89077`, ink `#1A1C26`, …), M3 chrome (`AppBar`, `BottomNavBar` filled
pill, 56dp rounded-square `CoralFab`, `MdField`/`MdToggle`/`MdSegment`,
`FilterPill`, `StatCard`, `ItemRow`, `ColorChip`, `Orbit` mark, sheets). The
**auth screen renders pixel-faithful to mockup 16** (Orbit, serif headline,
notched outlined fields, dark-ink submit).

**Reconciled (compile-green, built into the APK):** MainScaffold (M3 nav + coral
FAB + overlay route stack), Today (gradient Start-Next hero, filter pills, paused
ring, empty hero), Tasks (app bar + tabs), full-screen Task detail, New-task +
WHEN, Focus (dark ring + Orbit + 3 treatments) + Capture + Reflect, Calendar
Day/Week/Month, Collections 2-col grid + detail, Insights report + deep,
Settings hub + 6 subpages + Areas, Onboarding 4-step, Command palette
full-screen, Avatar menu. `:design` token test added (185+ tests still green).

**Audit + fix pass (v0.2.1, 2026-05-29):** a 28-agent workflow audited every
screen/sheet/component against the mockup JSX + web behavior (37 high / 61 med /
54 low verified findings → `audit-ui-findings.md`). Fixed this pass: system **back
button** (overlays/focus/tab), **status- & nav-bar insets** (edge-to-edge no longer
collides), **completed-today tasks now show on Today** (green check + strikethrough),
**running-vs-paused live card** with a real **progress ring**, focus **"Mark complete"**
action + **"← Out" no longer discards** the session, calendar **NOW line** + **life-area
block colors**, **per-tag capture colors**, **real signed-in account** in avatar +
Settings, **collections search**, new-task **no longer double-books** (passes real
blocks), and the **priority picker removed** (web/DB has no priority UI — per the
"web wins over mockup" rule). Build green, 185 module tests green.

**Remaining (tracked in `audit-ui-findings.md`):** larger build-outs not yet done —
Insights report/deep charts (weekday bars, calibration scatter, interruption bins,
heatmap headers) + Week/Month range toggle; Calendar **Week 7-col grid** + sidebar
stats + **Month grid header/nav**; Collections **new-collection / item pin·edit·remove
/ recolor·rename·delete**; **Settings persistence** (DataStore + reactive theme/
density/accent/focus/sound/a11y); Task-detail **inline edit + recurrence + capture
promote/discard**; new-task **date picker / time-slot chips / conflict warning**;
auth **surface card**; **OS-surface pixel-match** (custom RemoteViews live-focus
notification + richer Glance widget, mockups 19–21). Full **post-auth on-device
screenshot pass** is blocked on the emulator (Supabase `mailer_autoconfirm=false`,
so no session without a real sign-in) — verify on a signed-in device instead.

**Web-parity pass (2026-05-29, on emulator — NOT yet on Firebase):** Google logo
on the auth button; **settings now work** (SettingsStore + reactive `UnstuckTheme`
off theme/accent/density/larger-type — theme flips live; focus/sound/a11y persist);
**Tasks** tabs reordered to the web (Backlog·All·Today·Upcoming·Later·Completed,
default Today) + area filter; the **hamburger menu now shows Areas to filter** and
the **avatar holds account/Settings** on every screen; **new-task sheet** rebuilt to
the web create modal (WHEN incl. pick-date, free-slot chips + conflict warning,
capture drafts, no priority); **Lists → Collections**; **Calendar** Week = 7-col grid
+ rollup, Month = calendar grid + weekday headers + month nav. Build + 185 tests green.

**Web-parity batches → Firebase (v0.3.0–0.3.2, testers ahmad@/justtesting6363@/zyzkazaure@):**
Two 16-area agent sweeps (web = source of truth, mockup = cue only) drove this — full
gap+bug lists in `audit-web-parity-gaps.md` + `audit-sweep2.md`. Landed: Tags end-to-end
(picker/filter/manage + cascade rename/delete, dedup + case-insensitive), editable Task
detail (name/first-action/estimate-incl-custom/area/repeat/tags + cascade delete), capture
promote/discard/add, focus pause-reasons + Save-for-later, Collections CRUD, Account
(password+reauth/export/delete-with-email-confirm), Insights charts + Week/Month/All +
real-data threshold, Google Calendar **connect+pull** (push still TODO), colored task tabs
+ area pills, Today backlog (calendar-day age chips), 24h day grid + real Week hour-grid,
reactive theme/accent/density, area rename/recolor. **v0.3.2 bug sweep fixed:** external
Google `g_` blocks no longer enqueued (was stalling the outbox); `scheduleTask` reschedules
in place (no dup blocks) + only bumps moveCount on real moves (slip detector was inflated);
re-opening a paused focus stays paused; onboarding seeds picked areas once + persists
treatment.

**Known remaining (next batch, in `audit-sweep2.md`):** Google Calendar **push**
(insert/patch/delete local blocks) + `listConnections` snake_case decode; focus **sounds**
(chimes/bell) + **ambient** wiring + **overrun-extend** flow + soft-overrun grace at runtime;
command-palette completeness (areas/capture-jump/route shortcuts); account email-verification
flows; onboarding struggles + first-action steps; recurrence **until** date; calendar
**block-time** UI + fuller block-edit sheet; a11y toggles' runtime effects.

## Status snapshot

- **P0 — Foundation: DONE.** Gradle multi-module scaffold (wrapper 8.9, AGP
  8.7.3, version catalog), repo on `main` → `github.com/btambaya/Unstuck_Android.git`.
  `:core` fully ported from the web `lib/*` + iOS `UnstuckCore`, **157 JUnit
  tests green** (`./gradlew :core:test`). CI runs `:core:test`.

`:core` ports (each mirrors the web Vitest + iOS XCTest cases):
- Models + enums (`TaskItem`, `CalBlock`, `Session`, `Capture`, `ReasonLog`,
  `CalendarConnection`, `ExternalEvent`, `ItemCollection`/`CollectionItem`,
  `TagRow`, `LifeArea`, `LiveSession`, `Recurrence` sealed + custom serializer).
- `Time`/`Clock` (java.time, `ZoneId.systemDefault()` = JS Date LOCAL semantics).
- `Uuid`, `CalBlockKind`, `TaskBucket`, `VisibleTasks` (+slip), `PickStartNext`,
  `Recurrence` (materialise/regen/label), `FreeSlots`, `FocusTimer`, `Analytics`
  (H1–H7 + insights), `AuthErrors`, `GoogleSyncMapping`, `TaskMutations`.

## Roadmap (powering through P0→P7)

1. **P0 — Foundation** ✅
2. **`:design`** ✅ — oklch→sRGB converter (7 tests), brand `UnstuckColors` light/dark,
   `UnstuckTheme` (CompositionLocal + M3), type scale, components (UButton/Chip/Card/
   SectionLabel/AreaDot). Builds to AAR.
2b. **P1 — `:data` + `:sync`** ✅
   - `:data` — Room single `records` table (JSON blob per row) + `outbox` + `live_session`;
     `LocalStore` typed Flows; **6 Robolectric round-trip tests** (incl. JSONB shape +
     external `g_` preservation + outbox FIFO).
   - `:sync` — `DbRowCodec` (PostgREST boundary, **10 tests**: snake_case top-level /
     camelCase JSONB / explicit-null clear / duration_sec omit / uuid-or-null / round-trip),
     `SyncDecision` (**5 tests**), `SupabaseClientProvider` (PKCE, `unstuck://auth-callback`),
     `AuthService`, `SyncGateway`, `Hydrator`, `WriteThrough`, `OutboxFlusher`,
     `RealtimeMirror`, `CalendarClient`, `PushClient` (FCM), `NotificationsClient`,
     `PreferencesClient`, `SyncCoordinator` (sessionStatus → wipe-rule → flush → hydrate →
     subscribe). Compiles against supabase-kt 3.0.3.

   Total tests green so far: **157 (:core) + 7 (:design) + 6 (:data) + 15 (:sync) = 185.**
3. **P1b — backend FCM delta** (in the `unstuck` repo: migration 018 `fcm_token`,
   `_shared/fcm.ts`, platform branches in the senders, deploy)
4. **P2 — Tasks + Today** ✅ — Today (Start Next / Up Next / in-progress resume),
   Tasks (visibleTasks filter views + area + slip toggle + create/edit + schedule-next-free-slot).
5. **P3 — Focus** ✅ (core) — full-screen timer on FocusTimer + 1s ticker, 3 treatments,
   pause reasons → reason_logs, mid-session captures, extend-on-overrun. *Pending: FocusTimerService
   foreground notification + ambient audio (surfaces phase).*
6. **P4 — Calendar** ✅ (agenda) — upcoming blocks grouped by day, unschedule, external g_ blocks,
   schedule via free-slot. *Pending: drag-to-schedule day grid + Google connect UI (Custom Tabs).*
7. **P5 — Collections / Tags & Areas** ✅ — lists with items (add/toggle/delete), areas + tags CRUD in Settings.
8. **P6 — Insights / Settings / Auth** ✅ — Insights (topInsights + stats + pause anatomy + slipping),
   Settings (areas/tags/sync/sign-out), Auth (email/password + sign-up + magic link + Google).
   *Pending: onboarding struggles flow, command palette, recurrence editor UI (model + label done).*

**`:app` builds — `./gradlew :app:assembleDebug` → 20 MB debug APK.** Manual DI
(`AppGraph`) instead of Hilt (fewer codegen moving parts). Central `AppViewModel`
exposes every collection as a StateFlow off the Room store + all write actions
through the sync engine. Bottom nav Today · Tasks · [+] · Calendar · Lists, with
Focus / task-detail / settings as overlays.
9. **Surfaces** ✅ — `StartNextSnapshot` (DataStore) → Glance `StartNextWidget`
   (updates when the recommendation changes), `FocusTimerService` (foreground
   chronometer notification, started/stopped by FocusScreen), `UnstuckMessagingService`
   + `registerFcmToken` (FCM — dormant until google-services.json), `SyncWorker`
   (30-min periodic flush+hydrate via `SyncCoordinator.syncNow()`),
   POST_NOTIFICATIONS runtime request. Widget/snapshot live in :app (no :shared module).
10. **P6/P7 polish** — ✅ recurrence editor (`RecurrenceEditor` in new/edit
    sheets; `scheduleTask` materialises the horizon series), ✅ first-run
    onboarding (struggles → user_preferences + seeds canonical life areas),
    ✅ command palette (search tasks + actions, from the Today header),
    ✅ adaptive launcher icon (Orbit mark), ✅ dark via
    `UnstuckTheme(isSystemInDarkTheme())`, ✅ **bundled brand fonts** (Geist
    variable + Instrument Serif + IBM Plex Mono in `:design/res/font`, wired in
    Type.kt — verified rendering on-device), ✅ **ambient focus audio**
    (`res/raw/ambient_focus.wav` brown-noise loop + `AmbientAudio` MediaPlayer,
    speaker toggle in Focus), ✅ **drag-to-schedule day grid** (`DayGrid` —
    long-press a tray task, drop on an hour slot → schedules; Calendar tab has
    an Agenda/Grid toggle), ✅ **release signing** (`signingConfigs.release`
    reads gitignored `keystore.properties`; `./gradlew :app:assembleRelease`
    produces a signed APK — verified v1+v2).

### Release builds
`keystore.properties` + `unstuck-release.keystore` exist locally (gitignored;
dev passphrase `unstuck-dev`). `./gradlew :app:assembleRelease` → signed
`app/build/outputs/apk/release/app-release.apk`. For Play, swap in your own
upload key (enable Play App Signing): regenerate the keystore + update
`keystore.properties`.

**Validated on-device** (Pixel_Fold emulator, API 35): installs + launches with
no crashes; Supabase client initialises (`SupabaseClient created!`); Room + DI +
Compose theme + oklch brand colours render — the auth screen shows the serif
wordmark + coralDeep CTA. With `secrets.properties` (anon key) it reaches the
live auth screen; without it, the setup screen.

## Critical gotchas (inherited from the iOS build)

- **DbRowCodec:** top-level columns are snake_case but JSONB internals are
  camelCase (`recurrence.daysOfWeek`). Do NOT apply a global SnakeCase naming
  strategy — `@SerialName` per top-level row field, leave nested camelCase.
- **Null clearing:** kotlinx `explicitNulls=true` sends explicit `null` so an
  upsert clears a removed field (matches the web `?? null`); `duration_sec` is
  the one omit-when-nil (default + `encodeDefaults=false`).
- **Stable sort:** Kotlin's `sortedWith` IS stable (unlike Swift) — open-before-
  done partition is still explicit for parity.
- **Dates:** java.time + `ZoneId.systemDefault()` reproduce JS Date LOCAL math;
  ISO strings compare lexicographically; tests run `TZ=UTC`.
- **`:core` stays Android-free:** the assistant engine and the gateway logic are
  pure JVM so the web + iOS test cases port verbatim — no `android.*`, no
  supabase-kt, no Compose in `:core`. Platform needs go behind an interface
  `:core` defines and `:app` implements.
- **Tool result strings are contract text:** the server prompt reads them. Never
  reword an `ok:`/`error:` result by hand — re-vendor
  `docs/assistant-tool-contract.md` from the web repo (`npm run tool-contract`);
  `ContractDiffTest` fails on any drift in names, args, prefixes or the read-only set.

## Manual prerequisites (owner)

1. ✅ **DONE** — Firebase/FCM configured: project `unstuck-46e8c`, Android app
   (`tech.csalliance.unstuck`), `app/google-services.json` in place (gitignored;
   plugin auto-applies), Supabase secrets `FCM_PROJECT_ID` + `FCM_SERVICE_ACCOUNT`
   set. FCM is live end-to-end (token registers on sign-in; senders route
   Android→FCM / iOS→APNs).
2. ✅ `SUPABASE_ANON_KEY` → `secrets.properties` (gitignored) — set for local runs.
3. ✅ Google OAuth `/calendar-callback` redirect registered (shared w/ iOS).
4. **Remaining (optional, store only):** Play upload key — release builds sign
   with the gitignored dev keystore today; swap in your own upload key in
   `keystore.properties` + Play Console for distribution.
