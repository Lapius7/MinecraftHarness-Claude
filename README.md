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
- `plugin/` — PaperMC 1.21.x / 26.x Java plugin (`MinecraftHarness-*.jar`, local WebSocket `:25580`)
- `bridge/` — Node.js TypeScript Bridge daemon (`:25581`), Telemetry Proxy (`:25582`), Direct Agent, MCP server (`mc_*`), and prompt workspace
- `config/` — Clean configuration template (`openclaw.example.json`)
- `server/` — PaperMC 1.21.4 local server setup & launch scripts

---

## Quick Start (Windows — 3 Steps)

### Prerequisites
- **Java 21+** (Java 25+ if running PaperMC 26.x)
- **Node.js 20+** (for the Bridge)
- **Minecraft Java Edition 1.21.x / 26.x** (tested on PaperMC 1.21.4; compatible with Paper/Purpur **1.21.0–1.21.x & 26.x**, vanilla client or Fabric + Iris Shaders)
- An API key for Cerebras, OpenCode Go, OpenRouter, OpenDesign Go, Command Code, or any OpenAI Chat Completions-compatible model with tool calling

> **Standalone Single-JAR Mode (No Node.js Required)**: You can download `MinecraftHarness-*.jar` directly from [GitHub Releases](https://github.com/ykundesu/MinecraftHarness/releases), drop it into your PaperMC / Purpur `plugins/` folder, start the server, and run `/mh key <cerebras|opencode-go|openrouter|opendesign|commandcode> <YOUR_API_KEY>` (or edit `plugins/MinecraftHarness/config.yml`). When no external Bridge is connected, the plugin's built-in Java AI agent handles prompts automatically! If you also run the Node.js Bridge (`.\restart-all.ps1`), the plugin automatically switches to OpenClaw Gateway + Live Telemetry Dashboard mode.

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
.\set-provider.ps1 opendesign      # DeepSeek V4.1 Flash (OpenDesign Go, OPENDESIGN_API_KEY)
.\set-provider.ps1 opencode-go     # OpenCode Go (OPENCODE_GO_API_KEY or OPENCODE_API_KEY; auto x-opencode-session header)
.\set-provider.ps1 openrouter      # OpenRouter (OPENROUTER_API_KEY)
.\set-provider.ps1 openrouter -Model "deepseek/deepseek-r1"   # Any OpenRouter model ID
.\set-provider.ps1 commandcode
```

You can also switch models directly **in-game** with `/mh model` (clickable chat menu + Tab completion) or from the live Telemetry Dashboard (`http://127.0.0.1:25581/`). Any model on an existing provider can also be selected dynamically on the fly—for example:
- `/mh model opencode-go/kimi-k2.5`
- `/mh model openrouter/anthropic/claude-sonnet-4.5`
- `/mh model openrouter/<any-model-id>`

To register any HTTPS OpenAI Chat Completions-compatible provider (OpenAI, OpenRouter, Groq, DeepSeek, etc.):
```powershell
.\set-provider.ps1 myprovider -Model "your-model-id" -BaseUrl "https://api.example.com/v1" -ApiKeyEnv "MY_PROVIDER_API_KEY"
```
Put `MY_PROVIDER_API_KEY=...` in `.env` and select it with `.\set-provider.ps1 myprovider`.

---

## Basic Usage (In-Game)

1. **Build where you are looking**: Look at an open spot and type `!<prompt>` in chat:
   ```text
   !Build a cozy wooden cottage with a glowing garden and interior
   !Create a playable 3-stage neon parkour minigame with jump pads and a finish fireworks trigger
   ```
2. **Iterate naturally**: Follow up in the same conversation—MinecraftHarness remembers your recent builds (`s1`, `s2`, ...) so you can walk around and still edit them:
   ```text
   !Add a second-floor balcony and warm lanterns around the entrance
   !Lower that house by 2 blocks
   ```
3. **Quick adjustments & undo**:
   - `/mh shift -3` — Move the last build down 3 blocks without rebuilding
   - `/mh undo` — Restore the terrain & remove entities cleanly
   - `!new` — Start a fresh conversation session
4. **Switch AI models live**:
   - Type `/mh model` in chat for a clickable menu, or specify any model directly (e.g. `/mh model cerebras`, `/mh model opendesign`, `/mh model opencode-go/kimi-k2.5`, `/mh model openrouter/deepseek/deepseek-v3.2`).
5. **Watch live reasoning & metrics**:
   - Open `http://127.0.0.1:25581/` in your browser to see live reasoning tokens, TTFT, TPS, prompt cache hit rate, captured screenshots, and a model/thinking selector.

---

## In-Game Commands
- `!<prompt>` or `/mh <prompt>` — Build, edit, or create minigames at your gaze target
- `/mh model [name]` — Show clickable model selector in chat or switch active provider/model live (e.g. `/mh model cerebras`, `/mh model opendesign`, `/mh model opencode-go`, `/mh model openrouter/deepseek/deepseek-v3.2`)
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

**MinecraftHarness** は、マイクラ Java 版 **1.21.x / 26.x**（MOD不要・シェーダー対応）のチャット欄に `!作りたいもの` と打つだけで、視線の先にAIがオリジナル建築・内装・コマンドブロック製ミニゲームをリアルタイム生成してくれる PaperMC プラグイン＋Node.js ブリッジです。

### 必要環境
- **Java 21 以上**（Paper 26.x で動かす場合は **Java 25 以上**） / **Node.js 20 以上** / **Minecraft Java 1.21.x または 26.x**
- Cerebras・OpenCode Go・OpenRouter・OpenDesign Go などの OpenAI 互換 API キー（OpenClaw モード標準／OpenClaw 未導入環境では `.\restart-all.ps1 -Direct` で自動スタンドアロン起動）

### JAR 単体で動かす場合（Node.js 不要・Mac / Linux / レンタルサーバー対応）
1. [GitHub Releases](https://github.com/ykundesu/MinecraftHarness/releases) から `MinecraftHarness-*.jar` をダウンロードし、PaperMC / Purpur の `plugins/` フォルダへ配置して起動
2. サーバーコンソールまたはゲーム内チャット（OP権限）で API キーを設定（または `plugins/MinecraftHarness/config.yml` に直接記入）：
   ```text
   /mh key cerebras <APIキー>
   /mh model
   ```
3. そのままチャットで `!目の前に秘密基地作って` と話しかけるだけで動きます！（Node.js Bridge / OpenClaw が接続された場合は自動で Bridge モードへ切り替わります）

### 3ステップ起動（Windows・OpenClaw / ダッシュボード込みフル構成）
1. ルートの `.env.example` を `.env` にコピーし、`CEREBRAS_API_KEY=...` 等の API キーを記入
2. PowerShell で以下を実行（初回は PaperMC ダウンロード・ビルドまで自動完了します）：
   ```powershell
   .\restart-all.ps1
   ```
3. マイクラで `localhost:25565` に接続し、PaperMC コンソールで `op <自分のMCID>` を実行したら準備完了！

### 簡単な使い方（ゲーム内）
- **視線の先に建築・ミニゲーム生成**: 作りたい場所を見て、チャットに `!` 付きで話しかけます
  ```text
  !目の前に温泉付きの和風旅館を作って
  !ジャンプ台とゴール演出つきの遊べるアスレチックを作って
  ```
- **続けて修正・アレンジ**: 前の建築を覚えているので、移動した後でもそのまま追加指示できます
  ```text
  !さっきの建物の屋根をもう少し高くして、入口に提灯をつけて
  !全体を2ブロック下げて
  ```
- **よく使うコマンド**:
  - `/mh model` — チャット上のクリックメニューで AI モデルを即切替（`/mh model openrouter/<任意のモデルID>` や `/mh model opencode-go/<モデルID>` も可）
  - `/mh undo` — 直前の建築・エンティティをきれいに元に戻す
  - `/mh shift -3` — 直前の建築を丸ごと下へ 3 ブロック移動（`/mh shift <右> <上> <前>`）
  - `!new` — 会話履歴をリセットして新しい話題に切り替える
  - `/mh reasoning <off|low|medium|high>` — AI の思考量（`reasoning_effort`）を変更
  - `/mh film on` / `/mh cam orbit` — 録画向けの HUD 非表示・自動周回カメラ
- **ライブダッシュボード**: ブラウザで **`http://127.0.0.1:25581/`** を開くと、AIの思考テキスト・TTFT・TPS・キャッシュヒット率・スクショ・モデル切替をリアルタイムで確認できます。

---

## License
Released under the [MIT License](LICENSE).
