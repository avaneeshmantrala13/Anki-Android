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
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Feature 2 — Cognitive-load / fatigue offload (Kotlin mirror of desktop
 * `anki.brainlift.fatigue`). Constants, update rule, drain formula, the LEARNED
 * logistic-regression model (§5.5), and the intervention decision are IDENTICAL
 * to the Python engine and `BRAINLIFT_AI_SPEC.md` §5-§6, so a session detected on
 * one platform behaves the same on the other.
 *
 * The learned model decides WHEN drain is happening (its probability replaces the
 * fixed drain threshold) whenever the master AI toggle (`brainlift_ai_enabled`)
 * is ON; with it OFF — or on any model issue — the engine falls back to the
 * deterministic drain heuristic. Both paths always produce a decision. All state
 * persists in collection config (syncs).
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

    // --- learned fatigue model (see BRAINLIFT_AI_SPEC.md §5.5) ---------------
    // Logistic regression trained OFFLINE in Python on research-grounded
    // SIMULATED sessions (calibrated to Fortenbaugh 2015, Hanzal 2024,
    // Hassanzadeh-Behbaha 2018). Weights are copied VERBATIM from
    // brainlift_eval/train_fatigue_model.py so this runs byte-identical to
    // desktop `anki.brainlift.fatigue`. Inference: p = sigmoid(bias + w·features).
    const val FATIGUE_MODEL_VERSION = "logreg-sim-v1"

    // feature order: slowdown, accdrop, rt_var, post_error, session_pos
    const val FATIGUE_MODEL_BIAS = -4.125162
    val FATIGUE_MODEL_WEIGHTS =
        doubleArrayOf(
            4.943704, // slowdown
            3.092085, // accdrop
            0.795880, // rt_var
            1.538849, // post_error
            3.579352, // session_pos
        )

    // Pre-declared decision thresholds on the learned probability.
    const val MODEL_INTERVENE = 0.50
    const val MODEL_SEVERE = 0.80

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
        // EWMA-smoothed normalized model features (parallel to smoothedDrain).
        var sfSlowdown: Double = 0.0,
        var sfAccdrop: Double = 0.0,
        var sfVar: Double = 0.0,
        var sfPosterr: Double = 0.0,
        var answersSinceIntervention: Int = INTERVENTION_COOLDOWN,
    )

    data class FatigueDecision(
        val intervene: Boolean,
        val type: String?,
        val banner: String?,
        val drain: Double,
        val sessionMinutes: Double,
        val reason: String,
        // Learned-model fields (0.0 / false on the deterministic fallback path).
        val probability: Double = 0.0,
        val usedModel: Boolean = false,
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

        // instantaneous normalized signals -> deterministic drain + smoothing,
        // and the EWMA-smoothed feature vector consumed by the learned model.
        val nf = instantNormFeatures(s)
        val drain = clamp(W_SLOWDOWN * nf[0] + W_ACC * nf[1] + W_VAR * nf[2] + W_POSTERR * nf[3])
        s.smoothedDrain = (1 - DRAIN_ALPHA) * s.smoothedDrain + DRAIN_ALPHA * drain
        s.sfSlowdown = (1 - DRAIN_ALPHA) * s.sfSlowdown + DRAIN_ALPHA * nf[0]
        s.sfAccdrop = (1 - DRAIN_ALPHA) * s.sfAccdrop + DRAIN_ALPHA * nf[1]
        s.sfVar = (1 - DRAIN_ALPHA) * s.sfVar + DRAIN_ALPHA * nf[2]
        s.sfPosterr = (1 - DRAIN_ALPHA) * s.sfPosterr + DRAIN_ALPHA * nf[3]
        return s
    }

    /** The four normalized [0,1] drain signals for the current state (pure).
     * Shared by the deterministic drain score AND the learned model. */
    private fun instantNormFeatures(s: FatigueState): DoubleArray {
        val baselineRt = maxOf(s.baselineRt, RT_MIN)
        val baselineVar = maxOf(s.rtVar, RT_MIN)
        val slowdown = if (s.recentRt.isNotEmpty()) mean(s.recentRt) / baselineRt else 1.0
        val accdrop = if (s.recentAcc.isNotEmpty()) s.baselineAcc - mean(s.recentAcc) else 0.0
        val varRatio = if (s.recentRt.isNotEmpty()) popStd(s.recentRt) / baselineVar else 1.0
        val posterr = s.postErrorRt / baselineRt
        return doubleArrayOf(
            norm(slowdown, SLOWDOWN_LO, SLOWDOWN_HI),
            norm(accdrop, ACCDROP_LO, ACCDROP_HI),
            norm(varRatio, VAR_LO, VAR_HI),
            norm(posterr, POSTERR_LO, POSTERR_HI),
        )
    }

    fun computeDrain(s: FatigueState): Double {
        val nf = instantNormFeatures(s)
        return clamp(W_SLOWDOWN * nf[0] + W_ACC * nf[1] + W_VAR * nf[2] + W_POSTERR * nf[3])
    }

    // --- learned model inference (parity-critical; mirror of Python) --------

    /** Numerically-stable logistic sigmoid (identical to Python `sigmoid`). */
    fun sigmoid(z: Double): Double =
        if (z >= 0) {
            1.0 / (1.0 + exp(-z))
        } else {
            val ez = exp(z)
            ez / (1.0 + ez)
        }

    /** p(drained) = sigmoid(bias + w·features); features in FATIGUE_MODEL order. */
    fun predictDrainProbability(features: DoubleArray): Double {
        var z = FATIGUE_MODEL_BIAS
        for (i in FATIGUE_MODEL_WEIGHTS.indices) {
            z += FATIGUE_MODEL_WEIGHTS[i] * features[i]
        }
        return sigmoid(z)
    }

    /** Build the 5-feature model input from a session state (pure). */
    fun modelFeatureVector(
        state: FatigueState,
        now: Long,
    ): DoubleArray {
        val sessionMinutes = (now - state.sessionStart) / 60.0
        val sessionPos = norm(sessionMinutes, 0.0, PROD_MIN_MINUTES)
        return doubleArrayOf(state.sfSlowdown, state.sfAccdrop, state.sfVar, state.sfPosterr, sessionPos)
    }

    /** Learned probability for a state, or null if the model can't run (so the
     * caller falls back to the deterministic heuristic). */
    fun modelProbability(
        state: FatigueState,
        now: Long,
    ): Double? =
        try {
            if (FATIGUE_MODEL_WEIGHTS.size != 5) null else predictDrainProbability(modelFeatureVector(state, now))
        } catch (e: Exception) {
            null
        }

    fun decide(
        state: FatigueState,
        testMode: Boolean,
        now: Long,
        useModel: Boolean = false,
    ): FatigueDecision {
        val drain = round4(state.smoothedDrain)
        val rawSessionMinutes = (now - state.sessionStart) / 60.0
        val sessionMinutes = round2(rawSessionMinutes)

        // Learned score + thresholds, with a clean fallback to the heuristic.
        // `prob != null` iff the model ran (it is null when useModel is false),
        // so it doubles as the "used the learned model" flag.
        val prob = if (useModel) modelProbability(state, now) else null
        val score: Double
        val interveneCut: Double
        val severeCut: Double
        if (prob != null) {
            score = prob
            interveneCut = MODEL_INTERVENE
            severeCut = MODEL_SEVERE
        } else {
            score = state.smoothedDrain
            interveneCut = DRAIN_INTERVENE
            severeCut = SEVERE_DRAIN
        }
        val usedModel = prob != null
        val pReport = if (prob != null) round4(prob) else 0.0

        fun d(
            intervene: Boolean,
            type: String?,
            banner: String?,
            reason: String,
        ) = FatigueDecision(intervene, type, banner, drain, sessionMinutes, reason, pReport, usedModel)

        if (state.answers < MIN_ANSWERS_BEFORE_DETECT) {
            return d(false, null, null, "warming up")
        }
        if (state.answersSinceIntervention < INTERVENTION_COOLDOWN) {
            return d(false, null, null, "cooldown")
        }
        val timingOk = testMode || rawSessionMinutes >= PROD_MIN_MINUTES || score >= severeCut
        if (!(timingOk && score >= interveneCut)) {
            val reason = if (score < interveneCut) "below threshold" else "timing gate not met"
            return d(false, null, null, reason)
        }
        return if (state.sameTopicStreak >= SAME_TOPIC_STREAK_LIMIT) {
            d(true, TYPE_INTERLEAVE, BANNER_INTERLEAVE, "high same-topic streak")
        } else {
            d(true, TYPE_EASE, BANNER_EASE, "sustained drain")
        }
    }

    // --- config persistence (syncs) -----------------------------------------
    fun testMode(col: Collection): Boolean = col.config.get<Boolean>(TEST_MODE_KEY) ?: true

    /** Use the learned classifier iff the master AI toggle is ON; else fall back
     * to the deterministic heuristic (both still produce a decision). */
    fun modelEnabled(col: Collection): Boolean =
        try {
            BrainLiftAi.aiEnabled(col)
        } catch (e: Exception) {
            false
        }

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
        val decision = decide(state, testMode(col), now, useModel = modelEnabled(col))
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
            .put("sf_slowdown", s.sfSlowdown)
            .put("sf_accdrop", s.sfAccdrop)
            .put("sf_var", s.sfVar)
            .put("sf_posterr", s.sfPosterr)
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
            sfSlowdown = o.optDouble("sf_slowdown", 0.0),
            sfAccdrop = o.optDouble("sf_accdrop", 0.0),
            sfVar = o.optDouble("sf_var", 0.0),
            sfPosterr = o.optDouble("sf_posterr", 0.0),
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
