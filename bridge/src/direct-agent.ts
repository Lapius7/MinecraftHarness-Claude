import fs from "node:fs";
import path from "node:path";
import { OPENAI_TOOLS, ToolDispatcher, dispatchToolWithObserve } from "./mcp-tools.js";

export interface ResolvedProviderConfig {
  providerId: string;
  modelId: string;
  baseUrl: string;
  apiKey: string;
  supportsImage: boolean;
  maxTokens: number;
  maxTokensField: string;
  supportsReasoningEffort: boolean;
  supportedReasoningEfforts: string[];
  timeoutMs: number;
}

interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string | Array<Record<string, unknown>> | null;
  reasoning_content?: string;
  reasoning?: string;
  tool_calls?: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }>;
  tool_call_id?: string;
}

const OBSERVATION_TOOLS = new Set(["mc_inspect_world", "mc_take_photo", "mc_get_context"]);

function loadDotEnvFileInto(filePath: string, out: Record<string, string>): void {
  if (!fs.existsSync(filePath)) return;
  for (const line of fs.readFileSync(filePath, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq > 0) {
      const k = trimmed.slice(0, eq).trim();
      const v = trimmed.slice(eq + 1).trim();
      if (k && !(k in out)) out[k] = v;
    }
  }
}

function loadDotEnvMap(stateDir: string): Record<string, string> {
  const out: Record<string, string> = {};
  loadDotEnvFileInto(path.join(stateDir, ".env"), out);
  const projectRoot = path.resolve(stateDir, "..", "..");
  loadDotEnvFileInto(path.join(projectRoot, ".env"), out);
  return out;
}

function resolveEnvPlaceholders(value: string, dotEnv: Record<string, string>): string {
  return value.replace(/\$\{([A-Z0-9_]+)\}/g, (_match, key: string) => {
    return process.env[key] ?? dotEnv[key] ?? "";
  });
}

export function resolveActiveProviderConfig(
  openclawConfigPath: string,
  openclawStateDir: string
): ResolvedProviderConfig {
  let effectivePath = openclawConfigPath;
  if (!fs.existsSync(effectivePath)) {
    const fallback = path.resolve(openclawStateDir, "..", "..", "config", "openclaw.example.json");
    if (fs.existsSync(fallback)) {
      effectivePath = fallback;
    }
  }
  const raw = fs.readFileSync(effectivePath, "utf8").replace(/^\uFEFF/, "");
  const config = JSON.parse(raw) as Record<string, unknown>;
  const defaults = (config.agents as Record<string, unknown> | undefined)?.defaults as Record<string, unknown> | undefined;
  const modelField = defaults?.model;
  const primary =
    typeof modelField === "string"
      ? modelField
      : typeof (modelField as Record<string, unknown> | undefined)?.primary === "string"
        ? String((modelField as Record<string, unknown>).primary)
        : "";

  const slashIdx = primary.indexOf("/");
  if (slashIdx <= 0) {
    throw new Error(`Invalid agents.defaults.model.primary in openclaw.json: '${primary}'`);
  }
  const providerId = primary.slice(0, slashIdx);
  const modelId = primary.slice(slashIdx + 1);

  const providers = (config.models as Record<string, unknown> | undefined)?.providers as
    | Record<string, Record<string, unknown>>
    | undefined;
  const provider = providers?.[providerId];
  if (!provider) {
    throw new Error(`Provider '${providerId}' not found in openclaw.json`);
  }

  const dotEnv = loadDotEnvMap(openclawStateDir);
  const baseUrl = typeof provider.baseUrl === "string" ? provider.baseUrl.replace(/\/+$/, "") : "";
  if (!baseUrl) {
    throw new Error(`Provider '${providerId}' has no baseUrl in openclaw.json`);
  }
  const rawApiKey = typeof provider.apiKey === "string" ? provider.apiKey : "";
  const apiKey = resolveEnvPlaceholders(rawApiKey, dotEnv);

  const models = Array.isArray(provider.models) ? (provider.models as Array<Record<string, unknown>>) : [];
  const modelEntry = models.find((m) => m.id === modelId) ?? models[0];
  const inputList = Array.isArray(modelEntry?.input) ? (modelEntry.input as string[]) : ["text"];
  const compat = (modelEntry?.compat as Record<string, unknown> | undefined) ?? {};
  const supportedEfforts = Array.isArray(compat.supportedReasoningEfforts)
    ? (compat.supportedReasoningEfforts as string[])
    : ["low", "medium", "high"];

  const timeoutSec = typeof provider.timeoutSeconds === "number" ? provider.timeoutSeconds : 300;

  return {
    providerId,
    modelId,
    baseUrl,
    apiKey,
    supportsImage: inputList.includes("image"),
    maxTokens: typeof modelEntry?.maxTokens === "number" ? modelEntry.maxTokens : 16384,
    maxTokensField: typeof compat.maxTokensField === "string" ? compat.maxTokensField : "max_completion_tokens",
    supportsReasoningEffort: compat.supportsReasoningEffort !== false,
    supportedReasoningEfforts: supportedEfforts,
    timeoutMs: Math.max(15000, timeoutSec * 1000),
  };
}

