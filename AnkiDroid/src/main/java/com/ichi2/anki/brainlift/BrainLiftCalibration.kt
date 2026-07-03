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

import android.content.Context
import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Feature 1 — Metacognitive calibration (Kotlin mirror of desktop
 * `anki.brainlift.calibration`). The confidence scale, deviation/accuracy,
 * Goodman-Kruskal gamma, the confidence-authority multiplier, and
 * `effectiveMasteryGap` are IDENTICAL to the Python engine and
 * `BRAINLIFT_AI_SPEC.md` §1-§4. The calibration result + flat authority
 * multiplier persist in collection config and sync between platforms; the
 * planner on both platforms reads the same multiplier.
 */
object BrainLiftCalibration {
    const val CONFIG_KEY = "brainlift_calibration"
    const val CONFIG_MULTIPLIER_KEY = "brainlift_calibration_multiplier"

    val CONFIDENCE_SCALE =
        linkedMapOf(
            "Highly confident" to 1.0,
            "Confident" to 0.85,
            "Kind of confident" to 0.6,
            "Unsure" to 0.3,
            "Guessing" to 0.0,
        )
    val CONFIDENCE_ORDER = CONFIDENCE_SCALE.keys.toList()

    const val CALIBRATION_TEST_SIZE = 15
    const val CALIBRATION_PRODUCTION_SIZE = 50

    private const val CALIB_AUTHORITY_FLOOR_ACCURACY = 0.5
    private const val MIN_AUTHORITY = 0.25

    const val SEED_DECK_NAME = "Exam P — Sample Questions"

    /**
     * Shared SOA seed bank asset, generated from the desktop source of truth
     * (`pylib/anki/brainlift/examp_seed.py::SEED_CARDS`). Calibration selects from
     * this bank on BOTH platforms so desktop and Android calibrate on the EXACT
     * same questions (and therefore the same deterministic AI-off analogs, which
     * are seeded off the source index). Regenerate with:
     *   PYTHONPATH=pylib python3 -c "import json;from anki.brainlift.examp_seed \
     *     import SEED_CARDS;open('.../assets/brainlift/examp_seed.json','w')\
     *     .write(json.dumps(SEED_CARDS, ensure_ascii=False))"
     */
    const val SEED_BANK_ASSET = "brainlift/examp_seed.json"

    // The bundled SOA solutions were extracted from a PDF whose big-bracket,
    // integral and matrix glyphs land in the Unicode Private Use Area (e.g.
    // U+F8EE). Those code points have no portable font glyph and render as tofu
    // boxes, so we strip them for DISPLAY ONLY. Mirrors the desktop
    // `calibration._PRIVATE_USE_RE`: BMP PUA plus the two supplementary PUA
    // planes.
    private val PRIVATE_USE_RE = Regex("[\\x{E000}-\\x{F8FF}\\x{F0000}-\\x{FFFFD}\\x{100000}-\\x{10FFFD}]")

    /** Remove Unicode Private Use Area glyphs from text (display-only cleanup). */
    fun stripPrivateUse(text: String): String = PRIVATE_USE_RE.replace(text, "")

    fun confidenceValue(label: String): Double = CONFIDENCE_SCALE[label] ?: 0.6

    private fun clamp(
        x: Double,
        lo: Double = 0.0,
        hi: Double = 1.0,
    ) = maxOf(lo, minOf(hi, x))

    private fun sign(x: Double): Int =
        if (x > 0) {
            1
        } else if (x < 0) {
            -1
        } else {
            0
        }

    // --- core scoring (parity-critical) -------------------------------------
    fun meanAbsoluteDeviation(
        confidences: List<Double>,
        performances: List<Int>,
    ): Double {
        if (confidences.isEmpty()) return 0.0
        var total = 0.0
        for (i in confidences.indices) total += abs(confidences[i] - performances[i])
        return total / confidences.size
    }

