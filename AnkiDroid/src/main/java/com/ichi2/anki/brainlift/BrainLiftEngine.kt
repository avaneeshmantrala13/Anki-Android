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
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic BrainLift engine for Android (no AI).
 *
 * This is the Kotlin counterpart of the desktop `anki.brainlift` Python package.
 * It reads and writes the SAME collection-config keys with the SAME JSON shapes,
 * so all BrainLift progress (onboarding + diagnostic) syncs between desktop and
 * mobile through Anki's existing sync. Coverage/measurements are computed on
 * device from the (synced) collection, so both platforms show matching numbers.
 */
object BrainLiftEngine {
    // --- config keys (must match desktop pylib/anki/brainlift) --------------
    const val ONBOARDING_KEY = "brainlift_onboarding"
    const val DIAGNOSTIC_KEY = "brainlift_diagnostic"

    const val TAG_ROOT = "ExamP"

    // Study modes (match desktop).
    const val DURABLE = "durable"
    const val TIGHT = "tight"
    const val CRAMMING = "cramming"
    const val EXAM_PASSED = "exam_passed"

    const val EXPERIENCE_NONE = "none"
    const val EXPERIENCE_SOME = "some"
    const val EXPERIENCE_STRONG = "strong"

    // Onboarding thresholds (match desktop).
    private const val BASE_HOURS_FOR_DURABLE = 100.0
    private val EXPERIENCE_FACTORS = mapOf("none" to 1.0, "some" to 0.7, "strong" to 0.5)
    private const val CRAM_DAYS = 14
    private const val CRAM_HOURS_FRACTION = 0.5
    private const val MIN_REVIEWED_FOR_DATA = 50

    // Coverage status thresholds (match desktop exam_p).
    private const val COVERED_FRACTION = 0.8
    private const val MASTERED_FRACTION = 0.8

    const val NOT_STARTED = "Not Started"
    const val IN_PROGRESS = "In Progress"
    const val COVERED = "Covered"
    const val MASTERED = "Mastered"

    // Readiness give-up thresholds (match desktop measurements).
    private const val MIN_REVIEWS_FOR_READINESS = 200
    private const val MIN_COVERAGE_FOR_READINESS = 50.0
    private const val EXAM_SCALE_MAX = 10.0
    private const val W_PERFORMANCE = 0.6
    private const val W_MEMORY = 0.4

    // Planner weights (match desktop planner).
    private const val W_IMPORTANCE = 0.35
    private const val W_MASTERY_GAP = 0.30
    private const val W_DIAGNOSTIC_GAP = 0.20
    private const val W_COVERAGE_GAP = 0.15
    private const val NEUTRAL_DIAGNOSTIC_GAP = 0.5
    private const val CRAM_FOCUS_TOPICS = 3

    // --- Exam P syllabus (weights = SOA midpoints, match desktop) -----------
    data class Topic(
        val key: String,
        val name: String,
        val weight: Double,
    ) {
        val tag get() = "$TAG_ROOT::$key"
        val search get() = "(\"tag:$tag\" OR \"tag:$tag::*\")"
    }

    val SYLLABUS =
        listOf(
            Topic("GeneralProbability", "General Probability", 26.5),
            Topic("UnivariateRV", "Univariate Random Variables", 47.0),
            Topic("MultivariateRV", "Multivariate Random Variables", 26.5),
        )

    private val MAX_WEIGHT = SYLLABUS.maxOf { it.weight }

    // ------------------------------------------------------------------------
    // Onboarding
    // ------------------------------------------------------------------------
    data class OnboardingInput(
        val examDate: String,
        val goalScore: Double,
        val weeklyStudyHours: Double,
        val previousAttempts: Int = 0,
        val priorExperience: String = EXPERIENCE_NONE,
    )

    data class OnboardingResult(
        val mode: String,
        val daysUntilExam: Int,
        val weeksUntilExam: Double,
        val availableHours: Double,
        val estimatedHoursNeeded: Double,
        val enoughTime: Boolean,
        val isCramming: Boolean,
        val diagnosticRequired: Boolean,
        val recommendation: String,
        val profile: OnboardingInput,
    )

