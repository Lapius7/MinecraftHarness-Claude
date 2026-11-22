import fs from "node:fs";
import { randomUUID } from "node:crypto";

type Conversation = { id: string; turns: number };
type Player = { active: string; sessions: Record<string, Conversation> };

export class Conversations {
  private players: Record<string, Player>;
  constructor(private readonly filename: string) {
    this.players = fs.existsSync(filename) ? JSON.parse(fs.readFileSync(filename, "utf8")) : {};
  }

  private save() {
    const tmp = this.filename + "." + randomUUID() + ".tmp";
    try {
      fs.writeFileSync(tmp, JSON.stringify(this.players), { mode: 0o600 });
      fs.renameSync(tmp, this.filename);
    } finally {
      fs.rmSync(tmp, { force: true });
    }
  }

  private fresh(): Conversation {
    return { id: "minecraft-harness-" + randomUUID(), turns: 0 };
  }

  private player(name: string): Player {
    const key = "player:" + name.toLowerCase();
    if (!this.players[key]) {
      const session = this.fresh();
      if (process.env.MH_OPENCLAW_SESSION) session.id = process.env.MH_OPENCLAW_SESSION + "-" + encodeURIComponent(name.toLowerCase());
      this.players[key] = { active: "main", sessions: { main: session } };
    }
    return this.players[key];
  }

  command(name: string, prompt: string): string | undefined {
    const parts = prompt.trim().split(/\s+/);
    const verb = parts[0].toLowerCase();
    const reset = ["new", "reset-chat", "reset_chat"].includes(verb) && parts.length === 1
      || verb === "history" && parts.length === 2 && parts[1].toLowerCase() === "reset";
    if (!reset && verb !== "session") return;
    const player = this.player(name);
    if (reset) {
      player.sessions[player.active] = this.fresh();
      this.save();
      return "「" + player.active + "」の会話を新しくしました。";
    }
    if (parts.length === 1 || parts.length === 2 && parts[1].toLowerCase() === "list") {
      return "現在: " + player.active + " ｜ セッション: " + Object.keys(player.sessions).join("、");
    }
    if (parts.length !== 3 || parts[1].toLowerCase() !== "use") return "使い方: /mh session list または /mh session use <名前>";
    const target = parts[2].normalize("NFC");
    if (!/^[\p{L}\p{N}_-]{1,32}$/u.test(target) || target in Object.prototype) return "名前は文字・数字・ハイフン・下線で1〜32文字にしてください。";
    if (!Object.hasOwn(player.sessions, target)) player.sessions[target] = this.fresh();
    player.active = target;
    this.save();
    return "現在のセッション: " + target;
  }

  next(name: string, limit: number): Conversation {
    const player = this.player(name);
    let session = player.sessions[player.active];
    if (session.turns >= limit) session = player.sessions[player.active] = this.fresh();
    session.turns++;
    this.save();
    return session;
  }
}
