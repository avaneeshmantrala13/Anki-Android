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
import com.ichi2.anki.web.HttpFetcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * BrainLift AI provider for Android (Kotlin mirror of desktop `anki.brainlift.ai`).
 *
 * * [RealOpenAiClient] calls the OpenAI chat-completions REST API directly via
 *   OkHttp (the desktop client uses `requests` — same REST call, for parity).
 *   The key is read from the runtime (never stored in config / committed).
 * * [DeterministicAnalogClient] is a template-based re-parameterizer that always
 *   yields a valid, checkable analog MCQ with named-source traceability. Used
 *   when AI is OFF, no key is present, the service is offline/broken, and in
 *   tests. The real client falls back to it on any error (ok=false), so scoring
 *   never blocks and the app never crashes.
 */
object BrainLiftAi {
    const val CONFIG_AI_ENABLED = "brainlift_ai_enabled"
    const val CONFIG_AI_MODEL = "brainlift_ai_model"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"

    // --- leakage gate constants (shared with desktop; see BRAINLIFT_AI_SPEC.md)
    // An analog "leaks" when it is near-verbatim to its source AND resolves to
    // the SAME answer. The pipeline regenerates up to MAX_REGEN times with a
    // stronger re-parameterize instruction, then BLOCKS (withholds) if it still
    // leaks, so the SERVED set contains zero leaked items.
    const val LEAKAGE_SIM_THRESHOLD = 0.9 // jaccard question overlap == near-verbatim
    const val MAX_REGEN = 3 // regeneration attempts before an item is blocked/withheld
    const val REGEN_PARAM_STRIDE = 101L // deterministic regen perturbs numbers by this stride

    data class GeneratedAnalog(
        val question: String,
        val choices: List<String>,
        val correctIndex: Int,
        val sourceCardId: Long,
        val sourceText: String,
        val model: String = "deterministic",
        val ok: Boolean = true,
    )

    interface AiClient {
        fun generateAnalog(
            front: String,
            back: String,
            sourceCardId: Long,
            attempt: Int = 0,
        ): GeneratedAnalog
    }

    // --- leakage gate (mirror of desktop ai.py) -----------------------------
    private val leakWordRe = Regex("[a-z0-9]+")

    private fun leakTokens(text: String): Set<String> = leakWordRe.findAll(text.lowercase()).map { it.value }.toSet()

    /** Jaccard word overlap (identical to the eval harness `jaccard`). */
    fun questionSimilarity(
        a: String,
        b: String,
    ): Double {
        val ta = leakTokens(a)
        val tb = leakTokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size.toDouble()
    }

    /** True if generated answer resolves to the same value as the source answer. */
    private fun sameAnswer(
        generated: String,
        source: String,
    ): Boolean {
        val g =
            generated
                .trim()
                .lowercase()
                .split(Regex("\\s+"))
                .joinToString(" ")
        val s =
            source
                .trim()
                .lowercase()
                .split(Regex("\\s+"))
                .joinToString(" ")
        if (g == s) return true
        val gn = g.toDoubleOrNull()
        val sn = s.toDoubleOrNull()
        return gn != null && sn != null && abs(gn - sn) < 1e-9
    }

    /** Leakage = near-verbatim question wording AND same resolved answer. */
    fun isLeaked(
        analog: GeneratedAnalog,
        sourceFront: String,
        sourceBack: String,
    ): Boolean {
        if (analog.choices.isEmpty() || analog.correctIndex !in analog.choices.indices) return false
        val genAnswer = analog.choices[analog.correctIndex]
        val sim = questionSimilarity(analog.question, sourceFront)
        return sim >= LEAKAGE_SIM_THRESHOLD && sameAnswer(genAnswer, sourceBack)
    }

    /**
     * Result of running the leakage gate on one generated analog.
     * [served] items are safe to show; [blocked] items still leaked after
     * [MAX_REGEN] retries and are withheld. [regenAttempts]/[leakedInitially]
     * are reported for transparency.
     */
    data class GatedAnalog(
        val analog: GeneratedAnalog,
        val served: Boolean,
        val blocked: Boolean,
        val regenAttempts: Int,
        val leakedInitially: Boolean,
    )