    fun saveOnboarding(
        col: Collection,
        p: OnboardingInput,
    ) {
        val o =
            JSONObject()
                .put("exam_date", p.examDate)
                .put("goal_score", p.goalScore)
                .put("weekly_study_hours", p.weeklyStudyHours)
                .put("previous_attempts", p.previousAttempts)
                .put("prior_experience", p.priorExperience)
        col.config.set(ONBOARDING_KEY, o)
    }

    fun loadOnboarding(col: Collection): OnboardingInput? {
        val o = getObjectOrNull(col, ONBOARDING_KEY) ?: return null
        return OnboardingInput(
            examDate = o.optString("exam_date"),
            goalScore = o.optDouble("goal_score", 0.0),
            weeklyStudyHours = o.optDouble("weekly_study_hours", 0.0),
            previousAttempts = o.optInt("previous_attempts", 0),
            priorExperience = o.optString("prior_experience", EXPERIENCE_NONE),
        )
    }

    fun isOnboarded(col: Collection): Boolean = loadOnboarding(col) != null

    fun evaluateOnboarding(
        col: Collection,
        p: OnboardingInput,
        today: LocalDate = LocalDate.now(),
    ): OnboardingResult {
        val exam =
            try {
                LocalDate.parse(p.examDate)
            } catch (e: Exception) {
                today
            }
        val days = (exam.toEpochDay() - today.toEpochDay()).toInt()
        val weeks = maxOf(days, 0) / 7.0
        val available = weeks * p.weeklyStudyHours
        val needed = BASE_HOURS_FOR_DURABLE * (EXPERIENCE_FACTORS[p.priorExperience] ?: 1.0)

        val mode =
            when {
                days <= 0 -> EXAM_PASSED
                days < CRAM_DAYS || available < needed * CRAM_HOURS_FRACTION -> CRAMMING
                available < needed -> TIGHT
                else -> DURABLE
            }

        val reviewed = reviewedCardCount(col)
        val diagnosticRequired =
            if (reviewed >= MIN_REVIEWED_FOR_DATA) {
                false
            } else {
                p.priorExperience != EXPERIENCE_NONE || p.previousAttempts > 0
            }

        return OnboardingResult(
            mode = mode,
            daysUntilExam = days,
            weeksUntilExam = round2(weeks),
            availableHours = round1(available),
            estimatedHoursNeeded = round1(needed),
            enoughTime = mode == DURABLE,
            isCramming = mode == CRAMMING,
            diagnosticRequired = diagnosticRequired,
            recommendation = recommendation(mode),
            profile = p,
        )
    }

    private fun recommendation(mode: String) =
        when (mode) {
            DURABLE -> "You have enough time to study for durable, long-term mastery. Follow the full study plan and review consistently."
            TIGHT ->
                "You have enough calendar time, but your planned weekly hours are low for durable mastery. " +
                    "Increase weekly study time, or expect to prioritize the highest-weight topics."
            CRAMMING ->
                "Limited time before your exam. The plan will optimize for short-term exam performance. " +
                    "After the exam, return to build durable, lasting understanding."
            else -> "Your exam date is today or in the past. Update your exam date to continue."
        }

    // ------------------------------------------------------------------------
    // Diagnostic (author-written bank, matches desktop exactly)
    // ------------------------------------------------------------------------
    data class DiagnosticQuestion(
        val id: String,
        val topicKey: String,
        val prompt: String,
        val choices: List<String>,
        val correctIndex: Int,
    )

    data class DiagnosticResponse(
        val questionId: String,
        val chosenIndex: Int,
        val timeSeconds: Double = 0.0,
        val confidence: Double = 0.5,
    )

    data class TopicDiagnostic(
        val topicKey: String,
        val topicName: String,
        val total: Int,
        val correct: Int,
        val accuracy: Double,
        val avgTimeSeconds: Double,
        val avgConfidence: Double,
    )

