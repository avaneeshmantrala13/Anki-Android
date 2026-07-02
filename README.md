<!-- BEGIN BrainLift fork additions -->
## BrainLift (Exam P) — fork additions

**BrainLift** is a deterministic (no AI) exam-prep layer added to Anki/AnkiDroid
*from the inside*. It keeps Anki's FSRS scheduler, collection, review loop, and
sync, and adds an exam readiness model: three **separate** measurements — Memory
(FSRS recall), Performance (a fixed author-written diagnostic), and Readiness (a
transparent blend with an explicit give-up rule) — plus official-syllabus topic
coverage and a rule-based study plan. Every number comes from transparent rules.

- **Chosen exam:** **SOA Exam P** (Probability). Readiness is reported on the
  conventional 0–10 scale (6 = pass).
- **Shared logic, honestly:** desktop and mobile share Anki's real Rust FSRS
  scheduler and the collection/sync layer. The `TopicMastery` Rust RPC runs on
  the **desktop** fork; **this AnkiDroid fork** links the stock Anki backend and
  reimplements the same deterministic coverage/measurement aggregation in Kotlin
  (`AnkiDroid/src/main/java/com/ichi2/anki/brainlift/BrainLiftEngine.kt`) with
  identical formulas, thresholds, and config shapes, so results match desktop for
  the same collection. Building the forked backend into AnkiDroid to call
  `TopicMastery` directly is documented future work.

### Optional AI features (opt-in; the three scores work with AI OFF)

Two AI features sit on top of the deterministic core, behind a master toggle
(`brainlift_ai_enabled`, default OFF). **With AI off, Memory / Performance /
Readiness and both features still function** via a deterministic fallback. The
formulas are specified once in the desktop repo's `BRAINLIFT_AI_SPEC.md` and
mirrored here in Kotlin:

- **Metacognitive calibration** (`BrainLiftCalibration.kt`): self-rate confidence
  on 15 cards, answer 15 AI-generated analog MCQs (each recording its **source
  card id + text** for traceability), then compute deviation, calibration
  accuracy, gamma, and a confidence-authority multiplier that feeds the scheduler.
  Analog generation calls the OpenAI REST API directly via OkHttp
  (`BrainLiftAi.kt`); it degrades to a deterministic generator when AI is off /
  offline / rate-limited. Every generated analog passes through a **leakage gate**
  (`BrainLiftAi.generateGatedAnalog`, mirroring desktop `generate_gated_analog`):
  if an analog is near-verbatim to its source AND resolves to the same answer
  (`LEAKAGE_SIM_THRESHOLD=0.9`) it is **regenerated** up to `MAX_REGEN=3` times
  with a stronger re-parameterize instruction, then **blocked/withheld** if it
  still leaks — so the served analogs are guaranteed clean, never handing a
  student a free answer.
- **Cognitive-load / fatigue offload** (`BrainLiftFatigue.kt`, wired into
  `Reviewer.answerCardInner`): detects drain (response-time slowdown vs a slow
  personal baseline, accuracy drop, RT variability, post-error slowing;
  EWMA-smoothed) and shows a visible Snackbar banner when it eases difficulty or
  interleaves sub-topics. A TEST MODE flag fires interventions immediately.

The key is read only from `OPENAI_API_KEY` at runtime (never stored/committed).
Kotlin parity with the desktop numbers is enforced by `BrainLiftParityTest`.

### Attribution

- The mobile companion is built on **AnkiDroid** (GPL-3.0-or-later).
- The desktop counterpart is built on **Anki** (AGPL-3.0-or-later).
- Default bundled study content: **Society of Actuaries (SOA) Exam P Sample
  Questions & Solutions**, freely published by the SOA for candidates and
  reproduced for personal study (© Society of Actuaries). Classification into
  topics uses deterministic keyword rules only — no content is AI-generated.
  - Questions: https://www.soa.org/globalassets/assets/files/edu/edu-exam-p-sample-quest.pdf
  - Solutions: https://www.soa.org/globalassets/assets/files/edu/edu-exam-p-sample-sol.pdf

### Build & run

- **Mobile (this repo):** `./gradlew assemblePlayDebug`
- **Desktop (Anki fork):** `./run`

Upstream AnkiDroid's original README follows below. License files are unchanged.

<!-- END BrainLift fork additions -->

<p align="center">
<img alt="" src="docs/graphics/logos/banner_readme.png"/>
</p>

<a href="https://github.com/ankidroid/Anki-Android/releases"><img src="https://img.shields.io/github/v/release/ankidroid/Anki-Android" alt="release"/></a>
<a href="https://github.com/ankidroid/Anki-Android/actions"><img src="https://img.shields.io/github/checks-status/ankidroid/Anki-Android/main?label=build" alt="build"/></a>
<a href="https://opencollective.com/ankidroid"><img src="https://img.shields.io/opencollective/all/ankidroid" alt="Open Collective backers and sponsors"/></a>
<a href="https://github.com/ankidroid/Anki-Android/issues"><img src="https://img.shields.io/github/commit-activity/m/ankidroid/Anki-Android" alt="commit-activity"/></a>
<a href="https://github.com/ankidroid/Anki-Android/network/members"><img src="https://img.shields.io/github/forks/ankidroid/Anki-Android" alt="forks"/></a>
<a href="https://github.com/ankidroid/Anki-Android/stargazers"><img src="https://img.shields.io/github/stars/ankidroid/Anki-Android" alt="stars"/></a>
<a href="https://crowdin.com/project/ankidroid"><img src="https://badges.crowdin.net/ankidroid/localized.svg"></img></a>
<a href="https://github.com/ankidroid/Anki-Android/graphs/contributors"><img src="https://img.shields.io/github/contributors/ankidroid/Anki-Android" alt="contributors"/></a>
<a href="https://discord.gg/qjzcRTx"><img src="https://img.shields.io/discord/368267295601983490"></img></a>
<a href="https://github.com/ankidroid/Anki-Android/blob/main/COPYING"><img src="https://img.shields.io/github/license/ankidroid/Anki-Android" alt="license"/></a>

