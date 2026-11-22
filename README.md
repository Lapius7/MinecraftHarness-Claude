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
| `!<プロンプト>` / `/mh <プロンプト>` | use | 視線の先に建てる。送信すると送った人に進捗が出る（下の「進捗表示」）。入力した文の再表示はしない。実行中にもう一度送ると、前の依頼が終わってから実行する(下の「複数の指示」) |
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
| `!now <プロンプト>` / `/mh now <プロンプト>` | use | 自分の実行中の依頼を止めて、この指示を先に実行する |
| `/mh queue` / `/mh queue clear` | use | 実行中と待ちの一覧 / 自分の待ちを消す |
| `/mh stop` / `/mh stop all` | use | 実行中の依頼、建築キュー、演出を止める(待ちは残る) / 待ちも全部消す |
| `/mh review [off\|light\|normal\|strict\|max]` | use | 建築後の自己点検の強さ。引数なしでクリックできるメニュー。依頼の末尾に `--review strict` を付けるとその 1 回だけ変わる |
| `/mh detail [off\|short\|full]` | use | 結果の詳細表示。引数なしで現在値 |
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
queue:
  maxPerPlayer: 5       # 実行中と待ちの合計の上限
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

複数の指示の扱いは次の節のとおりです。

### 退出・再参加と他のプレイヤーの表示

- 依頼した人が退出しても、既定では実行中の依頼と待ちは続き、結果は再参加時にまとめて出ます(最大 20 件)。再参加するとボスバーが付き直ります。`progress.onQuit: stop` なら退出時に実行中の依頼と自分の待ちを止めます。
- 建築中は、ほかのプレイヤーにも目立たない色で「<名前> さんが建築中: 状態」が出ます。`progress.observers` は `all`(既定) / `owner` / `off`。待ちだけの状態は他人には出ません。Film モード中のプレイヤーには出ません。
- ワールド移動、死亡、リスポーン、退出ではボスバーをいったん外して次の更新で付け直します。

### レビュー(自己点検)

建築が終わると、Claude 自身が既存のツール(`mc_voxel_snapshot`、`mc_take_photo`、ブロック確認)で出来を点検し、問題を直してもう一度見直します。点検項目は、依頼との一致、形と比率、窓と扉の穴、浮いたブロック、内装と庭、地形とのなじみ、コマンドブロックやミニゲームの動作です。

| 強さ | 回数 | 時間 | トークン |
|------|------|------|----------|
| off | 0 | - | - |
| light | 1 | 300 秒 | 15 万 |
| normal | 3 | 600 秒 | 40 万 |
| strict | 5(厳しい項目つき) | 1200 秒 | 90 万 |
| max | 上限なし | 3600 秒 | 300 万 |

- トークンの上限は、入力、出力、キャッシュ書き込みの合計で数えます(キャッシュ読み込みは呼び出しのたびに繰り返されるので数えません)。
- `/mh review` と `/mh detail` の設定はメモリ上にだけあり、サーバーを再起動すると `config.yml` の既定に戻ります。
- 数値は `config.yml` の `review.levels.<強さ>` で変えられます(`rounds: 0` は回数の上限なし)。既定の強さは `review.default`(`light`)。
- 問題がなくなる、回数・時間・トークンの上限、`/mh stop` のどれかで止まり、止めた理由と点検結果をチャットに出します。
- 各ラウンドの前後で建築数と浮きブロック数を比べ、悪化していたらそのラウンドの分だけ undo で戻します。
- 進捗には「レビュー 2/3: 点検中・修正中」と出ます。待ちと `!now` は、レビューも 1 つの依頼の一部として扱うので順番は変わりません。

### 結果の詳細

依頼の終わりにチャットへ 1 行の要約が出ます。重ねるとすべての値が見えます。`/mh detail full` なら最初から全部、`off` なら出しません(`summary.detail` が既定)。

- 内訳: 時間(待ち、思考、ツール、建築、レビュー)、トークン(入力、出力、キャッシュ読み書き、思考)、モデルと推論の強さ、認証方式、ツール呼び出し数、置いたブロック、建築 ID、レビューの回数と直した内容。
- 「置いたブロック」は建築ツールが受け付けた数(キューに入れた数)で、実際に置かれた数ではありません。
- 値は CLI の出力か API の応答にあるものだけを使い、取れない値は「-」にして推測しません。API の思考トークンは内訳が取れないため空です。
- 金額は API 利用のときだけ、`pricing.<モデル>` の単価(USD / 100 万トークン)から概算を出します。単価が未設定なら出しません。サブスクリプション(claude CLI)では金額を出しません。

### 実機で未確認の点

単体テストとビルドは通していますが、次は実サーバーで試していません。ワールド移動・死亡・リスポーン時のボスバー、再参加の付け直しと他プレイヤー向けの表示、実際のレビューの流れ(建築ツールがレビュー中も使えること)、実際の要約表示、CLI の費用欄、持ち主が不在のときの写真ツール。

### 複数の指示

Claude は 1 つずつ処理するので、実行中に送った指示は順番待ちになります。待ちの扱いは次のとおりです。

- 待ちに入ると「前の依頼が終わってから実行します(待ち: N 番目)」と出ます。進捗の表示にも待ち数が付きます。
- 1 人が実行中と待ちを合わせて持てるのは `queue.maxPerPlayer` 件(既定 5)までです。超えると断ります。
- 自分の直前の指示と同じ文は無視して、注意を出します。
- 待っている指示は順番に実行するので、前の依頼で建てた `s1` などを踏まえた編集になります。それぞれの指示は、送った時点の視線の先を基準にします。
- `!now <プロンプト>`(`/mh now`)は、自分の実行中の依頼を止めて建築キューと演出を片づけ、この指示を先頭で実行します。ほかの人の依頼は止めません。
- `/mh stop` は実行中だけを止めて待ちを残し、`/mh stop all` は待ちも消します。`/mh queue` で順番を見て、`/mh queue clear` で自分の待ちを消せます。

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