    data class DiagnosticResult(
        val totalQuestions: Int,
        val answered: Int,
        val overallAccuracy: Double,
        val avgTimeSeconds: Double,
        val avgConfidence: Double,
        val calibrationGap: Double,
        val topics: List<TopicDiagnostic>,
        val weakTopicKeys: List<String>,
    )

    val QUESTION_BANK =
        listOf(
            DiagnosticQuestion(
                "gp1",
                "GeneralProbability",
                "A fair six-sided die is rolled. What is P(the result is even)?",
                listOf("1/6", "1/3", "1/2", "2/3"),
                2,
            ),
            DiagnosticQuestion(
                "gp2",
                "GeneralProbability",
                "Events A and B are independent with P(A)=0.5 and P(B)=0.4. P(A and B)?",
                listOf("0.20", "0.90", "0.10", "0.45"),
                0,
            ),
            DiagnosticQuestion(
                "gp3",
                "GeneralProbability",
                "A disease affects 1% of a population. A test is 99% sensitive and 99% specific. Given a positive test, P(has disease)?",
                listOf("0.01", "0.50", "0.99", "0.0099"),
                1,
            ),
            DiagnosticQuestion(
                "gp4",
                "GeneralProbability",
                "From a standard 52-card deck, P(drawing an Ace) is:",
                listOf("1/13", "1/4", "4/13", "1/52"),
                0,
            ),
            DiagnosticQuestion("uni1", "UnivariateRV", "X ~ Binomial(n=10, p=0.5). What is E[X]?", listOf("2.5", "5", "10", "0.5"), 1),
            DiagnosticQuestion("uni2", "UnivariateRV", "X ~ Poisson(lambda=3). What is Var(X)?", listOf("3", "1.73", "9", "1.5"), 0),
            DiagnosticQuestion(
                "uni3",
                "UnivariateRV",
                "X ~ Exponential with rate lambda=2. What is E[X]?",
                listOf("2", "0.5", "1", "4"),
                1,
            ),
            DiagnosticQuestion("uni4", "UnivariateRV", "X ~ Uniform(0, 10). What is P(X < 3)?", listOf("0.30", "0.70", "3", "0.03"), 0),
            DiagnosticQuestion(
                "mv1",
                "MultivariateRV",
                "For any random variable X, Cov(X, X) equals:",
                listOf("Var(X)", "0", "1", "E[X]"),
                0,
            ),
            DiagnosticQuestion(
                "mv2",
                "MultivariateRV",
                "If X and Y are independent, then Cov(X, Y) is:",
                listOf("0", "1", "Var(X)", "-1"),
                0,
            ),
            DiagnosticQuestion(
                "mv3",
                "MultivariateRV",
                "X and Y are independent with Var(X)=2 and Var(Y)=3. Var(X+Y)?",
                listOf("5", "6", "1", "2.45"),
                0,
            ),
            DiagnosticQuestion(
                "mv4",
                "MultivariateRV",
                "By the Central Limit Theorem, the distribution of the sample mean of many i.i.d. variables is approximately:",
                listOf("Normal", "Uniform", "Poisson", "Exponential"),
                0,
            ),
        )

    private val questionsById = QUESTION_BANK.associateBy { it.id }
    private val topicNames = SYLLABUS.associate { it.key to it.name }
    private val topicWeights = SYLLABUS.associate { it.key to it.weight }

