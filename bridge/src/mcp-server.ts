import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createMinecraftMcpServer } from "./mcp-tools.js";

const RPC_BASE = process.env.MINECRAFTHARNESS_RPC_URL ?? "http://127.0.0.1:25581";

const server = createMinecraftMcpServer(async (name, args) => {
  const res = await fetch(`${RPC_BASE}/tool/${name}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(args ?? {}),
  });
  const text = await res.text();
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return { ok: false, error: `Invalid JSON response (${res.status}): ${text}` };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