    /**
     * Generate an analog and enforce the leakage gate: regenerate up to
     * [MAX_REGEN] times with a stronger re-parameterize instruction, then BLOCK
     * (withhold) if it still leaks. Guarantees the served item is not leaked.
     */
    fun generateGatedAnalog(
        client: AiClient,
        front: String,
        back: String,
        sourceCardId: Long,
    ): GatedAnalog {
        var analog = client.generateAnalog(front, back, sourceCardId)
        val leakedInitially = isLeaked(analog, front, back)
        var attempts = 0
        while (isLeaked(analog, front, back) && attempts < MAX_REGEN) {
            attempts += 1
            analog = client.generateAnalog(front, back, sourceCardId, attempts)
        }
        val stillLeaked = isLeaked(analog, front, back)
        return GatedAnalog(
            analog = analog,
            served = !stillLeaked,
            blocked = stillLeaked,
            regenAttempts = attempts,
            leakedInitially = leakedInitially,
        )
    }

    // --- shared number formatting (matches Python fmt_num) ------------------
    fun fmtNum(x: Double): String {
        if (abs(x - round(x)) < 1e-9) return round(x).toLong().toString()
        return "%.4f".format(x).trimEnd('0').trimEnd('.')
    }

    private fun round2(v: Double) = Math.rint(v * 100.0) / 100.0

    private fun round4(v: Double) = Math.rint(v * 10000.0) / 10000.0

    // --- deterministic template bank (matches Python) ----------------------
    // Drop distractors equal to the correct answer or to each other, then pad
    // deterministically so we always have >= 2 distinct wrong choices. Mirrors
    // Python _dedupe_distractors exactly (parity-critical).
    private fun dedupeDistractors(
        correct: String,
        distractors: List<String>,
    ): List<String> {
        val out = mutableListOf<String>()
        for (d in distractors) {
            if (d != correct && !out.contains(d)) out.add(d)
        }
        if (out.size >= 2) return out.take(3)
        val base = correct.toDoubleOrNull()
        if (base != null) {
            val isInt = abs(base - round(base)) < 1e-9 && !correct.contains(".")
            var step = 0
            while (out.size < 3) {
                step += 1
                for (delta in listOf(step, -step)) {
                    val candVal = base + delta
                    val cand = if (isInt) candVal.toLong().toString() else fmtNum(round4(candVal))
                    if (cand != correct && !out.contains(cand)) out.add(cand)
                    if (out.size >= 3) break
                }
            }
        } else {
            var suffix = 1
            while (out.size < 3) {
                val cand = "$correct (alt $suffix)"
                if (!out.contains(cand)) out.add(cand)
                suffix += 1
            }
        }
        return out.take(3)
    }

    private fun place(
        correct: String,
        distractors: List<String>,
        cardId: Long,
    ): Pair<List<String>, Int> {
        val clean = dedupeDistractors(correct, distractors)
        val idx = (cardId % (clean.size + 1)).toInt()
        val choices = clean.toMutableList()
        choices.add(idx, correct)
        return Pair(choices, idx)
    }

    private fun tplCounting(cardId: Long): Triple<String, String, List<String>> {
        val sided = (2 + cardId % 5).toInt()
        val n = (2 + cardId % 3).toInt()
        val correct = sided.toDouble().pow(n).toLong()
        val q = "A fair $sided-sided die is rolled $n times. How many equally likely ordered outcomes are there?"
        val d =
            listOf(
                (sided * n).toString(),
                sided
                    .toDouble()
                    .pow(n - 1)
                    .toLong()
                    .toString(),
                (sided * (n + 1)).toString(),
            )
        return Triple(q, correct.toString(), d)
    }