    fun scoreDiagnostic(responses: List<DiagnosticResponse>): DiagnosticResult {
        data class Bucket(
            var total: Int = 0,
            var correct: Int = 0,
            var time: Double = 0.0,
            var conf: Double = 0.0,
        )
        val agg = HashMap<String, Bucket>()
        var totalCorrect = 0
        var totalTime = 0.0
        var totalConf = 0.0
        for (r in responses) {
            val q = questionsById[r.questionId] ?: continue
            val isCorrect = r.chosenIndex == q.correctIndex
            val b = agg.getOrPut(q.topicKey) { Bucket() }
            b.total++
            if (isCorrect) b.correct++
            b.time += r.timeSeconds
            b.conf += r.confidence
            if (isCorrect) totalCorrect++
            totalTime += r.timeSeconds
            totalConf += r.confidence
        }
        val answered = agg.values.sumOf { it.total }
        val topics =
            agg.map { (key, b) ->
                TopicDiagnostic(
                    topicKey = key,
                    topicName = topicNames[key] ?: key,
                    total = b.total,
                    correct = b.correct,
                    accuracy = round4(safeDiv(b.correct.toDouble(), b.total.toDouble())),
                    avgTimeSeconds = round2(safeDiv(b.time, b.total.toDouble())),
                    avgConfidence = round4(safeDiv(b.conf, b.total.toDouble())),
                )
            }
        val overall = round4(safeDiv(totalCorrect.toDouble(), answered.toDouble()))
        val avgConf = round4(safeDiv(totalConf, answered.toDouble()))
        val weak =
            topics.sortedWith(
                compareBy({ it.accuracy }, { -(topicWeights[it.topicKey] ?: 0.0) }),
            )
        return DiagnosticResult(
            totalQuestions = QUESTION_BANK.size,
            answered = answered,
            overallAccuracy = overall,
            avgTimeSeconds = round2(safeDiv(totalTime, answered.toDouble())),
            avgConfidence = avgConf,
            calibrationGap = round4(avgConf - overall),
            topics = topics,
            weakTopicKeys = weak.map { it.topicKey },
        )
    }

    fun saveDiagnostic(
        col: Collection,
        r: DiagnosticResult,
    ) {
        val topics = JSONArray()
        for (t in r.topics) {
            topics.put(
                JSONObject()
                    .put("topic_key", t.topicKey)
                    .put("topic_name", t.topicName)
                    .put("total", t.total)
                    .put("correct", t.correct)
                    .put("accuracy", t.accuracy)
                    .put("avg_time_seconds", t.avgTimeSeconds)
                    .put("avg_confidence", t.avgConfidence),
            )
        }
        val o =
            JSONObject()
                .put("total_questions", r.totalQuestions)
                .put("answered", r.answered)
                .put("overall_accuracy", r.overallAccuracy)
                .put("avg_time_seconds", r.avgTimeSeconds)
                .put("avg_confidence", r.avgConfidence)
                .put("calibration_gap", r.calibrationGap)
                .put("topics", topics)
                .put("weak_topic_keys", JSONArray(r.weakTopicKeys))
        col.config.set(DIAGNOSTIC_KEY, o)
    }

    fun loadDiagnostic(col: Collection): DiagnosticResult? {
        val o = getObjectOrNull(col, DIAGNOSTIC_KEY) ?: return null
        val topicsArr = o.optJSONArray("topics") ?: JSONArray()
        val topics =
            (0 until topicsArr.length()).map { i ->
                val t = topicsArr.getJSONObject(i)
                TopicDiagnostic(
                    topicKey = t.optString("topic_key"),
                    topicName = t.optString("topic_name"),
                    total = t.optInt("total"),
                    correct = t.optInt("correct"),
                    accuracy = t.optDouble("accuracy", 0.0),
                    avgTimeSeconds = t.optDouble("avg_time_seconds", 0.0),
                    avgConfidence = t.optDouble("avg_confidence", 0.0),
                )
            }
        val weakArr = o.optJSONArray("weak_topic_keys") ?: JSONArray()
        val weak = (0 until weakArr.length()).map { weakArr.getString(it) }
        return DiagnosticResult(
            totalQuestions = o.optInt("total_questions"),
            answered = o.optInt("answered"),
            overallAccuracy = o.optDouble("overall_accuracy", 0.0),
            avgTimeSeconds = o.optDouble("avg_time_seconds", 0.0),
            avgConfidence = o.optDouble("avg_confidence", 0.0),
            calibrationGap = o.optDouble("calibration_gap", 0.0),
            topics = topics,
            weakTopicKeys = weak,
        )
    }