# AnkiDroid
A semi-official port of the open source [Anki](https://apps.ankiweb.net/index.html) spaced repetition flashcard system to Android. Memorize anything with AnkiDroid!

<img src="docs/graphics/logos/ankidroid_logo.png" align="right" width="40%" height="100%"></img>

### Features

<div style="display:flex;">
 
- Night mode
- Whiteboard 
- Progress widget
- Detailed statistics
- Syncing with AnkiWeb
- Write answers (optional)
- Text-to-speech integration
- More than 10,000 premade decks
- Spaced repetition (AI-optimized [FSRS algorithm](https://github.com/open-spaced-repetition))
- Supported contents: text, images, sounds, MathJax
- Add cards by intent from other applications like dictionaries

</div>

Install
---------
<div style="display:flex;">

<a href="https://play.google.com/store/apps/details?id=com.ichi2.anki&utm_source=global_co&utm_medium=prtnr&utm_content=Mar2515&utm_campaign=PartBadge&pcampaignid=MKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1">
    <img alt="Get it on Google Play" height="80"
        src="docs/graphics/logos/google-badge.png" /></a>

<a href="https://f-droid.org/repository/browse/?fdid=com.ichi2.anki">
    <img alt="Get it on F-Droid" height="80"
        src="docs/graphics/logos/f-droid-badge.png"></a>

<a href="http://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/ankidroid/Anki-Android">
    <img alt="Get it on Obtainium" height="80"
        src="https://github.com/user-attachments/assets/713d71c5-3dec-4ec4-a3f2-8d28d025a9c6"/></a>

</div>

Signing certificate fingerprint to [verify](https://developer.android.com/studio/command-line/apksigner#usage-verify) the APK:
```
SHA-256: 2071534f0f4b5e54ae952dd275d70da6e3459ee69909d2ab1b4843c4c5b21a45 
SHA-1: f24e06a3657b190a12671100402df32d7b9b3d36
```

Wiki
----
View [Wiki](https://github.com/ankidroid/Anki-Android/wiki)

Help
----
Check the [user manual](https://docs.ankidroid.org/) and the wiki for usage instructions. See the [help page](https://docs.ankidroid.org/help.html) 
for how to submit a bug report or contact a project member, etc.

Contribute
----------
You can contribute to AnkiDroid by beta testing, translating, or submitting code. 
See the [contribution wiki page](https://github.com/ankidroid/Anki-Android/wiki/Contributing) for more info.

Join Us On
----------

<a href="https://discord.gg/qjzcRTx"><img src="docs/graphics/logos/discord_logo_color.svg" height="46px"/></a>
<a href="https://www.reddit.com/r/Anki"><img src="docs/graphics/logos/reddit_logo_color.png" height="50px"/></a>
<a href="https://www.facebook.com/AnkiDroid/"><img src="docs/graphics/logos/facebook_logo_color.png" height="50px"/></a>
<a href="https://x.com/ankidroid"><img src="docs/graphics/logos/twitter_logo.png" height="50px"/></a>
<a href="https://forums.ankiweb.net/"><img src="/docs/graphics/logos/anki_forums_logo.png" height="50px"/></a>

## Credits
<!--- Do not rename this section. AnkiDroid contains a deep link to the section
header - see https://github.com/ankidroid/Anki-Android/pull/11803 --->

### Code Contributors

Thanks to these awesome code contributors who keep this project going

<a href="https://github.com/ankidroid/Anki-Android/graphs/contributors"><img src="https://opencollective.com/ankidroid/contributors.svg?width=890&button=false" /></a>

### [Sponsors](https://opencollective.com/ankidroid#sponsor)
<a href="https://opencollective.com/ankidroid#sponsor" target="_blank">
  <img alt="AnkiDroid Sponsors" src="https://opencollective.com/Ankidroid/sponsors.svg?width=890" />
</a>

### [Backers](https://opencollective.com/ankidroid#backer)

A big thank you to each of our backers 🙏
<a href="https://opencollective.com/Ankidroid#backers" target="_blank"><img width=110 src="https://opencollective.com/Ankidroid/backers/badge.svg?"></a>

<p>Your generous donations mean the world to us, and we can't express our gratitude enough. Your support fuels our mission and helps us make a real difference</p>

<a href="https://opencollective.com/Ankidroid/donate" target="_blank">
  <img alt="Donate to AnkiDroid" src="https://opencollective.com/Ankidroid/donate/button@2x.png?color=blue" width=200 />
</a>

### [Translators](https://crowdin.com/project/ankidroid/activity-stream)

Thanks to our 1400 translators, for allowing us to be available, partially or totally, in 99 languages as of July 2022.

License
-------
* [GPL-3.0 License](https://github.com/ankidroid/Anki-Android/blob/main/COPYING)
* [AGPL-3.0 License](https://github.com/ankitects/anki/blob/main/LICENSE) for some part of the back-end
* [LGPL-3.0 License](https://github.com/ankidroid/Anki-Android/blob/main/api/COPYING.LESSER) for the AnkiDroid API
