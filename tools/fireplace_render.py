"""
Renders the Fireplace app's video: an original, procedurally generated fire (no footage), as a seamless loop.

Everything is made from random noise that repeats exactly over the loop (textures tile, and every motion
completes a whole number of cycles), so the last frame flows straight into the first.

  python tools/fireplace_render.py --w 3840 --h 2160 --secs 20 --fps 30 --out fireplace-4k.mp4
  python tools/fireplace_render.py --preview 5 --w 960 --h 540      (a few PNG frames to look at)

Needs numpy, opencv-python-headless and ffmpeg (libx265).
"""
import argparse
import subprocess
import sys

import cv2
import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("--w", type=int, default=3840)
ap.add_argument("--h", type=int, default=2160)
ap.add_argument("--secs", type=float, default=20)
ap.add_argument("--fps", type=int, default=30)
ap.add_argument("--out", default="fireplace-4k.mp4")
ap.add_argument("--preview", type=int, default=0, help="write N preview PNGs instead of a video")
ap.add_argument("--seed", type=int, default=7)
ap.add_argument("--crf", type=int, default=22)
a = ap.parse_args()

W, H = a.w, a.h
S = W / 3840.0  # sizes below are written for 4K and scaled
N = int(round(a.secs * a.fps))
rng = np.random.default_rng(a.seed)


def periodic_noise(h, w, lo, hi, beta=1.0):
    """Noise that tiles in both directions: random spectrum between frequencies lo..hi (cycles per texture)."""
    fy = np.fft.fftfreq(h)[:, None] * h
    fx = np.fft.rfftfreq(w)[None, :] * w
    f = np.sqrt(fx * fx + (fy * w / h) ** 2)
    amp = np.where((f >= lo) & (f <= hi), 1.0 / np.maximum(f, 1) ** beta, 0.0)
    spec = amp * np.exp(2j * np.pi * rng.random(amp.shape))
    n = np.fft.irfft2(spec, s=(h, w)).astype(np.float32)
    n -= n.min()
    n /= n.max()
    return n


# Textures are smaller than the frame in the vertical direction (they repeat while scrolling up).
TH = H
flame_tex = periodic_noise(TH, W, 3, 60, 1.1)          # main flame body
detail_tex = periodic_noise(TH, W, 30, 220, 0.9)       # fine licks
warp_tex = periodic_noise(TH, W, 2, 14, 1.3)           # sideways sway
warp_tex2 = periodic_noise(TH, W, 2, 14, 1.3)

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

# ---------------- the fireplace (static parts)
box_l, box_r = 0.17 * W, 0.83 * W           # firebox opening
box_t, box_b = 0.16 * H, 0.93 * H
base_y = 0.765 * H                            # where flames start (top of the logs)

def bricks():
    img = np.zeros((H, W, 3), np.float32)
    bh, bw = 92 * S, 230 * S
    row = np.floor(yy / bh)
    off = (row % 2) * bw / 2
    col = np.floor((xx + off) / bw)
    rnd = np.random.default_rng(3)
    tint = rnd.random((int(H / bh) + 3, int(W / bw) + 3)).astype(np.float32)
    t = tint[row.astype(int) % tint.shape[0], col.astype(int) % tint.shape[1]]
    mortar = ((yy % bh) < 7 * S) | (((xx + off) % bw) < 7 * S)
    grain = periodic_noise(H, W, 80, 400, 0.6)
    base = np.stack([0.30 + 0.10 * t, 0.12 + 0.04 * t, 0.07 + 0.02 * t], -1) * (0.75 + 0.5 * grain[..., None])
    base[mortar] = (0.10, 0.08, 0.07)
    return base

