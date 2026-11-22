import fs from "node:fs";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import type { InferenceTrace, TelemetryHub } from "./telemetry.js";

/**
 * Provider that drives the user's installed official `claude` CLI as a child process.
 * No OAuth token is read or forwarded: the CLI authenticates itself (`/login` inside `claude`).
 * One long-lived process is kept and turns are streamed in via `--input-format stream-json`.
 */

export const CLAUDE_CLI_PROVIDER_ID = "claude-cli";
const MCP_SERVER_NAME = "minecraft";
const IDLE_TIMEOUT_MS = 300_000;

export function claudeCliProviderEntry(): Record<string, unknown> {
  const model = (id: string, name: string) => ({ id, name, api: "claude-cli", reasoning: true, input: ["text", "image"] });
  return {
    baseUrl: "claude-cli://local",
    apiKey: "",
    api: "claude-cli",
    timeoutSeconds: 1800,
    models: [model("sonnet", "Claude Sonnet (claude CLI)"), model("opus", "Claude Opus (claude CLI)"), model("haiku", "Claude Haiku (claude CLI)")],
  };
}

export function isClaudeCliRef(ref: string): boolean {
  return ref === CLAUDE_CLI_PROVIDER_ID || ref.startsWith(CLAUDE_CLI_PROVIDER_ID + "/");
}

export const CLAUDE_LOGIN_HINT = "claude CLI にログインしていません。ターミナルで `claude` を起動し、`/login` でログインしてください。";
export const CLAUDE_MISSING_HINT = "claude CLI が見つかりません。公式の claude CLI をインストールし、ターミナルで `claude` を起動して `/login` でログインしてください。";

const MCP_TOOL_PREFIX = `mcp__${MCP_SERVER_NAME}__`;

function effortFor(level: string): string {
  const l = level.toLowerCase().trim();
  return l === "medium" || l === "high" ? l : "low";
}

function looksLikeAuthError(text: string): boolean {
  return /not logged in|please run \/login|\/login|authentication_failed|invalid api key|oauth token/i.test(text);
}

interface TurnState {
  resolve: (r: { ok: boolean; reply: string }) => void;
  onStepReply?: (reply: string) => void | Promise<void>;
  trace: InferenceTrace | null;
  openTrace: InferenceTrace | null;
  usage: Record<string, number | undefined>;
  authError: boolean;
  sawOutput: boolean;
  stepChain: Promise<void>;
  lastActivity: number;
  idleTimer: NodeJS.Timeout | null;
  model: string;
  settled: boolean;
}

export class ClaudeCliAgent {
  private proc: ChildProcessWithoutNullStreams | null = null;
  private procKey = "";
  private sessionId: string | null = null;
  private resumable = false;
  private buf = "";
  private stderrTail = "";
  private turn: TurnState | null = null;
  private toolCount = 0;
  private cleared = false;

  constructor(private readonly opts: {
    telemetry: TelemetryHub;
    mcpUrl: string;
    cwd: string;
    getSystemPrompt: () => string;
  }) {}

  /** Discard the claude session and process; the next turn starts a fresh conversation. */
  clear(): { hadSession: boolean } {
    const hadSession = this.sessionId !== null || this.proc !== null;
    this.sessionId = null;
    this.resumable = false;
    if (this.turn) {
      this.cleared = true;
      this.settle(false, "claude のセッションをクリアしたため、実行中の依頼を中断しました。");
    }
    this.killProc();
    return { hadSession };
  }

  status(): { running: boolean; sessionId: string | null; resumable: boolean } {
    return { running: this.proc !== null, sessionId: this.sessionId, resumable: this.resumable };
  }

  close(): void {
    this.settle(false, "Bridge を停止します。");
    this.killProc();
  }

