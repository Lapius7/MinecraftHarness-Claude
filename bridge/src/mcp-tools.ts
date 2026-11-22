import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { zodToJsonSchema } from "zod-to-json-schema";

export type ToolDispatcher = (name: string, args: Record<string, unknown>) => Promise<Record<string, unknown>>;

export interface OpenAiToolDef {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
}

export function formatMcpToolResponse(parsed: Record<string, unknown>) {
  const rawImage = parsed._image as { data?: string; mimeType?: string } | undefined;
  if (rawImage && typeof rawImage.data === "string" && typeof rawImage.mimeType === "string") {
    const { _image: _removed, ...meta } = parsed;
    return {
      content: [
        { type: "text" as const, text: JSON.stringify(meta) },
        { type: "image" as const, data: rawImage.data, mimeType: rawImage.mimeType },
      ],
      isError: parsed.ok === false,
    };
  }
  return {
    content: [{ type: "text" as const, text: JSON.stringify(parsed) }],
    isError: parsed.ok === false,
  };
}

const vec3Schema = z.array(z.number().int()).length(3);
const anchorIdSchema = z
  .string()
  .optional()
  .describe("Optional saved structure anchor id (e.g. 's1' or 'last') to target an existing build in its local coords.");

const TOOL_SPECS = {
  mc_get_context: {
    description:
      "Capture the player's current gaze anchor (lookTarget, camera basis right/up/forward, terrain heightmap, surrounding blocks, recentStructures). Do NOT call on turn 1—world snapshot is already provided.",
    shape: {
      playerName: z.string().optional().describe("Optional player name; defaults to active online player."),
      lockAnchor: z.boolean().optional().describe("If true, locks a fresh build anchor at the player's current gaze."),
    },
  },
  mc_build_batch: {
    description:
      "Queue ordered block placements using geometric primitives and custom block/pattern ops. Always put metadata (anchorId, coordMode, label, sortMode) BEFORE ops.",
    shape: {
      anchorId: anchorIdSchema,
      coordMode: z.enum(["local", "world"]).optional().describe("Default 'local': [right, up, forward] relative to locked gaze anchor or anchorId."),
      label: z.string().optional().describe("Short label for this build (e.g. 'Cozy Timber Cottage')."),
      sortMode: z.enum(["bottom_up", "center_out", "spiral", "front_to_back", "none"]).optional().describe("Placement order for visual reveal (default 'bottom_up')."),
      ops: z
        .array(z.record(z.unknown()))
        .describe(
          "Build ops (integer [r,u,f] coords; any op supports mirrorX:true (+optional mirrorCenter) and repeat:{axis:'r'|'u'|'f',count,step} or {offset:[dr,du,df],count}). " +
            "Ops: " +
            "box {type:'box',from,to,material|palette}, " +
            "hollow_box {type:'hollow_box',from,to,thickness,floorMaterial,wallMaterial,roofMaterial}, " +
            "pillar {type:'pillar',at,height,radius,material,capMaterial}, " +
            "cylinder {type:'cylinder',center,radius,height,hollow,axis,material|palette}, " +
            "dome {type:'dome',center,radius,height?,hollow,half:'upper'|'lower'|'full',material}, " +
            "arch {type:'arch',from,to,height,thickness,material}, " +
            "stairs_run {type:'stairs_run',from,to,width,material,railMaterial,autoHeadroom:true}, " +
            "bridge {type:'bridge',from,to,width,sag,material,railMaterial}, " +
            "spiral {type:'spiral',center,radius,height,turns,width,material}, " +
            "ring {type:'ring',center,radius,innerRadius,thickness,axis,material}, " +
            "scatter {type:'scatter',from,to,density,onlyAir,palette}, " +
            "pattern {type:'pattern',plane:'floor'|'front'|'side',at,rows,key} (spaces and '.' skip), " +
            "blocks {type:'blocks',items:[{pos,material}]} or {type:'blocks',positions,material}, " +
            "clear {type:'clear',from,to}, " +
            "move/shift/copy {type:'move'|'shift'|'copy',from,to,by:[dr,du,df],dy,structureId:'s1'|'last',copy:false,includeAir:false,clearFill:'air'}. " +
            "Plugin auto-fixes doors (upper half + double hinges), beds (head half), tall flora, stair headroom, and water_cauldron."
        ),
      commands: z
        .array(z.string())
        .optional()
        .describe("Optional 1.21.4 commands to validate & run immediately with this batch ({{REL r u f}} supported)."),
      finish: z
        .boolean()
        .optional()
        .describe("Default true: complete build with finish shockwave & VFX. Set false for intermediate batches."),
      reply: z
        .string()
        .optional()
        .describe("Concise 1-2 sentence reply in the player's language to show in chat immediately upon queueing."),
    },
  },
  mc_function_pack: {
    description:
      "Deploy, replace, or remove a persistent Minecraft datapack. Functions named tick and load run automatically at the deployment anchor. Other functions can be called as mh_<id>:<path>.",
    shape: {
      anchorId: anchorIdSchema,
      id: z.string().regex(/^[a-z0-9_]{1,48}$/).describe("Stable id for this mechanic."),
      action: z.enum(["deploy", "remove"]).optional().describe("Defaults to deploy."),
      functions: z
        .record(z.array(z.string()))
        .optional()
        .describe("Required for deploy. Map function paths to ordered mcfunction lines ({{REL r u f}} expanded)."),
    },
  },
  mc_place_command_chain: {
    description:
      "Place and wire an Impulse/Repeating/Chain 1.21.4 command block chain with Brigadier validation, {{REL r u f}} macros, Always Active auto:1b, optional Command Core, and optional trigger.",
    shape: {
      anchorId: anchorIdSchema,
      label: z.string().optional().describe("Label displayed above the Command Core."),
      mode: z.enum(["repeating", "impulse"]).optional().describe("First block type (default 'repeating')."),
      auto: z.boolean().optional().describe("Always Active for block 0 (default true for repeating, false if triggered)."),
      placement: z.enum(["core", "at", "hidden"]).optional().describe("'core' glass Command Core pod; 'at' uses local/world coords; 'hidden' omits casing."),
      at: vec3Schema.optional().describe("Optional [right, up, forward] coordinate when placement='at'."),
      commands: z
        .array(z.union([z.string(), z.object({ cmd: z.string(), conditional: z.boolean().optional() })]))
        .describe("Ordered 1.21.4 commands (no leading slash; supports {{REL r u f}})."),
      trigger: z
        .object({
          type: z.enum(["button", "pressure_plate", "lever", "none"]),
          at: vec3Schema.optional(),
          label: z.string().optional(),
        })
        .optional()
        .describe("Optional interactive trigger placed inside the arena."),
    },
  },
  mc_exec_commands: {
    description:
      "Execute one-off Minecraft 1.21.4 server commands immediately (scoreboard, gamerules, summon, give, time, title) with {{REL r u f}} macro expansion and Brigadier validation.",
    shape: {
      anchorId: anchorIdSchema,
      commands: z.array(z.string()).describe("Minecraft 1.21.4 commands to validate and run immediately as console."),
      observe: z
        .object({
          at: vec3Schema.describe("Local [right, up, forward] to sample immediately after these commands."),
          afterMs: z.number().int().min(1).max(5000).describe("Sample the same block again after this many milliseconds."),
        })
        .optional()
        .describe("For testing timed mechanics, returns the two observed block states and actual sample times in one call."),
    },
  },
  mc_inspect_world: {
    description:
      "Scan blocks, command blocks, and nearby entities in a local [right, up, forward] bounding box to inspect or modify an existing build.",
    shape: {
      anchorId: anchorIdSchema,
      view: z
        .enum(["front", "side", "top"])
        .optional()
        .describe("Exact material projection (up to 96x96). Dot is air, ? is overflow. Front/side rows descend; top rows advance forward."),
      sampleCoords: z.array(vec3Schema).optional().describe("Specific local blocks to read with their full block states."),
      from: vec3Schema.optional().describe("Start [r, u, f] (default [-8, -2, 0])."),
      to: vec3Schema.optional().describe("End [r, u, f] (default [8, 12, 16])."),
    },
  },
  mc_fx: {
    description:
      "Trigger finish shockwave, ambient illumination particle aura, or cinematic effects (finish, shockwave, firework, title, time_night, time_sunset, time_day, lightning, illumination, clear_illumination).",
    shape: {
      anchorId: anchorIdSchema,
      effect: z.enum([
        "finish",
        "shockwave",
        "firework",
        "title",
        "time_night",
        "time_sunset",
        "time_day",
        "lightning",
        "illumination",
        "clear_illumination",
      ]),
      preset: z.enum(["sparkle", "firefly", "magic", "aurora"]).optional().describe("Ambient particle preset when effect='illumination'."),
      from: vec3Schema.optional().describe("Local [r, u, f] min corner for illumination region."),
      to: vec3Schema.optional().describe("Local [r, u, f] max corner for illumination region."),
      label: z.string().optional(),
      at: vec3Schema.optional().describe("Local [r, u, f] target."),
      count: z.number().int().optional(),
      title: z.string().optional(),
      subtitle: z.string().optional(),
    },
  },
  mc_take_photo: {
    description:
      "Capture a live screenshot of the Minecraft client window (or top-down voxel map fallback if headless).",
    shape: {
      anchorId: anchorIdSchema,
      angle: z
        .enum(["current", "front", "orbit_left", "orbit_right", "top_overview", "custom"])
        .optional()
        .describe("Camera viewpoint; 'current' shoots from player's view, others temporarily move camera and restore immediately after."),
      cameraAt: vec3Schema.optional().describe("Local [r, u, f] camera position when angle='custom'."),
      lookAt: vec3Schema.optional().describe("Local [r, u, f] target point to aim camera at (default [0, 2, 8])."),
      distance: z.number().int().optional().describe("Distance from build for orbit/front angles (default 16)."),
      height: z.number().int().optional().describe("Height above anchor origin (default 6)."),
      waitMs: z.number().int().optional().describe("Render settle delay in ms after moving camera (default 350)."),
    },
  },
} as const;

