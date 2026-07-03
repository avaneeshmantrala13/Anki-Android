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

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.account.AccountActivity
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import java.time.LocalDate

/**
 * BrainLift home for Android: a WebView-hosted, guided dashboard that mirrors the
 * desktop BrainLift experience (onboarding, diagnostic, three separate
 * measurements with ranges + give-up rule, coverage, study plan).
 *
 * All state is read from / written to the shared Anki collection config, so it
 * syncs to and from the desktop app through Anki's existing sync.
 */
class BrainLiftActivity : AnkiActivity() {
    private lateinit var webView: WebView

    // Feature 1: the calibration cards + generated analogs currently on screen,
    // held between rendering the test and scoring the submitted answers so the
    // (possibly AI-generated) analogs are graded exactly as shown.
    private var pendingCalibrationCards: List<Triple<Long, String, String>> = emptyList()
    private var pendingCalibrationAnalogs: List<BrainLiftAi.GeneratedAnalog> = emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(Bridge(), "Android")
            }
        setContentView(webView)
        renderDashboard()
    }

    // --- rendering -----------------------------------------------------------
    private fun computeView(col: Collection): BrainLiftView {
        val onboarding = BrainLiftEngine.loadOnboarding(col)
        val diag = BrainLiftEngine.loadDiagnostic(col)
        val coverage = BrainLiftEngine.coverageReport(col)
        val memory = BrainLiftEngine.computeMemory(coverage)
        val performance = BrainLiftEngine.computePerformance(diag)
        // Match desktop's give-up rule input exactly: the total number of
        // graded reviews across the WHOLE collection (sum of card reps over
        // deck:*), not the count of reviewed cards within the ExamP tags.
        val totalReviews = BrainLiftEngine.totalGradedReviews(col)
        val readiness = BrainLiftEngine.computeReadiness(coverage, memory, performance, totalReviews)
        val plan = BrainLiftEngine.buildStudyPlan(col, coverage, LocalDate.now())
        return BrainLiftView(
            onboarded = onboarding != null,
            onboarding = onboarding,
            hasDiagnostic = diag != null,
            coverage = coverage,
            memory = memory,
            performance = performance,
            readiness = readiness,
            plan = plan,
            totalReviews = totalReviews,
        )
    }

    private fun renderDashboard() {
        launchCatchingTask {
            val html = withCol { BrainLiftHtml.dashboard(computeView(this)) }
            load(html)
        }
    }

    private fun renderOnboarding() {
        launchCatchingTask {
            val existing = withCol { BrainLiftEngine.loadOnboarding(this) }
            load(BrainLiftHtml.onboarding(existing))
        }
    }

    private fun renderDiagnostic() {
        load(BrainLiftHtml.diagnostic())
    }

    private fun renderCalibration() {
        launchCatchingTask {
            // Select from the shared SOA seed bank (bundled asset generated from
            // the desktop source of truth) so desktop and Android calibrate on
            // the EXACT same questions — and, since the deterministic analog
            // generator is seeded off the source index, the same AI-off analogs.
            val cards = BrainLiftCalibration.seedCalibrationItems(this@BrainLiftActivity)
            val (analogs, aiUsed) =
                withCol {
                    val analogs = BrainLiftCalibration.buildCalibrationQuestions(this, cards)
                    Pair(analogs, BrainLiftAi.aiEnabled(this) && BrainLiftAi.apiKeyFromEnv() != null)
                }
            pendingCalibrationCards = cards
            pendingCalibrationAnalogs = analogs
            if (cards.isEmpty()) {
                renderDashboard()
            } else {
                load(BrainLiftHtml.calibration(cards, analogs, aiUsed))
            }
        }
    }

    private fun submitCalibrationAndRender(json: String) {
        launchCatchingTask {
            val o = org.json.JSONObject(json)
            val labelsArr = o.optJSONArray("labels") ?: JSONArray()
            val chosenArr = o.optJSONArray("chosen") ?: JSONArray()
            val labels = (0 until labelsArr.length()).map { labelsArr.getString(it) }
            val chosen = (0 until chosenArr.length()).map { chosenArr.getInt(it) }
            val cards = pendingCalibrationCards
            val analogs = pendingCalibrationAnalogs
            val now =
                java.time.Instant
                    .now()
                    .epochSecond
            val result =
                withCol {
                    BrainLiftCalibration.runCalibration(this, cards, analogs, labels, chosen, now)
                }
            load(BrainLiftHtml.calibrationResult(result))
        }
    }

    private fun renderSettings() {
        launchCatchingTask {
            val html =
                withCol {
                    BrainLiftHtml.settings(
                        aiEnabled = BrainLiftAi.aiEnabled(this),
                        model = BrainLiftAi.aiModel(this),
                        testMode = BrainLiftFatigue.testMode(this),
                        keyPresent = BrainLiftAi.apiKeyFromEnv() != null,
                    )
                }
            load(html)
        }
    }

    private fun toggleAiAndRender() {
        launchCatchingTask {
            withCol { BrainLiftAi.setAiEnabled(this, !BrainLiftAi.aiEnabled(this)) }
            renderSettings()
        }
    }

    private fun toggleTestModeAndRender() {
        launchCatchingTask {
            withCol { BrainLiftFatigue.setTestMode(this, !BrainLiftFatigue.testMode(this)) }
            renderSettings()
        }
    }

    private fun load(html: String) {
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun handleCommand(c: String) {
        when (c) {
            "onboard" -> renderOnboarding()
            "diagnostic" -> renderDiagnostic()
            "calibrate" -> renderCalibration()
            "settings" -> renderSettings()
            "ai_toggle" -> toggleAiAndRender()
            "testmode_toggle" -> toggleTestModeAndRender()
            "study" -> finish()
            "account" -> openAccount()
            else -> renderDashboard()
        }
    }

    private fun openAccount() {
        startActivity(AccountActivity.getIntent(this))
    }

    private fun saveOnboardingAndRender(json: String) {
        launchCatchingTask {
            val input = OnboardingParser.parse(json)
            withCol { BrainLiftEngine.saveOnboarding(this, input) }
            renderDashboard()
        }
    }

    private fun runDiagnosticAndRender(json: String) {
        launchCatchingTask {
            val responses = DiagnosticParser.parse(json)
            withCol { BrainLiftEngine.runDiagnostic(this, responses) }
            renderDashboard()
        }
    }

    // --- JS bridge (methods run on a binder thread -> hop to the UI thread) --
    private inner class Bridge {
        @JavascriptInterface
        fun cmd(c: String) {
            runOnUiThread { handleCommand(c) }
        }

        @JavascriptInterface
        fun submitOnboarding(json: String) {
            runOnUiThread { saveOnboardingAndRender(json) }
        }

        @JavascriptInterface
        fun submitDiagnostic(json: String) {
            runOnUiThread { runDiagnosticAndRender(json) }
        }

        @JavascriptInterface
        fun submitCalibration(json: String) {
            runOnUiThread { submitCalibrationAndRender(json) }
        }
    }
}

private object OnboardingParser {
    fun parse(json: String): BrainLiftEngine.OnboardingInput {
        val o = org.json.JSONObject(json)
        return BrainLiftEngine.OnboardingInput(
            examDate = o.optString("exam_date"),
            goalScore = o.optDouble("goal_score", 0.0),
            weeklyStudyHours = o.optDouble("weekly_study_hours", 0.0),
            previousAttempts = o.optInt("previous_attempts", 0),
            priorExperience = o.optString("prior_experience", BrainLiftEngine.EXPERIENCE_NONE),
        )
    }
}

private object DiagnosticParser {
    fun parse(json: String): List<BrainLiftEngine.DiagnosticResponse> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BrainLiftEngine.DiagnosticResponse(
                questionId = o.optString("question_id"),
                chosenIndex = o.optInt("chosen_index", -1),
                timeSeconds = o.optDouble("time_seconds", 0.0),
                confidence = o.optDouble("confidence", 0.5),
            )
        }
    }
}
