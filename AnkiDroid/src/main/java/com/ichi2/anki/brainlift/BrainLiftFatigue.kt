/*
 *  Copyright (c) 2026 BrainLift contributors
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.brainlift

import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Feature 2 — Cognitive-load / fatigue offload (Kotlin mirror of desktop
 * `anki.brainlift.fatigue`). Constants, update rule, drain formula, and the
 * intervention decision are IDENTICAL to the Python engine and
 * `BRAINLIFT_AI_SPEC.md` §5-§6, so a session detected on one platform behaves
 * the same on the other. All state persists in collection config (syncs).
 */
object BrainLiftFatigue {
    const val SESSION_KEY = "brainlift_fatigue_session"
    const val LAST_INTERVENTION_KEY = "brainlift_fatigue_last_intervention"
    const val TEST_MODE_KEY = "brainlift_fatigue_test_mode"

    // Baselines adapt SLOWLY so they represent the user's fresh/early-session
    // norm; a fast recent window is compared against them.
    private const val EWMA_ALPHA = 0.05
    private const val DRAIN_ALPHA = 0.3
    private const val WARMUP = 5
    private const val WINDOW = 8
    const val MIN_ANSWERS_BEFORE_DETECT = 6

    private const val W_SLOWDOWN = 0.40
    private const val W_ACC = 0.30
    private const val W_VAR = 0.15
    private const val W_POSTERR = 0.15

    private const val SLOWDOWN_LO = 1.0
    private const val SLOWDOWN_HI = 1.8
    private const val ACCDROP_LO = 0.0
    private const val ACCDROP_HI = 0.30
    private const val VAR_LO = 1.0
    private const val VAR_HI = 1.7
    private const val POSTERR_LO = 1.0
    private const val POSTERR_HI = 1.5

    const val DRAIN_INTERVENE = 0.60
    const val SEVERE_DRAIN = 0.80
    const val SAME_TOPIC_STREAK_LIMIT = 12
    const val INTERVENTION_COOLDOWN = 10
    private const val PROD_MIN_MINUTES = 90.0

    private const val RT_MIN = 0.2
    private const val RT_MAX = 120.0

    const val BANNER_INTERLEAVE = "Cognitive offload deemed necessary — adding variety"
    const val BANNER_EASE = "Cognitive offload — easing difficulty"
    const val TYPE_EASE = "ease_difficulty"
    const val TYPE_INTERLEAVE = "interleave"

    data class FatigueState(
        var answers: Int = 0,
        var sessionStart: Long = 0,
        var baselineRt: Double = 0.0,
        var baselineAcc: Double = 1.0,
        var rtVar: Double = 0.0,
        var recentRt: MutableList<Double> = mutableListOf(),
        var recentAcc: MutableList<Double> = mutableListOf(),
        var postErrorRt: Double = 0.0,
        var lastCorrect: Boolean = true,
        var sameTopicStreak: Int = 0,
        var currentTopic: String = "",
        var smoothedDrain: Double = 0.0,
        var answersSinceIntervention: Int = INTERVENTION_COOLDOWN,
    )

    data class FatigueDecision(
        val intervene: Boolean,
        val type: String?,
        val banner: String?,
        val drain: Double,
        val sessionMinutes: Double,
        val reason: String,
    )

    fun newSession(now: Long): FatigueState = FatigueState(sessionStart = now)

    private fun clamp(
        v: Double,
        lo: Double = 0.0,
        hi: Double = 1.0,
    ) = maxOf(lo, min(hi, v))

    private fun norm(
        x: Double,
        lo: Double,
        hi: Double,
    ): Double = if (hi <= lo) 0.0 else clamp((x - lo) / (hi - lo))

    private fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

    private fun popStd(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
    }

