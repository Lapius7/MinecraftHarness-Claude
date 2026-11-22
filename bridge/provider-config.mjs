import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createHash, randomUUID } from "node:crypto";

const root = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(root, "..");
const configPath = path.join(projectRoot, ".openclaw-home/.openclaw/openclaw.json");
const examplePath = path.join(projectRoot, "config/openclaw.example.json");
const routesPath = path.join(root, "provider-routes.json");
const read = filename => JSON.parse(fs.readFileSync(filename, "utf8").replace(/^\uFEFF/, ""));

function ensureConfigExists() {
  if (fs.existsSync(configPath) || !fs.existsSync(examplePath)) return;
  fs.mkdirSync(path.dirname(configPath), { recursive: true });
  const ws = path.join(projectRoot, "bridge", "openclaw-workspace").replace(/\\/g, "\\\\");
  const raw = fs.readFileSync(examplePath, "utf8").replace("__WORKSPACE_DIR__", ws);
  fs.writeFileSync(configPath, raw, "utf8");
}

function save(filename, value) {
  const tmp = filename + "." + randomUUID() + ".tmp";
  try {
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2) + "\n", { flag: "wx", mode: 0o600 });
    fs.renameSync(tmp, filename);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

function main([id, model, base, keyEnv]) {
  ensureConfigExists();
  const config = read(configPath);
  const providers = config.models.providers;
  const selected = config.agents.defaults.model;
  if (id === "--list") {
    console.log("Active: " + selected.primary);
    for (const [name, provider] of Object.entries(providers)) {
      console.log(name + "  " + provider.models.map(entry => entry.id).join(", "));
    }
    return;
  }
  if (!/^[a-z][a-z0-9-]*$/.test(id) || id in Object.prototype) throw Error("Invalid provider id");
  if (id === "claude-cli" && !providers[id]) {
    // Built-in provider: drives the installed official `claude` CLI, so no API base or key is needed.
    const entry = (name) => ({ id: name, name: "Claude " + name + " (claude CLI)", api: "claude-cli", reasoning: true, input: ["text", "image"] });
    providers[id] = { baseUrl: "claude-cli://local", apiKey: "", api: "claude-cli", timeoutSeconds: 1800, models: ["sonnet", "opus", "haiku"].map(entry) };
  }
  const existing = providers[id];
  if (base || keyEnv || !existing) {
    if (existing) throw Error("Provider already registered. Select by name, or register a different id.");
    if (!model || !base || !keyEnv || !/^[A-Z_][A-Z0-9_]*$/.test(keyEnv)) throw Error("Supply -Model, -BaseUrl and -ApiKeyEnv to register a provider.");
    const url = new URL(base);
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) throw Error("Use an HTTPS API base without credentials, query or fragment.");
    const route = createHash("sha256").update(url.href).digest("hex").slice(0, 24);
    const routes = fs.existsSync(routesPath) ? read(routesPath) : {};
    routes[route] = url.href.replace(/\/+$/, "");
    save(routesPath, routes);
    const port = Number(process.env.MH_PROXY_PORT || 25582);
    providers[id] = { baseUrl: "http://127.0.0.1:" + port + "/providers/" + route + "/v1",
      apiKey: "${" + keyEnv + "}", api: "openai-completions", models: [{ id: model, name: model, input: ["text"] }] };
  }
  const provider = providers[id];
  const chosen = model || provider.models[0]?.id;
  if (!chosen) throw Error("No model configured for " + id);
  if (!provider.models.some(entry => entry.id === chosen)) {
    provider.models.push(id === "claude-cli" ? { id: chosen, name: chosen + " (claude CLI)", api: "claude-cli", reasoning: true, input: ["text", "image"] } : {
      id: chosen,
      name: `${chosen} (${id})`,
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
  }
  selected.primary = id + "/" + chosen;
  save(configPath, config);
  console.log("Active: " + selected.primary);
}
try { main(process.argv.slice(2)); } catch (err) { console.error(err.message); process.exitCode = 1; }