    private fun tplAtLeastOne(cardId: Long): Triple<String, String, List<String>> {
        val n = (2 + cardId % 4).toInt()
        val p = round2(0.1 * (1 + cardId % 5))
        val correct = round4(1 - (1 - p).pow(n))
        val q = "An event occurs independently with probability ${fmtNum(p)} on each of $n trials. What is P(it occurs at least once)?"
        val d = listOf(fmtNum(round4(p * n)), fmtNum(round4(p.pow(n))), fmtNum(round4((1 - p).pow(n))))
        return Triple(q, fmtNum(correct), d)
    }

    private fun tplBinomialMean(cardId: Long): Triple<String, String, List<String>> {
        val n = (5 + cardId % 8).toInt()
        val p = round2(0.1 * (1 + cardId % 8))
        val correct = round4(n * p)
        val q = "X ~ Binomial(n=$n, p=${fmtNum(p)}). What is E[X]?"
        val d = listOf(fmtNum(round4(n * p * (1 - p))), fmtNum(n.toDouble()), fmtNum(p))
        return Triple(q, fmtNum(correct), d)
    }

    private fun tplPoissonVar(cardId: Long): Triple<String, String, List<String>> {
        val lam = (1 + cardId % 6).toInt()
        val q = "X ~ Poisson(lambda=$lam). What is Var(X)?"
        val d = listOf(fmtNum(round4(sqrt(lam.toDouble()))), (lam * lam).toString(), (lam + 1).toString())
        return Triple(q, lam.toString(), d)
    }

    private fun tplExponentialMean(cardId: Long): Triple<String, String, List<String>> {
        val rate = (2 + cardId % 5).toInt()
        val correct = round4(1.0 / rate)
        val q = "X ~ Exponential with rate lambda=$rate. What is E[X]?"
        val d = listOf(rate.toString(), fmtNum(round4(1.0 / (rate * rate))), (rate * rate).toString())
        return Triple(q, fmtNum(correct), d)
    }

    private fun tplIndependentAnd(cardId: Long): Triple<String, String, List<String>> {
        val a = round2(0.1 * (2 + cardId % 7))
        val b = round2(0.1 * (1 + cardId % 6))
        val correct = round4(a * b)
        val q = "Events A and B are independent with P(A)=${fmtNum(a)} and P(B)=${fmtNum(b)}. What is P(A and B)?"
        val d = listOf(fmtNum(round4(a + b)), fmtNum(round4(a + b - a * b)), fmtNum(round4(abs(a - b))))
        return Triple(q, fmtNum(correct), d)
    }

    private val templates: List<Pair<List<String>, (Long) -> Triple<String, String, List<String>>>> =
        listOf(
            listOf("coin", "flip", "die", "roll", "outcome", "possib", "toss") to ::tplCounting,
            listOf("at least one", "at least", "complement") to ::tplAtLeastOne,
            listOf("binomial", "e[x]", "expected", "mean") to ::tplBinomialMean,
            listOf("poisson", "var(", "variance") to ::tplPoissonVar,
            listOf("exponential") to ::tplExponentialMean,
            listOf("independent", "p(a and b)", "intersection") to ::tplIndependentAnd,
        )

    private fun matchedTemplate(text: String): ((Long) -> Triple<String, String, List<String>>)? {
        val low = text.lowercase()
        for ((keywords, renderer) in templates) {
            if (keywords.any { low.contains(it) }) return renderer
        }
        return null
    }

    class DeterministicAnalogClient : AiClient {
        override fun generateAnalog(
            front: String,
            back: String,
            sourceCardId: Long,
            attempt: Int,
        ): GeneratedAnalog {
            val sourceText = "$front :: $back".trim()
            val matched = matchedTemplate(sourceText)
            val renderer = matched ?: templates[(sourceCardId % templates.size).toInt()].second
            // On regeneration (attempt>0) perturb the parameter id so the numbers
            // — and therefore the correct answer — change, resolving any leakage
            // while still testing the same concept. Source traceability unchanged.
            val paramId = sourceCardId + attempt * REGEN_PARAM_STRIDE
            val (question, correct, distractors) = renderer(paramId)
            val (choices, idx) = place(correct, distractors, paramId)
            return GeneratedAnalog(
                question = question,
                choices = choices,
                correctIndex = idx,
                sourceCardId = sourceCardId,
                sourceText = sourceText,
                model = if (matched != null) "deterministic" else "deterministic-variety",
                ok = true,
            )
        }
    }

