import { Conversations } from "./conversations.js";

import http from "node:http";
import { spawn, ChildProcess } from "node:child_process";
import net from "node:net";
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { McClient } from "./mc-client.js";
import { TelemetryHub, startCerebrasTelemetryProxy, StreamedBuildOpEvent } from "./telemetry.js";
import { renderDashboardHtml } from "./dashboard.js";
import { captureMinecraftScreenshot, closeScreenshotWorker } from "./screenshot.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createMinecraftMcpServer } from "./mcp-tools.js";
import { DirectAgent } from "./direct-agent.js";
import { opFingerprint } from "./stream-ops.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(__dirname, "..", "..");

const WS_URL = process.env.MH_WS_URL ?? "ws://127.0.0.1:25580";
const RPC_HOST = "127.0.0.1";
const RPC_PORT = Number(process.env.MH_RPC_PORT ?? 25581);
const PROXY_PORT = Number(process.env.MH_PROXY_PORT ?? 25582);
const GATEWAY_PORT = Number(process.env.MH_OPENCLAW_GATEWAY_PORT ?? 18789);
const configuredTurnLimit = Number(process.env.MH_MAX_SESSION_TURNS);
const MAX_SESSION_TURNS = Number.isInteger(configuredTurnLimit) && configuredTurnLimit > 0 ? configuredTurnLimit : Infinity;

const OPENCLAW_HOME = process.env.OPENCLAW_HOME ?? path.join(PROJECT_ROOT, ".openclaw-home");
const OPENCLAW_STATE_DIR = process.env.OPENCLAW_STATE_DIR ?? path.join(OPENCLAW_HOME, ".openclaw");
const OPENCLAW_CONFIG_PATH = process.env.OPENCLAW_CONFIG_PATH ?? path.join(OPENCLAW_STATE_DIR, "openclaw.json");
const OPENCLAW_ENTRY = path.join(PROJECT_ROOT, ".tools", "openclaw-pkg", "node_modules", "openclaw", "openclaw.mjs");
loadDotEnvInto(process.env);
const AGENT_MODE = (process.env.MH_AGENT_MODE ?? "openclaw").toLowerCase() === "direct" ? "direct" : "openclaw";

let currentThinking = process.env.MH_THINKING ?? "low";
const conversations = new Conversations(path.join(OPENCLAW_STATE_DIR, "mh-conversations.json"));
let gatewayProc: ChildProcess | null = null;
let gatewayReady = AGENT_MODE === "direct";
let gatewayWarm = AGENT_MODE === "direct";
let gatewayStartPromise: Promise<boolean> | null = null;
let gatewayWarmupPromise: Promise<void> | null = null;
let promptQueue: Promise<void> = Promise.resolve();

function loadDotEnvInto(env: NodeJS.ProcessEnv): void {
  const candidates = [
    path.join(OPENCLAW_STATE_DIR, ".env"),
    path.join(PROJECT_ROOT, ".env"),
  ];
  for (const envFile of candidates) {
    if (!fs.existsSync(envFile)) continue;
    for (const line of fs.readFileSync(envFile, "utf8").split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const eq = trimmed.indexOf("=");
      if (eq > 0) {
        const k = trimmed.slice(0, eq).trim();
        const v = trimmed.slice(eq + 1).trim();
        if (!env[k]) env[k] = v;
      }
    }
  }
  if (!env.OPENCODE_GO_API_KEY && env.OPENCODE_API_KEY) {
    env.OPENCODE_GO_API_KEY = env.OPENCODE_API_KEY;
  }
}

function buildOpenClawEnv(): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {
    ...process.env,
    OPENCLAW_HOME,
    OPENCLAW_STATE_DIR,
    OPENCLAW_CONFIG_PATH,
    TEMP: path.join(PROJECT_ROOT, ".tools", "tmp"),
    TMP: path.join(PROJECT_ROOT, ".tools", "tmp"),
  };
  loadDotEnvInto(env);
  return env;
}

