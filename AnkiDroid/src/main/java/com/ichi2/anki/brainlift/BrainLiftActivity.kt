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

    private fun load(html: String) {
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun handleCommand(c: String) {
        when (c) {
            "onboard" -> renderOnboarding()
            "diagnostic" -> renderDiagnostic()
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