    /** Fold one answered question into the rolling session state (mutates + returns). */
    fun updateState(
        state: FatigueState,
        rtSeconds: Double,
        correct: Boolean,
        topicKey: String = "",
    ): FatigueState {
        val s = state
        val rt = clamp(rtSeconds, RT_MIN, RT_MAX)
        val c = if (correct) 1.0 else 0.0
        s.answers += 1
        val n = s.answers

        when {
            n == 1 -> {
                s.baselineRt = rt
                s.baselineAcc = c
                s.rtVar = 0.0
            }
            n <= WARMUP -> {
                s.baselineRt += (rt - s.baselineRt) / n
                s.baselineAcc += (c - s.baselineAcc) / n
                s.rtVar += (kotlin.math.abs(rt - s.baselineRt) - s.rtVar) / n
            }
            else -> {
                s.baselineRt = (1 - EWMA_ALPHA) * s.baselineRt + EWMA_ALPHA * rt
                s.baselineAcc = (1 - EWMA_ALPHA) * s.baselineAcc + EWMA_ALPHA * c
                s.rtVar = (1 - EWMA_ALPHA) * s.rtVar + EWMA_ALPHA * kotlin.math.abs(rt - s.baselineRt)
            }
        }

        s.recentRt.add(rt)
        while (s.recentRt.size > WINDOW) s.recentRt.removeAt(0)
        s.recentAcc.add(c)
        while (s.recentAcc.size > WINDOW) s.recentAcc.removeAt(0)

        if (!s.lastCorrect) {
            val prev = if (s.postErrorRt == 0.0) rt else s.postErrorRt
            s.postErrorRt = (1 - EWMA_ALPHA) * prev + EWMA_ALPHA * rt
        }

        if (topicKey.isNotEmpty() && topicKey == s.currentTopic) {
            s.sameTopicStreak += 1
        } else {
            s.sameTopicStreak = 1
            s.currentTopic = topicKey
        }

        s.lastCorrect = correct
        s.answersSinceIntervention += 1

        val drain = computeDrain(s)
        s.smoothedDrain = (1 - DRAIN_ALPHA) * s.smoothedDrain + DRAIN_ALPHA * drain
        return s
    }

    fun computeDrain(s: FatigueState): Double {
        val baselineRt = maxOf(s.baselineRt, RT_MIN)
        val baselineVar = maxOf(s.rtVar, RT_MIN)
        val slowdown = if (s.recentRt.isNotEmpty()) mean(s.recentRt) / baselineRt else 1.0
        val accdrop = if (s.recentAcc.isNotEmpty()) s.baselineAcc - mean(s.recentAcc) else 0.0
        val varRatio = if (s.recentRt.isNotEmpty()) popStd(s.recentRt) / baselineVar else 1.0
        val posterr = s.postErrorRt / baselineRt
        val drain =
            W_SLOWDOWN * norm(slowdown, SLOWDOWN_LO, SLOWDOWN_HI) +
                W_ACC * norm(accdrop, ACCDROP_LO, ACCDROP_HI) +
                W_VAR * norm(varRatio, VAR_LO, VAR_HI) +
                W_POSTERR * norm(posterr, POSTERR_LO, POSTERR_HI)
        return clamp(drain)
    }

    fun decide(
        state: FatigueState,
        testMode: Boolean,
        now: Long,
    ): FatigueDecision {
        val drain = round4(state.smoothedDrain)
        val sessionMinutes = round2((now - state.sessionStart) / 60.0)
        if (state.answers < MIN_ANSWERS_BEFORE_DETECT) {
            return FatigueDecision(false, null, null, drain, sessionMinutes, "warming up")
        }
        if (state.answersSinceIntervention < INTERVENTION_COOLDOWN) {
            return FatigueDecision(false, null, null, drain, sessionMinutes, "cooldown")
        }
        val timingOk = testMode || sessionMinutes >= PROD_MIN_MINUTES || state.smoothedDrain >= SEVERE_DRAIN
        if (!(timingOk && state.smoothedDrain >= DRAIN_INTERVENE)) {
            val reason = if (state.smoothedDrain < DRAIN_INTERVENE) "below threshold" else "timing gate not met"
            return FatigueDecision(false, null, null, drain, sessionMinutes, reason)
        }
        return if (state.sameTopicStreak >= SAME_TOPIC_STREAK_LIMIT) {
            FatigueDecision(true, TYPE_INTERLEAVE, BANNER_INTERLEAVE, drain, sessionMinutes, "high same-topic streak")
        } else {
            FatigueDecision(true, TYPE_EASE, BANNER_EASE, drain, sessionMinutes, "sustained drain")
        }
    }