function checkPortOpen(host: string, port: number, timeoutMs = 600): Promise<boolean> {
  return new Promise((resolve) => {
    const sock = net.createConnection({ host, port });
    const done = (ok: boolean) => {
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(timeoutMs);
    sock.once("connect", () => done(true));
    sock.once("timeout", () => done(false));
    sock.once("error", () => done(false));
  });
}

async function ensureGateway(): Promise<boolean> {
  if (await checkPortOpen("127.0.0.1", GATEWAY_PORT)) {
    gatewayReady = true;
    return true;
  }
  if (gatewayStartPromise) {
    return gatewayStartPromise;
  }
  gatewayStartPromise = (async () => {
    const externalGateway = process.env.MH_NO_AUTO_GATEWAY === "1";
    if (!externalGateway) {
      if (!fs.existsSync(OPENCLAW_ENTRY)) {
        console.warn(`[Bridge] openclaw.mjs not found at ${OPENCLAW_ENTRY}`);
        return false;
      }
      if (!gatewayProc || gatewayProc.killed) {
        console.log(`[Bridge] Starting OpenClaw Gateway on port ${GATEWAY_PORT}...`);
        gatewayProc = spawn(
          process.execPath,
          [OPENCLAW_ENTRY, "gateway", "run", "--port", String(GATEWAY_PORT), "--bind", "loopback"],
          { env: buildOpenClawEnv(), stdio: ["ignore", "pipe", "pipe"] }
        );
        gatewayProc.stdout?.on("data", (d) => {
          const s = d.toString("utf8").trim();
          if (s) console.log(`[Gateway] ${s}`);
        });
        gatewayProc.stderr?.on("data", (d) => {
          const s = d.toString("utf8").trim();
          if (s) console.error(`[Gateway:err] ${s}`);
        });
        gatewayProc.on("exit", (code) => {
          console.log(`[Bridge] OpenClaw Gateway exited with code ${code}`);
          gatewayReady = false;
          gatewayProc = null;
        });
      }
    }

    for (let i = 0; i < 240; i++) {
      if (await checkPortOpen("127.0.0.1", GATEWAY_PORT)) {
        gatewayReady = true;
        console.log(`[Bridge] OpenClaw Gateway ready on 127.0.0.1:${GATEWAY_PORT} (HTTP direct mode — 0ms CLI startup)`);
        return true;
      }
      if (!externalGateway && !gatewayProc) {
        return false;
      }
      if ((i + 1) % 20 === 0) {
        console.log(`[Bridge] Waiting for OpenClaw Gateway to become ready (${Math.round((i + 1) * 0.5)}s)...`);
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    return false;
  })().finally(() => {
    gatewayStartPromise = null;
  });
  return gatewayStartPromise;
}

async function warmUpGatewayOnce(): Promise<void> {
  if (gatewayWarm) return;
  if (gatewayWarmupPromise) return gatewayWarmupPromise;
  gatewayWarmupPromise = (async () => {
    const t0 = Date.now();
    console.log(`[Bridge] Pre-warming OpenClaw embedded runtime & HTTP MCP...`);
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 120000);
    try {
      const res = await fetch(`http://127.0.0.1:${GATEWAY_PORT}/v1/chat/completions`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-openclaw-session-key": "agent:main:mh-warmup",
        },
        body: JSON.stringify({
          model: "openclaw/default",
          user: "mh-warmup",
          messages: [{ role: "user", content: "/think:off\n__mh_warmup__" }],
        }),
        signal: ctrl.signal,
      });
      await res.text().catch(() => "");
      gatewayWarm = true;
      console.log(`[Bridge] OpenClaw runtime & HTTP MCP pre-warmed in ${Date.now() - t0}ms (0ms cold-start ready!)`);
    } catch (err) {
      console.warn(`[Bridge] Gateway pre-warm warning:`, err instanceof Error ? err.message : String(err));
    } finally {
      clearTimeout(timer);
      gatewayWarmupPromise = null;
    }
  })();
  return gatewayWarmupPromise;
}

interface ConfiguredModelInfo {
  ref: string;
  provider: string;
  modelId: string;
  name: string;
  apiKeyEnv: string;
  hasApiKey: boolean;
  active: boolean;
}

function readOpenClawConfigObject(): Record<string, unknown> {
  let targetPath = OPENCLAW_CONFIG_PATH;
  if (!fs.existsSync(targetPath)) {
    const examplePath = path.join(PROJECT_ROOT, "config", "openclaw.example.json");
    if (fs.existsSync(examplePath)) {
      fs.mkdirSync(path.dirname(targetPath), { recursive: true });
      const ws = path.join(PROJECT_ROOT, "bridge", "openclaw-workspace").replace(/\\/g, "\\\\");
      const raw = fs.readFileSync(examplePath, "utf8").replace("__WORKSPACE_DIR__", ws);
      fs.writeFileSync(targetPath, raw, "utf8");
    }
  }
  return JSON.parse(fs.readFileSync(targetPath, "utf8").replace(/^\uFEFF/, "")) as Record<string, unknown>;
}

