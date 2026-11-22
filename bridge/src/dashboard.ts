export function renderDashboardHtml(): string {
  return `<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>MinecraftHarness — Live AI Reasoning &amp; Telemetry</title>
  <style>
    :root {
      --bg: #070b14;
      --panel: #0f1626;
      --panel2: #141e33;
      --border: #23304d;
      --text: #e6edf7;
      --muted: #8ea0c0;
      --cyan: #22d3ee;
      --gold: #fbbf24;
      --green: #34d399;
      --purple: #a78bfa;
      --red: #f87171;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
      background: radial-gradient(circle at top right, #111b33, var(--bg) 55%);
      color: var(--text);
      min-height: 100vh;
      padding-bottom: 84px;
    }
    header {
      position: sticky;
      top: 0;
      z-index: 20;
      background: rgba(7, 11, 20, 0.9);
      backdrop-filter: blur(10px);
      border-bottom: 1px solid var(--border);
      padding: 12px 20px;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
    }
    .brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 16px; }
    .dot { width: 10px; height: 10px; border-radius: 999px; background: var(--green); box-shadow: 0 0 10px var(--green); }
    .dot.off { background: var(--red); box-shadow: 0 0 10px var(--red); }
    .controls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    select, button, input {
      background: var(--panel2);
      color: var(--text);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 6px 10px;
      font-size: 13px;
    }
    button { cursor: pointer; font-weight: 600; }
    button:hover { border-color: var(--cyan); }
    button.primary { background: #0e7490; border-color: #22d3ee; }
    main { max-width: 1200px; margin: 18px auto; padding: 0 16px; display: grid; gap: 16px; }
    .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; }
    .card {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 14px 16px;
    }
    .kpi-label { font-size: 12px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.05em; }
    .kpi-val { font-size: 26px; font-weight: 800; margin-top: 6px; font-variant-numeric: tabular-nums; }
    .kpi-sub { font-size: 12px; color: var(--muted); margin-top: 4px; font-variant-numeric: tabular-nums; }
    .bar-wrap { height: 10px; border-radius: 999px; overflow: hidden; background: #1e293b; display: flex; margin-top: 10px; }
    .bar-q { background: var(--purple); }
    .bar-p { background: var(--cyan); }
    .bar-c { background: var(--green); }
    .trace-list { display: grid; gap: 14px; }
    .trace-card {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 14px;
      overflow: hidden;
    }
    .trace-head {
      padding: 12px 16px;
      background: var(--panel2);
      border-bottom: 1px solid var(--border);
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 10px;
    }
    .badge {
      font-size: 11px;
      font-weight: 700;
      padding: 3px 8px;
      border-radius: 999px;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    .st-QUEUED { background: rgba(167, 139, 250, 0.2); color: var(--purple); border: 1px solid var(--purple); }
    .st-REASONING { background: rgba(34, 211, 238, 0.2); color: var(--cyan); border: 1px solid var(--cyan); }
    .st-TOOL_CALLING { background: rgba(251, 191, 36, 0.2); color: var(--gold); border: 1px solid var(--gold); }
    .st-RESPONDING { background: rgba(52, 211, 153, 0.2); color: var(--green); border: 1px solid var(--green); }
    .st-DONE { background: rgba(52, 211, 153, 0.16); color: var(--green); border: 1px solid #059669; }
    .st-ERROR { background: rgba(248, 113, 113, 0.2); color: var(--red); border: 1px solid var(--red); }
    .pills { display: flex; flex-wrap: wrap; gap: 6px; padding: 10px 16px; border-bottom: 1px solid var(--border); }
    .pill {
      background: #0a101d;
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 4px 8px;
      font-size: 12px;
      font-variant-numeric: tabular-nums;
      color: var(--muted);
    }
    .pill b { color: var(--text); }
    .section { padding: 12px 16px; border-bottom: 1px solid var(--border); }
    .section:last-child { border-bottom: none; }
    .sec-title {
      font-size: 12px;
      font-weight: 700;
      color: var(--cyan);
      margin-bottom: 8px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    pre.reasoning {
      margin: 0;
      background: #060911;
      border: 1px solid #1b253d;
      border-radius: 8px;
      padding: 10px 12px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12.5px;
      line-height: 1.5;
      color: #c7d9f7;
      white-space: pre-wrap;
      word-break: break-word;
      max-height: 260px;
      overflow-y: auto;
    }
    .tool-row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: baseline;
      padding: 6px 10px;
      background: #0a101d;
      border: 1px solid #1b253d;
      border-radius: 8px;
      font-size: 12.5px;
      margin-bottom: 6px;
      font-family: ui-monospace, Consolas, monospace;
    }
    .tool-ok { color: var(--green); font-weight: 700; }
    .tool-err { color: var(--red); font-weight: 700; }
    .prompt-bar {
      position: fixed;
      bottom: 0;
      left: 0;
      right: 0;
      background: rgba(7, 11, 20, 0.95);
      border-top: 1px solid var(--border);
      padding: 12px 20px;
      display: flex;
      gap: 10px;
      justify-content: center;
    }
    .prompt-bar input { flex: 1; max-width: 900px; font-size: 14px; padding: 9px 12px; }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      <span id="sseDot" class="dot off"></span>
      <span>MinecraftHarness — Live AI Reasoning &amp; Telemetry</span>
      <span id="phaseBadge" class="badge st-DONE">IDLE</span>
    </div>
    <div class="controls">
      <span id="sessionSummary" style="font-size:12px;color:var(--muted);">Calls: 0 | Tokens: 0</span>
      <label style="font-size:12px;color:var(--muted);">Model:
        <select id="modelSel" style="max-width:260px;"><option value="">loading...</option></select>
      </label>
      <label style="font-size:12px;color:var(--muted);">Thinking:
        <select id="thinkingSel">
          <option value="off">off</option>
          <option value="minimal">minimal</option>
          <option value="low">low</option>
          <option value="medium" selected>medium</option>
          <option value="high">high</option>
        </select>
      </label>
      <button id="clearBtn">Clear</button>
    </div>
  </header>

  <main>
    <section class="kpi-grid">
      <div class="card">
        <div class="kpi-label">TTFT (First Token)</div>
        <div class="kpi-val" id="kpiTtft">-</div>
        <div class="kpi-sub" id="kpiTtftSub">Compute prompt_time: -</div>
      </div>
      <div class="card">
        <div class="kpi-label">Model Speed (TPS)</div>
        <div class="kpi-val" id="kpiTps">-</div>
        <div class="kpi-sub" id="kpiTpsSub">Live / E2E: -</div>
      </div>
      <div class="card">
        <div class="kpi-label">Latency Breakdown</div>
        <div class="kpi-val" id="kpiElapsed">-</div>
        <div class="bar-wrap">
          <div id="barQ" class="bar-q" style="width:0%"></div>
          <div id="barP" class="bar-p" style="width:0%"></div>
          <div id="barC" class="bar-c" style="width:0%"></div>
        </div>
        <div class="kpi-sub" id="kpiLatSub">Queue: - | Prompt: - | Comp: -</div>
      </div>
      <div class="card">
        <div class="kpi-label">Tokens &amp; Prompt Cache</div>
        <div class="kpi-val" id="kpiCache">-</div>
        <div class="kpi-sub" id="kpiTokSub">Reason: - | Output: - | Prompt: -</div>
      </div>
    </section>

    <section class="trace-list" id="traceList">
      <div class="card" style="color:var(--muted);text-align:center;padding:32px;">
        まだ推論ログがありません。マイクラで <code>!プロンプト</code> を打つか、下の入力欄からテスト送信してください。
      </div>
    </section>
  </main>

  <form class="prompt-bar" id="promptForm">
    <input id="promptInput" placeholder="ここから直接テスト指示を送信 (例: 目の前に光るジャンプ台付きのネオン神殿を作って)" />
    <button type="submit" class="primary">Send to OpenClaw</button>
  </form>

  <script>
    const state = { traces: [], totals: {} };

    const esc = (s) => String(s ?? "")
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const fmtMs = (v) => (v === null || v === undefined) ? "-" : (v >= 1000 ? (v / 1000).toFixed(2) + "s" : Math.round(v) + "ms");
    const fmtNum = (v) => (v === null || v === undefined) ? "-" : Number(v).toLocaleString();

    function upsertTrace(t) {
      const idx = state.traces.findIndex((x) => x.id === t.id);
      if (idx >= 0) state.traces[idx] = t;
      else state.traces.unshift(t);
    }

    let preparing = false;
    function updateKpis() {
      const latest = state.traces[0];
      const badge = document.getElementById("phaseBadge");
      if (!latest || preparing) {
        badge.className = "badge st-DONE";
        badge.textContent = preparing ? "PREPARING" : "IDLE";
        document.getElementById("kpiTtft").textContent = "-";
        document.getElementById("kpiTtftSub").textContent = "Compute prompt_time: -";
        document.getElementById("kpiTps").textContent = "-";
        document.getElementById("kpiTpsSub").textContent = "Live / E2E: -";
        document.getElementById("kpiElapsed").textContent = "-";
        document.getElementById("kpiLatSub").textContent = "Queue: - | Prompt: - | Comp: -";
        document.getElementById("kpiCache").textContent = "-";
        document.getElementById("kpiTokSub").textContent = "Reason: - | Output: - | Prompt: -";
        return;
      }
      badge.className = "badge st-" + latest.status;
      badge.textContent = "Call #" + latest.callNumber + " " + latest.status;

      document.getElementById("kpiTtft").textContent = fmtMs(latest.ttftAttemptMs ?? latest.ttftWallMs);
      document.getElementById("kpiTtftSub").textContent =
        "Wall: " + fmtMs(latest.ttftWallMs) + " | Compute: " + fmtMs(latest.ttftComputeMs) +
        (latest.retryCount > 0 ? " | Retry wait: " + fmtMs(latest.retryWaitMs) : "");

      const mainTps = latest.serverTps ?? latest.liveTps;
      document.getElementById("kpiTps").textContent = mainTps !== null ? (latest.serverTps === null ? "~" : "") + mainTps + " tok/s" : "-";
      document.getElementById("kpiTpsSub").textContent =
        "Live: " + (latest.liveTps === null ? "-" : "~" + latest.liveTps + " tok/s") + " | E2E: " + (latest.e2eTps ?? "-") + " tok/s";

      document.getElementById("kpiElapsed").textContent = fmtMs(latest.elapsedMs);
      const q = latest.queueMs ?? 0, p = latest.promptMs ?? 0, c = latest.completionMs ?? 0;
      const tot = Math.max(1, q + p + c);
      document.getElementById("barQ").style.width = ((q / tot) * 100).toFixed(1) + "%";
      document.getElementById("barP").style.width = ((p / tot) * 100).toFixed(1) + "%";
      document.getElementById("barC").style.width = ((c / tot) * 100).toFixed(1) + "%";
      document.getElementById("kpiLatSub").textContent =
        "Queue: " + fmtMs(latest.queueMs) + " | Prompt: " + fmtMs(latest.promptMs) + " | Comp: " + fmtMs(latest.completionMs);

      document.getElementById("kpiCache").textContent =
        latest.cacheHitPct !== null ? latest.cacheHitPct + "% cached" : "-";
      document.getElementById("kpiTokSub").textContent =
        "Reason: " + fmtNum(latest.reasoningTokens) + " | Out: " + fmtNum(latest.outputTokens) + " | Prompt: " + fmtNum(latest.promptTokens);

      let sumTok = 0;
      for (const tr of state.traces) sumTok += tr.totalTokens || 0;
      document.getElementById("sessionSummary").textContent =
        "Calls: " + state.traces.length + " | Tokens: " + sumTok.toLocaleString();
    }

    function renderTraceCard(t) {
      const ttftDetail = (t.ttftAttemptMs !== null ? "attempt " + fmtMs(t.ttftAttemptMs) : fmtMs(t.ttftWallMs)) +
        (t.ttftComputeMs !== null ? " / compute " + fmtMs(t.ttftComputeMs) : "");
      const pills = [
        ["Model", t.model],
        ["Effort", t.reasoningEffort],
        ...(t.preInference ? [
          ["Bridge queue", fmtMs(t.preInference.queueMs)],
          ["Bridge prep", fmtMs(t.preInference.preparationMs)],
          ["OpenClaw → model", fmtMs(t.preInference.agentMs)],
          ["Receipt → token", fmtMs(t.firstTokenAt == null ? null : t.preInference.queueMs + t.preInference.preparationMs + t.preInference.agentMs + t.ttftWallMs)],
        ] : []),
        ["TTFT", ttftDetail],
        ["Queue", fmtMs(t.queueMs)],
        ["Comp", fmtMs(t.completionMs)],
        ["Server TPS", t.serverTps !== null ? t.serverTps + " tok/s" : (t.liveTps !== null ? "~" + t.liveTps + " tok/s" : "-")],
        ["E2E TPS", t.e2eTps !== null ? t.e2eTps + " tok/s" : "-"],
        ["Cache", t.cacheHitPct !== null ? t.cacheHitPct + "% (" + fmtNum(t.cachedTokens) + "/" + fmtNum(t.promptTokens) + ")" : "-"],
        ["Prompt Tok", fmtNum(t.promptTokens)],
        ["Tools", String(t.toolCount ?? 0)],
        ["Reason Tok", fmtNum(t.reasoningTokens)],
        ["Out Tok", fmtNum(t.outputTokens)],
        ["Elapsed", fmtMs(t.elapsedMs)],
        ...(t.retryCount > 0 ? [["Retries", t.retryCount + " (" + fmtMs(t.retryWaitMs) + ")"]] : []),
      ].map(([k, v]) => "<span class='pill'>" + esc(k) + ": <b>" + esc(v) + "</b></span>").join("");

      const usedResultIdx = new Set();
      const matchToolResult = (tcName) => {
        const idx = (t.toolResults || []).findIndex((r, i) =>
          !usedResultIdx.has(i) && (r.toolName === tcName || tcName.endsWith("__" + r.toolName))
        );
        if (idx >= 0) {
          usedResultIdx.add(idx);
          return t.toolResults[idx];
        }
        return null;
      };

      const imgHtml = (r) => r && r.imageDataUrl
        ? "<div style='width:100%;margin-top:6px;'><img src='" + esc(r.imageDataUrl) + "' alt='Minecraft Photo' style='max-width:340px;border-radius:8px;border:1px solid var(--border);display:block;' /></div>"
        : "";

      const toolsHtml = [
        ...(t.toolCalls || []).map((tc) => {
          const matched = matchToolResult(tc.name || "");
          const argText = tc.argumentsText || "";
          const argPrev = argText
            ? argText.slice(0, 160) + (argText.length > 160 ? "..." : "")
            : (tc.argsLen ? "(" + tc.argsLen + " chars streaming...)" : "");
          return "<div class='tool-row'><span>🔧 <b>" + esc(tc.name) + "</b></span>" +
            "<span style='color:var(--muted)'>" + esc(argPrev) + "</span>" +
            (matched ? "<span class='" + (matched.ok ? "tool-ok" : "tool-err") + "'>" + (matched.ok ? "OK" : "ERR") + " " + matched.durationMs + "ms — " + esc(matched.summary) + "</span>" : "") +
            imgHtml(matched) +
            "</div>";
        }),
        ...(t.toolResults || []).filter((_, i) => !usedResultIdx.has(i)).map((r) =>
          "<div class='tool-row'><span class='" + (r.ok ? "tool-ok" : "tool-err") + "'>⚙ " + esc(r.toolName) + " " + r.durationMs + "ms</span><span>" + esc(r.summary) + "</span>" + imgHtml(r) + "</div>"
        )
      ].join("");

      return "<article class='trace-card' id='card-" + esc(t.id) + "'>" +
        "<div class='trace-head'>" +
          "<div><b>#" + t.callNumber + "</b> <span style='color:var(--muted);font-size:13px;'>" + esc(t.promptText) + "</span></div>" +
          "<span class='badge st-" + t.status + "'>" + esc(t.status) + "</span>" +
        "</div>" +
        "<div class='pills'>" + pills + "</div>" +
        "<div class='section'>" +
          "<div class='sec-title'><span>🧠 Live Reasoning (" + esc((t.reasoningText || "").length) + " chars)</span>" +
          "<button type='button' data-id='" + esc(t.id) + "' onclick='copyReason(this.dataset.id)' style='padding:2px 8px;font-size:11px;'>Copy</button></div>" +
          "<pre class='reasoning' id='reason-" + esc(t.id) + "'>" + esc(t.reasoningText || (t.status === "QUEUED" ? "Waiting for model..." : "(No reasoning tokens emitted)")) + "</pre>" +
        "</div>" +
        (toolsHtml ? "<div class='section'><div class='sec-title'>🛠 Tool Calls &amp; PaperMC Execution</div>" + toolsHtml + "</div>" : "") +
        (t.contentText ? "<div class='section'><div class='sec-title'>💬 Assistant Content</div><pre class='reasoning' style='max-height:120px;'>" + esc(t.contentText) + "</pre></div>" : "") +
        (t.error ? "<div class='section'><div class='sec-title' style='color:var(--red);'>⚠ Error</div><pre class='reasoning' style='color:var(--red);'>" + esc(t.error) + "</pre></div>" : "") +
      "</article>";
    }

    function renderAll() {
      updateKpis();
      const list = document.getElementById("traceList");
      if (state.traces.length === 0) {
        list.innerHTML = "<div class='card' style='color:var(--muted);text-align:center;padding:32px;'>まだ推論ログがありません。マイクラで <code>!プロンプト</code> を打つか、下の入力欄からテスト送信してください。</div>";
        return;
      }
      list.innerHTML = state.traces.map(renderTraceCard).join("");
    }

    window.copyReason = (id) => {
      const t = state.traces.find((x) => x.id === id);
      if (t) navigator.clipboard?.writeText(t.reasoningText || "");
    };

    function connectSse() {
      const dot = document.getElementById("sseDot");
      const es = new EventSource("/telemetry/events");
      es.onopen = () => dot.classList.remove("off");
      es.onerror = () => dot.classList.add("off");
      es.addEventListener("snapshot", (e) => {
        const snap = JSON.parse(e.data);
        preparing = Boolean(snap.preparing);
        state.traces = snap.traces || [];
        if (snap.configuredThinking) {
          document.getElementById("thinkingSel").value = snap.configuredThinking;
        }
        renderAll();
      });
      es.addEventListener("thinking_setting", (e) => { document.getElementById("thinkingSel").value = JSON.parse(e.data).level; });
      es.addEventListener("prompt_run", () => { preparing = true; updateKpis(); });
      es.addEventListener("preparation_end", () => { preparing = false; updateKpis(); });
      es.addEventListener("trace_start", (e) => {
        preparing = false;
        upsertTrace(JSON.parse(e.data));
        renderAll();
      });
      es.addEventListener("trace_update", (e) => {
        const upd = JSON.parse(e.data);
        const t = state.traces.find((x) => x.id === upd.id);
        if (t) {
          Object.assign(t, upd);
          updateKpis();
          const card = document.getElementById("card-" + t.id);
          if (card) card.outerHTML = renderTraceCard(t);
        }
      });
      es.addEventListener("trace_delta", (e) => {
        const d = JSON.parse(e.data);
        const t = state.traces.find((x) => x.id === d.id);
        if (t) {
          t.status = d.status;
          t.elapsedMs = d.elapsedMs;
          t.ttftWallMs = d.ttftWallMs;
          t.liveChunkCount = d.liveChunkCount;
          t.liveTps = d.liveTps;
          if (d.reasoningDelta) t.reasoningText = (t.reasoningText || "") + d.reasoningDelta;
          if (d.contentDelta) t.contentText = (t.contentText || "") + d.contentDelta;
          if (d.toolCalls) t.toolCalls = d.toolCalls;
          updateKpis();
          const rEl = document.getElementById("reason-" + t.id);
          if (rEl && d.reasoningDelta) {
            rEl.textContent = t.reasoningText;
            rEl.scrollTop = rEl.scrollHeight;
          } else {
            const card = document.getElementById("card-" + t.id);
            if (card) card.outerHTML = renderTraceCard(t);
          }
        }
      });
      es.addEventListener("trace_done", (e) => {
        upsertTrace(JSON.parse(e.data));
        renderAll();
      });
      es.addEventListener("tool_event", (e) => {
        const msg = JSON.parse(e.data);
        const t = state.traces.find((x) => x.id === msg.traceId);
        if (t) {
          t.toolResults = t.toolResults || [];
          t.toolResults.push(msg.event);
          renderAll();
        }
      });
      es.addEventListener("cleared", () => {
        state.traces = [];
        renderAll();
      });
    }

    async function loadModels() {
      const sel = document.getElementById("modelSel");
      try {
        const res = await fetch("/models");
        const data = await res.json();
        if (!data.ok || !Array.isArray(data.models)) return;
        sel.innerHTML = "";
        for (const m of data.models) {
          const opt = document.createElement("option");
          opt.value = m.ref;
          opt.textContent = (m.name || m.ref) + (m.hasApiKey ? "" : " [no key]");
          if (m.active) opt.selected = true;
          sel.appendChild(opt);
        }
      } catch {}
    }

    document.getElementById("modelSel").onchange = async (e) => {
      const sel = e.target;
      sel.disabled = true;
      try {
        const response = await fetch("/model", {
          method: "POST", headers: { "content-type": "application/json" },
          body: JSON.stringify({ target: sel.value }),
        });
        const data = await response.json();
        if (!response.ok || !data.ok) throw new Error(data.error || "モデルを切り替えできませんでした");
        if (data.warning) alert(data.warning);
      } catch (err) {
        alert(err.message);
        await loadModels();
      } finally {
        sel.disabled = false;
      }
    };

    document.getElementById("clearBtn").onclick = () => fetch("/telemetry/clear", { method: "POST" });
    document.getElementById("thinkingSel").onchange = async (e) => {
      const select = e.target;
      select.disabled = true;
      try {
        const response = await fetch("/thinking", {
          method: "POST", headers: { "content-type": "application/json" },
          body: JSON.stringify({ level: select.value }),
        });
        if (!response.ok) throw new Error((await response.json()).error || "設定を保存できませんでした");
      } catch (err) {
        alert(err.message);
        const health = await (await fetch("/health")).json();
        select.value = health.thinking;
      } finally {
        select.disabled = false;
      }
    };
    document.getElementById("promptForm").onsubmit = async (e) => {
      e.preventDefault();
      const inp = document.getElementById("promptInput");
      const txt = inp.value.trim();
      if (!txt) return;
      inp.value = "";
      await fetch("/prompt", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ prompt: txt }),
      });
    };

    loadModels();
    connectSse();
  </script>
</body>
</html>`;
}