  async runTurn(opts: {
    modelId: string;
    thinkingLevel: string;
    userMessage: string;
    onStepReply?: (reply: string) => void | Promise<void>;
  }): Promise<{ ok: boolean; reply: string; elapsedMs: number }> {
    const t0 = Date.now();
    let r = await this.attempt(opts);
    if (!r.ok && this.resumable === false && /No conversation found/i.test(this.stderrTail + r.reply)) {
      r = await this.attempt(opts);
    }
    return { ok: r.ok, reply: r.reply, elapsedMs: Date.now() - t0 };
  }

  private async attempt(opts: {
    modelId: string;
    thinkingLevel: string;
    userMessage: string;
    onStepReply?: (reply: string) => void | Promise<void>;
  }): Promise<{ ok: boolean; reply: string }> {
    const effort = effortFor(opts.thinkingLevel);
    const systemPrompt = this.opts.getSystemPrompt();
    const key = JSON.stringify([opts.modelId, effort, systemPrompt]);
    try {
      this.ensureProc(key, opts.modelId, effort, systemPrompt);
    } catch (err) {
      return { ok: false, reply: this.describeSpawnError(err) };
    }
    const proc = this.proc!;
    this.cleared = false;
    const model = `${CLAUDE_CLI_PROVIDER_ID}/${opts.modelId}`;
    const hub = this.opts.telemetry;
    return new Promise((resolve) => {
      const turn: TurnState = {
        resolve,
        onStepReply: opts.onStepReply,
        trace: null,
        openTrace: hub.startInference({ model, stream: true, messageCount: 1, toolCount: this.toolCount }),
        usage: {},
        authError: false,
        sawOutput: false,
        stepChain: Promise.resolve(),
        lastActivity: Date.now(),
        idleTimer: null,
        model,
        settled: false,
      };
      this.turn = turn;
      turn.idleTimer = setInterval(() => {
        if (Date.now() - turn.lastActivity > IDLE_TIMEOUT_MS) {
          this.settle(false, "claude CLI から 5 分間応答がありませんでした。");
          this.killProc();
        }
      }, 5000);
      const line = JSON.stringify({ type: "user", message: { role: "user", content: opts.userMessage } });
      proc.stdin.write(line + "\n", (err) => {
        if (err) this.settle(false, "claude CLI へ送信できませんでした: " + err.message);
      });
    });
  }

  private ensureProc(key: string, modelId: string, effort: string, systemPrompt: string): void {
    if (this.proc && this.procKey === key) return;
    this.killProc();
    fs.mkdirSync(this.opts.cwd, { recursive: true });

    const resume = this.resumable && this.sessionId !== null;
    if (!this.sessionId) this.sessionId = randomUUID();
    const mcpConfig = JSON.stringify({ mcpServers: { [MCP_SERVER_NAME]: { type: "http", url: this.opts.mcpUrl } } });
    const args = [
      "-p",
      "--input-format", "stream-json",
      "--output-format", "stream-json",
      "--include-partial-messages",
      "--verbose",
      "--model", modelId,
      "--effort", effort,
      "--tools", "",
      "--strict-mcp-config",
      "--mcp-config", mcpConfig,
      "--allowedTools", `mcp__${MCP_SERVER_NAME}__*`,
      "--permission-mode", "dontAsk",
      "--setting-sources", "project",
      "--disable-slash-commands",
      ...(systemPrompt ? ["--system-prompt", systemPrompt] : []),
      ...(resume ? ["--resume", this.sessionId] : ["--session-id", this.sessionId]),
    ];
    // Never hand API-key / token credentials to the CLI: it must use its own subscription login.
    const env: NodeJS.ProcessEnv = { ...process.env };
    delete env.ANTHROPIC_API_KEY;
    delete env.ANTHROPIC_AUTH_TOKEN;

    const bin = process.env.MH_CLAUDE_BIN || (process.platform === "win32" ? "claude.exe" : "claude");
    const child = spawn(bin, args, { cwd: this.opts.cwd, env, stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
    this.proc = child;
    this.procKey = key;
    this.buf = "";
    this.stderrTail = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      if (this.proc !== child) return;
      this.buf += chunk;
      let idx: number;
      while ((idx = this.buf.indexOf("\n")) >= 0) {
        const line = this.buf.slice(0, idx).trim();
        this.buf = this.buf.slice(idx + 1);
        if (!line) continue;
        try {
          this.handleEvent(JSON.parse(line) as Record<string, any>);
        } catch {
          // ignore non-JSON noise
        }
      }
    });
    child.stderr.on("data", (chunk: string) => {
      this.stderrTail = (this.stderrTail + chunk).slice(-2000);
    });
    child.stdin.on("error", () => {});
    child.on("error", (err) => {
      if (this.proc !== child) return;
      this.proc = null;
      this.settle(false, this.describeSpawnError(err));
    });
    child.on("exit", (code) => {
      if (this.proc !== child) return;
      this.proc = null;
      const tail = this.stderrTail.trim();
      if (this.turn && !this.cleared) {
        if (looksLikeAuthError(tail)) {
          this.settle(false, CLAUDE_LOGIN_HINT);
        } else if (/No conversation found/i.test(tail)) {
          this.resumable = false;
          this.sessionId = null;
          this.settle(false, "No conversation found");
        } else {
          this.settle(false, `claude CLI が終了しました (code=${code ?? "?"})${tail ? ": " + tail.slice(-300) : ""}`);
        }
      }
    });
  }

