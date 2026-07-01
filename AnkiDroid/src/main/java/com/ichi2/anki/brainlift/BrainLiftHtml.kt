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

import com.ichi2.anki.brainlift.BrainLiftEngine.CoverageReport
import com.ichi2.anki.brainlift.BrainLiftEngine.MemoryScore
import com.ichi2.anki.brainlift.BrainLiftEngine.OnboardingInput
import com.ichi2.anki.brainlift.BrainLiftEngine.PerformanceScore
import com.ichi2.anki.brainlift.BrainLiftEngine.Readiness
import com.ichi2.anki.brainlift.BrainLiftEngine.StudyPlan
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot of everything the dashboard needs, computed on the collection thread. */
data class BrainLiftView(
    val onboarded: Boolean,
    val onboarding: OnboardingInput?,
    val hasDiagnostic: Boolean,
    val coverage: CoverageReport,
    val memory: MemoryScore,
    val performance: PerformanceScore,
    val readiness: Readiness,
    val plan: StudyPlan,
    val totalReviews: Int,
)

/**
 * Renders the BrainLift screens as HTML for the WebView. Mobile-fitted styling,
 * but the same information and honest-uncertainty rules as the desktop app.
 */
object BrainLiftHtml {
    private const val CSS = """
      * { box-sizing: border-box; }
      body { font-family: -apple-system, Roboto, "Segoe UI", sans-serif; margin: 0;
             background: #f4f5f9; color: #1d1d24; padding: 14px 14px 40px; }
      h1 { font-size: 21px; margin: 4px 0 2px; }
      .sub { color: #66667a; font-size: 13px; margin: 0 0 14px; }
      .card { background: #fff; border-radius: 14px; padding: 16px; margin: 0 0 12px;
              box-shadow: 0 1px 4px rgba(30,30,60,.08); }
      .hero { background: linear-gradient(135deg,#4f6bed,#7d5be0); color:#fff; }
      .hero h1, .hero .sub { color:#fff; }
      .row { display:flex; gap:10px; }
      .metric { flex:1; text-align:center; }
      .metric .big { font-size: 26px; font-weight: 800; }
      .metric .lbl { font-size: 12px; color:#66667a; text-transform:uppercase; letter-spacing:.04em; }
      .metric .rng { font-size: 12px; color:#8a8aa0; margin-top:2px; }
      .na { color:#b23b3b; font-weight:700; }
      .btn { display:block; width:100%; border:0; border-radius:11px; padding:14px;
             font-size:16px; font-weight:700; color:#fff; background:#4f6bed; margin:8px 0 0;
             text-align:center; }
      .btn.secondary { background:#eceeff; color:#3b3b7a; border:1px solid #d7dcff; }
      .btn.ghost { background:#f0f1f6; color:#3b3b7a; }
      .step { display:flex; align-items:center; gap:12px; padding:10px 0; border-top:1px solid #eef; }
      .step:first-child { border-top:0; }
      .pill { font-size:11px; font-weight:700; padding:3px 9px; border-radius:20px; }
      .pill.done { background:#e3f5e9; color:#1c7c43; }
      .pill.todo { background:#fdeee0; color:#b5651d; }
      .stepbody { flex:1; }
      .stepbody .t { font-weight:700; }
      .stepbody .d { font-size:13px; color:#66667a; }
      table { width:100%; border-collapse:collapse; font-size:14px; }
      td, th { text-align:left; padding:7px 4px; border-bottom:1px solid #f0f0f6; }
      th { color:#66667a; font-size:12px; text-transform:uppercase; }
      .bar { height:8px; border-radius:5px; background:#eef; overflow:hidden; }
      .bar>i { display:block; height:100%; background:#4f6bed; }
      .tag { font-size:11px; padding:2px 8px; border-radius:12px; background:#eef; color:#3b3b7a; }
      ul { margin:6px 0 0; padding-left:18px; } li { margin:3px 0; font-size:13px; }
      .choice { display:block; width:100%; text-align:left; padding:14px; border:1px solid #d7dcff;
                border-radius:11px; background:#fff; margin:8px 0; font-size:15px; }
      .choice.sel { background:#4f6bed; color:#fff; border-color:#4f6bed; }
      label { font-size:13px; color:#66667a; display:block; margin:10px 0 4px; }
      input, select { width:100%; padding:12px; border:1px solid #d7dcff; border-radius:10px; font-size:15px; }
      .muted { color:#8a8aa0; font-size:12px; }
    """

