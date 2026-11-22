# MinecraftHarness-Claude

[![CI](https://github.com/Lapius7/MinecraftHarness-Claude/actions/workflows/ci.yml/badge.svg)](https://github.com/Lapius7/MinecraftHarness-Claude/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Paper](https://img.shields.io/badge/Paper-1.21.x%20%7C%2026.x-3b82f6)
![Java](https://img.shields.io/badge/Java-21%2B-orange)

チャットに一行書くだけで、Claude が見ている場所に建築物やコマンドブロックのミニゲームを作る PaperMC プラグインです。クライアント側の MOD は要りません。JAR を `plugins/` に 1 つ置けば動きます。

[ykundesu/MinecraftHarness](https://github.com/ykundesu/MinecraftHarness) をもとに、Claude 専用として作り直したフォークです。他の AI プロバイダーと外部ゲートウェイは取り除きました。

```text
!屋根に煙突のある、庭つきの木の家を建てて
!3ステージのネオンパルクールを作って。ジャンプ台とゴールの花火つき
!さっきの家を2ブロック下げて
```

## 特長

- 認証は 2 通りだけです。
  - Claude のサブスクリプション: 公式の `claude` CLI を子プロセスとして呼びます。OAuth トークンには触りません。
  - Anthropic API キー: Messages API を直接呼びます（ストリーミング、tool_use、プロンプトキャッシュ）。
- JAR 1 つで完結します。 Node のブリッジも別プロセスも要りません。`claude` CLI を使う場合だけ、サーバー機に CLI が必要です。
- 視線の先に建てます。 見ている場所を基準に建築し、`s1`、`s2` のように最近の建築物を覚えているので、後から直せます。
- 応答の生成中から、届いた分のブロックを置き始めます（API モード）。
- `/mh undo` で最後の建築を戻せます。
- 録画向けに、Film モード、カメラ（orbit / dolly）、VFX / SFX があります。

## 必要なもの

| 項目 | 内容 |
| --- | --- |
| サーバー | Paper または Paper 系（1.21.x、26.x） |
| Java | 21 以上（26.x は Paper の要件で 25 以上） |
| 認証 | `claude` CLI にログイン済みのサブスクリプション、または Anthropic API キー |

## 導入

1. [Releases](https://github.com/Lapius7/MinecraftHarness-Claude/releases) の `MinecraftHarness-<version>.jar` を、サーバーの `plugins/` に置きます。
2. サーバーを起動します。`plugins/MinecraftHarness/config.yml` ができます。
3. 認証を選びます。

### A. Claude サブスクリプション（`claude` CLI）

サーバー機に [Claude Code](https://docs.claude.com/en/docs/claude-code) をインストールし、サーバーを動かすユーザーで一度ログインします。

```bash
claude        # 初回のみ。ブラウザでログインする
```

ゲーム内では何もせず `cli/opus` のまま使えます。プラグインは次のように CLI を呼びます。

- 1 プレイヤーにつき 1 セッションを使い回し、2 回目以降は `--resume` で続けます。
- ツールは `--tools ""` で CLI 標準のものをすべて無効にし、プラグインがローカル（`127.0.0.1`）で立てる MCP サーバーのツールだけを許可します。
- 子プロセスの環境変数から `ANTHROPIC_API_KEY` と `ANTHROPIC_AUTH_TOKEN` を外すので、サブスクリプションの枠で動きます。

`claude` が `PATH` に無い場合は、`config.yml` の `claude.binary` か環境変数 `MH_CLAUDE_BIN` でパスを指定します。

### B. Anthropic API キー

**コンソールから**設定してください。

```text
mh key anthropic sk-ant-...
mh model api/claude-opus-5-5
```

> 注意: ゲーム内で `/mh key anthropic <キー>` を打つと、コマンドとしてサーバーの `latest.log` に残ります。コンソールから実行するか、環境変数 `ANTHROPIC_API_KEY`、または `plugins/MinecraftHarness/.env` を使ってください。

キーの探索順は `config.yml`、環境変数 `ANTHROPIC_API_KEY`、`.env` です。

## コマンド

`/mh` の権限は 2 段階です。`minecraftharness.use` は建築とその操作、`minecraftharness.admin` はモデルやキーなどサーバー全体に効く操作です。どちらも初期設定では OP だけが持ちます。

| コマンド | 権限 | 説明 |
| --- | --- | --- |
| `!<プロンプト>` / `/mh <プロンプト>` | use | 視線の先に建てる。送信すると送った人に進捗が出る（下の「進捗表示」）。入力した文の再表示はしない。実行中にもう一度送ると、前の依頼が終わってから実行する |
| `!new` / `/mh claude clear` | use | 自分の会話を破棄して、新しいセッションで始める |
| `/mh model` | use | クリックで切り替えるモデル一覧。使用中のモデルも分かる |
| `/mh model <参照>` | admin | モデルを切り替える（例: `cli/sonnet`、`api/claude-opus-5-5`） |
| `/mh key anthropic <キー>` | admin | API キーを保存する（`clear` で削除） |
| `/mh effort` | use | 現在の effort を表示し、クリックで選べる一覧を開く |
| `/mh effort <low\|medium\|high\|xhigh\|max>` | admin | 推論の強さを直接指定する。`config.yml` に保存され、サブスクと API の両方に効く（旧名 `/mh reasoning` も使える） |
| `/mh undo` | use | 最後の建築を取り消す |
| `/mh reset` | admin | このセッションの建築をすべて戻す |
| `/mh shift <上下>` / `/mh shift <右> <上> <前>` | use | 直近の建築物をずらす |
| `/mh film on\|off` | use | 録画モード（HUD とコマンド出力を隠す） |
| `/mh cam orbit\|dolly\|off` | use | カメラワーク |
| `/mh progress <bossbar\|actionbar\|off>` | admin | 進捗の表示先。`config.yml` に保存される。引数なしで現在値を表示 |
| `/mh hud on\|off` / `/mh fx on\|off` / `/mh speed <turbo\|fast\|cinematic>` | use | 全員向けの詳細 HUD（既定は off）、演出、設置速度 |
| `/mh` / `/mh help [ページ\|カテゴリ]` | use | ヘルプ。コマンドの行をクリックすると入力欄に入り、ホバーで使用例と権限が出る。権限のないコマンドは灰色になる |
| `/mh stop` / `/mh status` | use | 実行中の処理を止める / 状態を見る |

Tab 補完には持っている権限で使えるものだけ出ます。

### モデル

| 参照 | 実体 |
| --- | --- |
| `cli/opus`、`cli/sonnet`、`cli/haiku` | `claude` CLI（サブスクリプション） |
| `api/claude-opus-5-5`、`api/claude-sonnet-5-5`、`api/claude-haiku-5-5`、`api/claude-fable-5-2` | Messages API（API キー） |
| `cli/<任意のID>`、`api/<任意のID>` | 上の一覧に無いモデルを直接指定 |

effort の値は Claude CLI には `--effort`、API には `output_config.effort` として渡します。Anthropic の公式ドキュメントでは、組み込みの 4 つの API モデルが 5 段階すべてに対応しています。組み込み外のモデル ID で `xhigh` か `max` を選ぶと、対応を確認できないため警告が出ます。

## 設定（`config.yml`）

```yaml
activeModel: cli/opus
effort: low             # low | medium | high | xhigh | max（旧キー reasoning も読む）
maxSteps: 12            # API モードの最大ツール往復数
anthropic:
  apiKey: ""
  baseUrl: https://api.anthropic.com
  maxTokens: 32000
claude:
  binary: ""            # 空なら "claude"（Windows は "claude.exe"）
  idleTimeoutSeconds: 300
  debugLog: false       # true で logs/cli-stream.log に CLI の生の出力を残す
progress:
  mode: bossbar         # bossbar | actionbar | off
agent:
  tone: friendly        # friendly | concise
mcp:
  port: 0               # 0 なら空きポートを自動で選ぶ（127.0.0.1 のみ）
```

## ビルド

JDK 21 が必要です。

```bash
cd plugin
./gradlew build
```

できた JAR は `plugin/build/libs/MinecraftHarness-<version>.jar` です。これをサーバーの `plugins/` に置きます。

26.x の API に対するコンパイル確認は、JDK 25 で次のように行います。

```bash
./gradlew build -PpaperApi=26.3.build.258-stable
```

### 進捗表示

`!<プロンプト>` か `/mh <プロンプト>` を送った人だけに、応答が終わるまで 1 秒ごとに更新される表示が出ます。`progress.mode` か `/mh progress` で、画面上部のボスバー、アクションバー、なしから選べます。Film モード中は出ません。

| 表示 | 意味 |
| --- | --- |
| 受付: 待機中 | 送信直後。CLI や API の最初の反応を待っている |
| 思考中 / 応答中 約Nトークン | 考えている / 文章を書いている |
| ツール実行中 / 建築中: ツール名 N件目 | `mc_*` ツールの呼び出し。建築系は「建築中」 |
| 応答待ち(N秒) | 30 秒以上なにも届いていない |
| 完了 / 失敗: 理由 / 中断: 理由 | 数秒で消える。`/mh stop`、完了、エラー、退出でも必ず消える |

実行中にもう一度送ると、前の依頼のあとに実行します。そのときは「前の依頼が終わってから実行します(N 番目)」と出ます。

### 会話の口調と継続

`agent.tone` で返事の口調を選べます（起動時に読みます）。`friendly` は建築後に作ったものを短く伝え、次の提案を 2、3 個出して、直したい点を尋ねます。`concise` は 1、2 文で済ませます。

直前の建築は、スナップショットの `Recent structures`（`s1`、`s2` など）としてモデルに渡されます。「もっと庭を付けて」のような依頼は、その `anchorId` を使う編集として扱います。会話を捨てるのは、`!new` か `/mh claude clear` を使ったときだけです。

### 遅いときの調べ方

`claude.debugLog: true` にすると、CLI の起動、終了コード、標準出力の生の行、標準エラーを `plugins/MinecraftHarness/logs/cli-stream.log` に追記します。ファイルが 20 MB を超えると作り直します。

## 設計メモ

- Node ブリッジは Java に移しました。 ストリーミング、ツールの往復、プロンプトキャッシュ、履歴の整合性（`tool_use` と `tool_result` の対応）はすべてプラグイン内にあります。
- API の呼び出しは `java.net.http` で直接行います。 公式 SDK を同梱すると JAR が大きくなり、1 JAR で配る前提と合わないためです。再試行（408、409、429、5xx、529）と、思考パラメータを拒否された場合の再送は自前で持っています。
- `claude` CLI だけは別プロセスです。 サブスクリプションの認証は CLI の中にあり、取り出さない方針なので、移植せずに子プロセスとして呼びます。CLI にはプラグインの MCP サーバーのトークン付き URL を渡し、そのツールだけを許可します。

## 確認済みの範囲

- 単体テスト（SSE、履歴、リクエスト生成、MCP、CLI の引数）と、Paper 1.21.4 API / 26.3 API に対するコンパイルは CI で通ります。
- `claude` CLI 2.1.330 で、MCP サーバーへの接続、ツール呼び出しの往復、`--session-id` と `--resume` による会話の引き継ぎを、ダミーのツールで確認しました。
- 実サーバー上のゲーム内動作（26.x での Film モードのゲームルール操作を含む）と、API キーでの実呼び出しは、まだ確認していません。

## ライセンス

MIT。元の著作権表示（MinecraftHarness Contributors）は [LICENSE](LICENSE) に残しています。元プロジェクト: [ykundesu/MinecraftHarness](https://github.com/ykundesu/MinecraftHarness)
