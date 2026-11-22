import { existsSync, readFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import path from "node:path";
import { URL } from "node:url";
import { StreamingBatchExtractor, StreamedOpEvent } from "./stream-ops.js";

export type TraceStatus =
  | "QUEUED"
  | "REASONING"
  | "TOOL_CALLING"
  | "RESPONDING"
  | "DONE"
  | "ERROR";

export interface ToolTraceEvent {
  id: string;
  callId?: string;
  toolName: string;
  startedAt: number;
  durationMs: number;
  ok: boolean;
  summary: string;
  error?: string;
  imageDataUrl?: string;
}

export interface StreamedToolCall {
  index: number;
  id: string;
  name: string;
  argumentsText: string;
}

export interface InferenceTrace {
  id: string;
  callNumber: number;
  promptRunId: string;
  promptText: string;
  preInference: { queueMs: number; preparationMs: number; agentMs: number } | null;
  model: string;
  reasoningEffort: string;
  stream: boolean;
  messageCount: number;
  toolCount: number;
  httpStatus: number | null;
  retryCount: number;
  status: TraceStatus;
  startedAt: number;
  attemptStartedAt: number;
  headersAt: number | null;
  firstTokenAt: number | null;
  finishedAt: number | null;
  elapsedMs: number;
  retryWaitMs: number;
  ttftWallMs: number | null;
  ttftAttemptMs: number | null;
  ttftComputeMs: number | null;
  queueMs: number | null;
  promptMs: number | null;
  completionMs: number | null;
  liveChunkCount: number;
  streamedChars: number;
  liveTps: number | null;
  serverTps: number | null;
  e2eTps: number | null;
  firstReasoningAt: number | null;
  lastReasoningAt: number | null;
  firstOutputAt: number | null;
  lastOutputAt: number | null;
  reasonPhaseTps: number | null;
  outputPhaseTps: number | null;
  promptTokens: number | null;
  cachedTokens: number | null;
  cacheHitPct: number | null;
  reasoningTokens: number | null;
  outputTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  reasoningText: string;
  contentText: string;
  toolCalls: StreamedToolCall[];
  toolResults: ToolTraceEvent[];
  error?: string;
}

export interface HudPayload {
  phase: TraceStatus | "IDLE" | "PREPARING";
  callNumber: number;
  model: string;
  reasoningEffort: string;
  elapsedMs: number;
  ttftWallMs: number | null;
  serverTps: number | null;
  liveTps: number | null;
  queueMs: number | null;
  promptMs: number | null;
  completionMs: number | null;
  reasoningTokens: number | null;
  outputTokens: number | null;
  cacheHitPct: number | null;
  reasoningSnippet: string;
  answerSnippet?: string;
  retryCount?: number;
  activeTool: string | null;
  done: boolean;
}

const MAX_TRACES = 50;

export class TelemetryHub {
  private traces: InferenceTrace[] = [];
  private sseClients = new Set<http.ServerResponse>();
  private hudCallback: ((payload: HudPayload) => void) | null = null;

  private callSeq = 0;
  private promptRunSeq = 0;
  private receivedAt = 0;
  private runStartedAt = 0;
  private dispatchAt = 0;
  private firstCallPending = false;
  private activePromptRunId = "run-0";
  private activePromptText = "";
  private activeReasoningEffort = "low";
  private activeToolName: string | null = null;
  private lastHudEmitAt = 0;
  private hudTimer: NodeJS.Timeout | null = null;
  private deltaTimers = new Map<string, NodeJS.Timeout>();
  private pendingDeltas = new Map<string, { trace: InferenceTrace; reasoning: string; content: string; toolsChanged: boolean }>();
  private queueHeartbeat: NodeJS.Timeout | null = null;

  constructor() {
    this.queueHeartbeat = setInterval(() => {
      if (this.firstCallPending) {
        this.scheduleHudEmit(false);
        return;
      }
      const active = this.getActiveTrace();
      if (active && (active.status === "QUEUED" || active.status === "REASONING" || active.status === "TOOL_CALLING" || active.status === "RESPONDING")) {
        active.elapsedMs = Date.now() - active.startedAt;
        this.broadcast("trace_update", this.compactUpdatePayload(active));
        this.scheduleHudEmit(false);
      }
    }, 250);
  }

  close(): void {
    if (this.queueHeartbeat) {
      clearInterval(this.queueHeartbeat);
      this.queueHeartbeat = null;
    }
    if (this.hudTimer) {
      clearTimeout(this.hudTimer);
      this.hudTimer = null;
    }
    for (const timer of this.deltaTimers.values()) {
      clearTimeout(timer);
    }
    this.deltaTimers.clear();
    this.pendingDeltas.clear();
    for (const res of this.sseClients) {
      res.end();
    }
    this.sseClients.clear();
  }

  onHudUpdate(cb: (payload: HudPayload) => void): void {
    this.hudCallback = cb;
  }

  beginPromptRun(playerName: string, prompt: string, thinking: string, receivedAt = Date.now()): void {
    this.receivedAt = receivedAt;
    this.runStartedAt = Date.now();
    this.dispatchAt = 0;
    this.firstCallPending = true;
    this.promptRunSeq++;
    this.activePromptRunId = `run-${this.promptRunSeq}`;
    this.activePromptText = `[${playerName}] ${prompt}`;
    this.activeReasoningEffort = thinking || "low";
    this.activeToolName = null;
    this.scheduleHudEmit(true);
    this.broadcast("prompt_run", {
      promptRunId: this.activePromptRunId,
      promptText: this.activePromptText,
      reasoningEffort: this.activeReasoningEffort,
      startedAt: Date.now(),
    });
  }

  endPreparation(): void {
    if (!this.firstCallPending) return;
    this.firstCallPending = false;
    this.scheduleHudEmit(true);
    this.broadcast("preparation_end", {});
  }

  markAgentDispatch(): void {
    this.dispatchAt = Date.now();
  }

  startInference(opts: {
    model: string;
    reasoningEffort?: string;
    stream: boolean;
    messageCount: number;
    toolCount: number;
  }): InferenceTrace {
    this.callSeq++;
    const trace: InferenceTrace = {
      id: `call-${this.callSeq}`,
      callNumber: this.callSeq,
      promptRunId: this.activePromptRunId,
      promptText: this.activePromptText || "(Direct OpenClaw turn)",
      preInference: this.firstCallPending && this.dispatchAt ? {
        queueMs: this.runStartedAt - this.receivedAt,
        preparationMs: this.dispatchAt - this.runStartedAt,
        agentMs: Date.now() - this.dispatchAt,
      } : null,
      model: opts.model,
      reasoningEffort: opts.reasoningEffort ?? this.activeReasoningEffort,
      stream: opts.stream,
      messageCount: opts.messageCount,
      toolCount: opts.toolCount,
      httpStatus: null,
      retryCount: 0,
      status: "QUEUED",
      startedAt: Date.now(),
      attemptStartedAt: Date.now(),
      headersAt: null,
      firstTokenAt: null,
      finishedAt: null,
      elapsedMs: 0,
      retryWaitMs: 0,
      ttftWallMs: null,
      ttftAttemptMs: null,
      ttftComputeMs: null,
      queueMs: null,
      promptMs: null,
      completionMs: null,
      liveChunkCount: 0,
      streamedChars: 0,
      liveTps: null,
      serverTps: null,
      e2eTps: null,
      firstReasoningAt: null,
      lastReasoningAt: null,
      firstOutputAt: null,
      lastOutputAt: null,
      reasonPhaseTps: null,
      outputPhaseTps: null,
      promptTokens: null,
      cachedTokens: null,
      cacheHitPct: null,
      reasoningTokens: null,
      outputTokens: null,
      completionTokens: null,
      totalTokens: null,
      reasoningText: "",
      contentText: "",
      toolCalls: [],
      toolResults: [],
    };
    this.firstCallPending = false;
    this.traces.unshift(trace);
    if (this.traces.length > MAX_TRACES) {
      this.traces.length = MAX_TRACES;
    }
    this.broadcast("trace_start", trace);
    this.scheduleHudEmit(true);
    return trace;
  }

  markHeaders(trace: InferenceTrace, httpStatus: number): void {
    trace.httpStatus = httpStatus;
    trace.headersAt = Date.now();
    trace.elapsedMs = trace.headersAt - trace.startedAt;
    this.broadcast("trace_update", this.compactUpdatePayload(trace));
  }

  appendDelta(
    trace: InferenceTrace,
    delta: {
      reasoningChunk?: string;
      contentChunk?: string;
      toolCalls?: Array<{ index?: number; id?: string; function?: { name?: string; arguments?: string } }>;
    }
  ): void {
    const now = Date.now();
    const hasReasoning = Boolean(delta.reasoningChunk && delta.reasoningChunk.length > 0);
    const hasContent = Boolean(delta.contentChunk && delta.contentChunk.length > 0);
    const hasTools = Boolean(delta.toolCalls && delta.toolCalls.length > 0);
    if (!hasReasoning && !hasContent && !hasTools) {
      return;
    }

    if (trace.firstTokenAt === null) {
      trace.firstTokenAt = now;
      trace.ttftWallMs = now - trace.startedAt;
      trace.ttftAttemptMs = Math.max(0, now - trace.attemptStartedAt);
    }
    trace.elapsedMs = now - trace.startedAt;
    trace.liveChunkCount++;

    let chunkChars = (delta.reasoningChunk?.length ?? 0) + (delta.contentChunk?.length ?? 0);

    if (hasReasoning) {
      if (trace.firstReasoningAt === null) trace.firstReasoningAt = now;
      trace.lastReasoningAt = now;
      trace.reasoningText += delta.reasoningChunk!;
      trace.status = "REASONING";
    }
    if (hasTools) {
      if (trace.firstOutputAt === null) trace.firstOutputAt = now;
      trace.lastOutputAt = now;
      for (const tc of delta.toolCalls!) {
        const idx = typeof tc.index === "number" ? tc.index : 0;
        let existing = trace.toolCalls.find((t) =>
          (tc.id && t.id) ? t.id === tc.id : t.index === idx
        );
        if (existing && tc.id && existing.id && existing.id !== tc.id) {
          existing = undefined;
        }
        if (!existing) {
          existing = { index: idx, id: tc.id ?? "", name: "", argumentsText: "" };
          trace.toolCalls.push(existing);
        }
        if (tc.id) existing.id = tc.id;
        if (tc.function?.name) {
          existing.name += tc.function.name;
          chunkChars += tc.function.name.length;
        }
        if (tc.function?.arguments) {
          existing.argumentsText += tc.function.arguments;
          chunkChars += tc.function.arguments.length;
        }
      }
      trace.status = "TOOL_CALLING";
    }
    if (hasContent) {
      if (trace.firstOutputAt === null) trace.firstOutputAt = now;
      trace.lastOutputAt = now;
      trace.contentText += delta.contentChunk!;
      if (trace.toolCalls.length === 0) {
        trace.status = "RESPONDING";
      }
    }

    trace.streamedChars += chunkChars;
    const streamSec = Math.max(0.05, (now - trace.firstTokenAt) / 1000);
    const estTokens = Math.max(trace.liveChunkCount, Math.round(trace.streamedChars / 3.6));
    trace.liveTps = Math.round((estTokens / streamSec) * 10) / 10;

    let pending = this.pendingDeltas.get(trace.id);
    if (!pending) {
      pending = { trace, reasoning: "", content: "", toolsChanged: false };
      this.pendingDeltas.set(trace.id, pending);
    }
    if (hasReasoning) pending.reasoning += delta.reasoningChunk!;
    if (hasContent) pending.content += delta.contentChunk!;
    if (hasTools) pending.toolsChanged = true;

    if (!this.deltaTimers.has(trace.id)) {
      const timer = setTimeout(() => {
        this.deltaTimers.delete(trace.id);
        this.flushPendingDelta(trace.id);
      }, 60);
      this.deltaTimers.set(trace.id, timer);
    }
    this.scheduleHudEmit(false);
  }

  private flushPendingDelta(traceId: string): void {
    const timer = this.deltaTimers.get(traceId);
    if (timer) {
      clearTimeout(timer);
      this.deltaTimers.delete(traceId);
    }
    const pending = this.pendingDeltas.get(traceId);
    if (!pending) return;
    this.pendingDeltas.delete(traceId);

    const t = pending.trace;
    this.broadcast("trace_delta", {
      id: t.id,
      status: t.status,
      elapsedMs: t.elapsedMs,
      ttftWallMs: t.ttftWallMs,
      liveChunkCount: t.liveChunkCount,
      liveTps: t.liveTps,
      reasoningDelta: pending.reasoning,
      contentDelta: pending.content,
      toolCalls: pending.toolsChanged
        ? t.toolCalls.map((c) => ({ index: c.index, id: c.id, name: c.name, argsLen: c.argumentsText.length }))
        : undefined,
    });
  }

  applyUsageAndTiming(
    trace: InferenceTrace,
    usage?: {
      prompt_tokens?: number;
      completion_tokens?: number;
      total_tokens?: number;
      prompt_tokens_details?: { cached_tokens?: number };
      completion_tokens_details?: { reasoning_tokens?: number };
    },
    timeInfo?: {
      queue_time?: number;
      prompt_time?: number;
      completion_time?: number;
      total_time?: number;
    }
  ): void {
    if (usage) {
      const promptTokens = typeof usage.prompt_tokens === "number" ? usage.prompt_tokens : 0;
      const cachedTokens = typeof usage.prompt_tokens_details?.cached_tokens === "number"
        ? usage.prompt_tokens_details.cached_tokens
        : 0;
      const completionTokens = typeof usage.completion_tokens === "number" ? usage.completion_tokens : 0;
      const reasoningTokens = typeof usage.completion_tokens_details?.reasoning_tokens === "number"
        ? usage.completion_tokens_details.reasoning_tokens
        : 0;

      trace.promptTokens = promptTokens;
      trace.cachedTokens = cachedTokens;
      trace.cacheHitPct = promptTokens > 0 ? Math.round((cachedTokens / promptTokens) * 1000) / 10 : 0;
      trace.completionTokens = completionTokens;
      trace.reasoningTokens = reasoningTokens;
      trace.outputTokens = Math.max(0, completionTokens - reasoningTokens);
      trace.totalTokens = typeof usage.total_tokens === "number" ? usage.total_tokens : promptTokens + completionTokens;
    }

    if (timeInfo) {
      if (typeof timeInfo.queue_time === "number") {
        trace.queueMs = Math.round(timeInfo.queue_time * 1000);
      }
      if (typeof timeInfo.prompt_time === "number") {
        trace.promptMs = Math.round(timeInfo.prompt_time * 1000);
        trace.ttftComputeMs = trace.promptMs;
      }
      if (typeof timeInfo.completion_time === "number") {
        trace.completionMs = Math.round(timeInfo.completion_time * 1000);
      }
      if (typeof timeInfo.completion_time === "number" && timeInfo.completion_time > 0 && trace.completionTokens) {
        trace.serverTps = Math.round((trace.completionTokens / timeInfo.completion_time) * 10) / 10;
      }
    }
  }

  finishInference(trace: InferenceTrace, errorMsg?: string): void {
    if (trace.finishedAt !== null) return;
    this.flushPendingDelta(trace.id);
    const now = Date.now();
    trace.finishedAt = now;
    trace.elapsedMs = now - trace.startedAt;
    if (errorMsg) {
      trace.status = "ERROR";
      trace.error = errorMsg;
    } else if (trace.httpStatus && trace.httpStatus >= 400) {
      trace.status = "ERROR";
      trace.error = trace.error ?? `HTTP ${trace.httpStatus}`;
    } else {
      trace.status = "DONE";
    }

    if (trace.completionTokens && trace.elapsedMs > 0) {
      trace.e2eTps = Math.round((trace.completionTokens / (trace.elapsedMs / 1000)) * 10) / 10;
    }
    if (trace.serverTps === null && trace.completionTokens && trace.firstTokenAt !== null) {
      const streamSec = Math.max(0.05, (now - trace.firstTokenAt) / 1000);
      trace.serverTps = Math.round((trace.completionTokens / streamSec) * 10) / 10;
    }
    if (trace.reasoningTokens && trace.reasoningTokens > 0 && trace.firstReasoningAt !== null && trace.lastReasoningAt !== null) {
      const rSec = Math.max(0.05, (trace.lastReasoningAt - trace.firstReasoningAt) / 1000);
      trace.reasonPhaseTps = Math.round((trace.reasoningTokens / rSec) * 10) / 10;
    }
    if (trace.outputTokens && trace.outputTokens > 0) {
      const rawOSec = (trace.firstOutputAt !== null && trace.lastOutputAt !== null)
        ? (trace.lastOutputAt - trace.firstOutputAt) / 1000
        : 0;
      const rMs = (trace.firstReasoningAt !== null && trace.lastReasoningAt !== null)
        ? Math.max(0, trace.lastReasoningAt - trace.firstReasoningAt)
        : 0;
      const remSec = trace.completionMs !== null && trace.completionMs > rMs + 50
        ? (trace.completionMs - rMs) / 1000
        : 0;
      const oSec = rawOSec >= 0.08 ? rawOSec : remSec;
      if (oSec >= 0.05) {
        trace.outputPhaseTps = Math.round((trace.outputTokens / oSec) * 10) / 10;
      }
    }

    this.logConsoleSummary(trace);
    this.broadcast("trace_done", trace);
    this.scheduleHudEmit(true);
  }

  markToolStart(toolName: string): void {
    this.activeToolName = toolName;
    this.scheduleHudEmit(false);
  }

  attachToolEvent(ev: ToolTraceEvent): void {
    this.activeToolName = null;
    const target = this.traces.find((t) => t.promptRunId === this.activePromptRunId) ?? this.traces[0];
    if (target) {
      target.toolResults.push(ev);
      this.pruneOldImages(10);
      this.broadcast("tool_event", { traceId: target.id, event: ev });
    }
    this.scheduleHudEmit(true);
  }

  private pruneOldImages(maxImages: number): void {
    let seen = 0;
    for (const t of this.traces) {
      for (let i = t.toolResults.length - 1; i >= 0; i--) {
        const r = t.toolResults[i];
        if (r.imageDataUrl) {
          seen++;
          if (seen > maxImages) {
            delete r.imageDataUrl;
          }
        }
      }
    }
  }

  clear(): void {
    this.traces = [];
    this.activeToolName = null;
    this.broadcast("cleared", {});
  }

  getSnapshot(): Record<string, unknown> {
    let totalCalls = this.traces.length;
    let totalTokens = 0;
    let totalReasoningTokens = 0;
    let totalPromptTokens = 0;
    let totalCachedTokens = 0;
    let serverTpsSum = 0;
    let serverTpsCount = 0;

    for (const t of this.traces) {
      totalTokens += t.totalTokens ?? 0;
      totalReasoningTokens += t.reasoningTokens ?? 0;
      totalPromptTokens += t.promptTokens ?? 0;
      totalCachedTokens += t.cachedTokens ?? 0;
      if (typeof t.serverTps === "number") {
        serverTpsSum += t.serverTps;
        serverTpsCount++;
      }
    }

    return {
      ok: true,
      activePromptRunId: this.activePromptRunId,
      activePromptText: this.activePromptText,
      preparing: this.firstCallPending,
      configuredThinking: this.configuredThinking,
      activeReasoningEffort: this.activeReasoningEffort,
      totals: {
        calls: totalCalls,
        totalTokens,
        reasoningTokens: totalReasoningTokens,
        avgServerTps: serverTpsCount > 0 ? Math.round((serverTpsSum / serverTpsCount) * 10) / 10 : null,
        cacheHitPct: totalPromptTokens > 0 ? Math.round((totalCachedTokens / totalPromptTokens) * 1000) / 10 : 0,
      },
      traces: this.traces,
    };
  }

  addSseClient(res: http.ServerResponse): void {
    res.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
      "x-accel-buffering": "no",
    });
    this.sseClients.add(res);
    res.write(`event: snapshot\ndata: ${JSON.stringify(this.getSnapshot())}\n\n`);
    res.on("close", () => {
      this.sseClients.delete(res);
    });
  }

  private getActiveTrace(): InferenceTrace | undefined {
    return this.traces[0];
  }

  private compactUpdatePayload(t: InferenceTrace) {
    return {
      id: t.id,
      status: t.status,
      httpStatus: t.httpStatus,
      retryCount: t.retryCount,
      retryWaitMs: t.retryWaitMs,
      elapsedMs: t.elapsedMs,
      ttftWallMs: t.ttftWallMs,
      ttftAttemptMs: t.ttftAttemptMs,
      ttftComputeMs: t.ttftComputeMs,
      queueMs: t.queueMs,
      promptMs: t.promptMs,
      completionMs: t.completionMs,
      liveChunkCount: t.liveChunkCount,
      liveTps: t.liveTps,
      serverTps: t.serverTps,
      e2eTps: t.e2eTps,
    };
  }

  markRetry(trace: InferenceTrace, attempt: number, plannedDelayMs: number): void {
    trace.retryCount = attempt;
    trace.retryWaitMs += plannedDelayMs;
    trace.status = "QUEUED";
    trace.elapsedMs = Date.now() - trace.startedAt;
    this.broadcast("trace_update", this.compactUpdatePayload(trace));
    this.scheduleHudEmit(false);
  }

  markAttemptStart(trace: InferenceTrace): void {
    trace.attemptStartedAt = Date.now();
  }

  private scheduleHudEmit(immediate: boolean): void {
    if (!this.hudCallback) return;
    const now = Date.now();
    if (immediate || now - this.lastHudEmitAt >= 120) {
      if (this.hudTimer) {
        clearTimeout(this.hudTimer);
        this.hudTimer = null;
      }
      this.lastHudEmitAt = now;
      this.hudCallback(this.buildHudPayload());
      return;
    }
    if (!this.hudTimer) {
      this.hudTimer = setTimeout(() => {
        this.hudTimer = null;
        this.lastHudEmitAt = Date.now();
        this.hudCallback?.(this.buildHudPayload());
      }, 120);
    }
  }

  private buildHudPayload(): HudPayload {
    const latest = this.traces[0];
    if (!latest || this.firstCallPending || latest.promptRunId !== this.activePromptRunId) {
      return {
        phase: this.firstCallPending ? "PREPARING" : "IDLE",
        callNumber: 0,
        model: latest?.model ?? "unknown",
        reasoningEffort: this.activeReasoningEffort,
        elapsedMs: this.firstCallPending ? Date.now() - this.receivedAt : 0,
        ttftWallMs: null,
        serverTps: null,
        liveTps: null,
        queueMs: null,
        promptMs: null,
        completionMs: null,
        reasoningTokens: null,
        outputTokens: null,
        cacheHitPct: null,
        reasoningSnippet: "",
        activeTool: this.activeToolName,
        done: !this.firstCallPending,
      };
    }

    const cleanSnippet = latest.reasoningText.replace(/\s+/g, " ").trim();
    const tail = cleanSnippet.length > 96 ? "..." + cleanSnippet.slice(-93) : cleanSnippet;
    return {
      phase: latest.status,
      callNumber: latest.callNumber,
      model: latest.model,
      reasoningEffort: latest.reasoningEffort,
      elapsedMs: latest.elapsedMs,
      ttftWallMs: latest.ttftWallMs,
      serverTps: latest.serverTps,
      liveTps: latest.liveTps,
      queueMs: latest.queueMs,
      promptMs: latest.promptMs,
      completionMs: latest.completionMs,
      reasoningTokens: latest.reasoningTokens,
      outputTokens: latest.outputTokens,
      cacheHitPct: latest.cacheHitPct,
      reasoningSnippet: tail,
      answerSnippet: latest.status === "RESPONDING" ? Array.from(latest.contentText.slice(-200)).slice(-80).join("").replace(/\s+/g, " ").trim() : undefined,
      retryCount: latest.retryCount,
      activeTool: this.activeToolName,
      done: latest.status === "DONE" || latest.status === "ERROR",
    };
  }

  private configuredThinking = "low";
  setConfiguredThinking(level: string): void {
    this.configuredThinking = level;
    this.broadcast("thinking_setting", { level });
  }

  private broadcast(event: string, data: unknown): void {
    if (this.sseClients.size === 0) return;
    const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const res of this.sseClients) {
      try {
        res.write(frame);
      } catch {
        this.sseClients.delete(res);
      }
    }
  }

  private logConsoleSummary(t: InferenceTrace): void {
    const ttft = t.ttftWallMs !== null ? `${t.ttftWallMs}ms` : "-";
    const ttftAtt = t.ttftAttemptMs !== null ? `${t.ttftAttemptMs}ms` : "-";
    const retryInfo = t.retryCount > 0 ? ` | Retries=${t.retryCount}(${t.retryWaitMs}ms)` : "";
    const q = t.queueMs !== null ? `${t.queueMs}ms` : "-";
    const comp = t.completionMs !== null ? `${t.completionMs}ms` : "-";
    const stps = t.serverTps !== null ? `${t.serverTps} tok/s` : "-";
    const phaseInfo = (t.reasonPhaseTps !== null || t.outputPhaseTps !== null)
      ? ` (R:${t.reasonPhaseTps ?? "-"} O:${t.outputPhaseTps ?? "-"})`
      : "";
    const etps = t.e2eTps !== null ? `${t.e2eTps} tok/s` : "-";
    const rTok = t.reasoningTokens ?? 0;
    const oTok = t.outputTokens ?? 0;
    const cache = t.cacheHitPct !== null ? `${t.cacheHitPct}%` : "-";
    console.log(
      `[LLM #${t.callNumber}] ${t.status} (${t.reasoningEffort}) | TTFT=${ttft} (attempt=${ttftAtt}) | Queue=${q} | Comp=${comp} | ServerTPS=${stps}${phaseInfo} | E2ETPS=${etps} | Reason=${rTok}t Out=${oTok}t | Cache=${cache}${retryInfo} | Tools=${t.toolCalls.map((c) => c.name).join(",") || "none"}`
    );
  }
}