    class RealOpenAiClient(
        private val apiKey: String,
        private val model: String = DEFAULT_MODEL,
    ) : AiClient {
        private val fallback = DeterministicAnalogClient()

        override fun generateAnalog(
            front: String,
            back: String,
            sourceCardId: Long,
            attempt: Int,
        ): GeneratedAnalog {
            val sourceText = "$front :: $back".trim()
            return try {
                val system =
                    "You are an actuarial exam tutor. Given a source flashcard, write ONE " +
                        "multiple-choice analog question that tests the SAME concept but is reworded " +
                        "and RE-PARAMETERIZED. You MUST change the numbers/scenario so the correct " +
                        "answer is DIFFERENT from the source answer — never copy the source question " +
                        "and never reuse its answer. Return STRICT JSON: " +
                        "{\"question\": str, \"choices\": [str,...], \"correct_index\": int}. " +
                        "3-4 choices, exactly one correct."
                var user =
                    "SOURCE FRONT: $front\nSOURCE BACK: $back\n" +
                        "The source answer is '$back'. Your analog MUST use different numbers so its " +
                        "correct answer is NOT equal to that. Return only JSON."
                if (attempt > 0) {
                    user +=
                        "\n\nRETRY $attempt: your previous analog was too close to the source and " +
                        "resolved to the SAME answer. Substantially change the numbers and phrasing " +
                        "so the correct answer clearly differs."
                }
                val messages =
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                val payload =
                    JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put("temperature", 0.7)
                        .put("response_format", JSONObject().put("type", "json_object"))
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request =
                    Request
                        .Builder()
                        .url(OPENAI_ENDPOINT)
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build()
                val client = HttpFetcher.getOkHttpBuilder(false).build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) throw RuntimeException("OpenAI HTTP ${response.code}")
                    val content =
                        JSONObject(response.body.string())
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    val parsed = JSONObject(content)
                    val question = parsed.getString("question").trim()
                    val choicesArr = parsed.getJSONArray("choices")
                    val choices = (0 until choicesArr.length()).map { choicesArr.getString(it) }
                    val correctIndex = parsed.getInt("correct_index")
                    if (question.isEmpty() || choices.size < 2 || correctIndex !in choices.indices) {
                        throw RuntimeException("malformed analog")
                    }
                    GeneratedAnalog(question, choices, correctIndex, sourceCardId, sourceText, model, true)
                }
            } catch (e: Exception) {
                Timber.w(e, "OpenAI analog generation failed; using deterministic fallback")
                val fb = fallback.generateAnalog(front, back, sourceCardId, attempt)
                fb.copy(model = "$model-fallback", ok = false)
            }
        }
    }

    // --- config + factory ---------------------------------------------------
    fun aiEnabled(col: Collection): Boolean = col.config.get<Boolean>(CONFIG_AI_ENABLED) ?: false

    fun setAiEnabled(
        col: Collection,
        enabled: Boolean,
    ) = col.config.set(CONFIG_AI_ENABLED, enabled)

    fun aiModel(col: Collection): String = col.config.get<String>(CONFIG_AI_MODEL) ?: DEFAULT_MODEL

    fun apiKeyFromEnv(): String? = System.getenv("OPENAI_API_KEY")?.trim()?.ifEmpty { null }

    fun getClient(
        enabled: Boolean,
        model: String = DEFAULT_MODEL,
        apiKey: String? = null,
    ): AiClient =
        if (enabled && apiKey != null) {
            RealOpenAiClient(apiKey, model)
        } else {
            DeterministicAnalogClient()
        }

    fun clientForCollection(col: Collection): AiClient = getClient(aiEnabled(col), aiModel(col), apiKeyFromEnv())
}