function stripSchemaMeta(schema: Record<string, unknown>): Record<string, unknown> {
  const { $schema: _s, ...rest } = schema;
  return rest;
}

export const OPENAI_TOOLS: OpenAiToolDef[] = (Object.keys(TOOL_SPECS) as Array<keyof typeof TOOL_SPECS>).map((name) => {
  const spec = TOOL_SPECS[name];
  const jsonSchema = stripSchemaMeta(zodToJsonSchema(z.object(spec.shape), { target: "openApi3" }) as Record<string, unknown>);
  return {
    type: "function",
    function: {
      name,
      description: spec.description,
      parameters: jsonSchema,
    },
  };
});

/**
 * Shared execution wrapper that handles `mc_exec_commands` with optional `observe`
 * identically for both MCP mode and Direct Agent mode.
 */
export async function dispatchToolWithObserve(
  dispatch: ToolDispatcher,
  name: string,
  args: Record<string, unknown>
): Promise<Record<string, unknown>> {
  if (name !== "mc_exec_commands" || !args.observe || typeof args.observe !== "object") {
    return dispatch(name, args);
  }
  const observe = args.observe as { at?: number[]; afterMs?: number };
  if (!Array.isArray(observe.at) || observe.at.length !== 3 || typeof observe.afterMs !== "number") {
    return dispatch(name, args);
  }
  const execArgs: Record<string, unknown> = { commands: args.commands };
  if (typeof args.anchorId === "string") execArgs.anchorId = args.anchorId;
  const result = await dispatch("mc_exec_commands", execArgs);
  if (result.ok === false) return result;

  const started = Date.now();
  const observations: Array<{ elapsedMs: number; state: unknown }> = [];
  for (const offset of [0, observe.afterMs]) {
    const remaining = started + offset - Date.now();
    if (remaining > 0) await new Promise((resolve) => setTimeout(resolve, remaining));
    const inspectArgs: Record<string, unknown> = {
      from: observe.at,
      to: observe.at,
      sampleCoords: [observe.at],
    };
    if (typeof args.anchorId === "string") inspectArgs.anchorId = args.anchorId;
    const inspection = await dispatch("mc_inspect_world", inspectArgs);
    if (inspection.ok === false) {
      return { ...result, ok: false, observeError: inspection };
    }
    const sampleBlocks = inspection.sampleBlocks as Record<string, unknown> | undefined;
    observations.push({
      elapsedMs: Date.now() - started,
      state: sampleBlocks?.[observe.at.join(",")],
    });
  }
  return { ...result, observe: { at: observe.at, observations } };
}

export function createMinecraftMcpServer(dispatch: ToolDispatcher): McpServer {
  const server = new McpServer({
    name: "minecraft-harness",
    version: "0.2.0",
  });

  const runTool = async (name: string, args: Record<string, unknown>) => {
    try {
      const parsed = await dispatchToolWithObserve(dispatch, name, args ?? {});
      return formatMcpToolResponse(parsed);
    } catch (err) {
      return {
        content: [
          {
            type: "text" as const,
            text: JSON.stringify({
              ok: false,
              error: err instanceof Error ? err.message : String(err),
            }),
          },
        ],
        isError: true,
      };
    }
  };

  for (const name of Object.keys(TOOL_SPECS) as Array<keyof typeof TOOL_SPECS>) {
    const spec = TOOL_SPECS[name];
    server.tool(name, spec.description, spec.shape, async (args: Record<string, unknown>) => runTool(name, args));
  }

  return server;
}