    // --- config persistence (syncs) -----------------------------------------
    fun testMode(col: Collection): Boolean = col.config.get<Boolean>(TEST_MODE_KEY) ?: true

    fun setTestMode(
        col: Collection,
        enabled: Boolean,
    ) = col.config.set(TEST_MODE_KEY, enabled)

    fun loadSession(col: Collection): FatigueState? {
        val o = getObjectOrNull(col, SESSION_KEY) ?: return null
        return fromJson(o)
    }

    fun saveSession(
        col: Collection,
        state: FatigueState,
    ) = col.config.set(SESSION_KEY, toJson(state))

    fun resetSession(
        col: Collection,
        now: Long,
    ): FatigueState {
        val s = newSession(now)
        saveSession(col, s)
        return s
    }

    /** Fold an answer into the persisted session and decide on intervention. */
    fun recordAnswer(
        col: Collection,
        rtSeconds: Double,
        correct: Boolean,
        topicKey: String,
        now: Long,
    ): FatigueDecision {
        val state = loadSession(col) ?: newSession(now)
        updateState(state, rtSeconds, correct, topicKey)
        val decision = decide(state, testMode(col), now)
        if (decision.intervene) {
            state.answersSinceIntervention = 0
            col.config.set(
                LAST_INTERVENTION_KEY,
                JSONObject()
                    .put("type", decision.type)
                    .put("banner", decision.banner)
                    .put("drain", decision.drain)
                    .put("at", now),
            )
        }
        saveSession(col, state)
        return decision
    }

    fun lastIntervention(col: Collection): JSONObject? = getObjectOrNull(col, LAST_INTERVENTION_KEY)

    // --- JSON helpers -------------------------------------------------------
    private fun toJson(s: FatigueState): JSONObject =
        JSONObject()
            .put("answers", s.answers)
            .put("session_start", s.sessionStart)
            .put("baseline_rt", s.baselineRt)
            .put("baseline_acc", s.baselineAcc)
            .put("rt_var", s.rtVar)
            .put("recent_rt", JSONArray(s.recentRt))
            .put("recent_acc", JSONArray(s.recentAcc))
            .put("post_error_rt", s.postErrorRt)
            .put("last_correct", s.lastCorrect)
            .put("same_topic_streak", s.sameTopicStreak)
            .put("current_topic", s.currentTopic)
            .put("smoothed_drain", s.smoothedDrain)
            .put("answers_since_intervention", s.answersSinceIntervention)

    private fun fromJson(o: JSONObject): FatigueState {
        fun arr(name: String): MutableList<Double> {
            val a = o.optJSONArray(name) ?: JSONArray()
            return (0 until a.length()).map { a.getDouble(it) }.toMutableList()
        }
        return FatigueState(
            answers = o.optInt("answers", 0),
            sessionStart = o.optLong("session_start", 0),
            baselineRt = o.optDouble("baseline_rt", 0.0),
            baselineAcc = o.optDouble("baseline_acc", 1.0),
            rtVar = o.optDouble("rt_var", 0.0),
            recentRt = arr("recent_rt"),
            recentAcc = arr("recent_acc"),
            postErrorRt = o.optDouble("post_error_rt", 0.0),
            lastCorrect = o.optBoolean("last_correct", true),
            sameTopicStreak = o.optInt("same_topic_streak", 0),
            currentTopic = o.optString("current_topic", ""),
            smoothedDrain = o.optDouble("smoothed_drain", 0.0),
            answersSinceIntervention = o.optInt("answers_since_intervention", INTERVENTION_COOLDOWN),
        )
    }

    private fun getObjectOrNull(
        col: Collection,
        key: String,
    ): JSONObject? {
        val sentinel = JSONObject().put("__missing__", true)
        val o = col.config.getObject(key, sentinel)
        return if (o.optBoolean("__missing__", false)) null else o
    }

    private fun round2(v: Double) = Math.rint(v * 100.0) / 100.0

    private fun round4(v: Double) = Math.rint(v * 10000.0) / 10000.0
}