  private describeSpawnError(err: unknown): string {
    const e = err as NodeJS.ErrnoException;
    if (e && (e.code === "ENOENT" || e.code === "EACCES")) return CLAUDE_MISSING_HINT;
    return "claude CLI を起動できませんでした: " + (e instanceof Error ? e.message : String(e));
  }

  private killProc(): void {
    const p = this.proc;
    this.proc = null;
    this.procKey = "";
    if (!p) return;
    try { p.stdin.end(); } catch {}
    try { p.kill(); } catch {}
  }

  private settle(ok: boolean, reply: string): void {
    const turn = this.turn;
    if (!turn || turn.settled) return;
    turn.settled = true;
    this.turn = null;
    if (turn.idleTimer) clearInterval(turn.idleTimer);
    const hub = this.opts.telemetry;
    for (const t of [turn.openTrace, turn.trace]) {
      if (t && t.finishedAt === null) hub.finishInference(t, ok ? undefined : reply.slice(0, 300));
    }
    void turn.stepChain.finally(() => turn.resolve({ ok, reply }));
  }

  private handleEvent(ev: Record<string, any>): void {
    const turn = this.turn;
    const hub = this.opts.telemetry;
    if (ev.type === "system" && ev.subtype === "init") {
      this.toolCount = Array.isArray(ev.tools) ? ev.tools.length : 0;
      if (turn?.openTrace) turn.openTrace.toolCount = this.toolCount;
      const servers: Array<{ name?: string; status?: string }> = Array.isArray(ev.mcp_servers) ? ev.mcp_servers : [];
      const mc = servers.find((s) => s.name === MCP_SERVER_NAME);
      if (turn && (!mc || mc.status !== "connected")) {
        this.settle(false, `claude CLI から Bridge の MCP (${this.opts.mcpUrl}) に接続できませんでした (status=${mc?.status ?? "none"})。`);
        this.killProc();
      }
      return;
    }
    if (!turn) return;
    turn.lastActivity = Date.now();

    if (ev.type === "stream_event") {
      this.handleStream(turn, ev.event ?? {});
      return;
    }
    if (ev.type === "assistant") {
      if (ev.error === "authentication_failed") turn.authError = true;
      const blocks: Array<Record<string, any>> = Array.isArray(ev.message?.content) ? ev.message.content : [];
      const text = blocks.filter((b) => b.type === "text" && typeof b.text === "string").map((b) => b.text).join("").trim();
      if (text && !turn.authError && turn.onStepReply) {
        const cb = turn.onStepReply;
        turn.stepChain = turn.stepChain.then(() => Promise.resolve(cb(text)).catch(() => {}));
      }
      return;
    }
    if (ev.type === "result") {
      if (typeof ev.session_id === "string") this.sessionId = ev.session_id;
      const text = typeof ev.result === "string" ? ev.result : "";
      if (ev.is_error) {
        if (turn.authError || looksLikeAuthError(text)) {
          this.resumable = false;
          this.settle(false, CLAUDE_LOGIN_HINT);
        } else {
          this.settle(false, `claude CLI エラー: ${text.slice(0, 300) || "unknown"}`);
        }
        return;
      }
      this.resumable = true;
      this.settle(true, text.trim() || "処理を完了しました。");
      return;
    }
    if (ev.type === "user" && Array.isArray(ev.message?.content) && ev.message.content.some((b: Record<string, any>) => b?.type === "tool_result")) {
      // Tool results are back: the next API call is timed from this moment.
      if (!turn.openTrace && !turn.trace) {
        turn.openTrace = hub.startInference({ model: turn.model, stream: true, messageCount: 1, toolCount: this.toolCount });
      }
    }
  }

