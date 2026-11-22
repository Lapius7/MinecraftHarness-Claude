import { spawn, ChildProcess } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { McClient } from "./mc-client.js";

export interface PhotoResult {
  ok: boolean;
  mode: "window" | "voxel_fallback";
  base64: string;
  mimeType: "image/jpeg" | "image/png";
  width: number;
  height: number;
  windowTitle?: string;
  note?: string;
}

const WORKER_PS1 = `
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class McWinCap {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc lpEnumFunc, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT lpPoint);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
}
"@

$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" } | Select-Object -First 1
$encParams = New-Object System.Drawing.Imaging.EncoderParameters(1)
$encParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]80)

while (($line = [Console]::In.ReadLine()) -ne $null) {
  $trimmed = $line.Trim()
  if ([string]::IsNullOrEmpty($trimmed)) { continue }
  if ($trimmed -eq "EXIT") { break }
  try {
    $parts = $trimmed.Split("|")
    $outPath = $parts[0]
    $targetW = if ($parts.Length -ge 2) { [int]$parts[1] } else { 768 }
    $targetH = if ($parts.Length -ge 3) { [int]$parts[2] } else { 432 }

    $script:found = $null
    [McWinCap+EnumProc]$cb = {
      param($hWnd, $lParam)
      if (-not [McWinCap]::IsWindowVisible($hWnd)) { return $true }
      if ([McWinCap]::IsIconic($hWnd)) { return $true }
      $sb = New-Object System.Text.StringBuilder 512
      [void][McWinCap]::GetWindowText($hWnd, $sb, 512)
      $title = $sb.ToString()
      if ([string]::IsNullOrWhiteSpace($title)) { return $true }
      if ($title -notmatch "Minecraft") { return $true }
      if ($title -match "MinecraftHarness|Visual Studio|Antigravity|Chrome|Edge|Firefox|PowerShell|Terminal|cmd\\.exe|Notepad") { return $true }
      $procId = [uint32]0
      [void][McWinCap]::GetWindowThreadProcessId($hWnd, [ref]$procId)
      $procName = ""
      try { $procName = (Get-Process -Id $procId -ErrorAction SilentlyContinue).ProcessName } catch {}
      if ($procName -match "Code|explorer|chrome|msedge|powershell|pwsh|WindowsTerminal|node") { return $true }
      $r = New-Object McWinCap+RECT
      if (-not [McWinCap]::GetClientRect($hWnd, [ref]$r)) { return $true }
      $cw = $r.Right - $r.Left
      $ch = $r.Bottom - $r.Top
      if ($cw -lt 160 -or $ch -lt 120) { return $true }
      $pt = New-Object McWinCap+POINT
      $pt.X = 0; $pt.Y = 0
      [void][McWinCap]::ClientToScreen($hWnd, [ref]$pt)
      $script:found = @{ hWnd = $hWnd; title = $title; x = $pt.X; y = $pt.Y; w = $cw; h = $ch }
      return $false
    }
    [void][McWinCap]::EnumWindows($cb, [IntPtr]::Zero)
    if ($null -eq $script:found) {
      [Console]::Out.WriteLine('{"ok":false,"reason":"no_window"}')
      [Console]::Out.Flush()
      continue
    }

    $rawBmp = New-Object System.Drawing.Bitmap($script:found.w, $script:found.h)
    $gRaw = [System.Drawing.Graphics]::FromImage($rawBmp)
    $gRaw.CopyFromScreen($script:found.x, $script:found.y, 0, 0, (New-Object System.Drawing.Size($script:found.w, $script:found.h)))
    $gRaw.Dispose()

    $outBmp = New-Object System.Drawing.Bitmap($targetW, $targetH)
    $gOut = [System.Drawing.Graphics]::FromImage($outBmp)
    $gOut.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gOut.DrawImage($rawBmp, 0, 0, $targetW, $targetH)
    $gOut.Dispose()
    $rawBmp.Dispose()

    $outBmp.Save($outPath, $codec, $encParams)
    $outBmp.Dispose()

    $resObj = @{ ok = $true; width = $targetW; height = $targetH; windowTitle = $script:found.title }
    [Console]::Out.WriteLine(($resObj | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
  } catch {
    $errObj = @{ ok = $false; reason = $_.Exception.Message }
    [Console]::Out.WriteLine(($errObj | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
  }
}
`;