back = bricks()
inside = (xx > box_l) & (xx < box_r) & (yy > box_t) & (yy < box_b)
# Soot: the back wall is darker toward the top centre.
soot = np.clip(1.0 - np.exp(-(((xx - W / 2) / (0.22 * W)) ** 2) - (((yy - 0.35 * H) / (0.30 * H)) ** 2)) * 0.75, 0.2, 1)
back *= soot[..., None]
back[~inside] *= 0.35                         # the surround (outside the opening) is in shadow
# Opening edge: a dark frame around the firebox.
edge = (~inside) & (xx > box_l - 40 * S) & (xx < box_r + 40 * S) & (yy > box_t - 40 * S)
back[edge] *= 0.35
# Hearth floor.
floor = yy > box_b
back[floor] = back[floor] * 0.5 + np.array([0.06, 0.05, 0.05], np.float32)

# Logs: three rounded logs in front of the flames.
logs = np.zeros((H, W), np.float32)
log_col = np.zeros((H, W, 3), np.float32)
bark = periodic_noise(H, W, 40, 300, 0.8)
bark = cv2.resize(np.ascontiguousarray(bark[:, : W // 5]), (W, H), interpolation=cv2.INTER_CUBIC)  # grain runs along the logs
def add_log(cx, cy, length, radius, angle):
    global logs
    c, s = np.cos(angle), np.sin(angle)
    dx, dy = xx - cx, yy - cy
    u = dx * c + dy * s
    v = -dx * s + dy * c
    inside_l = (np.abs(u) < length / 2) & (np.abs(v) < radius)
    endcap = ((np.abs(u) - length / 2) ** 2 + v ** 2) < radius ** 2
    m = inside_l | endcap
    shade = np.clip(1 - (v / radius) ** 2, 0, 1) ** 0.5     # rounder in the middle
    char = np.clip((bark - 0.45) * 3, 0, 1)[..., None]          # patches of black char
    wood = np.stack([0.16 + 0.10 * bark, 0.085 + 0.05 * bark, 0.045 + 0.03 * bark], -1)
    col = (wood * (1 - char) + np.array([0.025, 0.02, 0.018], np.float32) * char) * (0.25 + 0.75 * shade[..., None])
    log_col[m] = col[m]
    logs = np.maximum(logs, m.astype(np.float32))
    return m, v / radius

m1, v1 = add_log(0.41 * W, 0.805 * H, 0.36 * W, 78 * S, -0.10)
m2, v2 = add_log(0.59 * W, 0.81 * H, 0.36 * W, 76 * S, 0.11)
m3, v3 = add_log(0.50 * W, 0.735 * H, 0.30 * W, 66 * S, 0.02)
# Ember cracks: thin bright lines in the bark, mostly on the upper sides of the logs.
cracks = periodic_noise(H, W, 20, 120, 1.0)
cracks = np.clip(1 - np.abs(cracks - 0.5) * 34, 0, 1) * (periodic_noise(H, W, 6, 30, 1.0) > 0.58)
upper = np.zeros((H, W), np.float32)
for m, v in ((m1, v1), (m2, v2), (m3, v3)):
    upper = np.maximum(upper, (m * np.clip(-v + 0.2, 0, 1)).astype(np.float32))
ember_mask = cracks * upper * logs
glow_bed = np.exp(-(((yy - 0.84 * H) / (0.05 * H)) ** 2)) * np.exp(-(((xx - W / 2) / (0.20 * W)) ** 2))  # coals under the logs
ember_tex = periodic_noise(TH, W, 4, 40, 1.0)

# Grate bars under the logs.
grate = np.zeros((H, W), bool)
for gx in np.linspace(0.30, 0.70, 7):
    grate |= (np.abs(xx - gx * W) < 9 * S) & (yy > 0.83 * H) & (yy < 0.90 * H)
grate |= (yy > 0.875 * H) & (yy < 0.89 * H) & (xx > 0.28 * W) & (xx < 0.72 * W)

# Flame envelope: a few tall tongues over the logs, tallest in the middle.
cx = (xx - W / 2) / (0.27 * W)
env = (np.exp(-((xx - 0.50 * W) / (0.09 * W)) ** 2) * 1.0 + np.exp(-((xx - 0.39 * W) / (0.07 * W)) ** 2) * 0.75
       + np.exp(-((xx - 0.61 * W) / (0.07 * W)) ** 2) * 0.8 + np.exp(-((xx - 0.31 * W) / (0.05 * W)) ** 2) * 0.45
       + np.exp(-((xx - 0.69 * W) / (0.05 * W)) ** 2) * 0.5)
env = np.clip(env, 0, 1.1) * np.exp(-cx ** 6 * 0.5)

# Fire palette (intensity 0..1 -> colour).
stops = np.array([
    [0.00, 0, 0, 0], [0.18, 40, 3, 0], [0.35, 150, 25, 2], [0.52, 235, 85, 10],
    [0.70, 255, 150, 35], [0.86, 255, 215, 110], [1.00, 255, 250, 220]], np.float32)
lut = np.zeros((256, 3), np.float32)
for i in range(256):
    t = i / 255
    j = np.searchsorted(stops[:, 0], t) if t > 0 else 1
    j = min(max(j, 1), len(stops) - 1)
    p0, p1 = stops[j - 1], stops[j]
    f = (t - p0[0]) / (p1[0] - p0[0])
    lut[i] = (p0[1:] * (1 - f) + p1[1:] * f) / 255.0

# Sparks: each rises along its own path a whole number of times per loop.
NS = 60
sp_x = rng.uniform(0.38, 0.62, NS) * W
sp_ph = rng.random(NS)
sp_rep = rng.integers(2, 6, NS)              # loops per video loop
sp_sway = rng.uniform(15, 70, NS) * S
sp_size = rng.uniform(2.0, 4.5, NS) * S
sp_life = rng.uniform(0.25, 0.5, NS)        # fraction of its cycle it's visible


def sample(tex, dx, dy, ys=1.0):
    """tex sampled at (x + dx, y * ys + dy), wrapping. ys < 1 stretches it upward (flame tongues)."""
    return cv2.remap(tex, (xx + dx) % W, (yy * ys + dy) % TH, cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def frame(i):
    t = i / N                                  # 0..1 over the loop
    tau = 2 * np.pi * t
    # Sideways sway (whole cycles per loop) and rising scroll (whole texture heights per loop).
    wx = (sample(warp_tex, 0, TH * 2 * t) - 0.5) * 220 * S + (sample(warp_tex2, 0, TH * 3 * t) - 0.5) * 120 * S
    height_k = np.clip((base_y - yy) / (0.55 * H), 0, 1)
    wx *= 0.3 + height_k                      # flames sway more toward the tips
    n1 = sample(flame_tex, wx, TH * 4 * t, 0.42)
    n2 = sample(detail_tex, wx * 0.6, TH * 7 * t, 0.55)
    f = n1 * 0.72 + n2 * 0.28
    # Flame height varies slowly across the width (breathing), periodic in time.
    breathe = 0.85 + 0.15 * np.sin(tau * 3 + cx * 2.0) + 0.08 * np.sin(tau * 5 - cx * 3.1)
    h = (0.56 * H) * env * breathe + 10 * S
    u = (base_y - yy) / h                     # 0 at the base, 1 at the tip
    grad = np.clip(1 - u, 0, 1) ** 0.8 * np.clip(env * 3, 0, 1)
    below = yy > base_y
    grad[below] = (np.clip(1 - (yy[below] - base_y) / (0.06 * H), 0, 1) * np.clip(env[below] * 1.5, 0, 1))
    I = np.clip((f + grad * 1.0 - 1.02) * 2.5, 0, 1) * 0.94
    I *= inside
    # Light on the walls from the fire: blur of the flames, plus a gentle flicker.
    small = cv2.resize(I, (W // 16, H // 16), interpolation=cv2.INTER_AREA)
    light = cv2.GaussianBlur(small, (0, 0), 9)
    light = cv2.resize(light, (W, H), interpolation=cv2.INTER_LINEAR)
    flick = 0.92 + 0.08 * np.sin(tau * 11) * np.sin(tau * 7 + 1)
    lit = np.clip(0.18 + light * 3.2 * flick, 0, 2.2)
    img = back * lit[..., None] * np.array([1.0, 0.72, 0.45], np.float32)
    # Coals under the logs.
    coal = glow_bed * (0.6 + 0.4 * sample(ember_tex, 0, TH * 1 * t))
    img += lut[np.clip(coal * 200, 0, 255).astype(np.uint8)] * coal[..., None]
    # Flames (additive, then the logs in front).
    fire = lut[(I * 255).astype(np.uint8)]
    img = img * (1 - np.clip(I * 1.6, 0, 0.92))[..., None] + fire   # bright flames hide the bricks behind them
    # Logs: lit by the fire, with glowing cracks.
    ember = ember_mask * (0.55 + 0.45 * sample(ember_tex, 0, TH * 2 * t))
    lg = log_col * (0.35 + 0.7 * lit[..., None]) + lut[np.clip(ember * 255, 0, 255).astype(np.uint8)] * 1.2
    img = img * (1 - logs[..., None]) + lg * logs[..., None]
    img[grate] = img[grate] * 0.15 + np.array([0.03, 0.025, 0.02], np.float32)
    # Sparks.
    for k in range(NS):
        ph = (t * sp_rep[k] + sp_ph[k]) % 1.0
        if ph > sp_life[k]:
            continue
        q = ph / sp_life[k]
        y = base_y - q * 0.6 * H
        x = sp_x[k] + np.sin(q * 6.0 + k) * sp_sway[k]
        b = (1 - q) ** 1.5
        r = sp_size[k]
        x0, x1 = int(max(x - 4 * r, 0)), int(min(x + 4 * r + 1, W))
        y0, y1 = int(max(y - 4 * r, 0)), int(min(y + 4 * r + 1, H))
        if x1 <= x0 or y1 <= y0:
            continue
        gx = (xx[y0:y1, x0:x1] - x) / r
        gy = (yy[y0:y1, x0:x1] - y) / r
        g = np.exp(-(gx * gx + gy * gy)) * b
        img[y0:y1, x0:x1] += g[..., None] * np.array([1.0, 0.62, 0.22], np.float32)
    # Soft vignette, then to 8-bit (with a little dither so dark gradients don't band).
    img *= (0.75 + 0.25 * np.exp(-((xx - W / 2) / (0.6 * W)) ** 2 - ((yy - H * 0.55) / (0.7 * H)) ** 2))[..., None]
    img = np.clip(img, 0, 1) ** (1 / 1.1)
    out = (img * 255 + rng.random((H, W, 1), dtype=np.float32) - 0.5).clip(0, 255).astype(np.uint8)
    return out


if a.preview:
    for k in range(a.preview):
        i = int(k * N / a.preview)
        cv2.imwrite(f"fire-preview-{k}.png", cv2.cvtColor(frame(i), cv2.COLOR_RGB2BGR))
        print("preview", k, flush=True)
    sys.exit(0)

ff = subprocess.Popen([
    "ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(a.fps), "-i", "-",
    "-c:v", "libx265", "-preset", "fast", "-crf", str(a.crf), "-pix_fmt", "yuv420p", "-tag:v", "hvc1",
    "-x265-params", "keyint=60:min-keyint=60:log-level=error", "-movflags", "+faststart", a.out,
], stdin=subprocess.PIPE)
for i in range(N):
    ff.stdin.write(frame(i).tobytes())
    if i % 25 == 0:
        print(f"frame {i}/{N}", flush=True)
ff.stdin.close()
ff.wait()
print("done", a.out)
