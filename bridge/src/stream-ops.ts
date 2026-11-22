export interface StreamedBatchMeta {
  coordMode?: string;
  anchorId?: string;
  label?: string;
  sortMode?: string;
}

export interface StreamedOpEvent {
  meta: StreamedBatchMeta;
  opIndex: number;
  op: Record<string, unknown>;
  fingerprint: string;
}

/**
 * Deterministic canonical JSON stringification for op deduplication.
 * Sorts object keys recursively so semantically identical ops match regardless of key order or whitespace.
 */
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return "[" + value.map((item) => canonicalJson(item)).join(",") + "]";
  }
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  const parts: string[] = [];
  for (const k of keys) {
    const v = obj[k];
    if (v === undefined) continue;
    parts.push(JSON.stringify(k) + ":" + canonicalJson(v));
  }
  return "{" + parts.join(",") + "}";
}

export function opFingerprint(op: unknown): string {
  return canonicalJson(op);
}

type ParserState =
  | "SEEK_ROOT"
  | "TOP_EXPECT_KEY"
  | "TOP_EXPECT_COLON"
  | "TOP_EXPECT_VALUE"
  | "IN_OPS_ARRAY"
  | "DONE";

const META_KEYS = new Set(["coordMode", "anchorId", "label", "sortMode"]);

/**
 * Incrementally extracts top-level metadata and each completed element of `ops: [...]`
 * from a streamed `mc_build_batch` JSON arguments string.
 *
 * Safe against arbitrary chunk boundaries (mid-token, mid-string, mid-escape, nested objects/arrays).
 */
export class StreamingBatchExtractor {
  private buf = "";
  private pos = 0;
  private state: ParserState = "SEEK_ROOT";
  private currentKey = "";
  private opIndex = 0;
  private readonly meta: StreamedBatchMeta = {};

  getMeta(): StreamedBatchMeta {
    return { ...this.meta };
  }

  feed(chunk: string): StreamedOpEvent[] {
    if (!chunk || this.state === "DONE") return [];
    this.buf += chunk;
    const events: StreamedOpEvent[] = [];

    while (this.pos < this.buf.length && this.state !== "DONE") {
      this.skipWs();
      if (this.pos >= this.buf.length) break;

      if (this.state === "SEEK_ROOT") {
        if (this.buf[this.pos] === "{") {
          this.pos++;
          this.state = "TOP_EXPECT_KEY";
        } else {
          // Unexpected leading garbage; skip one char
          this.pos++;
        }
        continue;
      }

      if (this.state === "TOP_EXPECT_KEY") {
        const ch = this.buf[this.pos];
        if (ch === ",") {
          this.pos++;
          continue;
        }
        if (ch === "}") {
          this.pos++;
          this.state = "DONE";
          break;
        }
        if (ch !== '"') {
          // Malformed JSON; stop parsing safely
          this.state = "DONE";
          break;
        }
        const strEnd = this.findStringEnd(this.pos);
        if (strEnd < 0) break; // Need more chunks
        try {
          this.currentKey = JSON.parse(this.buf.slice(this.pos, strEnd + 1)) as string;
        } catch {
          this.state = "DONE";
          break;
        }
        this.pos = strEnd + 1;
        this.state = "TOP_EXPECT_COLON";
        continue;
      }

      if (this.state === "TOP_EXPECT_COLON") {
        if (this.buf[this.pos] === ":") {
          this.pos++;
          this.state = "TOP_EXPECT_VALUE";
        } else {
          this.state = "DONE";
        }
        continue;
      }

      if (this.state === "TOP_EXPECT_VALUE") {
        const ch = this.buf[this.pos];
        if (this.currentKey === "ops" && ch === "[") {
          this.pos++;
          this.state = "IN_OPS_ARRAY";
          continue;
        }

        const valEnd = this.findValueEnd(this.pos);
        if (valEnd < 0) break; // Need more chunks

        if (META_KEYS.has(this.currentKey)) {
          try {
            const parsed = JSON.parse(this.buf.slice(this.pos, valEnd));
            if (typeof parsed === "string") {
              (this.meta as Record<string, string>)[this.currentKey] = parsed;
            }
          } catch {
            // Ignore unparseable metadata value
          }
        }
        this.pos = valEnd;
        this.state = "TOP_EXPECT_KEY";
        continue;
      }

      if (this.state === "IN_OPS_ARRAY") {
        const ch = this.buf[this.pos];
        if (ch === ",") {
          this.pos++;
          continue;
        }
        if (ch === "]") {
          this.pos++;
          this.state = "TOP_EXPECT_KEY";
          continue;
        }
        const elemEnd = this.findValueEnd(this.pos);
        if (elemEnd < 0) break; // Need more chunks

        const rawElem = this.buf.slice(this.pos, elemEnd);
        this.pos = elemEnd;

        try {
          const parsed = JSON.parse(rawElem);
          if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
            const op = parsed as Record<string, unknown>;
            events.push({
              meta: { ...this.meta },
              opIndex: this.opIndex++,
              op,
              fingerprint: opFingerprint(op),
            });
          }
        } catch {
          // Skip malformed element
        }
        continue;
      }
    }

    // Compact consumed buffer prefix to keep memory bounded
    if (this.pos > 4096) {
      this.buf = this.buf.slice(this.pos);
      this.pos = 0;
    }

    return events;
  }

  private skipWs(): void {
    while (this.pos < this.buf.length) {
      const code = this.buf.charCodeAt(this.pos);
      if (code === 32 || code === 9 || code === 10 || code === 13) {
        this.pos++;
      } else {
        break;
      }
    }
  }

  /**
   * Returns index of closing quote `"` for a JSON string starting at `start`, or -1 if incomplete.
   */
  private findStringEnd(start: number): number {
    let i = start + 1;
    let escaped = false;
    while (i < this.buf.length) {
      const ch = this.buf[i];
      if (escaped) {
        escaped = false;
      } else if (ch === "\\") {
        escaped = true;
      } else if (ch === '"') {
        return i;
      }
      i++;
    }
    return -1;
  }

  /**
   * Returns exclusive end index of the JSON value starting at `start`, or -1 if incomplete.
   */
  private findValueEnd(start: number): number {
    if (start >= this.buf.length) return -1;
    const ch = this.buf[start];

    if (ch === '"') {
      const end = this.findStringEnd(start);
      return end < 0 ? -1 : end + 1;
    }

    if (ch === "{" || ch === "[") {
      let depth = 0;
      let inStr = false;
      let escaped = false;
      for (let i = start; i < this.buf.length; i++) {
        const c = this.buf[i];
        if (inStr) {
          if (escaped) {
            escaped = false;
          } else if (c === "\\") {
            escaped = true;
          } else if (c === '"') {
            inStr = false;
          }
          continue;
        }
        if (c === '"') {
          inStr = true;
        } else if (c === "{" || c === "[") {
          depth++;
        } else if (c === "}" || c === "]") {
          depth--;
          if (depth === 0) {
            return i + 1;
          }
        }
      }
      return -1;
    }

    // Primitive (number, true, false, null): ends at structural delimiter or whitespace
    for (let i = start; i < this.buf.length; i++) {
      const c = this.buf[i];
      if (c === "," || c === "}" || c === "]" || c === " " || c === "\t" || c === "\r" || c === "\n") {
        return i;
      }
    }
    return -1;
  }
}