const keepAliveAgent = new https.Agent({ keepAlive: true, maxSockets: 16 });

export interface StreamedBuildOpEvent extends StreamedOpEvent {
  callIndex: number;
  toolName: string;
}

export interface TelemetryProxyOptions {
  host?: string;
  port?: number;
  upstreamBase?: string;
  getTargetReasoningEffort?: () => string;
  onStreamedBuildOp?: (ev: StreamedBuildOpEvent) => void;
  openclawConfigPath?: string;
  openclawStateDir?: string;
}

function loadEnvValue(varName: string, stateDir?: string): string | undefined {
  if (process.env[varName]) return process.env[varName];
  if (!stateDir) return undefined;
  const envFile = path.join(stateDir, ".env");
  if (!existsSync(envFile)) return undefined;
  for (const line of readFileSync(envFile, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq > 0 && trimmed.slice(0, eq).trim() === varName) {
      return trimmed.slice(eq + 1).trim();
    }
  }
  return undefined;
}

function resolveFallbackTarget(
  fallbackProviderId: string,
  configPath?: string,
  stateDir?: string
): { upstreamUrl: URL; targetPath: string; apiKey: string; modelId: string } | null {
  try {
    if (!configPath || !existsSync(configPath)) return null;
    const cfg = JSON.parse(readFileSync(configPath, "utf8").replace(/^\uFEFF/, "")) as Record<string, unknown>;
    const providers = (cfg.models as Record<string, unknown> | undefined)?.providers as Record<string, Record<string, unknown>> | undefined;
    const p = providers?.[fallbackProviderId];
    if (!p) return null;

    const rawKey = typeof p.apiKey === "string" ? p.apiKey : "";
    const apiKey = rawKey.replace(/\$\{([A-Z0-9_]+)\}/g, (_m, k: string) => loadEnvValue(k, stateDir) ?? "");
    const models = Array.isArray(p.models) ? (p.models as Array<Record<string, unknown>>) : [];
    const modelId = typeof models[0]?.id === "string" ? models[0].id : "";
    if (!apiKey || !modelId) return null;

    if (fallbackProviderId === "commandcode") {
      return {
        upstreamUrl: new URL("https://api.commandcode.ai"),
        targetPath: "/provider/v1/chat/completions",
        apiKey,
        modelId,
      };
    }
    if (fallbackProviderId === "cerebras") {
      return {
        upstreamUrl: new URL("https://api.cerebras.ai"),
        targetPath: "/v1/chat/completions",
        apiKey,
        modelId,
      };
    }
    const baseUrl = typeof p.baseUrl === "string" ? p.baseUrl : "";
    const route = /\/providers\/([a-f0-9]{24})\/v1/.exec(baseUrl);
    if (route) {
      const routes = JSON.parse(readFileSync(new URL("../provider-routes.json", import.meta.url), "utf8")) as Record<string, string>;
      const resolved = routes[route[1]];
      if (resolved) {
        const u = new URL(resolved);
        return {
          upstreamUrl: u,
          targetPath: u.pathname.replace(/\/+$/, "") + "/chat/completions",
          apiKey,
          modelId,
        };
      }
    }
    return null;
  } catch {
    return null;
  }
}

