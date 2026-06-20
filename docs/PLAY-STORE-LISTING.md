# Google Play Store — listing + submission pack

Everything needed to publish Unstuck to Google Play, mirroring the iOS App
Store submission. Fill these into the Play Console once the developer account is
verified. The app is `io.unstucknow.app` (renamed from `tech.csalliance.unstuck`
on 2026-06-12 for the Play launch — permanent once published).

## Store listing

- **App name** (≤30): `UnstuckNow` (matches the iOS App Store name)
- **Short description** (≤80): `Calm ADHD-friendly day planner — plan today, focus, and finish what you start.`
- **Full description** (≤4000):

```
Unstuck is the calm day planner for brains that don't do boring.

Plan today, not someday. Capture stray thoughts before they derail you. Start the next right task with one tap — and actually finish it, with a focus timer that understands pauses, overruns, and "wait, what was I doing?".

TODAY, NOT EVERYTHING
One screen with what matters now: your next task, today's plan, and nothing else shouting at you.

START, DON'T STALL
The Start Next card picks the task to begin. A first-step prompt shrinks scary tasks into one concrete move.

FOCUS THAT FORGIVES
A timer built for real attention: pause with a reason, get a gentle check-in when you run over, and a "you did the thing" recap when you finish.

CAPTURE EVERYTHING
Thought pops up mid-focus? Park it in the Inbox with one line and stay on task. Triage later — promote it to a task, archive it, or let it go.

REPEATS WITHOUT CLUTTER
Recurring tasks show up on the day they're due — not as thirty copies in your list.

SEE YOUR PATTERNS
Insights show when you actually focus, what interrupts you, and how your estimates compare with reality.

PLAN ON A CALENDAR
Drag tasks into your day. Connect Google Calendar to see everything in one place.

SHARE LISTS
Collections keep lists with other people — groceries, trips, projects — with live updates and gentle accountability.

TALK IT OUT
An optional assistant plans with you by chat or voice: "what should I do next?" actually gets an answer.

WIDGETS
Your next task on the Home Screen, always one glance away.

Your data syncs across Android, iPhone and the web at unstucknow.io.
```

## Graphics (Play requirements)

- **App icon** ✅ `store-assets/icon-512.png` (512×512, opaque, Orbit mark).
- **Feature graphic** ✅ `store-assets/feature-1024x500.png` (cream bg + Orbit +
  "Unstuck" wordmark + tagline).
- **Phone screenshots** ⬜ STILL TODO — 2–8, min 320px. Android has no demo-seed
  harness yet (iOS used UITEST_SEED). Options: build an Android demo boot +
  emulator capture (reusable, ~the iOS approach), or seed the demo account's
  data in Supabase and screencap the emulator. NOT required for the Internal
  testing track — only before Production/Closed rollout.

## Data safety form (mirror of the iOS App Privacy answers)

Data collected + linked to the user (NOT used for tracking/ads):
- **Personal info**: email address; name (optional).
- **App activity**: tasks/captures/sessions (user content); product interactions.
- **App info & performance**: none beyond crash basics.
- **Device/other IDs**: approximate location (country/city, derived from IP at
  sign-in — declare as "Approximate location", "Analytics").
- **Messages/audio**: assistant chat text + (if voice used) dictated audio are
  sent to a third-party AI provider (Alibaba DashScope) to generate replies —
  declare data is shared with a third party for app functionality.

Security: data encrypted in transit; users can request deletion (in-app
Settings → Account → Delete my account). All consistent with
https://unstucknow.io/privacy.

## Content rating (IARC questionnaire)

Utility/productivity app, no objectionable content → expect **Everyone / PEGI 3**.
Answer "No" to all violence/sexual/gambling/etc. questions.

## Other required fields

- **Privacy policy URL**: https://unstucknow.io/privacy
- **App category**: Productivity
- **Contact email**: privacy@unstucknow.io (or support@)
- **Target audience**: 18+ / not directed at children (matches the under-16
  clause in the privacy policy).

## Build → upload

- Signed AAB: `./gradlew :app:bundleRelease` →
  `app/build/outputs/bundle/release/app-release.aab` (signed via
  `keystore.properties` + `unstuck-release.keystore`).
- **Play App Signing**: opt in on first upload; Play re-signs with its own key
  and keeps our upload key as the upload cert. Keep `unstuck-release.keystore`
  safe regardless — it's the upload key.
- First track: **Internal testing** (instant, no review) → add Ahmad + Zubair →
  then promote to Closed/Production when ready.

## Foreground-service special-use declaration (App content)

The app declares `FOREGROUND_SERVICE_SPECIAL_USE` for `FocusTimerService`
(subtype `focus_timer`) — the live focus-session chronometer notification.
Play's "Foreground service permissions" section requires: tick **Other**, a
**description** (why it starts immediately + can't be paused/restarted — the
running timer must stay accurate), AND a **demo video link** (unlisted
YouTube or shareable Drive) showing the focus timer + its persistent
notification. The video is required to complete the declaration; special-use
enforcement bites at Production review, so Internal testing can roll out first.

## Still TODO (needs the verified Play account / console)

- [ ] Create the app in Play Console under `io.unstucknow.app`.
- [ ] Feature graphic (1024×500) + 512 icon.
- [ ] Android screenshot set.
- [ ] Fill Data safety + content rating + listing from this doc.
- [ ] Upload the AAB to Internal testing.