class PersistentScreenshotWorker {
  private proc: ChildProcess | null = null;
  private stdoutBuf = "";
  private pendingResolve: ((line: string) => void) | null = null;
  private queue: Promise<unknown> = Promise.resolve();

  private ensureStarted(tmpDir: string): ChildProcess {
    if (this.proc && !this.proc.killed && this.proc.exitCode === null) {
      return this.proc;
    }
    fs.mkdirSync(tmpDir, { recursive: true });
    const scriptPath = path.join(tmpDir, "capture-mc-worker.ps1");
    fs.writeFileSync(scriptPath, WORKER_PS1, "utf8");

    const child = spawn(
      "powershell.exe",
      ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptPath],
      { stdio: ["pipe", "pipe", "pipe"] }
    );

    this.proc = child;
    this.stdoutBuf = "";

    child.stdout?.on("data", (d: Buffer) => {
      this.stdoutBuf += d.toString("utf8");
      let nl: number;
      while ((nl = this.stdoutBuf.indexOf("\n")) >= 0) {
        const line = this.stdoutBuf.slice(0, nl).trim();
        this.stdoutBuf = this.stdoutBuf.slice(nl + 1);
        if (line.startsWith("{") && this.pendingResolve) {
          const resolve = this.pendingResolve;
          this.pendingResolve = null;
          resolve(line);
        }
      }
    });

    child.on("exit", () => {
      if (this.proc === child) {
        this.proc = null;
      }
      if (this.pendingResolve) {
        const resolve = this.pendingResolve;
        this.pendingResolve = null;
        resolve('{"ok":false,"reason":"worker_exited"}');
      }
    });

    return child;
  }

  capture(tmpDir: string, outPath: string, width: number, height: number, timeoutMs = 6000): Promise<string> {
    const task = this.queue.then(() => this.runOne(tmpDir, outPath, width, height, timeoutMs));
    this.queue = task.then(() => {}, () => {});
    return task;
  }

  private runOne(tmpDir: string, outPath: string, width: number, height: number, timeoutMs: number): Promise<string> {
    return new Promise((resolve) => {
      let child: ChildProcess;
      try {
        child = this.ensureStarted(tmpDir);
      } catch {
        resolve('{"ok":false,"reason":"spawn_failed"}');
        return;
      }

      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        this.pendingResolve = null;
        this.close();
        resolve('{"ok":false,"reason":"timeout"}');
      }, timeoutMs);

      this.pendingResolve = (line: string) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(line);
      };

      try {
        child.stdin?.write(`${outPath}|${width}|${height}\n`);
      } catch {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          this.pendingResolve = null;
          this.close();
          resolve('{"ok":false,"reason":"stdin_write_failed"}');
        }
      }
    });
  }

  close(): void {
    if (this.proc && !this.proc.killed) {
      try {
        this.proc.stdin?.write("EXIT\n");
      } catch {
        // ignore
      }
      try {
        this.proc.kill();
      } catch {
        // ignore
      }
    }
    this.proc = null;
    if (this.pendingResolve) {
      const resolve = this.pendingResolve;
      this.pendingResolve = null;
      resolve('{"ok":false,"reason":"closed"}');
    }
  }
}

const screenshotWorker = new PersistentScreenshotWorker();

export function closeScreenshotWorker(): void {
  screenshotWorker.close();
}