function stripFrontmatter(md: string): string {
  if (!md.startsWith("---")) return md.trim();
  const end = md.indexOf("\n---", 3);
  if (end < 0) return md.trim();
  return md.slice(end + 4).trim();
}

export class DirectAgent {
  private readonly openclawConfigPath: string;
  private readonly openclawStateDir: string;
  private readonly workspaceDir: string;
  private readonly dispatch: ToolDispatcher;
  private readonly maxSteps: number;

  private readonly sessions = new Map<string, ChatMessage[]>();
  private readonly providersRequiringReasoningContent = new Set<string>();
  private readonly turnReasoningByMsg = new WeakMap<ChatMessage, string>();
  private cachedSystemPrompt = "";
  private cachedPromptKey = "";

  constructor(opts: {
    openclawConfigPath: string;
    openclawStateDir: string;
    workspaceDir: string;
    dispatch: ToolDispatcher;
    maxSteps?: number;
  }) {
    this.openclawConfigPath = opts.openclawConfigPath;
    this.openclawStateDir = opts.openclawStateDir;
    this.workspaceDir = opts.workspaceDir;
    this.dispatch = opts.dispatch;
    this.maxSteps = opts.maxSteps ?? Math.max(2, Number(process.env.MH_DIRECT_MAX_STEPS ?? 12));
  }

  private getSystemPrompt(): string {
    const agentsPath = path.join(this.workspaceDir, "AGENTS.md");
    const skillPath = path.join(this.workspaceDir, "skills", "minecraft-harness", "SKILL.md");
    const m1 = fs.existsSync(agentsPath) ? fs.statSync(agentsPath).mtimeMs : 0;
    const m2 = fs.existsSync(skillPath) ? fs.statSync(skillPath).mtimeMs : 0;
    const key = `${m1}:${m2}`;
    if (key === this.cachedPromptKey && this.cachedSystemPrompt) {
      return this.cachedSystemPrompt;
    }

    const parts: string[] = [];
    if (fs.existsSync(agentsPath)) {
      parts.push(fs.readFileSync(agentsPath, "utf8").trim());
    }
    if (fs.existsSync(skillPath)) {
      parts.push(stripFrontmatter(fs.readFileSync(skillPath, "utf8")));
    }
    this.cachedSystemPrompt = parts.join("\n\n");
    this.cachedPromptKey = key;
    return this.cachedSystemPrompt;
  }

  private getSessionHistory(sessionId: string): ChatMessage[] {
    let hist = this.sessions.get(sessionId);
    if (!hist) {
      hist = [];
      this.sessions.set(sessionId, hist);
      // Bound total in-memory sessions
      if (this.sessions.size > 32) {
        const oldestKey = this.sessions.keys().next().value;
        if (oldestKey) this.sessions.delete(oldestKey);
      }
    }
    return hist;
  }

  private pruneHistory(history: ChatMessage[]): void {
    // Keep up to 24 messages while ensuring we always start at a 'user' message boundary
    const MAX_MESSAGES = 24;
    if (history.length > MAX_MESSAGES) {
      let cut = history.length - MAX_MESSAGES;
      while (cut < history.length && history[cut].role !== "user") {
        cut++;
      }
      if (cut > 0 && cut < history.length) {
        history.splice(0, cut);
      }
    }

    // Compact bulky older tool results and strip old reasoning_content (keep the last 6 messages untouched for prompt cache stability)
    const compactLimit = Math.max(0, history.length - 6);
    for (let i = 0; i < compactLimit; i++) {
      const m = history[i];
      if (m.role === "assistant") {
        if (m.reasoning_content !== undefined) delete m.reasoning_content;
        if (m.reasoning !== undefined) delete m.reasoning;
      } else if (m.role === "tool" && typeof m.content === "string" && m.content.length > 1600) {
        m.content = m.content.slice(0, 1400) + "...(truncated)";
      } else if (m.role === "user" && Array.isArray(m.content)) {
        // Strip old base64 images from older turns to save tokens
        m.content = "[Previous screenshot omitted]";
      }
    }
  }