  private handleStream(turn: TurnState, e: Record<string, any>): void {
    const hub = this.opts.telemetry;
    switch (e.type) {
      case "message_start": {
        const t = turn.openTrace ?? hub.startInference({ model: turn.model, stream: true, messageCount: 1, toolCount: this.toolCount });
        turn.openTrace = null;
        turn.trace = t;
        turn.usage = {};
        hub.markHeaders(t, 200);
        this.mergeUsage(turn, e.message?.usage);
        break;
      }
      case "content_block_start": {
        const b = e.content_block ?? {};
        if (b.type === "tool_use" && turn.trace) {
          hub.appendDelta(turn.trace, { toolCalls: [{ index: e.index, id: b.id, function: { name: typeof b.name === "string" ? b.name.replace(MCP_TOOL_PREFIX, "") : b.name, arguments: "" } }] });
        }
        break;
      }
      case "content_block_delta": {
        const d = e.delta ?? {};
        if (!turn.trace) break;
        if (d.type === "text_delta" && d.text) hub.appendDelta(turn.trace, { contentChunk: d.text });
        else if (d.type === "thinking_delta" && d.thinking) hub.appendDelta(turn.trace, { reasoningChunk: d.thinking });
        else if (d.type === "input_json_delta" && d.partial_json) {
          hub.appendDelta(turn.trace, { toolCalls: [{ index: e.index, function: { arguments: d.partial_json } }] });
        }
        break;
      }
      case "message_delta":
        this.mergeUsage(turn, e.usage);
        break;
      case "message_stop": {
        const t = turn.trace;
        if (!t) break;
        const u = turn.usage;
        const input = u.input_tokens ?? 0;
        const cacheRead = u.cache_read_input_tokens ?? 0;
        const cacheCreate = u.cache_creation_input_tokens ?? 0;
        if (u.output_tokens !== undefined) {
          hub.applyUsageAndTiming(t, {
            prompt_tokens: input + cacheRead + cacheCreate,
            completion_tokens: u.output_tokens,
            prompt_tokens_details: { cached_tokens: cacheRead },
            completion_tokens_details: { reasoning_tokens: u.thinking_tokens ?? 0 },
          });
          // Only report the reasoning/output split when the CLI actually provided it.
          if (u.thinking_tokens === undefined) {
            t.reasoningTokens = null;
            t.outputTokens = null;
          }
        }
        hub.finishInference(t);
        turn.trace = null;
        break;
      }
      default:
        break;
    }
  }

  private mergeUsage(turn: TurnState, usage: Record<string, any> | undefined): void {
    if (!usage) return;
    for (const k of ["input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "output_tokens"]) {
      if (typeof usage[k] === "number") turn.usage[k] = usage[k];
    }
    const th = usage.output_tokens_details?.thinking_tokens;
    if (typeof th === "number") turn.usage.thinking_tokens = th;
  }
}