    fun runDiagnostic(
        col: Collection,
        responses: List<DiagnosticResponse>,
    ): DiagnosticResult {
        val r = scoreDiagnostic(responses)
        saveDiagnostic(col, r)
        return r
    }

    // ------------------------------------------------------------------------
    // Coverage (computed from the synced collection; matches desktop counts)
    // ------------------------------------------------------------------------
    data class TopicReport(
        val key: String,
        val name: String,
        val weight: Double,
        val totalCards: Int,
        val reviewedCards: Int,
        val masteredCards: Int,
        val totalReviews: Int,
        val averageRetrievability: Double,
        val status: String,
    ) {
        val masteredFraction get() = if (totalCards > 0) masteredCards.toDouble() / totalCards else 0.0
        val reviewedFraction get() = if (totalCards > 0) reviewedCards.toDouble() / totalCards else 0.0
    }

    data class CoverageReport(
        val topics: List<TopicReport>,
        val coveragePercent: Double,
        val studiedCoveragePercent: Double,
        val masteredPercent: Double,
    ) {
        fun weakTopics(): List<TopicReport> =
            topics
                .filter { it.totalCards > 0 }
                .sortedWith(compareBy({ it.masteredFraction }, { -it.weight }))
    }

    private fun count(
        col: Collection,
        query: String,
    ): Int =
        try {
            col.findCards(query).size
        } catch (e: Exception) {
            0
        }

    fun coverageReport(col: Collection): CoverageReport {
        val reports =
            SYLLABUS.map { topic ->
                val total = count(col, topic.search)
                val reviewed = count(col, "(${topic.search}) -is:new")
                // Mastery must match desktop EXACTLY. Desktop's live path
                // (dashboard -> exam_p.coverage_report) calls
                // Collection.topic_mastery with mastered_threshold=0.0, which
                // the Rust backend (topic_mastery.rs) maps to its default of
                // 0.9. So a card counts as "mastered" iff its current FSRS
                // retrievability >= 0.9. Count that top bucket directly here
                // (a reviewed, non-new card with prop:r>=0.9), instead of every
                // card that merely has a retrievability value.
                val mastered = count(col, "(${topic.search}) -is:new prop:r>=0.9")
                val avgR = estimateRetrievability(col, topic.search, reviewed)
                TopicReport(
                    key = topic.key,
                    name = topic.name,
                    weight = topic.weight,
                    totalCards = total,
                    reviewedCards = reviewed,
                    masteredCards = mastered,
                    totalReviews = reviewed,
                    averageRetrievability = avgR,
                    status = classifyStatus(total, reviewed, mastered),
                )
            }
        val totalWeight = SYLLABUS.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
        val coverage = reports.filter { it.totalCards > 0 }.sumOf { it.weight }
        val studied = reports.sumOf { it.weight * it.reviewedFraction }
        val mastered = reports.sumOf { it.weight * it.masteredFraction }
        return CoverageReport(
            topics = reports,
            coveragePercent = coverage / totalWeight * 100.0,
            studiedCoveragePercent = studied / totalWeight * 100.0,
            masteredPercent = mastered / totalWeight * 100.0,
        )
    }

    /**
     * Estimate mean FSRS retrievability over reviewed cards of a topic using
     * Anki's `prop:r` search in buckets. Returns the average retrievability.
     * The bucket `counted` total is used only for this average (it is NOT the
     * mastered count; mastery is the >=0.9 top bucket, see `coverageReport`).
     * If FSRS is off (search unsupported), returns 0.0 so Memory reports
     * "not enough data" rather than a fabricated value.
     */
    private fun estimateRetrievability(
        col: Collection,
        search: String,
        reviewed: Int,
    ): Double {
        if (reviewed == 0) return 0.0
        return try {
            var sum = 0.0
            var counted = 0
            var lo = 0.0
            while (lo < 1.0) {
                val hi = min(lo + 0.1, 1.0)
                val q = "($search) -is:new prop:r>=$lo prop:r<${hi + 1e-9}"
                val c = col.findCards(q).size
                sum += c * (lo + hi) / 2.0
                counted += c
                lo += 0.1
            }
            if (counted == 0) 0.0 else round4(sum / counted)
        } catch (e: Exception) {
            0.0
        }
    }