  async runTurn(opts: {
    sessionId: string;
    userMessage: string;
    thinkingLevel: string;
  }): Promise<{ ok: boolean; reply: string; elapsedMs: number }> {
    const t0 = Date.now();
    const cfg = resolveActiveProviderConfig(this.openclawConfigPath, this.openclawStateDir);
    const systemPrompt = this.getSystemPrompt();
    const history = this.getSessionHistory(opts.sessionId);

    this.pruneHistory(history);
    const turnStartIdx = history.length;
    history.push({ role: "user", content: opts.userMessage });

    try {
      for (let step = 0; step < this.maxSteps; step++) {
        const sendMessages = (): Array<Record<string, unknown>> => {
          const includeReasoning = this.providersRequiringReasoningContent.has(cfg.providerId);
          return [
            { role: "system", content: systemPrompt },
            ...history.map((m) => {
              const out: Record<string, unknown> = { role: m.role, content: m.content };
              if (m.tool_calls) out.tool_calls = m.tool_calls;
              if (m.tool_call_id) out.tool_call_id = m.tool_call_id;
              if (includeReasoning && m.role === "assistant" && m.tool_calls) {
                const rc = this.turnReasoningByMsg.get(m);
                if (rc) out.reasoning_content = rc;
              }
              return out;
            }),
          ];
        };

        const runAttempt = async (): Promise<{
          ok: boolean;
          status: number;
          content: string;
          reasoningContent: string;
          toolCalls: Array<{ id: string; type: "function"; function: { name: string; arguments: string } }>;
          errorText?: string;
        }> => {
          const reqBody: Record<string, unknown> = {
            model: cfg.modelId,
            messages: sendMessages(),
            tools: OPENAI_TOOLS,
            tool_choice: "auto",
            parallel_tool_calls: true,
            stream: true,
            stream_options: { include_usage: true },
          };
          reqBody[cfg.maxTokensField] = cfg.maxTokens;

          // Respect the user's configured thinkingLevel faithfully (NO per-phase auto-switching)
          if (cfg.supportsReasoningEffort) {
            const lvl = opts.thinkingLevel.toLowerCase().trim();
            if (lvl === "low" || lvl === "medium" || lvl === "high") {
              reqBody.reasoning_effort = lvl;
            } else if (lvl === "minimal") {
              reqBody.reasoning_effort = "low";
            }
            // For "off", omit reasoning_effort
          }

          const headers: Record<string, string> = {
            "content-type": "application/json",
            accept: "text/event-stream",
          };
          if (cfg.apiKey) {
            headers.authorization = `Bearer ${cfg.apiKey}`;
          }

          const ctrl = new AbortController();
          const timer = setTimeout(() => ctrl.abort(), cfg.timeoutMs);
          try {
            const res = await fetch(`${cfg.baseUrl}/chat/completions`, {
              method: "POST",
              headers,
              body: JSON.stringify(reqBody),
              signal: ctrl.signal,
            });

            if (!res.ok) {
              const errBody = await res.text().catch(() => "");
              return { ok: false, status: res.status, content: "", reasoningContent: "", toolCalls: [], errorText: errBody };
            } else if (!res.body) {
              return { ok: false, status: res.status, content: "", reasoningContent: "", toolCalls: [], errorText: "Empty response body" };
            } else {
              return await this.consumeSseResponse(res.body, res.status);
            }
          } finally {
            clearTimeout(timer);
          }
        };

        let streamRes = await runAttempt();
        // Auto-adapt if a specific provider requires reasoning_content on assistant tool-call messages
        if (
          !streamRes.ok &&
          streamRes.status === 400 &&
          !this.providersRequiringReasoningContent.has(cfg.providerId) &&
          typeof streamRes.errorText === "string" &&
          /reasoning_content/i.test(streamRes.errorText) &&
          /missing|required/i.test(streamRes.errorText)
        ) {
          this.providersRequiringReasoningContent.add(cfg.providerId);
          streamRes = await runAttempt();
        }

        if (!streamRes.ok) {
          // Roll back dangling user message if first step failed before any assistant output
          if (history.length === turnStartIdx + 1) {
            history.pop();
          }
          const elapsedMs = Date.now() - t0;
          if (streamRes.status === 429) {
            return {
              ok: false,
              reply: "AIが混雑していて回答を完了できませんでした。少し待ってから続けてください。",
              elapsedMs,
            };
          }
          return {
            ok: false,
            reply: `Direct LLM HTTP ${streamRes.status}: ${(streamRes.errorText ?? "").slice(0, 300)}`,
            elapsedMs,
          };
        }

        const assistantMsg: ChatMessage = {
          role: "assistant",
          content: streamRes.content || null,
        };
        if (streamRes.toolCalls.length > 0) {
          assistantMsg.tool_calls = streamRes.toolCalls;
          if (streamRes.reasoningContent) {
            this.turnReasoningByMsg.set(assistantMsg, streamRes.reasoningContent);
          }
        }
        history.push(assistantMsg);

        if (streamRes.toolCalls.length === 0) {
          return { ok: true, reply: streamRes.content.trim(), elapsedMs: Date.now() - t0 };
        }

        // Execute all tool calls in parallel in-process (0ms MCP overhead)
        const executed = await Promise.all(
          streamRes.toolCalls.map(async (tc) => {
            let parsedArgs: Record<string, unknown> = {};
            try {
              parsedArgs = tc.function.arguments ? (JSON.parse(tc.function.arguments) as Record<string, unknown>) : {};
            } catch (err) {
              return {
                tc,
                args: parsedArgs,
                result: {
                  ok: false,
                  error: `Invalid JSON arguments for ${tc.function.name}: ${err instanceof Error ? err.message : String(err)}`,
                } as Record<string, unknown>,
              };
            }
            try {
              const result = await dispatchToolWithObserve(this.dispatch, tc.function.name, parsedArgs);
              return { tc, args: parsedArgs, result };
            } catch (err) {
              return {
                tc,
                args: parsedArgs,
                result: {
                  ok: false,
                  error: err instanceof Error ? err.message : String(err),
                } as Record<string, unknown>,
              };
            }
          })
        );

        const pendingImages: Array<{ data: string; mimeType: string; label: string }> = [];
        let allSucceeded = true;
        let hasObservation = false;
        let hasPendingMultiBatch = false;
        let fastFinishReply: string | null = null;
        let builtSummaryFallback: string | null = null;
        let actionSummaryFallback: string | null = null;

        for (const item of executed) {
          const toolName = item.tc.function.name;
          if (OBSERVATION_TOOLS.has(toolName) || (toolName === "mc_exec_commands" && item.args.observe)) {
            hasObservation = true;
          }
          if (item.result.ok === false) {
            allSucceeded = false;
          }
          if (toolName === "mc_build_batch") {
            if (item.args.finish === false) {
              hasPendingMultiBatch = true;
            } else if (item.result.ok !== false) {
              if (typeof item.args.reply === "string" && item.args.reply.trim().length > 0) {
                fastFinishReply = item.args.reply.trim();
              } else {
                const label = typeof item.args.label === "string" && item.args.label.trim() ? `「${item.args.label.trim()}」` : "建築";
                const q = Number(item.result.queued ?? item.result.totalQueued ?? 0);
                builtSummaryFallback = q > 0 ? `${label}を完了しました！（${q}ブロック）` : `${label}を完了しました！`;
              }
            }
          } else if (
            item.result.ok !== false &&
            (toolName === "mc_fx" || toolName === "mc_exec_commands" || toolName === "mc_place_command_chain" || toolName === "mc_function_pack")
          ) {
            actionSummaryFallback = "処理を完了しました！";
          }

          const rawImage = item.result._image as { data?: string; mimeType?: string } | undefined;
          let toolPayload: Record<string, unknown> = item.result;
          if (rawImage && typeof rawImage.data === "string" && typeof rawImage.mimeType === "string") {
            const { _image: _removed, ...rest } = item.result;
            toolPayload = rest;
            if (cfg.supportsImage) {
              pendingImages.push({ data: rawImage.data, mimeType: rawImage.mimeType, label: toolName });
            }
          }

          history.push({
            role: "tool",
            tool_call_id: item.tc.id,
            content: JSON.stringify(toolPayload),
          });
        }

        // Fast-Finish: skip redundant final LLM roundtrip whenever all action tools succeeded in 1 turn
        const finalFastReply =
          fastFinishReply ??
          (streamRes.content.trim() || builtSummaryFallback || actionSummaryFallback);
        if (allSucceeded && !hasObservation && !hasPendingMultiBatch && finalFastReply) {
          history.push({ role: "assistant", content: finalFastReply });
          return { ok: true, reply: finalFastReply, elapsedMs: Date.now() - t0 };
        }

        if (pendingImages.length > 0) {
          const contentParts: Array<Record<string, unknown>> = [
            { type: "text", text: "Captured screenshot(s) from tool call:" },
          ];
          for (const img of pendingImages) {
            contentParts.push({
              type: "image_url",
              image_url: { url: `data:${img.mimeType};base64,${img.data}` },
            });
          }
          history.push({ role: "user", content: contentParts });
        }
      }

      return {
        ok: true,
        reply: "処理を完了しました。",
        elapsedMs: Date.now() - t0,
      };
    } catch (err) {
      if (history.length === turnStartIdx + 1) {
        history.pop();
      }
      return {
        ok: false,
        reply: `Direct agent error: ${err instanceof Error ? err.message : String(err)}`,
        elapsedMs: Date.now() - t0,
      };
    }
  }