export async function captureMinecraftWindowJpeg(
  tmpDir: string,
  width = 768,
  height = 432
): Promise<PhotoResult | null> {
  fs.mkdirSync(tmpDir, { recursive: true });
  const outPath = path.join(tmpDir, `mc-shot-${Date.now()}-${Math.random().toString(36).slice(2, 6)}.jpg`);

  try {
    const line = await screenshotWorker.capture(tmpDir, outPath, width, height, 6000);
    if (!line || !line.startsWith("{")) return null;
    const parsed = JSON.parse(line) as { ok?: boolean; width?: number; height?: number; windowTitle?: string };
    if (!parsed.ok || !fs.existsSync(outPath)) {
      return null;
    }
    const base64 = fs.readFileSync(outPath).toString("base64");
    return {
      ok: true,
      mode: "window",
      base64,
      mimeType: "image/jpeg",
      width: parsed.width ?? width,
      height: parsed.height ?? height,
      windowTitle: parsed.windowTitle,
    };
  } catch {
    return null;
  } finally {
    fs.rmSync(outPath, { force: true });
  }
}

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf: Buffer): number {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, "ascii");
  const crcInput = Buffer.concat([typeBuf, data]);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(crcInput), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function encodeRgbPng(width: number, height: number, rgb: Buffer): Buffer {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    raw[y * stride] = 0;
    rgb.copy(raw, y * stride + 1, y * width * 3, (y + 1) * width * 3);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", zlib.deflateSync(raw)),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

function materialColor(mat: string, relY: number): [number, number, number] {
  const m = mat.toLowerCase();
  let rgb: [number, number, number] = [130, 140, 155];
  if (m === "air") return [18, 24, 38];
  if (m.includes("sea_lantern") || m.includes("beacon")) rgb = [100, 240, 255];
  else if (m.includes("glowstone") || m.includes("froglight") || m.includes("shroomlight") || m.includes("lantern")) rgb = [255, 220, 120];
  else if (m.includes("quartz")) rgb = [238, 236, 230];
  else if (m.includes("deepslate") || m.includes("blackstone") || m.includes("obsidian")) rgb = [48, 54, 68];
  else if (m.includes("cyan") || m.includes("prismarine")) rgb = [45, 190, 210];
  else if (m.includes("glass")) rgb = [120, 200, 245];
  else if (m.includes("repeating_command")) rgb = [145, 95, 230];
  else if (m.includes("chain_command")) rgb = [75, 185, 155];
  else if (m.includes("command_block")) rgb = [230, 140, 70];
  else if (m.includes("gold")) rgb = [250, 205, 65];
  else if (m.includes("water")) rgb = [45, 110, 225];
  else if (m.includes("lava")) rgb = [245, 95, 25];
  else if (m.includes("grass") || m.includes("leaves")) rgb = [85, 165, 75];
  else if (m.includes("dirt") || m.includes("oak") || m.includes("spruce") || m.includes("wood")) rgb = [140, 102, 68];
  else if (m.includes("sand")) rgb = [220, 205, 155];
  else if (m.includes("stone") || m.includes("andesite")) rgb = [145, 148, 154];

  const boost = Math.max(-28, Math.min(35, relY * 3));
  return [
    Math.max(0, Math.min(255, rgb[0] + boost)),
    Math.max(0, Math.min(255, rgb[1] + boost)),
    Math.max(0, Math.min(255, rgb[2] + boost)),
  ];
}

export async function captureMinecraftScreenshot(
  mcClient: McClient,
  tmpDir: string
): Promise<PhotoResult> {
  const winShot = await captureMinecraftWindowJpeg(tmpDir, 768, 432);
  if (winShot) {
    return winShot;
  }

  // Headless / windowless fallback: render top-down 240x240 RGB PNG from PaperMC voxel_snapshot
  const snap = await mcClient.request("voxel_snapshot", {}, 8000);
  const gridW = Number(snap.width ?? 48);
  const gridH = Number(snap.height ?? 48);
  const grid = Array.isArray(snap.grid) ? (snap.grid as Array<Array<[string, number]>>) : [];

  const scale = 5;
  const imgW = gridW * scale;
  const imgH = gridH * scale;
  const rgb = Buffer.alloc(imgW * imgH * 3);

  for (let row = 0; row < gridH; row++) {
    // Flip forward axis so top of image = +forward (away from player)
    const srcRow = grid[gridH - 1 - row] ?? [];
    for (let col = 0; col < gridW; col++) {
      const cell = srcRow[col] ?? ["air", -12];
      const isOrigin = col === 24 && gridH - 1 - row === 12;
      const [r, g, b] = isOrigin ? [255, 60, 180] : materialColor(String(cell[0] ?? "air"), Number(cell[1] ?? 0));
      for (let py = 0; py < scale; py++) {
        for (let px = 0; px < scale; px++) {
          const offset = ((row * scale + py) * imgW + (col * scale + px)) * 3;
          rgb[offset] = r;
          rgb[offset + 1] = g;
          rgb[offset + 2] = b;
        }
      }
    }
  }

  const png = encodeRgbPng(imgW, imgH, rgb);
  return {
    ok: true,
    mode: "voxel_fallback",
    base64: png.toString("base64"),
    mimeType: "image/png",
    width: imgW,
    height: imgH,
    note: "No visible Minecraft client window found; rendered top-down 48x48 voxel map (+forward is up, magenta dot is gaze origin).",
  };
}