function saveOpenClawConfigObject(config: Record<string, unknown>): void {
  const tmp = `${OPENCLAW_CONFIG_PATH}.${Date.now()}.${Math.random().toString(36).slice(2, 8)}.tmp`;
  try {
    fs.writeFileSync(tmp, JSON.stringify(config, null, 2) + "\n", { flag: "wx", mode: 0o600 });
    fs.renameSync(tmp, OPENCLAW_CONFIG_PATH);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

function listConfiguredModels(): { active: string; models: ConfiguredModelInfo[] } {
  const envMap: NodeJS.ProcessEnv = { ...process.env };
  loadDotEnvInto(envMap);
  const config = readOpenClawConfigObject();
  const modelField = (config.agents as Record<string, unknown> | undefined)?.defaults as Record<string, unknown> | undefined;
  const rawModel = modelField?.model;
  const active =
    typeof rawModel === "string"
      ? rawModel
      : typeof (rawModel as Record<string, unknown> | undefined)?.primary === "string"
        ? String((rawModel as Record<string, unknown>).primary)
        : "";
  const providers = ((config.models as Record<string, unknown> | undefined)?.providers ?? {}) as Record<
    string,
    Record<string, unknown>
  >;
  const models: ConfiguredModelInfo[] = [];
  for (const [providerId, provider] of Object.entries(providers)) {
    const rawKey = typeof provider.apiKey === "string" ? provider.apiKey : "";
    const envMatch = /^\$\{([A-Z0-9_]+)\}$/.exec(rawKey.trim());
    const apiKeyEnv = envMatch ? envMatch[1] : "";
    const resolvedKey = rawKey.replace(/\$\{([A-Z0-9_]+)\}/g, (_m, k: string) => envMap[k] ?? "");
    const hasApiKey = resolvedKey.trim().length > 0;
    const list = Array.isArray(provider.models) ? (provider.models as Array<Record<string, unknown>>) : [];
    for (const entry of list) {
      const id = typeof entry.id === "string" ? entry.id : "";
      if (!id) continue;
      const ref = `${providerId}/${id}`;
      const name = typeof entry.name === "string" && entry.name ? entry.name : ref;
      models.push({
        ref,
        provider: providerId,
        modelId: id,
        name,
        apiKeyEnv,
        hasApiKey,
        active: ref === active,
      });
    }
  }
  return { active, models };
}

function selectConfiguredModel(targetRaw: string): {
  ok: boolean;
  active?: string;
  selected?: ConfiguredModelInfo;
  warning?: string;
  error?: string;
} {
  const query = targetRaw.trim().toLowerCase();
  if (!query) {
    return { ok: false, error: "Model target is empty" };
  }
  const { models } = listConfiguredModels();
  const rawTrimmed = targetRaw.trim();
  const config = readOpenClawConfigObject();
  const providers = ((config.models as Record<string, unknown> | undefined)?.providers ?? {}) as Record<
    string,
    Record<string, unknown>
  >;

  let match =
    models.find((m) => m.ref.toLowerCase() === query) ??
    models.find((m) => m.provider.toLowerCase() === query) ??
    models.find((m) => m.modelId.toLowerCase() === query);

  // Allow dynamic `<existing-provider>/<any-model-id>` (e.g. openrouter/deepseek/deepseek-r1 or opencode-go/kimi-k3)
  if (!match && rawTrimmed.includes("/")) {
    const slashIdx = rawTrimmed.indexOf("/");
    const providerPrefix = rawTrimmed.slice(0, slashIdx).toLowerCase();
    const dynamicModelId = rawTrimmed.slice(slashIdx + 1).trim();
    const matchedProviderKey = Object.keys(providers).find((k) => k.toLowerCase() === providerPrefix);
    if (matchedProviderKey && dynamicModelId && !/\s/.test(dynamicModelId)) {
      const provider = providers[matchedProviderKey];
      const list = Array.isArray(provider.models) ? (provider.models as Array<Record<string, unknown>>) : [];
      if (!list.some((entry) => entry.id === dynamicModelId)) {
        list.push({
          id: dynamicModelId,
          name: `${dynamicModelId} (${matchedProviderKey})`,
          api: "openai-completions",
          reasoning: true,
          input: ["text", "image"],
          contextWindow: 131072,
          maxTokens: 16384,
          compat: {
            supportsReasoningEffort: true,
            supportedReasoningEfforts: ["low", "medium", "high"],
            supportsDeveloperRole: false,
            supportsStrictMode: false,
            supportsTools: true,
            maxTokensField: "max_completion_tokens",
          },
        });
        provider.models = list;
      }
      const envMap: NodeJS.ProcessEnv = { ...process.env };
      loadDotEnvInto(envMap);
      const rawKey = typeof provider.apiKey === "string" ? provider.apiKey : "";
      const envMatch = /^\$\{([A-Z0-9_]+)\}$/.exec(rawKey.trim());
      const apiKeyEnv = envMatch ? envMatch[1] : "";
      const resolvedKey = rawKey.replace(/\$\{([A-Z0-9_]+)\}/g, (_m, k: string) => envMap[k] ?? "");
      match = {
        ref: `${matchedProviderKey}/${dynamicModelId}`,
        provider: matchedProviderKey,
        modelId: dynamicModelId,
        name: `${dynamicModelId} (${matchedProviderKey})`,
        apiKeyEnv,
        hasApiKey: resolvedKey.trim().length > 0,
        active: true,
      };
    }
  }

  if (!match) {
    match = models.find((m) => m.ref.toLowerCase().includes(query) || m.name.toLowerCase().includes(query));
  }

  if (!match) {
    const valid = models.map((m) => m.provider + " (" + m.ref + ")").join(", ");
    return { ok: false, error: `Unknown model '${targetRaw}'. Available: ${valid} (or <provider>/<model-id>)` };
  }
  const agents = (config.agents ?? {}) as Record<string, unknown>;
  const defaults = (agents.defaults ?? {}) as Record<string, unknown>;
  const modelObj = typeof defaults.model === "object" && defaults.model !== null
    ? (defaults.model as Record<string, unknown>)
    : {};
  modelObj.primary = match.ref;
  defaults.model = modelObj;
  agents.defaults = defaults;
  config.agents = agents;
  saveOpenClawConfigObject(config);

  const warning = !match.hasApiKey && match.apiKeyEnv
    ? `${match.apiKeyEnv} が未設定です。.env に ${match.apiKeyEnv}=<キー> を設定してください。`
    : undefined;
  return {
    ok: true,
    active: match.ref,
    selected: { ...match, active: true },
    warning,
  };
}

async function configuredModelHeaders(): Promise<Record<string, string>> {
  const config = readOpenClawConfigObject();
  const model = (config.agents as Record<string, unknown> | undefined)?.defaults as Record<string, unknown> | undefined;
  const m = model?.model;
  const primary = typeof m === "string" ? m : (m as Record<string, unknown> | undefined)?.primary;
  return typeof primary === "string" && primary ? { "x-openclaw-model": primary } : {};
}

type PatchRpc = (method: string, options: Record<string, unknown>, params: Record<string, unknown>, extra: Record<string, unknown>) => Promise<Record<string, unknown>>;
let gatewayRuntime: Promise<{ callGatewayFromCli: PatchRpc }> | undefined;
let lastPatched: { key: string; level: string; model?: string } | undefined;
async function applyThinking(key: string, level: string, model?: string) {
  if (lastPatched?.key === key && lastPatched.level === level && lastPatched.model === model) return;
  const moduleUrl = pathToFileURL(path.join(path.dirname(OPENCLAW_ENTRY), "dist/plugin-sdk/gateway-runtime.js")).href;
  gatewayRuntime ??= import(moduleUrl);
  const { callGatewayFromCli } = await gatewayRuntime;
  const result = await callGatewayFromCli("sessions.patch", { json: true, port: String(GATEWAY_PORT), timeout: "15000" },
    { key, thinkingLevel: level }, { progress: false });
  if (result.ok !== true) throw new Error("OpenClaw could not apply thinking level " + level);
  lastPatched = { key, level, model };
}

async function runOpenClawGatewayHttpTurn(
  message: string,
  thinkingLevel: string,
  activeSessionId: string
): Promise<{ ok: boolean; reply: string; elapsedMs: number; fallbackToCli?: boolean }> {
  const t0 = Date.now();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 360000);
  try {
    const key = 'agent:main:' + activeSessionId;
    const modelHeaders = await configuredModelHeaders();
    await applyThinking(key, thinkingLevel, modelHeaders["x-openclaw-model"]);
    const res = await fetch(`http://127.0.0.1:${GATEWAY_PORT}/v1/chat/completions`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...modelHeaders,
        "x-openclaw-session-key": `agent:main:${activeSessionId}`,
      },
      body: JSON.stringify({
        model: "openclaw/default",
        user: activeSessionId,
        messages: [{ role: "user", content: message }],
      }),
      signal: ctrl.signal,
    });
    const elapsedMs = Date.now() - t0;
    if (res.status === 404 || res.status === 405) {
      return { ok: false, reply: `Gateway HTTP endpoint unavailable (${res.status})`, elapsedMs, fallbackToCli: true };
    }
    const text = await res.text();
    let parsed: Record<string, unknown> = {};
    try {
      parsed = JSON.parse(text) as Record<string, unknown>;
    } catch {
      return { ok: false, reply: `Invalid Gateway HTTP response (${res.status}): ${text.slice(0, 300)}`, elapsedMs };
    }
    if (!res.ok) {
      if (res.status === 429) {
        return { ok: false, reply: "AIが混雑していて回答を完了できませんでした。少し待ってから続けてください。", elapsedMs };
      }
      const errMsg = String((parsed.error as Record<string, unknown> | undefined)?.message ?? text.slice(0, 300));
      return { ok: false, reply: `Gateway HTTP ${res.status}: ${errMsg}`, elapsedMs };
    }
    const choices = Array.isArray(parsed.choices) ? (parsed.choices as Array<Record<string, unknown>>) : [];
    const msg = choices[0]?.message as Record<string, unknown> | undefined;
    const reply = typeof msg?.content === "string" ? msg.content.trim() : "";
    return { ok: true, reply, elapsedMs };
  } catch (err) {
    return {
      ok: false,
      reply: err instanceof Error ? err.message : String(err),
      elapsedMs: Date.now() - t0,
      fallbackToCli: true,
    };
  } finally {
    clearTimeout(timer);
  }
}