    private fun classifyStatus(
        total: Int,
        reviewed: Int,
        mastered: Int,
    ): String =
        when {
            total == 0 || reviewed == 0 -> NOT_STARTED
            mastered.toDouble() / total >= MASTERED_FRACTION -> MASTERED
            reviewed.toDouble() / total >= COVERED_FRACTION -> COVERED
            else -> IN_PROGRESS
        }

    private fun reviewedCardCount(col: Collection): Int = count(col, "deck:* -is:new")

    /**
     * Total graded reviews across the whole collection, for the readiness
     * give-up rule. This must match desktop EXACTLY: desktop's
     * `dashboard._total_graded_reviews` calls `topic_mastery([("All", "deck:*")])`
     * and reads `total_reviews`, which the Rust engine computes as the sum of
     * each card's `reps` over every deck (so a card answered 10 times counts as
     * 10). We mirror that with a single scalar `sum(reps)` over all cards
     * (`deck:*` matches all cards), which is the exact equivalent. This is NOT
     * the count of reviewed cards within the ExamP tags.
     */
    fun totalGradedReviews(col: Collection): Int =
        try {
            col.db.queryScalar("select coalesce(sum(reps), 0) from cards")
        } catch (e: Exception) {
            0
        }

    // ------------------------------------------------------------------------
    // Measurements: Memory / Performance / Readiness (kept separate)
    // ------------------------------------------------------------------------
    data class MemoryScore(
        val point: Double,
        val low: Double,
        val high: Double,
        val reviewedCards: Int,
        val available: Boolean,
    )

    data class PerformanceScore(
        val point: Double,
        val low: Double,
        val high: Double,
        val answered: Int,
        val available: Boolean,
    )

    data class Readiness(
        val available: Boolean,
        val projectedScore: Double?,
        val scoreLow: Double?,
        val scoreHigh: Double?,
        val passProbability: Double?,
        val confidenceLevel: String,
        val coveragePercent: Double,
        val evidence: List<String>,
        val missingEvidence: List<String>,
    )

    private fun clamp(
        v: Double,
        lo: Double = 0.0,
        hi: Double = 1.0,
    ) = maxOf(lo, min(hi, v))

    private fun margin(n: Int) = min(0.25, 0.5 / sqrt(n + 1.0))

    fun computeMemory(cov: CoverageReport): MemoryScore {
        var totalWeight = 0.0
        var weighted = 0.0
        var reviewed = 0
        for (t in cov.topics) {
            if (t.reviewedCards > 0 && t.averageRetrievability > 0.0) {
                totalWeight += t.weight
                weighted += t.weight * t.averageRetrievability
                reviewed += t.reviewedCards
            }
        }
        if (reviewed == 0 || totalWeight == 0.0) return MemoryScore(0.0, 0.0, 0.0, 0, false)
        val point = clamp(weighted / totalWeight)
        val m = margin(reviewed)
        return MemoryScore(round4(point), round4(clamp(point - m)), round4(clamp(point + m)), reviewed, true)
    }

    fun computePerformance(diag: DiagnosticResult?): PerformanceScore {
        if (diag == null || diag.answered == 0) return PerformanceScore(0.0, 0.0, 0.0, 0, false)
        var totalWeight = 0.0
        var weighted = 0.0
        for (t in diag.topics) {
            val w = topicWeights[t.topicKey] ?: 0.0
            totalWeight += w
            weighted += w * t.accuracy
        }
        val point = if (totalWeight > 0) clamp(weighted / totalWeight) else diag.overallAccuracy
        val m = margin(diag.answered)
        return PerformanceScore(round4(point), round4(clamp(point - m)), round4(clamp(point + m)), diag.answered, true)
    }

