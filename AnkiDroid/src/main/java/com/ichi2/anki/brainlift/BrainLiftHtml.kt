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
    // BrainLift design tokens — same palette as the desktop app (landing,
    // dashboard, and skinned Anki chrome), with dark-mode variants applied when
    // the system/WebView reports a dark color scheme.
    private const val CSS = """
      :root {
        --bl-bg: #f6f7fb;
        --bl-surface: #ffffff;
        --bl-surface-2: #eef1f8;
        --bl-border: #e4e7f0;
        --bl-row-line: #eef0f6;
        --bl-text: #1b1e28;
        --bl-text-2: #575d70;
        --bl-text-3: #8a90a3;
        --bl-primary: #4f6bed;
        --bl-primary-active: #3a50c4;
        --bl-primary-tint: #edf1fe;
        --bl-primary-tint-border: #dbe2fc;
        --bl-success: #178c53;
        --bl-success-tint: #e3f4ea;
        --bl-warn: #b3590a;
        --bl-hero-a: #5068ea;
        --bl-hero-b: #3d51cd;
        --bl-shadow-card: 0 1px 2px rgba(18,22,45,.05), 0 1px 6px rgba(18,22,45,.04);
      }
      @media (prefers-color-scheme: dark) {
        :root {
          --bl-bg: #13151c;
          --bl-surface: #1c1f29;
          --bl-surface-2: #262b38;
          --bl-border: #2c3140;
          --bl-row-line: #262b37;
          --bl-text: #e8eaf3;
          --bl-text-2: #a4aabc;
          --bl-text-3: #737990;
          --bl-primary: #7387f2;
          --bl-primary-active: #6377e8;
          --bl-primary-tint: rgba(115,135,242,.14);
          --bl-primary-tint-border: rgba(115,135,242,.32);
          --bl-success: #46c088;
          --bl-success-tint: rgba(70,192,136,.15);
          --bl-warn: #e09a4e;
          --bl-hero-a: #4358cf;
          --bl-hero-b: #333fa8;
          --bl-shadow-card: 0 1px 2px rgba(0,0,0,.4);
        }
      }
      * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
      body { font-family: -apple-system, Roboto, "Segoe UI", sans-serif; margin: 0;
             background: var(--bl-bg); color: var(--bl-text); padding: 16px 16px 44px;
             -webkit-font-smoothing: antialiased; }
      h1 { font-size: 20px; margin: 4px 0 2px; letter-spacing: -.02em; }
      .sub { color: var(--bl-text-2); font-size: 13px; line-height: 1.5; margin: 2px 0 12px; }
      .card { background: var(--bl-surface); border: 1px solid var(--bl-border);
              border-radius: 14px; padding: 16px; margin: 0 0 12px;
              box-shadow: var(--bl-shadow-card); }
      .hero { background: linear-gradient(160deg,var(--bl-hero-a),var(--bl-hero-b));
              color:#fff; border-color: transparent;
              box-shadow: 0 8px 24px rgba(61,81,205,.22); }
      .hero h1, .hero .sub { color:#fff; }
      .hero .sub { opacity:.92; margin-bottom: 2px; }
      .row { display:flex; gap:10px; }
      .metric { flex:1; text-align:center; padding:6px 0 2px; }
      .metric .big { font-size: 26px; font-weight: 700; letter-spacing: -.02em;
                     font-variant-numeric: tabular-nums; }
      .metric .lbl { font-size: 11px; color: var(--bl-text-3); font-weight: 600;
                     text-transform:uppercase; letter-spacing:.06em; margin-bottom: 2px; }
      .metric .rng { font-size: 12px; color: var(--bl-text-3); margin-top:2px;
                     font-variant-numeric: tabular-nums; }
      .na { color: var(--bl-text-3); font-weight:600; }
      .btn { display:block; width:100%; border:0; border-radius:11px; padding:14px;
             font-size:15px; font-weight:600; color:#fff; background: var(--bl-primary);
             margin:10px 0 0; text-align:center;
             transition: background .12s ease, transform .06s ease; }
      .btn:active { background: var(--bl-primary-active); transform: scale(.99); }
      .btn.secondary { background: var(--bl-primary-tint); color: var(--bl-primary);
             border:1px solid var(--bl-primary-tint-border); }
      .btn.secondary:active { background: var(--bl-surface-2); transform: scale(.99); }
      .btn.ghost { background: var(--bl-surface-2); color: var(--bl-text-2); }
      .btn.ghost:active { background: var(--bl-border); }
      .step { display:flex; align-items:center; gap:12px; padding:12px 0;
              border-top:1px solid var(--bl-row-line); }
      .step:first-child { border-top:0; }
      .pill { font-size:10px; font-weight:600; padding:3px 9px; border-radius:20px;
              text-transform:uppercase; letter-spacing:.04em; }
      .pill.done { background: var(--bl-success-tint); color: var(--bl-success); }
      .pill.todo { background: var(--bl-primary-tint); color: var(--bl-primary); }
      .stepbody { flex:1; }
      .stepbody .t { font-weight:600; letter-spacing:-.01em; }
      .stepbody .d { font-size:13px; line-height:1.5; color: var(--bl-text-2);
                     margin-top:1px; }
      table { width:100%; border-collapse:collapse; font-size:13px;
              font-variant-numeric: tabular-nums; }
      td, th { text-align:left; padding:9px 4px;
               border-bottom:1px solid var(--bl-row-line); }
      tr:last-child td { border-bottom:0; }
      th { color: var(--bl-text-3); font-size:11px; font-weight:600;
           text-transform:uppercase; letter-spacing:.06em; }
      .bar { height:6px; border-radius:4px; background: var(--bl-surface-2);
             overflow:hidden; }
      .bar>i { display:block; height:100%; border-radius:4px;
               background: var(--bl-primary); }
      .tag { font-size:11px; font-weight:600; padding:2px 8px; border-radius:12px;
             background: var(--bl-surface-2); color: var(--bl-text-2); }
      ul { margin:6px 0 0; padding-left:18px; }
      li { margin:3px 0; font-size:13px; line-height:1.5; color: var(--bl-text-2); }
      .choice { display:block; width:100%; text-align:left; padding:14px;
                border:1px solid var(--bl-border); border-radius:11px;
                background: var(--bl-surface); color: var(--bl-text); margin:8px 0;
                font-size:15px; transition: background .12s ease,
                border-color .12s ease, color .12s ease; }
      .choice:active { border-color: var(--bl-primary-tint-border);
                background: var(--bl-primary-tint); }
      .choice.sel { background: var(--bl-primary); color:#fff;
                border-color: var(--bl-primary); }
      label { font-size:12px; font-weight:600; color: var(--bl-text-2);
              display:block; margin:12px 0 4px; }
      input, select { width:100%; padding:12px; border:1px solid var(--bl-border);
              border-radius:10px; font-size:15px; background: var(--bl-surface);
              color: var(--bl-text); }
      input:focus, select:focus { outline:none; border-color: var(--bl-primary);
              box-shadow: 0 0 0 3px rgba(79,107,237,.18); }
      .muted { color: var(--bl-text-3); font-size:12px; line-height:1.5; }
    """

    private fun page(body: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\">" +
            "<style>$CSS</style></head><body>$body</body></html>"

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Percentages must render identically to the desktop app, whose Python
    // formatting (f"{x:.0f}") rounds half-to-even (banker's rounding). Math.round
    // rounds halves up and would disagree at .5 boundaries (e.g. 56.5 -> 57 vs
    // desktop's 56), so use Math.rint, which rounds half-to-even like Python.
    private fun pct(v: Double) = "${Math.rint(v).toLong()}%"

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
        sb.append(
            metric(
                "Memory",
                v.memory.available,
                v.memory.point,
                v.memory.low,
                v.memory.high,
                if (v.memory.available) v.memory.confidenceLevel else null,
            ),
        )
        sb.append(
            metric(
                "Performance",
                v.performance.available,
                v.performance.point,
                v.performance.low,
                v.performance.high,
                if (v.performance.available) v.performance.confidenceLevel else null,
            ),
        )
        sb.append("<div class=\"metric\"><div class=\"lbl\">Readiness</div>")
        if (v.readiness.available) {
            sb.append("<div class=\"big\">${v.readiness.projectedScore}</div>")
            sb.append("<div class=\"rng\">${v.readiness.scoreLow}–${v.readiness.scoreHigh} / 10 · ${v.readiness.confidenceLevel}</div>")
        } else {
            sb.append("<div class=\"big na\">N/A</div><div class=\"rng\">Not enough data</div>")
        }
        sb.append("</div></div>")
        // Per-score metadata (parity with desktop): confidence, coverage, why,
        // last-updated for Memory and Performance.
        sb.append(
            scoreMeta(
                "Memory",
                v.memory.available,
                v.memory.confidenceLevel,
                v.memory.coveragePercent,
                v.memory.lastUpdated,
                v.memory.reasons,
            ),
        )
        sb.append(
            scoreMeta(
                "Performance",
                v.performance.available,
                v.performance.confidenceLevel,
                v.performance.coveragePercent,
                v.performance.lastUpdated,
                v.performance.reasons,
            ),
        )
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
        sb.append("<button class=\"btn secondary\" onclick=\"cmd('calibrate')\">Confidence calibration</button>")
        sb.append("<button class=\"btn secondary\" onclick=\"cmd('settings')\">AI settings</button>")
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
        confidence: String? = null,
    ): String {
        val body =
            if (available) {
                val conf = if (confidence != null) "<div class=\"rng\">${esc(confidence)}</div>" else ""
                "<div class=\"big\">${pct(point * 100)}</div><div class=\"rng\">${pct(low * 100)}–${pct(high * 100)}</div>$conf"
            } else {
                "<div class=\"big na\">N/A</div><div class=\"rng\">Not enough data</div>"
            }
        return "<div class=\"metric\"><div class=\"lbl\">$label</div>$body</div>"
    }

    // Time formatting must match desktop (time.strftime("%Y-%m-%d %H:%M")).
    private fun fmtUpdated(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return fmt.format(java.util.Date(epochSeconds * 1000L))
    }

    /** Per-score metadata block: confidence, coverage, why-reasons, last-updated.
     * Mirrors the desktop dashboard `_score_meta` so the two stay in parity. */
    private fun scoreMeta(
        label: String,
        available: Boolean,
        confidence: String,
        coveragePercent: Double,
        lastUpdated: Long,
        reasons: List<String>,
    ): String {
        if (!available) return ""
        val sb = StringBuilder()
        sb.append("<div class=\"card\"><h1 style=\"font-size:15px\">${esc(label)} — how sure</h1>")
        sb.append(
            "<p class=\"sub\">Confidence: ${esc(confidence)} · coverage ${pct(coveragePercent)}</p>",
        )
        sb.append("<p class=\"muted\">Why:</p><ul>")
        for (r in reasons) sb.append("<li>${esc(r)}</li>")
        sb.append("</ul>")
        sb.append("<p class=\"muted\">Last updated: ${esc(fmtUpdated(lastUpdated))}</p>")
        sb.append("</div>")
        return sb.toString()
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

    // --- Feature 1: confidence calibration ----------------------------------
    fun calibration(
        cards: List<Triple<Long, String, String>>,
        analogs: List<BrainLiftAi.GeneratedAnalog>,
        aiUsed: Boolean,
    ): String {
        val fronts = JSONArray()
        for ((_, front, _) in cards) fronts.put(front)
        val qs = JSONArray()
        for (a in analogs) {
            qs.put(JSONObject().put("question", a.question).put("choices", JSONArray(a.choices)))
        }
        val order = JSONArray(BrainLiftCalibration.CONFIDENCE_ORDER)
        val aiNote =
            if (aiUsed) {
                "Analog questions are AI-generated; each traces to its source card."
            } else {
                "AI is off — analogs are generated deterministically (each still traces to its source card)."
            }
        val body =
            """
            <div class="card hero"><h1>Confidence calibration</h1><p class="sub" id="progress"></p></div>
            <div class="card">
              <div id="prompt" style="font-size:16px;font-weight:600;margin-bottom:6px"></div>
              <div id="choices"></div>
              <button class="btn" id="next" onclick="next()">Next</button>
              <p class="muted">${esc(aiNote)}</p>
            </div>
            <script>
              var FRONTS = $fronts, QS = $qs, ORDER = $order;
              var phase='confidence', i=0, chosen=-1, labels=[], answers=[];
              function render(){
                var c=document.getElementById('choices'); c.innerHTML=''; chosen=-1;
                if(phase==='confidence'){
                  document.getElementById('progress').innerText='Step 1 of 2 · Rate confidence '+(i+1)+' of '+FRONTS.length;
                  document.getElementById('prompt').innerText=FRONTS[i];
                  ORDER.forEach(function(lbl,idx){
                    var b=document.createElement('button'); b.className='choice'; b.innerText=lbl;
                    b.onclick=function(){ chosen=idx; Array.from(c.children).forEach(function(x){x.className='choice';}); b.className='choice sel'; };
                    c.appendChild(b);
                  });
                  document.getElementById('next').innerText=(i===FRONTS.length-1)?'Start analog questions':'Next';
                } else {
                  document.getElementById('progress').innerText='Step 2 of 2 · Analog '+(i+1)+' of '+QS.length;
                  document.getElementById('prompt').innerText=QS[i].question;
                  QS[i].choices.forEach(function(ch,idx){
                    var b=document.createElement('button'); b.className='choice'; b.innerText=ch;
                    b.onclick=function(){ chosen=idx; Array.from(c.children).forEach(function(x){x.className='choice';}); b.className='choice sel'; };
                    c.appendChild(b);
                  });
                  document.getElementById('next').innerText=(i===QS.length-1)?'Finish':'Next';
                }
              }
              function next(){
                if(chosen<0){ alert('Please choose an option'); return; }
                if(phase==='confidence'){ labels.push(ORDER[chosen]); if(i<FRONTS.length-1){i++;} else {phase='answer'; i=0;} render(); }
                else { answers.push(chosen); if(i<QS.length-1){i++; render();} else { Android.submitCalibration(JSON.stringify({labels:labels, chosen:answers})); } }
              }
              render();
            </script>
            """.trimIndent() + bridgeJs()
        return page(body)
    }

    fun calibrationResult(r: BrainLiftCalibration.CalibrationResult): String {
        val gamma = if (r.gamma == null) "n/a" else String.format("%+.2f", r.gamma)
        val body =
            """
            <div class="card hero"><h1>Calibration complete</h1>
              <p class="sub">${pct(r.accuracy * 100)} calibration accuracy</p></div>
            <div class="card">
              <p style="font-size:15px">${esc(r.explanation)}</p>
              <table>
                <tr><td>Mean deviation</td><td>${String.format("%.2f", r.mad)}</td></tr>
                <tr><td>Resolution (gamma)</td><td>$gamma</td></tr>
                <tr><td>Confidence authority applied to scheduling</td><td>${pct(r.authorityMultiplier * 100)}</td></tr>
              </table>
              <p class="muted">${if (r.aiUsed) "Analogs were AI-generated" else "AI was off — analogs generated deterministically"}; every analog traces to its source card.</p>
              <button class="btn" onclick="cmd('home')">Back to dashboard</button>
            </div>
            """.trimIndent() + bridgeJs()
        return page(body)
    }

    fun settings(
        aiEnabled: Boolean,
        model: String,
        testMode: Boolean,
        keyPresent: Boolean,
    ): String {
        fun onoff(b: Boolean) = if (b) "ON" else "OFF"
        val body =
            """
            <div class="card hero"><h1>BrainLift — AI settings</h1><p class="sub">All settings sync via the collection.</p></div>
            <div class="card">
              <div class="step"><div class="stepbody"><div class="t">AI features</div>
                <div class="d">OpenAI analog generation. Currently <b>${onoff(aiEnabled)}</b>.
                ${if (keyPresent) "An API key is set." else "No OPENAI_API_KEY detected — analogs fall back to the deterministic generator."}</div>
                <button class="btn secondary" style="margin-top:8px" onclick="cmd('ai_toggle')">Turn AI ${if (aiEnabled) "OFF" else "ON"}</button></div></div>
              <div class="step"><div class="stepbody"><div class="t">Model</div><div class="d">${esc(model)}</div></div></div>
              <div class="step"><div class="stepbody"><div class="t">Fatigue TEST MODE</div>
                <div class="d">Intervene immediately for testing. Currently <b>${onoff(testMode)}</b>.</div>
                <button class="btn secondary" style="margin-top:8px" onclick="cmd('testmode_toggle')">Turn TEST MODE ${if (testMode) "OFF" else "ON"}</button></div></div>
              <button class="btn" onclick="cmd('calibrate')">Run confidence calibration</button>
              <button class="btn ghost" onclick="cmd('home')">Back</button>
            </div>
            """.trimIndent() + bridgeJs()
        return page(body)
    }

    private fun bridgeJs(): String = "<script>function cmd(c){ Android.cmd(c); }</script>"
}