    fun calibrationAccuracy(
        confidences: List<Double>,
        performances: List<Int>,
    ): Double = clamp(1.0 - meanAbsoluteDeviation(confidences, performances))

    /** Goodman-Kruskal gamma; null if undefined (no ranked pairs). */
    fun goodmanKruskalGamma(
        confidences: List<Double>,
        performances: List<Int>,
    ): Double? {
        var concordant = 0
        var discordant = 0
        val n = confidences.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val cs = sign(confidences[i] - confidences[j])
                val ps = sign((performances[i] - performances[j]).toDouble())
                if (cs == 0 || ps == 0) continue
                if (cs == ps) concordant++ else discordant++
            }
        }
        val denom = concordant + discordant
        return if (denom == 0) null else (concordant - discordant).toDouble() / denom
    }

    fun authorityMultiplier(accuracy: Double): Double {
        val norm = clamp((accuracy - CALIB_AUTHORITY_FLOOR_ACCURACY) / (1.0 - CALIB_AUTHORITY_FLOOR_ACCURACY))
        return MIN_AUTHORITY + (1.0 - MIN_AUTHORITY) * norm
    }

    fun calibratedSuppression(
        rawSuppression: Double,
        multiplier: Double,
    ): Double = clamp(rawSuppression) * clamp(multiplier)

    /** How much a topic still needs review after applying self-rating authority. */
    fun effectiveMasteryGap(
        masteredFraction: Double,
        multiplier: Double,
    ): Double = clamp(1.0 - clamp(masteredFraction) * clamp(multiplier))

    fun explainAccuracy(accuracy: Double): String =
        when {
            accuracy >= 0.85 -> "You're excellent at gauging what you know."
            accuracy >= 0.70 -> "You're good at judging what you know, with a little room to tighten up."
            accuracy >= 0.55 -> "Your self-judgment is roughly right, but not fully reliable yet."
            else -> "Your self-judgment isn't fully reliable yet — treat your confidence with caution."
        }

    // --- data types ---------------------------------------------------------
    data class CalibrationItem(
        val sourceCardId: Long,
        val sourceFront: String,
        val sourceBack: String,
        val confidenceLabel: String,
        val confidenceValue: Double,
        val generatedQuestion: String,
        val generatedChoices: List<String>,
        val generatedCorrectIndex: Int,
        val generatedSourceCardId: Long,
        val generatedSourceText: String,
        val chosenIndex: Int,
        val performance: Int,
        val deviation: Double,
    )

    data class CalibrationResult(
        val testSize: Int,
        val aiUsed: Boolean,
        val items: List<CalibrationItem>,
        val mad: Double,
        val accuracy: Double,
        val gamma: Double?,
        val authorityMultiplier: Double,
        val completedAt: Long,
        val explanation: String,
    )

    /** Score a completed calibration test (parity with desktop score_calibration). */
    fun scoreCalibration(
        cards: List<Triple<Long, String, String>>,
        analogs: List<BrainLiftAi.GeneratedAnalog>,
        confidenceLabels: List<String>,
        chosenIndices: List<Int>,
        now: Long,
    ): CalibrationResult {
        val items = mutableListOf<CalibrationItem>()
        val confidences = mutableListOf<Double>()
        val performances = mutableListOf<Int>()
        var aiUsed = false
        for (i in cards.indices) {
            val (cid, front, back) = cards[i]
            val analog = analogs[i]
            val conf = confidenceValue(confidenceLabels[i])
            val perf = if (chosenIndices[i] == analog.correctIndex) 1 else 0
            if (analog.ok && analog.model != "deterministic" && analog.model != "deterministic-variety") aiUsed = true
            items.add(
                CalibrationItem(
                    sourceCardId = cid,
                    sourceFront = front,
                    sourceBack = back,
                    confidenceLabel = confidenceLabels[i],
                    confidenceValue = conf,
                    generatedQuestion = analog.question,
                    generatedChoices = analog.choices,
                    generatedCorrectIndex = analog.correctIndex,
                    generatedSourceCardId = analog.sourceCardId,
                    generatedSourceText = analog.sourceText,
                    chosenIndex = chosenIndices[i],
                    performance = perf,
                    deviation = round4(abs(conf - perf)),
                ),
            )
            confidences.add(conf)
            performances.add(perf)
        }
        val mad = round4(meanAbsoluteDeviation(confidences, performances))
        val accuracy = round4(calibrationAccuracy(confidences, performances))
        val gamma = goodmanKruskalGamma(confidences, performances)
        val mult = round4(authorityMultiplier(accuracy))
        return CalibrationResult(
            testSize = items.size,
            aiUsed = aiUsed,
            items = items,
            mad = mad,
            accuracy = accuracy,
            gamma = gamma?.let { round4(it) },
            authorityMultiplier = mult,
            completedAt = now,
            explanation = explainAccuracy(accuracy),
        )
    }

    // --- card selection + generation ----------------------------------------

    /**
     * Return [size] `(id, front, back)` items from the bundled SOA Exam P seed
     * bank, spread across the bank for topic variety. This is an EXACT mirror of
     * the desktop `calibration.seed_calibration_items`: same even-spread index
     * selection, same Private-Use-Area stripping, and the stable SEED INDEX is
     * used as the id. Selecting from the shared bank (rather than the user's
     * collection) guarantees desktop and Android calibrate on identical
     * questions — and, since the deterministic analog generator is seeded off
     * this id, identical AI-off analogs too.
     *
     * [json] is the raw contents of [SEED_BANK_ASSET] (a JSON array of objects
     * with `front`/`back`/`tags` keys).
     */
    fun seedCalibrationItems(
        json: String,
        size: Int = CALIBRATION_TEST_SIZE,
    ): List<Triple<Long, String, String>> {
        val bank = JSONArray(json)
        val total = bank.length()
        if (total == 0) return emptyList()
        val n = minOf(size, total)
        val step = maxOf(1, total / n) // evenly spread so we sample multiple topics
        val items = mutableListOf<Triple<Long, String, String>>()
        val seen = mutableSetOf<Int>()
        for (k in 0 until n) {
            var idx = minOf(k * step, total - 1)
            while (idx in seen && idx < total - 1) idx += 1
            seen.add(idx)
            val card = bank.getJSONObject(idx)
            val front = stripPrivateUse(card.optString("front", ""))
            val back = stripPrivateUse(card.optString("back", ""))
            items.add(Triple(idx.toLong(), front, back))
        }
        return items
    }

    /** Load [SEED_BANK_ASSET] and select the calibration items (see above). */
    fun seedCalibrationItems(
        context: Context,
        size: Int = CALIBRATION_TEST_SIZE,
    ): List<Triple<Long, String, String>> = seedCalibrationItems(loadSeedBankJson(context), size)

    private fun loadSeedBankJson(context: Context): String =
        context.assets
            .open(SEED_BANK_ASSET)
            .bufferedReader()
            .use { it.readText() }

    /**
     * Generate one analog per selected calibration card. The analog generator is
     * seeded off each card's SEED INDEX id, so with AI off both platforms produce
     * identical deterministic analogs. Order matches [cards].
     */
    fun buildCalibrationQuestions(
        col: Collection,
        cards: List<Triple<Long, String, String>>,
    ): List<BrainLiftAi.GeneratedAnalog> {
        val client = BrainLiftAi.clientForCollection(col)
        return cards.map { (cid, f, b) -> client.generateAnalog(f, b, cid) }
    }

    // --- persistence (syncs) ------------------------------------------------
    fun save(
        col: Collection,
        result: CalibrationResult,
    ) {
        col.config.set(CONFIG_KEY, toJson(result))
        col.config.set(CONFIG_MULTIPLIER_KEY, result.authorityMultiplier)
    }

    fun load(col: Collection): CalibrationResult? {
        val o = getObjectOrNull(col, CONFIG_KEY) ?: return null
        return fromJson(o)
    }

    fun hasCalibration(col: Collection): Boolean = load(col) != null

    /** The synced authority multiplier read by the scheduling layer (default 1). */
    fun calibrationMultiplier(col: Collection): Double = col.config.get<Double>(CONFIG_MULTIPLIER_KEY) ?: 1.0

    fun runCalibration(
        col: Collection,
        cards: List<Triple<Long, String, String>>,
        analogs: List<BrainLiftAi.GeneratedAnalog>,
        confidenceLabels: List<String>,
        chosenIndices: List<Int>,
        now: Long,
    ): CalibrationResult {
        val result = scoreCalibration(cards, analogs, confidenceLabels, chosenIndices, now)
        save(col, result)
        return result
    }

    // --- JSON ---------------------------------------------------------------
    private fun toJson(r: CalibrationResult): JSONObject {
        val items = JSONArray()
        for (it in r.items) {
            items.put(
                JSONObject()
                    .put("source_card_id", it.sourceCardId)
                    .put("source_front", it.sourceFront)
                    .put("source_back", it.sourceBack)
                    .put("confidence_label", it.confidenceLabel)
                    .put("confidence_value", it.confidenceValue)
                    .put("generated_question", it.generatedQuestion)
                    .put("generated_choices", JSONArray(it.generatedChoices))
                    .put("generated_correct_index", it.generatedCorrectIndex)
                    .put("generated_source_card_id", it.generatedSourceCardId)
                    .put("generated_source_text", it.generatedSourceText)
                    .put("chosen_index", it.chosenIndex)
                    .put("performance", it.performance)
                    .put("deviation", it.deviation),
            )
        }
        return JSONObject()
            .put("test_size", r.testSize)
            .put("ai_used", r.aiUsed)
            .put("items", items)
            .put("mad", r.mad)
            .put("accuracy", r.accuracy)
            .put("gamma", r.gamma ?: JSONObject.NULL)
            .put("authority_multiplier", r.authorityMultiplier)
            .put("completed_at", r.completedAt)
            .put("explanation", r.explanation)
    }

    private fun fromJson(o: JSONObject): CalibrationResult {
        val itemsArr = o.optJSONArray("items") ?: JSONArray()
        val items =
            (0 until itemsArr.length()).map { i ->
                val t = itemsArr.getJSONObject(i)
                val choicesArr = t.optJSONArray("generated_choices") ?: JSONArray()
                CalibrationItem(
                    sourceCardId = t.optLong("source_card_id"),
                    sourceFront = t.optString("source_front"),
                    sourceBack = t.optString("source_back"),
                    confidenceLabel = t.optString("confidence_label"),
                    confidenceValue = t.optDouble("confidence_value", 0.0),
                    generatedQuestion = t.optString("generated_question"),
                    generatedChoices = (0 until choicesArr.length()).map { choicesArr.getString(it) },
                    generatedCorrectIndex = t.optInt("generated_correct_index"),
                    generatedSourceCardId = t.optLong("generated_source_card_id"),
                    generatedSourceText = t.optString("generated_source_text"),
                    chosenIndex = t.optInt("chosen_index", -1),
                    performance = t.optInt("performance", 0),
                    deviation = t.optDouble("deviation", 0.0),
                )
            }
        val gamma = if (o.isNull("gamma")) null else o.optDouble("gamma")
        return CalibrationResult(
            testSize = o.optInt("test_size"),
            aiUsed = o.optBoolean("ai_used", false),
            items = items,
            mad = o.optDouble("mad", 0.0),
            accuracy = o.optDouble("accuracy", 0.0),
            gamma = gamma,
            authorityMultiplier = o.optDouble("authority_multiplier", 1.0),
            completedAt = o.optLong("completed_at", 0),
            explanation = o.optString("explanation"),
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

    private fun round4(v: Double) = Math.rint(v * 10000.0) / 10000.0
}
