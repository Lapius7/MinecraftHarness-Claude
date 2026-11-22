# MinecraftHarness

**Real-time AI Minecraft 1.21.4 building, VFX/SFX, and playable command-block / datapack minigame harness powered by OpenClaw.**  
Type one line in chat (`!<prompt>`) and watch AI design original builds, interiors, and playable minigames right where you are looking—with **no client mods required** (vanilla Java 1.21.4 + optional Iris/shaders).

> **[日本語クイックスタート (Japanese Quick Start)](#日本語クイックスタート)** は下部にあります。

---

## Highlights
- **OpenClaw Gateway Mode (Default) + Optional Direct Mode**: Runs on **OpenClaw** (`:18789`) over HTTP + in-memory Streamable HTTP MCP by default, and also supports standalone Direct Mode (`MH_AGENT_MODE=direct` or `.\restart-all.ps1 -Direct`) for zero-Gateway environments—with identical in-game HUD, VFX, and telemetry. Works with Cerebras, Command Code, OpenAI, OpenRouter, Groq, or any OpenAI Chat Completions-compatible provider.
- **Streaming Speculative Execution**: Incrementally parses streamed tool-call JSON as tokens arrive and starts placing blocks in PaperMC **while the model is still generating**.
- **Player Gaze Anchor & Persistent `anchorId`**: Captures raycast `lookTarget`, nearest-cardinal `(right, up, forward)` local coordinate basis, terrain heightmap, and `recentStructures` (`s1`, `s2`, ...) so follow-up edits target the exact build even after you walk around.
- **Expressive Geometry & Space Tools (No Canned Templates)**:
  - Full geometric primitive suite (`box`, `hollow_box`, `pillar`, `cylinder`, `dome`, `arch`, `stairs_run`, `bridge`, `spiral`, `ring`, `scatter`, `pattern`, `blocks`, `clear`)
  - Universal **`mirrorX`** (auto-flips directional facing & double-door hinges) and **`repeat`** modifiers on any op
  - **3D Region `move` / `shift` / `copy`** (plus `/mh shift <du>` / `/mh shift <dr> <du> <df>`) with 2-phase snapshot-first shifting so overlapping moves (e.g. lowering a build by 3 blocks) never corrupt blocks and remain fully undoable
  - **Physical Auto-Fixers**: Auto-completes upper door halves & double-door hinges, bed heads, 2-block tall flora, stair headroom clearance (`autoHeadroom`), and common 1.21.4 block-state aliases
- **Playable Command Blocks & Datapacks**:
  - Automatic Impulse/Repeating/Chain command block wiring (`auto:1b`, `{{REL r u f}}` coordinate macros, glass Command Core, arena triggers) and persistent `mc_function_pack` datapacks
  - 1ms live Mojang **Brigadier** syntax validation on PaperMC 1.21.4
- **Cinematic VFX / SFX & Recording Tools**:
  - Hovering constructor drones firing particle lasers, FPS-capped `BlockDisplay` pop-in animations, pentatonic amethyst chimes, persistent `illumination` particle presets
  - `/mh film on` clean recording HUD & `/mh cam orbit|dolly` camera rigs
- **Live Telemetry & Vision Dashboard (`http://127.0.0.1:25581/`)**:
  - Real-time streaming reasoning viewer, TTFT / queue / prompt / completion timing, Server TPS & E2E TPS, Prompt Cache Hit %, and captured `mc_take_photo` screenshots

---

## Project Layout
- `plugin/` — PaperMC 1.21.4 Java plugin (`MinecraftHarness-0.1.0.jar`, local WebSocket `:25580`)
- `bridge/` — Node.js TypeScript Bridge daemon (`:25581`), Telemetry Proxy (`:25582`), Direct Agent, MCP server (`mc_*`), and prompt workspace
- `config/` — Clean configuration template (`openclaw.example.json`)
- `server/` — PaperMC 1.21.4 local server setup & launch scripts

---

## Quick Start (Windows — 3 Steps)

### Prerequisites
- **Java 21+** (for PaperMC 1.21.4 & Gradle build)
- **Node.js 20+** (for the Bridge)
- **Minecraft Java Edition 1.21.4** (vanilla or Fabric + Iris Shaders)
- An API key for Cerebras or any OpenAI Chat Completions-compatible model with tool calling

### 1. Configure your API key
Copy `.env.example` to `.env` in the project root and set your key:
```powershell
Copy-Item .env.example .env
# Edit .env and set CEREBRAS_API_KEY=... (or another provider's API key)
```

### 2. Build & launch PaperMC + Bridge in one command
```powershell
.\restart-all.ps1
```
This automatically:
- Bootstraps `.openclaw-home/.openclaw/openclaw.json` from `config/openclaw.example.json`
- Downloads PaperMC 1.21.4 (first run only; see EULA notice below)
- Builds the Java plugin (`plugin/`) and installs/builds the TypeScript Bridge (`bridge/`)
- Launches **PaperMC (:25565)**, **OpenClaw Gateway (:18789)**, and **Bridge + Telemetry Dashboard (`http://127.0.0.1:25581/`)** in parallel *(automatically falls back to standalone Direct Mode if OpenClaw is not installed locally)*

*(Options: `.\restart-all.ps1 -Build` to force rebuild, `.\restart-all.ps1 -StopOnly` to stop cleanly, `.\restart-all.ps1 -Direct` to run standalone without OpenClaw Gateway.)*

### 3. Join & build
1. Connect in Minecraft Java 1.21.4 to `localhost:25565`.
2. Make yourself OP in the PaperMC server console (`op <your_minecraft_name>`).
3. Type in chat:
   ```text
   !Build a cozy wooden cottage with a glowing garden and interior right in front of me
   ```

---

## macOS / Linux Manual Launch
1. Build the plugin and copy the JAR into your PaperMC 1.21.4 `plugins/` folder:
   ```bash
   cd plugin && ./gradlew build && cd ..
   ```
2. Copy `.env.example` to `.env` and fill in your API key.
3. Build and start the Bridge:
   ```bash
   cd bridge && npm install && npm run build && node dist/server.js
   ```
   *(On non-Windows platforms, `mc_take_photo` automatically falls back to the built-in top-down voxel map renderer.)*

---

## Switching / Adding LLM Providers

Use `set-provider.ps1` (or `node bridge/provider-config.mjs`)—Direct Mode reads the updated config on the next turn without restarting Bridge:

```powershell
.\set-provider.ps1 -List
.\set-provider.ps1 cerebras
.\set-provider.ps1 commandcode
```

To register any HTTPS OpenAI Chat Completions-compatible provider (OpenAI, OpenRouter, Groq, DeepSeek, etc.):
```powershell
.\set-provider.ps1 myprovider -Model "your-model-id" -BaseUrl "https://api.example.com/v1" -ApiKeyEnv "MY_PROVIDER_API_KEY"
```
Put `MY_PROVIDER_API_KEY=...` in `.env` and select it with `.\set-provider.ps1 myprovider`.

---

## In-Game Commands
- `!<prompt>` or `/mh <prompt>` — Build, edit, or create minigames at your gaze target
- `!new` — Reset conversation history for your player
- `/mh undo` / `/mh reset` — Restore terrain & remove spawned entities cleanly
- `/mh shift <du>` / `/mh shift <dr> <du> <df>` — Shift the most recent structure by an offset (e.g. `/mh shift -3` lowers it by 3 blocks)
- `/mh film on|off` — Clean recording mode (hides BossBar/ActionBar HUD, chat echoes, and command feedback)
- `/mh cam orbit [radius] [height] [speed]` | `/mh cam dolly` | `/mh cam off` — Smooth cinematic camera rig
- `/mh hud on|off` | `/mh hud reasoning on|off` — Toggle in-game AI Telemetry BossBar & live reasoning ActionBar
- `/mh fx on|off` | `/mh fx sound on|off` — Toggle VFX / SFX
- `/mh speed <turbo|fast|cinematic>` — Adjust block placement speed (`fast` default)
- `/mh reasoning <off|minimal|low|medium|high>` — Set model `reasoning_effort` (`low` default)
- `/mh status` / `/mh stop` — Inspect status or halt active build

---

## Security & Minecraft EULA Notice
- **Local / Trusted OP Use Only**: MinecraftHarness allows the AI to execute server commands (`mc_exec_commands`), wire command blocks (`mc_place_command_chain`), and deploy datapacks (`mc_function_pack`) with server console privileges.
  - All local sockets (`25580`, `25581`, `25582`, `18789`) bind strictly to `127.0.0.1`. **Never expose these ports to a public network or install this plugin on an untrusted public server.**
  - In-game triggers require the `minecraftharness.use` permission (defaults to `op`).
- **Minecraft EULA**: Running `server/setup-server.ps1` downloads PaperMC 1.21.4 and writes `eula=true` to `server/eula.txt`. By running it, you indicate your agreement to the [Minecraft EULA](https://aka.ms/MinecraftEULA).

---

## 日本語クイックスタート

**MinecraftHarness** は、マイクラ Java 版 1.21.4（MOD不要・シェーダー対応）のチャット欄に `!作りたいもの` と打つだけで、視線の先にAIがオリジナル建築・内装・コマンドブロック製ミニゲームをリアルタイム生成してくれる PaperMC プラグイン＋Node.js ブリッジです。

### 必要環境
- **Java 21 以上** / **Node.js 20 以上** / **Minecraft Java 1.21.4**
- Cerebras などの OpenAI 互換 API キー（OpenClaw モード標準／OpenClaw 未導入環境では `.\restart-all.ps1 -Direct` で自動スタンドアロン起動）

### 3ステップ起動（Windows）
1. ルートの `.env.example` を `.env` にコピーし、`CEREBRAS_API_KEY=...` 等の API キーを記入
2. PowerShell で以下を実行（初回は PaperMC ダウンロード・ビルドまで自動完了します）：
   ```powershell
   .\restart-all.ps1
   ```
3. マイクラ 1.21.4 で `localhost:25565` に接続し、PaperMC コンソールで `op <自分のMCID>` を実行したら、チャットで `!目の前に秘密基地作って` のように話しかけるだけ！

ブラウザで **`http://127.0.0.1:25581/`** を開くと、AIの思考テキスト・TTFT・TPS・キャッシュヒット率・スクショをリアルタイムで確認できます。

---

## License
Released under the [MIT License](LICENSE).