async function runOpenClawTurn(message: string, thinkingLevel: string, useLocalFallback: boolean, activeSessionId: string): Promise<{ ok: boolean; reply: string; elapsedMs: number; raw?: string }> {
  const t0 = Date.now();
  const env = buildOpenClawEnv();
  const tmpDir = path.join(PROJECT_ROOT, ".tools", "tmp");
  fs.mkdirSync(tmpDir, { recursive: true });
  const msgFile = path.join(tmpDir, `prompt-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.txt`);
  fs.writeFileSync(msgFile, message, "utf8");

  const args = [OPENCLAW_ENTRY, "agent"];
  if (useLocalFallback) {
    args.push("--local");
  }
  args.push(
    "--session-id", activeSessionId,
    "--thinking", thinkingLevel,
    "--message-file", msgFile,
    "--json",
    "--timeout", "360"
  );

  return new Promise((resolve) => {
    const child = spawn(process.execPath, args, { env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout?.on("data", (d) => { stdout += d.toString("utf8"); });
    child.stderr?.on("data", (d) => { stderr += d.toString("utf8"); });
    child.on("close", (code) => {
      fs.rmSync(msgFile, { force: true });
      const elapsedMs = Date.now() - t0;
      if (code !== 0) {
        resolve({
          ok: false,
          elapsedMs,
          reply: `OpenClaw exited with code ${code}: ${stderr.slice(0, 400) || stdout.slice(0, 400)}`,
          raw: stdout + stderr,
        });
        return;
      }
      let reply = stdout.trim();
      try {
        const idx = stdout.indexOf("{");
        if (idx >= 0) {
          const parsed = JSON.parse(stdout.slice(idx)) as Record<string, unknown>;
          const payloads = (parsed.result as Record<string, unknown> | undefined)?.payloads as Array<Record<string, unknown>> | undefined;
          const texts = payloads?.map((p) => p.text).filter((t): t is string => typeof t === "string" && t.length > 0);
          if (texts && texts.length > 0) {
            reply = texts.join(" ").trim();
          } else if (typeof parsed.summary === "string") {
            reply = parsed.summary;
          }
        }
      } catch {
        // Keep trimmed raw stdout
      }
      resolve({ ok: true, reply, elapsedMs, raw: stdout });
    });
  });
}

function formatSurroundingSummary(ctxRaw: unknown): string {
  const ctx = ctxRaw as Record<string, unknown> | undefined;
  return ctx?.anchor
    ? `[World snapshot]\n${JSON.stringify(ctx)}`
    : "[World snapshot unavailable]";
}

function readJsonBody(req: http.IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8").trim();
      if (!raw) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw) as Record<string, unknown>);
      } catch (err) {
        reject(err);
      }
    });
    req.on("error", reject);
  });
}

function summarizeToolResult(toolName: string, body: Record<string, unknown>, result: Record<string, unknown>): string {
  if (result.ok === false) {
    if (Array.isArray(result.errors) && result.errors.length > 0) {
      const first = result.errors[0] as Record<string, unknown>;
      return String(first.error ?? result.message ?? "failed").slice(0, 140);
    }
    return String(result.error ?? result.message ?? "failed").slice(0, 140);
  }
  switch (toolName) {
    case "mc_build_batch": {
      const base = `queued=${result.queued ?? 0} blocks (total=${result.totalQueued ?? 0}, ticks=${result.targetTicks ?? "-"})`;
      const cmdResults = result.commandResults as Record<string, unknown> | undefined;
      const cmdsPart = cmdResults && Array.isArray(cmdResults.results) ? `, cmds=${cmdResults.results.length}` : "";
      const finPart = result.finished === true ? ", finished" : "";
      return `${base}${cmdsPart}${finPart}`;
    }
    case "mc_place_command_chain":
      return `placed=${result.placedCount ?? 0} (${result.mode ?? "chain"}, brigadier=${result.brigadierValidated ?? false})`;
    case "mc_exec_commands":
      return `ran=${Array.isArray(result.results) ? result.results.length : 0} commands`;
    case "mc_inspect_world":
      return `nonAir=${result.nonAirBlocks ?? 0}, cmdBlocks=${Array.isArray(result.commandBlocks) ? result.commandBlocks.length : 0}`;
    case "mc_fx":
      return `effect=${String(body.effect ?? result.effect ?? "fx")}`;
    case "mc_get_context":
      return `anchor=${JSON.stringify((result.anchor as Record<string, unknown> | undefined)?.origin ?? [])}`;
    default:
      return "ok";
  }
}