  private async consumeSseResponse(
    body: ReadableStream<Uint8Array>,
    status: number
  ): Promise<{
    ok: boolean;
    status: number;
    content: string;
    reasoningContent: string;
    toolCalls: Array<{ id: string; type: "function"; function: { name: string; arguments: string } }>;
  }> {
    const reader = body.getReader();
    const decoder = new TextDecoder("utf-8");
    let sseBuf = "";
    let content = "";
    let reasoningContent = "";
    let reasoningFallback = "";
    const tcMap = new Map<number, { id: string; name: string; arguments: string }>();

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      sseBuf += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = sseBuf.indexOf("\n")) >= 0) {
        const line = sseBuf.slice(0, idx).trim();
        sseBuf = sseBuf.slice(idx + 1);
        if (!line.startsWith("data:")) continue;
        const payload = line.slice(5).trim();
        if (!payload || payload === "[DONE]") continue;
        try {
          const j = JSON.parse(payload) as Record<string, unknown>;
          const choices = Array.isArray(j.choices) ? (j.choices as Array<Record<string, unknown>>) : [];
          const delta = choices[0]?.delta as Record<string, unknown> | undefined;
          if (!delta) continue;
          if (typeof delta.reasoning_content === "string") {
            reasoningContent += delta.reasoning_content;
          } else if (typeof delta.reasoning === "string") {
            reasoningFallback += delta.reasoning;
          }
          if (typeof delta.content === "string") {
            content += delta.content;
          }
          if (Array.isArray(delta.tool_calls)) {
            for (const tc of delta.tool_calls as Array<{
              index?: number;
              id?: string;
              function?: { name?: string; arguments?: string };
            }>) {
              const tcIdx = typeof tc.index === "number" ? tc.index : 0;
              let entry = tcMap.get(tcIdx);
              if (!entry) {
                entry = { id: tc.id ?? `call_${tcIdx}_${Date.now()}`, name: "", arguments: "" };
                tcMap.set(tcIdx, entry);
              }
              if (tc.id) entry.id = tc.id;
              if (tc.function?.name) entry.name += tc.function.name;
              if (tc.function?.arguments) entry.arguments += tc.function.arguments;
            }
          }
        } catch {
          // Ignore partial or non-JSON SSE lines
        }
      }
    }

    const toolCalls = Array.from(tcMap.entries())
      .sort((a, b) => a[0] - b[0])
      .map(([_idx, entry]) => ({
        id: entry.id,
        type: "function" as const,
        function: { name: entry.name, arguments: entry.arguments },
      }));

    return {
      ok: true,
      status,
      content,
      reasoningContent: reasoningContent || reasoningFallback,
      toolCalls,
    };
  }
}