    fun computeReadiness(
        cov: CoverageReport,
        mem: MemoryScore,
        perf: PerformanceScore,
        totalReviews: Int,
    ): Readiness {
        val coverage = cov.coveragePercent
        val evidence = mutableListOf<String>()
        val missing = mutableListOf<String>()
        evidence.add("$totalReviews graded reviews")
        evidence.add("${coverage.toInt()}% of the syllabus covered")
        if (perf.available) evidence.add("diagnostic: ${perf.answered} questions answered")

        if (totalReviews < MIN_REVIEWS_FOR_READINESS) {
            missing.add("Need >= $MIN_REVIEWS_FOR_READINESS graded reviews (have $totalReviews).")
        }
        if (coverage < MIN_COVERAGE_FOR_READINESS) {
            missing.add("Need >= ${MIN_COVERAGE_FOR_READINESS.toInt()}% syllabus coverage (have ${coverage.toInt()}%).")
        }
        if (!perf.available) missing.add("Complete the diagnostic assessment.")

        if (missing.isNotEmpty()) {
            return Readiness(false, null, null, null, null, "none", round1(coverage), evidence, missing)
        }
        val blend = W_PERFORMANCE * perf.point + W_MEMORY * mem.point
        val blendLow = W_PERFORMANCE * perf.low + W_MEMORY * mem.low
        val blendHigh = W_PERFORMANCE * perf.high + W_MEMORY * mem.high
        val level =
            when {
                coverage >= 80 && totalReviews >= 500 && perf.answered >= 10 -> "high"
                coverage >= MIN_COVERAGE_FOR_READINESS && totalReviews >= MIN_REVIEWS_FOR_READINESS -> "medium"
                else -> "low"
            }
        return Readiness(
            available = true,
            projectedScore = round1(blend * EXAM_SCALE_MAX),
            scoreLow = round1(blendLow * EXAM_SCALE_MAX),
            scoreHigh = round1(blendHigh * EXAM_SCALE_MAX),
            passProbability = round2(clamp((blend - 0.4) / 0.4)),
            confidenceLevel = level,
            coveragePercent = round1(coverage),
            evidence = evidence,
            missingEvidence = emptyList(),
        )
    }

    // ------------------------------------------------------------------------
    // Study planner (deterministic; match desktop planner)
    // ------------------------------------------------------------------------
    data class TopicPriority(
        val topicKey: String,
        val topicName: String,
        val score: Double,
        var recommendedHours: Double,
        val reasons: List<String>,
    )

    data class StudyPlan(
        val mode: String,
        val weeklyHours: Double,
        val nextTopicKey: String?,
        val priorities: List<TopicPriority>,
        val summary: String,
    )