    private fun page(body: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">" +
            "<style>$CSS</style></head><body>$body</body></html>"

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun pct(v: Double) = "${Math.round(v)}%"

    // --- Landing + dashboard -------------------------------------------------
    fun dashboard(v: BrainLiftView): String {
        val sb = StringBuilder()
        sb.append(
            """<div class="card hero"><h1>BrainLift — Exam P</h1>
               <p class="sub">Memory, Performance and Readiness — measured honestly, no AI.</p></div>""",
        )

        // Guided steps
        sb.append("<div class=\"card\"><h1 style=\"font-size:17px\">Your path</h1>")
        sb.append(
            step(
                done = v.onboarded,
                title = "1. Set up your exam plan",
                desc =
                    if (v.onboarded) {
                        "Exam ${esc(
                            v.onboarding?.examDate ?: "",
                        )}, goal ${v.onboarding?.goalScore?.toInt()}"
                    } else {
                        "Tell us your exam date, goal and study time."
                    },
                cmd = "onboard",
                btn = if (v.onboarded) "Edit plan" else "Start onboarding",
            ),
        )
        sb.append(
            step(
                done = v.hasDiagnostic,
                title = "2. Take the diagnostic",
                desc =
                    if (v.hasDiagnostic) {
                        "Performance: ${pct(
                            v.performance.point * 100,
                        )} accuracy"
                    } else {
                        "12 quick Exam P questions to find weak spots."
                    },
                cmd = "diagnostic",
                btn = if (v.hasDiagnostic) "Retake diagnostic" else "Start diagnostic",
            ),
        )
        sb.append(
            step(
                done = v.coverage.studiedCoveragePercent > 0,
                title = "3. Study your cards",
                desc = "Review with Anki's FSRS scheduler. ${pct(v.coverage.coveragePercent)} of the syllabus is in your deck.",
                cmd = "study",
                btn = "Go to decks",
            ),
        )
        sb.append(
            step(
                done = false,
                title = "4. Sign in to sync across devices",
                desc = "Log into (or create) your free AnkiWeb account so progress syncs between desktop and mobile.",
                cmd = "account",
                btn = "Log in / Sign up",
            ),
        )
        sb.append("</div>")

        // Three measurements
        sb.append("<div class=\"card\"><h1 style=\"font-size:17px\">Three measurements</h1><div class=\"row\">")
        sb.append(metric("Memory", v.memory.available, v.memory.point, v.memory.low, v.memory.high))
        sb.append(metric("Performance", v.performance.available, v.performance.point, v.performance.low, v.performance.high))
        sb.append("<div class=\"metric\"><div class=\"lbl\">Readiness</div>")
        if (v.readiness.available) {
            sb.append("<div class=\"big\">${v.readiness.projectedScore}</div>")
            sb.append("<div class=\"rng\">${v.readiness.scoreLow}–${v.readiness.scoreHigh} / 10 · ${v.readiness.confidenceLevel}</div>")
        } else {
            sb.append("<div class=\"big na\">N/A</div><div class=\"rng\">Not enough data</div>")
        }
        sb.append("</div></div>")
        // Readiness evidence / give-up explanation
        if (!v.readiness.available) {
            sb.append("<p class=\"muted\">Readiness is withheld until there is enough evidence:</p><ul>")
            for (m in v.readiness.missingEvidence) sb.append("<li>${esc(m)}</li>")
            sb.append("</ul>")
        } else {
            sb.append(
                "<p class=\"muted\">Based on: ${v.readiness.evidence.joinToString(
                    " · ",
                ) { esc(it) }}. Pass chance ~${pct((v.readiness.passProbability ?: 0.0) * 100)}.</p>",
            )
        }
        sb.append("</div>")

        // Coverage table
        sb.append("<div class=\"card\"><h1 style=\"font-size:17px\">Syllabus coverage</h1><table>")
        sb.append("<tr><th>Topic</th><th>Weight</th><th>Studied</th><th>Status</th></tr>")
        for (t in v.coverage.topics) {
            sb.append(
                "<tr><td>${esc(t.name)}</td><td>${t.weight.toInt()}%</td>" +
                    "<td><div class=\"bar\"><i style=\"width:${(t.reviewedFraction * 100).toInt()}%\"></i></div></td>" +
                    "<td><span class=\"tag\">${esc(t.status)}</span></td></tr>",
            )
        }
        sb.append("</table></div>")

        // Study plan
        sb.append("<div class=\"card\"><h1 style=\"font-size:17px\">Study plan (${esc(v.plan.mode)})</h1>")
        sb.append("<p class=\"sub\">${esc(v.plan.summary)}</p>")
        val top = v.plan.priorities.firstOrNull()
        if (top != null) {
            sb.append("<div class=\"t\" style=\"font-weight:700\">Study next: ${esc(top.topicName)}</div><ul>")
            for (r in top.reasons) sb.append("<li>${esc(r)}</li>")
            sb.append("</ul>")
        }
        sb.append("</div>")

        sb.append("<button class=\"btn\" onclick=\"cmd('study')\">Start studying</button>")
        sb.append("<button class=\"btn secondary\" onclick=\"cmd('account')\">Log in / Sign up (sync)</button>")
        sb.append("<button class=\"btn ghost\" onclick=\"cmd('refresh')\">Refresh</button>")
        sb.append(bridgeJs())
        return page(sb.toString())
    }

    private fun metric(
        label: String,
        available: Boolean,
        point: Double,
        low: Double,
        high: Double,
    ): String {
        val body =
            if (available) {
                "<div class=\"big\">${pct(point * 100)}</div><div class=\"rng\">${pct(low * 100)}–${pct(high * 100)}</div>"
            } else {
                "<div class=\"big na\">N/A</div><div class=\"rng\">Not enough data</div>"
            }
        return "<div class=\"metric\"><div class=\"lbl\">$label</div>$body</div>"
    }

    private fun step(
        done: Boolean,
        title: String,
        desc: String,
        cmd: String,
        btn: String,
    ): String {
        val pill = if (done) "<span class=\"pill done\">Done</span>" else "<span class=\"pill todo\">To do</span>"
        return "<div class=\"step\">$pill<div class=\"stepbody\"><div class=\"t\">${esc(title)}</div>" +
            "<div class=\"d\">${esc(desc)}</div>" +
            "<button class=\"btn secondary\" style=\"margin-top:8px\" onclick=\"cmd('$cmd')\">${esc(btn)}</button></div></div>"
    }

    // --- Onboarding form -----------------------------------------------------
    fun onboarding(existing: OnboardingInput?): String {
        val date = existing?.examDate ?: ""
        val goal = existing?.goalScore?.toInt() ?: 6
        val hours = existing?.weeklyStudyHours?.toInt() ?: 8
        val attempts = existing?.previousAttempts ?: 0
        val exp = existing?.priorExperience ?: "none"

        fun sel(v: String) = if (exp == v) "selected" else ""
        val body =
            """
            <div class="card hero"><h1>Set up your plan</h1><p class="sub">Deterministic — no AI. Syncs to your desktop.</p></div>
            <div class="card">
              <label>Exam date</label><input id="date" type="date" value="$date">
              <label>Goal score (0–10)</label><input id="goal" type="number" min="0" max="10" step="0.5" value="$goal">
              <label>Weekly study hours</label><input id="hours" type="number" min="0" step="1" value="$hours">
              <label>Previous Exam P attempts</label><input id="attempts" type="number" min="0" step="1" value="$attempts">
              <label>Prior probability experience</label>
              <select id="exp">
                <option value="none" ${sel("none")}>None</option>
                <option value="some" ${sel("some")}>Some</option>
                <option value="strong" ${sel("strong")}>Strong</option>
              </select>
              <button class="btn" onclick="submitOnboarding()">Save plan</button>
              <button class="btn ghost" onclick="cmd('home')">Cancel</button>
            </div>
            <script>
              function submitOnboarding() {
                var d = {
                  exam_date: document.getElementById('date').value,
                  goal_score: parseFloat(document.getElementById('goal').value||'0'),
                  weekly_study_hours: parseFloat(document.getElementById('hours').value||'0'),
                  previous_attempts: parseInt(document.getElementById('attempts').value||'0'),
                  prior_experience: document.getElementById('exp').value
                };
                if(!d.exam_date){ alert('Please choose an exam date'); return; }
                Android.submitOnboarding(JSON.stringify(d));
              }
            </script>
            """.trimIndent() + bridgeJs()
        return page(body)
    }

    // --- Diagnostic quiz -----------------------------------------------------
    fun diagnostic(): String {
        val qs = JSONArray()
        for (q in BrainLiftEngine.QUESTION_BANK) {
            qs.put(
                JSONObject()
                    .put("id", q.id)
                    .put("prompt", q.prompt)
                    .put("choices", JSONArray(q.choices)),
            )
        }
        val body =
            """
            <div class="card hero"><h1>Diagnostic</h1><p class="sub" id="progress"></p></div>
            <div class="card">
              <div id="prompt" style="font-size:16px;font-weight:600;margin-bottom:6px"></div>
              <div id="choices"></div>
              <label>How confident are you?</label>
              <select id="conf"><option value="0.25">Low</option><option value="0.5" selected>Medium</option><option value="0.9">High</option></select>
              <button class="btn" id="next" onclick="next()">Next</button>
            </div>
            <script>
              var QS = $qs;
              var i = 0, chosen = -1, t0 = Date.now(), responses = [];
              function render() {
                var q = QS[i];
                document.getElementById('progress').innerText = 'Question ' + (i+1) + ' of ' + QS.length;
                document.getElementById('prompt').innerText = q.prompt;
                var c = document.getElementById('choices'); c.innerHTML='';
                chosen = -1; t0 = Date.now();
                document.getElementById('conf').value = '0.5';
                q.choices.forEach(function(ch, idx){
                  var b = document.createElement('button');
                  b.className='choice'; b.innerText = ch;
                  b.onclick = function(){ chosen=idx; Array.from(c.children).forEach(function(x){x.className='choice';}); b.className='choice sel'; };
                  c.appendChild(b);
                });
                document.getElementById('next').innerText = (i===QS.length-1)?'Finish':'Next';
              }
              function next() {
                if(chosen<0){ alert('Please pick an answer'); return; }
                responses.push({question_id: QS[i].id, chosen_index: chosen,
                  time_seconds: (Date.now()-t0)/1000.0,
                  confidence: parseFloat(document.getElementById('conf').value)});
                i++;
                if(i>=QS.length){ Android.submitDiagnostic(JSON.stringify(responses)); }
                else render();
              }
              render();
            </script>
            """.trimIndent() + bridgeJs()
        return page(body)
    }

    private fun bridgeJs(): String = "<script>function cmd(c){ Android.cmd(c); }</script>"
}
