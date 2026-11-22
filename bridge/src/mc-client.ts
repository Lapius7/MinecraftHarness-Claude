import WebSocket from "ws";

export interface WsEvent {
  type: "event";
  event: string;
  data: Record<string, unknown>;
}

interface PendingReq {
  resolve: (value: Record<string, unknown>) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
}

/**
 * Persistent WebSocket client to the PaperMC MinecraftHarness plugin
 * plus EWMA inter-batch latency estimator for seamless Adaptive Pacing.
 */
export class McClient {
  private readonly url: string;
  private ws: WebSocket | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private nextId = 1;
  private readonly pending = new Map<string, PendingReq>();
  private eventHandler: ((event: string, data: Record<string, unknown>) => void) | null = null;

  private lastBatchAt: number | null = null;
  private ewmaInterBatchMs = 1100;
  private closed = false;

  constructor(url = "ws://127.0.0.1:25580") {
    this.url = url;
  }

  onEvent(handler: (event: string, data: Record<string, unknown>) => void): void {
    this.eventHandler = handler;
  }

  isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  connect(): void {
    if (this.closed) return;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    const ws = new WebSocket(this.url);
    this.ws = ws;

    ws.on("open", () => {
      console.log(`[McClient] Connected to PaperMC plugin at ${this.url}`);
    });

    ws.on("message", (buf) => {
      let msg: Record<string, unknown>;
      try {
        msg = JSON.parse(buf.toString("utf8")) as Record<string, unknown>;
      } catch {
        return;
      }

      if (msg.type === "response" && typeof msg.id === "string") {
        const p = this.pending.get(msg.id);
        if (p) {
          clearTimeout(p.timer);
          this.pending.delete(msg.id);
          p.resolve((msg.result as Record<string, unknown>) ?? {});
        }
        return;
      }

      if (msg.type === "event" && typeof msg.event === "string") {
        this.eventHandler?.(msg.event, (msg.data as Record<string, unknown>) ?? {});
      }
    });

    ws.on("close", () => {
      this.ws = null;
      this.rejectAllPending(new Error("WebSocket disconnected"));
      this.scheduleReconnect();
    });

    ws.on("error", () => {
      // close event handles reconnect
    });
  }

  close(): void {
    this.closed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.rejectAllPending(new Error("McClient closed"));
    this.ws?.close();
    this.ws = null;
  }

  resetPacing(): void {
    this.lastBatchAt = null;
    this.ewmaInterBatchMs = 1100;
  }

  recordBatchAndPredictNextMs(): number {
    const now = Date.now();
    if (this.lastBatchAt !== null) {
      const delta = Math.max(300, Math.min(3500, now - this.lastBatchAt));
      this.ewmaInterBatchMs = Math.round(this.ewmaInterBatchMs * 0.55 + delta * 0.45);
    }
    this.lastBatchAt = now;
    return this.ewmaInterBatchMs;
  }

  async request(action: string, params: Record<string, unknown> = {}, timeoutMs = 15000): Promise<Record<string, unknown>> {
    if (!this.isConnected()) {
      throw new Error(`PaperMC plugin WebSocket not connected (${this.url})`);
    }
    const id = `req-${this.nextId++}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`WS request '${action}' timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      this.pending.set(id, { resolve, reject, timer });
      this.ws!.send(JSON.stringify({ id, action, params }));
    });
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, 1500);
  }

  private rejectAllPending(err: Error): void {
    for (const [id, p] of this.pending.entries()) {
      clearTimeout(p.timer);
      p.reject(err);
      this.pending.delete(id);
    }
  }
}