function isBuildBatchToolName(name: string): boolean {
  return name === "mc_build_batch" || name === "minecraft-harness__mc_build_batch";
}

export function startCerebrasTelemetryProxy(
  hub: TelemetryHub,
  opts: TelemetryProxyOptions = {}
): http.Server {
  const host = opts.host ?? "127.0.0.1";
  const port = opts.port ?? 25582;
  const cerebrasUrl = new URL(opts.upstreamBase ?? "https://api.cerebras.ai");

  const proxy = http.createServer((clientReq, clientRes) => {
    const chunks: Buffer[] = [];
    clientReq.on("data", (c: Buffer) => chunks.push(c));
    clientReq.on("end", () => {
      const rawBody = Buffer.concat(chunks);
      const reqPath = clientReq.url ?? "/";
      const commandCode = reqPath.startsWith("/commandcode/v1/");
      let upstreamUrl = commandCode ? new URL("https://api.commandcode.ai") : cerebrasUrl;
      let targetPath = commandCode ? "/provider" + reqPath.slice("/commandcode".length)
        : reqPath.startsWith("/v1") ? reqPath : `/v1${reqPath.startsWith("/") ? "" : "/"}${reqPath}`;
      if (reqPath.startsWith("/providers/")) {
        const route = /^\/providers\/([a-f0-9]{24})\/v1(\/[^?#]*)(\?[^#]*)?$/.exec(reqPath);
        try {
          if (!route) throw new Error();
          const routes = JSON.parse(readFileSync(new URL("../provider-routes.json", import.meta.url), "utf8"));
          upstreamUrl = new URL(routes[route[1]]);
          if (upstreamUrl.protocol !== "https:") throw new Error();
          targetPath = upstreamUrl.pathname.replace(/\/+$/, "") + route[2] + (route[3] ?? "");
        } catch {
          clientRes.writeHead(404, { "content-type": "application/json" });
          clientRes.end(JSON.stringify({ error: "Unknown provider route" }));
          return;
        }
      }
      const isChatCompletion = clientReq.method === "POST" && targetPath.includes("/chat/completions");

      let forwardBody = rawBody;
      let trace: InferenceTrace | null = null;
      let isStream = false;

      if (isChatCompletion && rawBody.length > 0) {
        const rawText = rawBody.toString("utf8");
        if (rawText.includes("__mh_warmup__")) {
          let warmupStream = true;
          let warmupModel = "unknown";
          try {
            const warmup = JSON.parse(rawText) as Record<string, unknown>;
            warmupStream = Boolean(warmup.stream);
            warmupModel = String(warmup.model ?? "unknown");
          } catch {}
          if (warmupStream) {
            clientRes.writeHead(200, {
              "content-type": "text/event-stream; charset=utf-8",
              "cache-control": "no-cache",
              connection: "keep-alive",
            });
            const id = `chatcmpl-warmup-${Date.now()}`;
            clientRes.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: warmupModel, choices: [{ index: 0, delta: { role: "assistant", content: "OK" }, finish_reason: null }] })}\n\n`);
            clientRes.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: warmupModel, choices: [{ index: 0, delta: {}, finish_reason: "stop" }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })}\n\n`);
            clientRes.end("data: [DONE]\n\n");
          } else {
            clientRes.writeHead(200, { "content-type": "application/json" });
            clientRes.end(JSON.stringify({
              id: `chatcmpl-warmup-${Date.now()}`,
              object: "chat.completion",
              created: Math.floor(Date.now() / 1000),
              model: warmupModel,
              choices: [{ index: 0, message: { role: "assistant", content: "OK" }, finish_reason: "stop" }],
              usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
            }));
          }
          return;
        }
        let parsedBody: Record<string, unknown> | null = null;
        try {
          const parsed = JSON.parse(rawText) as Record<string, unknown>;
          parsedBody = parsed;
          let bodyMutated = false;
          isStream = Boolean(parsed.stream);
          if (isStream) {
            const existingStreamOpts = (parsed.stream_options as Record<string, unknown> | undefined) ?? {};
            if (!existingStreamOpts.include_usage) {
              parsed.stream_options = { ...existingStreamOpts, include_usage: true };
              bodyMutated = true;
            }
          }

          let recordedEffort = typeof parsed.reasoning_effort === "string" ? parsed.reasoning_effort : "default";
          if (opts.getTargetReasoningEffort) {
            const target = opts.getTargetReasoningEffort().toLowerCase().trim();
            if (target === "low" || target === "medium" || target === "high") {
              if (parsed.reasoning_effort !== target) {
                parsed.reasoning_effort = target;
                bodyMutated = true;
              }
              recordedEffort = target;
            } else if (target === "minimal") {
              if (parsed.reasoning_effort !== "low") {
                parsed.reasoning_effort = "low";
                bodyMutated = true;
              }
              recordedEffort = "low";
            } else if (target === "off") {
              if ("reasoning_effort" in parsed) {
                delete parsed.reasoning_effort;
                bodyMutated = true;
              }
              recordedEffort = "off";
            }
          }

          if (bodyMutated) {
            forwardBody = Buffer.from(JSON.stringify(parsed), "utf8");
          }

          const messages = Array.isArray(parsed.messages) ? parsed.messages : [];
          const tools = Array.isArray(parsed.tools) ? parsed.tools : [];
          trace = hub.startInference({
            model: typeof parsed.model === "string" ? parsed.model : "unknown",
            reasoningEffort: recordedEffort,
            stream: isStream,
            messageCount: messages.length,
            toolCount: tools.length,
          });
        } catch {
          // Forward raw body untouched if unparseable
        }
        (clientReq as unknown as { __mhParsedBody?: Record<string, unknown> | null }).__mhParsedBody = parsedBody;
      }

      const headers: Record<string, string | string[] | undefined> = { ...clientReq.headers };
      delete headers.host;
      delete headers.connection;
      delete headers["content-length"];
      // Force uncompressed identity encoding so SSE lines can be tee-parsed cleanly with zero gzip buffering
      headers["accept-encoding"] = "identity";
      if (forwardBody.length > 0) {
        headers["content-length"] = String(forwardBody.length);
      }

      const RETRY_DELAYS_MS = [400, 900, 1800];
      const fallbackProviderId = (process.env.MH_FALLBACK_PROVIDER ?? "").trim().toLowerCase();
      let usedFallback = false;
      let clientClosed = false;
      let activeUpstreamReq: http.ClientRequest | null = null;
      clientRes.on("close", () => {
        if (!clientRes.writableEnded) {
          clientClosed = true;
          activeUpstreamReq?.destroy();
          if (trace && trace.finishedAt === null) {
            hub.finishInference(trace, "Client disconnected");
          }
        }
      });

      const sendAttempt = (
        attempt: number,
        reqUrl = upstreamUrl,
        reqTargetPath = targetPath,
        reqHeaders = headers,
        reqBody = forwardBody
      ): void => {
        if (clientClosed || clientRes.writableEnded || clientRes.destroyed) {
          return;
        }
        if (trace) hub.markAttemptStart(trace);
        const upstreamReq = https.request(
          {
            protocol: reqUrl.protocol,
            hostname: reqUrl.hostname,
            port: reqUrl.port || 443,
            path: reqTargetPath,
            method: clientReq.method,
            headers: { ...reqHeaders },
            agent: keepAliveAgent,
            timeout: 300000,
          },
          (upstreamRes) => {
            const status = upstreamRes.statusCode ?? 500;

            if (trace && status === 429 && (attempt < RETRY_DELAYS_MS.length || (!usedFallback && fallbackProviderId))) {
              const errChunks: Buffer[] = [];
              upstreamRes.on("data", (c: Buffer) => errChunks.push(c));
              upstreamRes.on("end", () => {
                const body429 = Buffer.concat(errChunks).toString("utf8");
                const isQueue = body429.includes("queue_exceeded") || body429.includes("high traffic") || body429.includes("too_many_requests");
                const retryAfter = upstreamRes.headers["retry-after"];
                const seconds = Number(retryAfter);
                const requestedDelay = retryAfter == null ? 0 : Number.isFinite(seconds)
                  ? seconds * 1000 : Date.parse(retryAfter) - Date.now();
                if (attempt < RETRY_DELAYS_MS.length) {
                  const delay = Math.max(RETRY_DELAYS_MS[attempt], requestedDelay || 0);
                  if (isQueue && delay <= 3000) {
                    console.log(`[Telemetry] #${trace!.callNumber} hit upstream 429 queue_exceeded — transparent retry ${attempt + 1}/${RETRY_DELAYS_MS.length} in ${delay}ms`);
                    hub.markRetry(trace!, attempt + 1, delay);
                    setTimeout(() => sendAttempt(attempt + 1, reqUrl, reqTargetPath, reqHeaders, reqBody), delay);
                    return;
                  }
                }
                if (!usedFallback && fallbackProviderId) {
                  const fb = resolveFallbackTarget(fallbackProviderId, opts.openclawConfigPath, opts.openclawStateDir);
                  const parsedBody = (clientReq as unknown as { __mhParsedBody?: Record<string, unknown> | null }).__mhParsedBody;
                  if (fb && parsedBody && fb.upstreamUrl.hostname !== reqUrl.hostname) {
                    usedFallback = true;
                    const fbBodyObj = { ...parsedBody, model: fb.modelId };
                    const fbBody = Buffer.from(JSON.stringify(fbBodyObj), "utf8");
                    const fbHeaders: Record<string, string | string[] | undefined> = {
                      ...reqHeaders,
                      authorization: `Bearer ${fb.apiKey}`,
                      "content-length": String(fbBody.length),
                    };
                    trace!.model = fb.modelId;
                    console.log(`[Telemetry] #${trace!.callNumber} failing over to MH_FALLBACK_PROVIDER=${fallbackProviderId} (${fb.modelId})`);
                    hub.markRetry(trace!, attempt + 1, 0);
                    sendAttempt(attempt + 1, fb.upstreamUrl, fb.targetPath, fbHeaders, fbBody);
                    return;
                  }
                }
                hub.markHeaders(trace!, status);
                const outHeaders: Record<string, string | string[] | undefined> = { ...upstreamRes.headers };
                delete outHeaders["transfer-encoding"];
                clientRes.writeHead(status, outHeaders);
                clientRes.end(body429);
                hub.finishInference(trace!, body429.slice(0, 300));
              });
              return;
            }

            if (trace) hub.markHeaders(trace, status);

            const outHeaders: Record<string, string | string[] | undefined> = { ...upstreamRes.headers };
            delete outHeaders["transfer-encoding"];
            clientRes.writeHead(status, outHeaders);

            if (!trace) {
              upstreamRes.pipe(clientRes);
              return;
            }

            if (isStream && status < 400) {
              let sseBuf = "";
              let finished = false;
              const streamExtractors = new Map<number, { name: string; pendingArgs: string; extractor: StreamingBatchExtractor }>();
              const finalizeOnce = (errMsg?: string) => {
                if (!finished) {
                  finished = true;
                  hub.finishInference(trace!, errMsg);
                }
              };

              upstreamRes.on("data", (chunk: Buffer) => {
                clientRes.write(chunk);
                sseBuf += chunk.toString("utf8");
                let idx: number;
                while ((idx = sseBuf.indexOf("\n")) >= 0) {
                  const line = sseBuf.slice(0, idx).trim();
                  sseBuf = sseBuf.slice(idx + 1);
                  if (!line.startsWith("data:")) continue;
                  const payload = line.slice(5).trim();
                  if (payload === "[DONE]") {
                    finalizeOnce();
                    continue;
                  }
                  if (!payload) continue;
                  try {
                    const j = JSON.parse(payload) as Record<string, unknown>;
                    const choices = Array.isArray(j.choices) ? (j.choices as Array<Record<string, unknown>>) : [];
                    const delta = choices[0]?.delta as Record<string, unknown> | undefined;
                    if (delta) {
                      const rChunk =
                        (typeof delta.reasoning === "string" ? delta.reasoning : "") ||
                        (typeof delta.reasoning_content === "string" ? delta.reasoning_content : "");
                      const cChunk = typeof delta.content === "string" ? delta.content : "";
                      const tc = Array.isArray(delta.tool_calls)
                        ? (delta.tool_calls as Array<{ index?: number; id?: string; function?: { name?: string; arguments?: string } }>)
                        : undefined;
                      hub.appendDelta(trace!, {
                        reasoningChunk: rChunk,
                        contentChunk: cChunk,
                        toolCalls: tc,
                      });
                      if (tc && opts.onStreamedBuildOp) {
                        for (const item of tc) {
                          const tcIdx = typeof item.index === "number" ? item.index : 0;
                          let entry = streamExtractors.get(tcIdx);
                          if (!entry) {
                            entry = { name: "", pendingArgs: "", extractor: new StreamingBatchExtractor() };
                            streamExtractors.set(tcIdx, entry);
                          }
                          if (item.function?.name) {
                            entry.name += item.function.name;
                          }
                          const argChunk = item.function?.arguments ?? "";
                          if (isBuildBatchToolName(entry.name)) {
                            const toFeed = entry.pendingArgs ? entry.pendingArgs + argChunk : argChunk;
                            entry.pendingArgs = "";
                            if (toFeed) {
                              const events = entry.extractor.feed(toFeed);
                              for (const ev of events) {
                                opts.onStreamedBuildOp({ ...ev, callIndex: tcIdx, toolName: entry.name });
                              }
                            }
                          } else if (argChunk && !entry.name) {
                            entry.pendingArgs += argChunk;
                          }
                        }
                      }
                    }
                    if (j.usage || j.time_info) {
                      hub.applyUsageAndTiming(
                        trace!,
                        j.usage as Parameters<TelemetryHub["applyUsageAndTiming"]>[1],
                        j.time_info as Parameters<TelemetryHub["applyUsageAndTiming"]>[2]
                      );
                    }
                  } catch {
                    // Ignore partial/malformed SSE chunk
                  }
                }
              });

              upstreamRes.on("end", () => {
                clientRes.end();
                finalizeOnce();
              });
              upstreamRes.on("error", (err) => {
                clientRes.end();
                finalizeOnce(err.message);
              });
            } else {
              const respChunks: Buffer[] = [];
              upstreamRes.on("data", (chunk: Buffer) => {
                respChunks.push(chunk);
                clientRes.write(chunk);
              });
              upstreamRes.on("end", () => {
                clientRes.end();
                const full = Buffer.concat(respChunks).toString("utf8");
                try {
                  const j = JSON.parse(full) as Record<string, unknown>;
                  if (status >= 400) {
                    const errText =
                      typeof j.message === "string"
                        ? j.message
                        : JSON.stringify(j.error ?? j).slice(0, 300);
                    hub.finishInference(trace!, errText);
                    return;
                  }
                  const choices = Array.isArray(j.choices) ? (j.choices as Array<Record<string, unknown>>) : [];
                  const msg = choices[0]?.message as Record<string, unknown> | undefined;
                  if (msg) {
                    const rText =
                      (typeof msg.reasoning === "string" ? msg.reasoning : "") ||
                      (typeof msg.reasoning_content === "string" ? msg.reasoning_content : "");
                    const cText = typeof msg.content === "string" ? msg.content : "";
                    const tc = Array.isArray(msg.tool_calls)
                      ? (msg.tool_calls as Array<{ index?: number; id?: string; function?: { name?: string; arguments?: string } }>)
                      : undefined;
                    hub.appendDelta(trace!, {
                      reasoningChunk: rText,
                      contentChunk: cText,
                      toolCalls: tc,
                    });
                  }
                  if (j.usage || j.time_info) {
                    hub.applyUsageAndTiming(
                      trace!,
                      j.usage as Parameters<TelemetryHub["applyUsageAndTiming"]>[1],
                      j.time_info as Parameters<TelemetryHub["applyUsageAndTiming"]>[2]
                    );
                  }
                  hub.finishInference(trace!, status >= 400 ? full.slice(0, 300) : undefined);
                } catch {
                  hub.finishInference(trace!, status >= 400 ? full.slice(0, 300) : undefined);
                }
              });
            }
          }
        );

        activeUpstreamReq = upstreamReq;
        upstreamReq.on("error", (err) => {
          if (clientClosed) return;
          if (trace && trace.finishedAt === null) hub.finishInference(trace, err.message);
          if (!clientRes.headersSent) {
            clientRes.writeHead(502, { "content-type": "application/json" });
          }
          if (!clientRes.writableEnded) {
            clientRes.end(JSON.stringify({ error: { message: `Telemetry proxy error: ${err.message}` } }));
          }
        });

        if (reqBody.length > 0) {
          upstreamReq.write(reqBody);
        }
        upstreamReq.end();
      };

      sendAttempt(0);
    });
  });

  proxy.listen(port, host, () => {
    console.log(`[Telemetry] Inference telemetry listening on http://${host}:${port} (Cerebras and Command Code)`);
  });

  return proxy;
}