    fun buildStudyPlan(
        col: Collection,
        cov: CoverageReport,
        today: LocalDate = LocalDate.now(),
    ): StudyPlan {
        val diag = loadDiagnostic(col)
        val diagAcc = diag?.topics?.associate { it.topicKey to it.accuracy } ?: emptyMap()
        // Feature 1: confidence-authority multiplier (synced). Scales how
        // strongly a topic's demonstrated "knownness" suppresses its review
        // priority. Defaults to 1.0 when no calibration exists.
        val authority = BrainLiftCalibration.calibrationMultiplier(col)
        val profile = loadOnboarding(col)
        val mode: String
        val weeklyHours: Double
        if (profile != null) {
            mode = evaluateOnboarding(col, profile, today).mode
            weeklyHours = profile.weeklyStudyHours
        } else {
            mode = DURABLE
            weeklyHours = 0.0
        }

        val priorities =
            cov.topics
                .map { t ->
                    val importance = t.weight / MAX_WEIGHT
                    val masteryGap = BrainLiftCalibration.effectiveMasteryGap(t.masteredFraction, authority)
                    val coverageGap = if (t.totalCards == 0) 1.0 else 1.0 - t.reviewedFraction
                    val diagnosticGap = if (diagAcc.containsKey(t.key)) 1.0 - diagAcc[t.key]!! else NEUTRAL_DIAGNOSTIC_GAP
                    val score =
                        W_IMPORTANCE * importance +
                            W_MASTERY_GAP * masteryGap +
                            W_DIAGNOSTIC_GAP * diagnosticGap +
                            W_COVERAGE_GAP * coverageGap
                    val reasons = mutableListOf<String>()
                    if (t.totalCards == 0) {
                        reasons.add("No cards yet for this topic (gap in coverage).")
                    } else if (t.reviewedFraction < 0.5) {
                        reasons.add("Most cards in this topic have not been studied.")
                    }
                    if (diagAcc.containsKey(t.key) && diagAcc[t.key]!! < 0.5) {
                        reasons.add("Low diagnostic accuracy (${(diagAcc[t.key]!! * 100).toInt()}%).")
                    }
                    if (importance >= 0.9) reasons.add("High-weight topic on the exam.")
                    if (t.masteredFraction < 0.5 && t.totalCards > 0) reasons.add("Few cards mastered so far.")
                    if (reasons.isEmpty()) reasons.add("On track; keep reviewing to maintain mastery.")
                    TopicPriority(t.key, t.name, round4(score), 0.0, reasons)
                }.sortedByDescending { it.score }

        val allocatable = if (mode == CRAMMING) priorities.take(CRAM_FOCUS_TOPICS) else priorities
        val totalScore = allocatable.sumOf { it.score }.takeIf { it > 0 } ?: 1.0
        for (p in allocatable) {
            p.recommendedHours = roundHalf(weeklyHours * p.score / totalScore)
        }

        val nextTopic = priorities.firstOrNull()?.topicKey
        return StudyPlan(mode, weeklyHours, nextTopic, priorities, planSummary(mode, cov, priorities, weeklyHours))
    }

    private fun planSummary(
        mode: String,
        cov: CoverageReport,
        priorities: List<TopicPriority>,
        weeklyHours: Double,
    ): String {
        val top = priorities.firstOrNull()?.topicName ?: "your weakest topic"
        val c = "${cov.coveragePercent.toInt()}% of the syllabus is covered by your deck"
        return when (mode) {
            CRAMMING -> "Cramming mode: with limited time, focus your ${fmt(
                weeklyHours,
            )} weekly hours on the highest-impact topics, starting with $top. $c."
            TIGHT -> "Tight schedule: prioritize $top and the other high-weight gaps. Consider adding study hours. $c."
            EXAM_PASSED -> "Your exam date is today or in the past. Update it to get a fresh plan."
            else -> "Durable plan: steady, spaced study across the syllabus, beginning with $top. $c."
        }
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------
    private fun getObjectOrNull(
        col: Collection,
        key: String,
    ): JSONObject? {
        val sentinel = JSONObject().put("__missing__", true)
        val o = col.config.getObject(key, sentinel)
        return if (o.optBoolean("__missing__", false)) null else o
    }

    private fun safeDiv(
        n: Double,
        d: Double,
    ) = if (d != 0.0) n / d else 0.0

    // The desktop reference engine rounds with Python's round(), which is
    // round-half-to-even (banker's rounding). Math.round rounds halves up and
    // would drift from desktop on exact .5 cases, so use Math.rint (half-to-even)
    // to keep every measurement — including the Performance point and its
    // low/high range — numerically identical to desktop for identical inputs.
    private fun round1(v: Double) = Math.rint(v * 10.0) / 10.0

    private fun round2(v: Double) = Math.rint(v * 100.0) / 100.0

    private fun round4(v: Double) = Math.rint(v * 10000.0) / 10000.0

    private fun roundHalf(v: Double) = Math.round(v * 2.0) / 2.0

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