async function main() {
  const telemetry = new TelemetryHub();
  telemetry.setAgentMode(AGENT_MODE);
  telemetry.setConfiguredThinking(currentThinking);

  const mcClient = new McClient(WS_URL);
  mcClient.connect();

  let batchNeedsFinish = false;
  let activeRunFilmMode = false;
  let activePlayerName: string | undefined;
  let activeRunThinking: string | undefined;
  let activeSessionId = "minecraftharness-default";
  let outstandingPrompts = 0;

  interface StreamedOpRecord {
    coordMode: string;
    anchorId?: string;
    fingerprint: string;
    queued: number;
  }
  let streamSendQueue: Promise<void> = Promise.resolve();
  let streamedOpsLog: StreamedOpRecord[] = [];
  let streamRunToken = 0;

  function resetStreamState(): void {
    streamRunToken++;
    streamedOpsLog = [];
    streamSendQueue = Promise.resolve();
  }

  function handleStreamedBuildOp(ev: StreamedBuildOpEvent): void {
    if (!mcClient.isConnected() || !activePlayerName) return;
    const myRunToken = streamRunToken;
    const coordMode = typeof ev.meta.coordMode === "string" ? ev.meta.coordMode : "local";
    const anchorId = typeof ev.meta.anchorId === "string" && ev.meta.anchorId ? ev.meta.anchorId : undefined;
    const payload: Record<string, unknown> = {
      coordMode,
      ops: [ev.op],
      finish: false,
      streamed: true,
    };
    if (anchorId) payload.anchorId = anchorId;
    if (ev.meta.label) payload.label = ev.meta.label;
    if (ev.meta.sortMode) payload.sortMode = ev.meta.sortMode;

    streamSendQueue = streamSendQueue.then(async () => {
      if (myRunToken !== streamRunToken || !mcClient.isConnected()) return;
      try {
        payload.predictedNextMs = mcClient.recordBatchAndPredictNextMs();
        const res = await mcClient.request("build_batch", payload, 15000);
        if (myRunToken !== streamRunToken) return;
        if (res.ok !== false) {
          streamedOpsLog.push({
            coordMode,
            anchorId,
            fingerprint: ev.fingerprint,
            queued: Number(res.queued ?? 0),
          });
          batchNeedsFinish = true;
        }
      } catch (err) {
        console.warn("[Bridge] Speculative streamed op failed (will run on final tool call):", err instanceof Error ? err.message : String(err));
      }
    });
  }

  const proxyServer = startCerebrasTelemetryProxy(telemetry, {
    host: RPC_HOST,
    port: PROXY_PORT,
    getTargetReasoningEffort: () => activeRunThinking ?? currentThinking,
    getActiveSessionId: () => activeSessionId,
    onStreamedBuildOp: handleStreamedBuildOp,
    openclawConfigPath: OPENCLAW_CONFIG_PATH,
    openclawStateDir: OPENCLAW_STATE_DIR,
  });

  const directAgent = new DirectAgent({
    openclawConfigPath: OPENCLAW_CONFIG_PATH,
    openclawStateDir: OPENCLAW_STATE_DIR,
    workspaceDir: path.join(PROJECT_ROOT, "bridge", "openclaw-workspace"),
    dispatch: (name, args) => executeLocalTool(name, args),
  });

  telemetry.onHudUpdate((hud) => {
    if (mcClient.isConnected()) {
      mcClient.request("telemetry", { ...hud, playerName: activePlayerName }, 3000).catch(() => {});
    }
  });

  async function notifyPlayer(playerName: string | undefined, message: string) {
    if (!mcClient.isConnected()) return;
    const chars = Array.from(message.trim());
    for (let start = 0; start < chars.length; start += 2048) {
      await mcClient.request("notify_status", { playerName, message: chars.slice(start, start + 2048).join("") }).catch(() => {});
    }
  }
  const sentChatReplies = new Set<string>();
  let toolSeq = 0;

  function sanitizeChatReply(text: string): string {
    return text
      .replace(/<think>[\s\S]*?<\/think>/gi, "")
      .replace(/<\/?think>/gi, "")
      .trim();
  }

  async function sendReplyOnceToChat(
    playerName: string | undefined,
    rawText: string,
    filmMode: boolean,
    force = false
  ): Promise<void> {
    let clean = sanitizeChatReply(rawText);
    if (!clean || filmMode || !mcClient.isConnected()) return;
    if (!force) {
      if (sentChatReplies.has(clean)) return;
      if (sentChatReplies.size > 0) {
        const prevList = Array.from(sentChatReplies);
        if (prevList.join("\n\n") === clean || prevList.join("\n") === clean) return;
        let remainder = clean;
        for (const prev of prevList) {
          if (remainder === prev) {
            remainder = "";
            break;
          }
          if (remainder.startsWith(prev + "\n") || remainder.startsWith(prev + "\r\n")) {
            remainder = remainder.slice(prev.length).trim();
          }
        }
        if (!remainder || sentChatReplies.has(remainder)) return;
        clean = remainder;
      }
    }
    sentChatReplies.add(clean);
    await notifyPlayer(playerName, clean);
  }

  telemetry.onInferenceDone((trace) => {
    if (trace.status !== "DONE" || !activePlayerName || activeRunFilmMode) return;
    const clean = sanitizeChatReply(trace.contentText);
    if (!clean) return;
    void sendReplyOnceToChat(activePlayerName, clean, activeRunFilmMode);
  });

  async function runPrompt(opts: {
    playerName: string;
    prompt: string;
    filmMode: boolean;
    thinking: string;
    context?: unknown;
    promptAnchor?: unknown;
    receivedAt: number;
  }) {
    const { playerName, prompt, filmMode, thinking } = opts;
    const trimmedPrompt = prompt.trim();
    const response = conversations.command(playerName, trimmedPrompt);
    if (response !== undefined) {
      await notifyPlayer(playerName, response);
      return { ok: true, reply: response, elapsedMs: 0 };
    }
    const session = conversations.next(playerName, MAX_SESSION_TURNS);
    console.log(`[Bridge] Prompt from ${playerName} (mode=${AGENT_MODE}, session=${session.id} turn=${session.turns}, thinking=${thinking}): ${trimmedPrompt}`);
    activePlayerName = playerName;
    activeRunThinking = thinking;
    activeSessionId = session.id;
    resetStreamState();
    mcClient.resetPacing();
    telemetry.beginPromptRun(playerName, trimmedPrompt, thinking, opts.receivedAt);
    batchNeedsFinish = false;
    activeRunFilmMode = filmMode;
    sentChatReplies.clear();

    let ctx = opts.context;
    if (opts.promptAnchor) {
      ctx = await mcClient.request("begin_prompt", { playerName, anchor: opts.promptAnchor });
      if (!(ctx as Record<string, unknown>)?.anchor) throw new Error("Could not activate prompt anchor");
    } else if (!ctx && mcClient.isConnected()) {
      ctx = await mcClient.request("get_context", { playerName, lockAnchor: true }).catch(() => undefined);
    }

    const userMessage = [
      formatSurroundingSummary(ctx),
      ``,
      `Player "${playerName}" says: ${trimmedPrompt}`,
    ].join("\n");

    let res: { ok: boolean; reply: string; elapsedMs: number };
    if (AGENT_MODE === "direct") {
      telemetry.markAgentDispatch();
      res = await directAgent.runTurn({
        sessionId: session.id,
        userMessage,
        thinkingLevel: thinking,
        onStepReply: (stepReply) => sendReplyOnceToChat(playerName, stepReply, filmMode),
      });
    } else {
      const gatewayOk = await ensureGateway();
      telemetry.markAgentDispatch();
      if (gatewayOk) {
        const httpRes = await runOpenClawGatewayHttpTurn(userMessage, thinking, session.id);
        if (httpRes.fallbackToCli) {
          console.warn("[Bridge] Gateway HTTP unavailable, falling back to OpenClaw CLI:", httpRes.reply);
          res = await runOpenClawTurn(userMessage, thinking, false, session.id);
        } else {
          res = httpRes;
        }
      } else {
        res = await runOpenClawTurn(userMessage, thinking, true, session.id);
      }
    }

    await streamSendQueue.catch(() => {});
    resetStreamState();
    console.log(`[Bridge] ${AGENT_MODE === "direct" ? "Direct" : "OpenClaw"} turn finished in ${res.elapsedMs}ms (ok=${res.ok}): ${res.reply}`);
    if (batchNeedsFinish && mcClient.isConnected()) {
      await mcClient.request("finish_build", {}).catch(() => {});
    }
    if (res.reply) {
      await sendReplyOnceToChat(playerName, res.reply, filmMode, !res.ok);
    }
    return res;
  }

  function enqueuePrompt(opts: Parameters<typeof runPrompt>[0]) {
    const ahead = outstandingPrompts++;
    if (ahead && !opts.filmMode) {
      void notifyPlayer(opts.playerName, `「${Array.from(opts.prompt).slice(0, 30).join("")}」を受け付けました。先に処理する依頼は${ahead}件です。`);
    }
    const result = promptQueue.then(async () => {
      try {
        return await runPrompt(opts);
      } catch (err) {
        if (!opts.filmMode) await notifyPlayer(opts.playerName, "依頼を処理できませんでした。Bridgeの接続状況を確認してください。");
        throw err;
      } finally {
        activePlayerName = undefined;
        activeRunThinking = undefined;
        resetStreamState();
        outstandingPrompts--;
        telemetry.endPreparation();
      }
    });
    promptQueue = result.then(() => {}, () => {});
    return result;
  }

  function syncModelsToPlugin(): void {
    telemetry.broadcastModelsUpdated();
    if (!mcClient.isConnected()) return;
    try {
      const payload = { ...listConfiguredModels(), agentMode: AGENT_MODE };
      void mcClient.request("sync_models", payload as unknown as Record<string, unknown>, 5000).catch(() => {});
    } catch {}
  }

  mcClient.onEvent((event, data) => {
    if (event === "bridge_hello") {
      void mcClient.request("get_reasoning", {}).then(result => {
        if (typeof result.level === "string") {
          currentThinking = result.level;
          telemetry.setConfiguredThinking(currentThinking);
        }
      }).catch(() => {});
      syncModelsToPlugin();
    }
    if (event === "player_command") {
      if (data.command === "reasoning" && typeof data.value === "string") {
        currentThinking = data.value;
        telemetry.setConfiguredThinking(currentThinking);
        console.log(`[Bridge] Updated thinking level to: ${currentThinking}`);
      } else if (data.command === "model") {
        const playerName = typeof data.playerName === "string" ? data.playerName : undefined;
        const target = typeof data.value === "string" ? data.value.trim() : "";
        if (!target) {
          syncModelsToPlugin();
          return;
        }
        const res = selectConfiguredModel(target);
        if (res.ok && res.active) {
          console.log(`[Bridge] Switched active model to: ${res.active}`);
          syncModelsToPlugin();
          const msg = res.warning
            ? `モデルを ${res.active} に切り替えました。（注意: ${res.warning}）`
            : `モデルを ${res.active} (${res.selected?.name ?? res.active}) に切り替えました！`;
          void notifyPlayer(playerName, msg);
        } else {
          void notifyPlayer(playerName, `モデル切替失敗: ${res.error ?? "unknown error"}`);
        }
      }
      return;
    }

    if (event === "player_prompt") {
      const playerName = typeof data.playerName === "string" ? data.playerName : "Player";
      const prompt = typeof data.prompt === "string" ? data.prompt : "";
      const filmMode = Boolean(data.filmMode);
      const thinking = typeof data.reasoning === "string" ? data.reasoning : currentThinking;
      const context = data.context;
      const receivedAt = Date.now();

      void enqueuePrompt({ playerName, prompt, filmMode, thinking, context, receivedAt, promptAnchor: data.promptAnchor }).catch((err) => {
        console.error("[Bridge] Prompt pipeline error:", err);
      });
    }
  });

  const rpcServer = http.createServer(async (req, res) => {
    const url = req.url ?? "/";
    if (req.method === "GET" && url === "/") {
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      res.end(renderDashboardHtml());
      return;
    }

    if (req.method === "GET" && url === "/telemetry/state") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(telemetry.getSnapshot()));
      return;
    }

    if (req.method === "GET" && url === "/telemetry/events") {
      telemetry.addSseClient(res);
      return;
    }

    if (req.method === "POST" && url === "/telemetry/clear") {
      telemetry.clear();
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    if (req.method === "POST" && url === "/thinking") {
      try {
        const body = await readJsonBody(req);
        const lvl = typeof body.level === "string" ? body.level.toLowerCase().trim() : "";
        if (!["off", "minimal", "low", "medium", "high"].includes(lvl)) {
          res.writeHead(400, { "content-type": "application/json" });
          res.end(JSON.stringify({ ok: false, error: "Invalid level" }));
          return;
        }
        const applied = await mcClient.request("set_reasoning", { level: lvl });
        if (applied.level !== lvl) throw new Error("Paper did not apply the requested effort");
        currentThinking = lvl;
        telemetry.setConfiguredThinking(currentThinking);
        console.log(`[Bridge] Updated thinking level to: ${currentThinking}`);
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: true, thinking: currentThinking }));
      } catch (err) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (req.method === "GET" && url === "/models") {
      try {
        const list = listConfiguredModels();
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: true, ...list }));
      } catch (err) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (req.method === "POST" && url === "/model") {
      try {
        const body = await readJsonBody(req);
        const target = typeof body.target === "string" ? body.target.trim() : "";
        const result = selectConfiguredModel(target);
        if (!result.ok) {
          res.writeHead(400, { "content-type": "application/json" });
          res.end(JSON.stringify(result));
          return;
        }
        console.log(`[Bridge] Switched active model via HTTP to: ${result.active}`);
        syncModelsToPlugin();
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (req.method === "GET" && url === "/health") {
      let activeModel = "";
      try {
        activeModel = listConfiguredModels().active;
      } catch {}
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({
        ok: true,
        wsConnected: mcClient.isConnected(),
        agentMode: AGENT_MODE,
        activeModel,
        gatewayReady,
        gatewayWarm,
        thinking: currentThinking,
        dashboardUrl: `http://${RPC_HOST}:${RPC_PORT}/`,
        proxyUrl: `http://${RPC_HOST}:${PROXY_PORT}/v1`,
      }));
      return;
    }

    if (req.method === "POST" && url === "/admin/shutdown-mc") {
      try {
        if (!mcClient.isConnected()) {
          res.writeHead(200, { "content-type": "application/json" });
          res.end(JSON.stringify({ ok: false, error: "PaperMC WS not connected" }));
          return;
        }
        const r = await mcClient.request("shutdown_server", {}, 5000);
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(r));
      } catch (err) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (req.method === "POST" && url === "/prompt") {
      try {
        const body = await readJsonBody(req);
        const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
        if (!prompt) {
          res.writeHead(400, { "content-type": "application/json" });
          res.end(JSON.stringify({ ok: false, error: "prompt is required" }));
          return;
        }
        const playerName = typeof body.playerName === "string" ? body.playerName : "Player";
        const filmMode = Boolean(body.filmMode);
        const thinking = typeof body.thinking === "string" ? body.thinking : currentThinking;
        const turnResult = await enqueuePrompt({ playerName, prompt, filmMode, thinking, receivedAt: Date.now() });
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(turnResult));
      } catch (err) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (url === "/mcp" || url.startsWith("/mcp?")) {
      const mcpTransport = new StreamableHTTPServerTransport({
        sessionIdGenerator: undefined,
      });
      const mcpServer = createMinecraftMcpServer((name, args) => executeLocalTool(name, args));
      res.on("close", () => {
        mcpTransport.close().catch(() => {});
        mcpServer.close().catch(() => {});
      });
      try {
        await mcpServer.connect(mcpTransport);
        await mcpTransport.handleRequest(req, res);
      } catch (err) {
        if (!res.headersSent) {
          res.writeHead(500, { "content-type": "application/json" });
          res.end(JSON.stringify({ jsonrpc: "2.0", error: { code: -32603, message: err instanceof Error ? err.message : String(err) }, id: null }));
        }
      }
      return;
    }

    if (req.method === "POST" && url.startsWith("/tool/")) {
      const toolName = url.slice("/tool/".length);
      try {
        const body = await readJsonBody(req);
        const result = await executeLocalTool(toolName, body);
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(result));
      } catch (err) {
        const errMsg = err instanceof Error ? err.message : String(err);
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: errMsg }));
      }
      return;
    }

    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: false, error: "Not found" }));
  });

  async function executeLocalTool(toolName: string, body: Record<string, unknown>): Promise<Record<string, unknown>> {
    const t0 = Date.now();
    const evId = `tool-${++toolSeq}`;
    telemetry.markToolStart(toolName);
    try {
      if (toolName === "mc_take_photo") {
        const tmpDir = path.join(PROJECT_ROOT, ".tools", "tmp");
        let snapInfo: Record<string, unknown> = { moved: false };
        const angle = typeof body.angle === "string" ? body.angle : "current";
        if (angle !== "current") {
          snapInfo = await mcClient.request("camera_snap", body, 10000);
          if (snapInfo.moved) {
            const waitMs = Math.max(50, Math.min(2000, Number(body.waitMs ?? 350)));
            await new Promise((r) => setTimeout(r, waitMs));
          }
        }
        try {
          const img = await captureMinecraftScreenshot(mcClient, tmpDir);
          const summary = `${img.mode} ${img.width}x${img.height}${snapInfo.moved ? ` (${angle})` : ""}`;
          telemetry.attachToolEvent({
            id: evId,
            toolName,
            startedAt: t0,
            durationMs: Date.now() - t0,
            ok: true,
            summary,
            imageDataUrl: `data:${img.mimeType};base64,${img.base64}`,
          });
          return {
            ok: true,
            mode: img.mode,
            width: img.width,
            height: img.height,
            windowTitle: img.windowTitle,
            note: img.note,
            camera: snapInfo,
            _image: { data: img.base64, mimeType: img.mimeType },
          };
        } finally {
          if (snapInfo.moved) {
            await mcClient.request("camera_restore", body, 5000).catch(() => {});
          }
        }
      }

      const action = toolName.replace(/^mc_/, "");
      let requestBody = body;
      let preStreamedCount = 0;
      let preStreamedQueued = 0;
      let skipWsCall = false;

      if (toolName === "mc_build_batch") {
        await streamSendQueue.catch(() => {});
        const reqOps = Array.isArray(body.ops) ? body.ops : [];
        const reqCoordMode = typeof body.coordMode === "string" ? body.coordMode : "local";
        const reqAnchorId = typeof body.anchorId === "string" && body.anchorId ? body.anchorId : undefined;

        if (streamedOpsLog.length > 0 && reqOps.length > 0) {
          let matched = 0;
          while (matched < streamedOpsLog.length && matched < reqOps.length) {
            const rec = streamedOpsLog[matched];
            if (
              rec.coordMode === reqCoordMode &&
              rec.anchorId === reqAnchorId &&
              rec.fingerprint === opFingerprint(reqOps[matched])
            ) {
              preStreamedQueued += rec.queued;
              matched++;
            } else {
              break;
            }
          }
          if (matched > 0) {
            streamedOpsLog.splice(0, matched);
            preStreamedCount = matched;
            const remainingOps = reqOps.slice(matched);
            const hasCommands = Array.isArray(body.commands) && body.commands.length > 0;
            const needsFinish = body.finish !== false;
            if (remainingOps.length === 0 && !hasCommands && !needsFinish) {
              skipWsCall = true;
            } else {
              requestBody = { ...body, ops: remainingOps };
            }
          } else {
            // coordMode or anchorId or ops mismatched; discard stale speculative log and execute normally
            streamedOpsLog = [];
          }
        }
        if (!skipWsCall) {
          requestBody = { ...requestBody, predictedNextMs: mcClient.recordBatchAndPredictNextMs() };
        }
      }

      const result = skipWsCall
        ? {
            ok: true,
            queued: preStreamedQueued,
            totalQueued: preStreamedQueued,
            preStreamed: preStreamedCount,
            finished: false,
          }
        : await mcClient.request(action, requestBody, 20000);

      if (!skipWsCall && preStreamedCount > 0 && result.ok !== false) {
        result.queued = Number(result.queued ?? 0) + preStreamedQueued;
        result.preStreamed = preStreamedCount;
      }
      if (result.ok !== false) {
        if (toolName === "mc_build_batch") batchNeedsFinish = body.finish === false;
        if (toolName === "mc_fx" && body.effect === "finish") batchNeedsFinish = false;
        if (toolName === "mc_build_batch" && typeof body.reply === "string" && body.reply.trim()) {
          await sendReplyOnceToChat(activePlayerName, body.reply, activeRunFilmMode);
        }
      }
      const summary = summarizeToolResult(toolName, body, result);
      telemetry.attachToolEvent({
        id: evId,
        toolName,
        startedAt: t0,
        durationMs: Date.now() - t0,
        ok: result.ok !== false,
        summary,
        error: result.ok === false ? summary : undefined,
      });
      return result;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : String(err);
      telemetry.attachToolEvent({
        id: evId,
        toolName,
        startedAt: t0,
        durationMs: Date.now() - t0,
        ok: false,
        summary: errMsg,
        error: errMsg,
      });
      throw err;
    }
  }

  rpcServer.listen(RPC_PORT, RPC_HOST, () => {
    console.log(`[Bridge] Live Telemetry Dashboard: http://${RPC_HOST}:${RPC_PORT}/`);
    console.log(`[Bridge] In-Memory MCP HTTP Server: http://${RPC_HOST}:${RPC_PORT}/mcp`);
    console.log(`[Bridge] HTTP tool RPC listening on http://${RPC_HOST}:${RPC_PORT}/tool/*`);
  });

  if (AGENT_MODE === "direct") {
    console.log(`[Bridge] Direct Agent mode active (0ms startup, bypassing OpenClaw Gateway; set MH_AGENT_MODE=openclaw to use Gateway)`);
  } else if (process.env.MH_SKIP_WARMUP !== "1") {
    ensureGateway()
      .then(async (ok) => {
        if (!ok) return;
        await warmUpGatewayOnce();
        if (mcClient.isConnected()) {
          mcClient.request("notify_status", {
            message: "⚡ OpenClaw Gateway & MCP ウォームアップ完了！即応答待機中",
          }).catch(() => {});
        }
      })
      .catch(() => {});
  }

  const shutdown = () => {
    closeScreenshotWorker();
    telemetry.close();
    proxyServer.close();
    mcClient.close();
    rpcServer.close();
    if (gatewayProc && !gatewayProc.killed) {
      gatewayProc.kill();
    }
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

main().catch((err) => {
  console.error("[Bridge] Fatal:", err);
  process.exit(1);
});
