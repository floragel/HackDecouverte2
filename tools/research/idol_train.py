"""
Light inertial-odometry network trained on IDOL (CC BY 4.0, Sun et al., AAAI 2021) — our own code.

  idol_train.py train  <idol_dir> <out.pt> [--epochs 12] [--pocket 0.5]
  idol_train.py walk   <model.pt> <walk.txt.gz> <out.csv>     # run on one of our recordings

Input: phone gyro + accelerometer (gravity included) rotated into the world frame, 100 Hz, 2 s windows
(at least one stride, so a pocketed thigh's swing averages out). Output: horizontal velocity over the
1 s around the window centre. Augmentations: random walking direction, simulated pocket (thigh pendulum
synchronised with the stride, body velocity unchanged), sensor biases and noise.
"""
import argparse
import glob
import gzip
import json
import math
import os
import sys

import numpy as np
import pandas as pd
import quaternion
import torch
import torch.nn as nn

RATE = 100
WIN = 200          # 2 s
HALF_T = 50        # target: velocity over ±0.5 s around the centre
G = 9.80665


# ---------------------------------------------------------------- data

def idol_world(df):
    """IDOL iPhone IMU → Android convention, stencil axes, rotated by the ground-truth orientation."""
    acc = df[["iphoneAccX", "iphoneAccY", "iphoneAccZ"]].values
    gyr = df[["iphoneGyroX", "iphoneGyroY", "iphoneGyroZ"]].values
    # iOS reports acceleration in g with the opposite sign of Android; iPhone x, y = −stencil x, y.
    acc_st = np.stack([G * acc[:, 0], G * acc[:, 1], -G * acc[:, 2]], axis=1)
    gyr_st = np.stack([-gyr[:, 0], -gyr[:, 1], gyr[:, 2]], axis=1)
    q = quaternion.from_float_array(df[["orientW", "orientX", "orientY", "orientZ"]].values)
    q = q / np.abs(q)
    def rot(v):
        vq = quaternion.from_float_array(np.concatenate([np.zeros((len(v), 1)), v], axis=1))
        return quaternion.as_float_array(q * vq * q.conj())[:, 1:]
    feat = np.concatenate([rot(gyr_st), rot(acc_st)], axis=1).astype(np.float32)
    pos = df[["processedPosX", "processedPosY"]].values.astype(np.float32)
    return feat, pos


def load_idol(root):
    seqs = []
    for f in sorted(glob.glob(os.path.join(root, "building*", "*", "*.feather"))):
        df = pd.read_feather(f)
        if len(df) < 2 * WIN:
            continue
        feat, pos = idol_world(df)
        split = "val" if os.sep + "unknown" + os.sep in f else "train"
        seqs.append((split, feat, pos, f))
    return seqs


class Windows(torch.utils.data.Dataset):
    def __init__(self, seqs, n, augment, pocket, seed):
        self.seqs = seqs
        self.n = n
        self.augment = augment
        self.pocket = pocket
        self.rng = np.random.default_rng(seed)
        self.lens = np.array([len(s[1]) for s in seqs])
        self.p = self.lens / self.lens.sum()

    def __len__(self):
        return self.n

    def __getitem__(self, i):
        rng = self.rng
        k = rng.choice(len(self.seqs), p=self.p)
        _, feat, pos, _ = self.seqs[k]
        e = rng.integers(WIN, len(feat) - HALF_T)
        c = e - WIN // 2
        x = feat[e - WIN:e].copy()
        v = (pos[c + HALF_T] - pos[c - HALF_T]) / (2 * HALF_T / RATE)
        if self.augment:
            x, v = augment(x, v, rng, self.pocket)
        return torch.from_numpy(x.T.copy()), torch.from_numpy(v.astype(np.float32))


def rotz(x, v, a):
    c, s = math.cos(a), math.sin(a)
    r = np.array([[c, -s], [s, c]], np.float32)
    x[:, 0:2] = x[:, 0:2] @ r.T
    x[:, 3:5] = x[:, 3:5] @ r.T
    return x, v @ r.T


def augment(x, v, rng, pocket):
    x, v = rotz(x, v, rng.uniform(-math.pi, math.pi))
    speed = float(np.linalg.norm(v))
    if rng.random() < pocket and speed > 0.3:
        # Thigh pendulum: phone at L from the hip, angle θ(t) = A sin(2π f t + φ) in the walking plane.
        u = v / speed
        side = np.array([-u[1], u[0]], np.float32)  # horizontal axis of the swing
        f = rng.uniform(0.8, 1.05)                  # stride frequency (Hz)
        A = rng.uniform(0.2, 0.45)
        L = rng.uniform(0.3, 0.5)
        t = np.arange(WIN) / RATE
        w = 2 * math.pi * f
        th = A * np.sin(w * t + rng.uniform(0, 2 * math.pi))
        thd = A * w * np.cos(w * t)
        thdd = -A * w * w * np.sin(w * t)
        ax = L * (thdd * np.cos(th) - thd ** 2 * np.sin(th))
        az = L * (thdd * np.sin(th) + thd ** 2 * np.cos(th))
        x[:, 3] += ax * u[0]
        x[:, 4] += ax * u[1]
        x[:, 5] += az
        x[:, 0] += thd * side[0]
        x[:, 1] += thd * side[1]
    # Sensor biases and noise (phones differ), random small tilt error of the attitude.
    x[:, 0:3] += rng.normal(0, 0.01, 3) + rng.normal(0, 0.02, (WIN, 3))
    x[:, 3:6] += rng.normal(0, 0.1, 3) + rng.normal(0, 0.15, (WIN, 3))
    x[:, 3:6] *= rng.uniform(0.97, 1.03)
    return x.astype(np.float32), v


# ---------------------------------------------------------------- model

class SepBlock(nn.Module):
    def __init__(self, c_in, c_out, stride):
        super().__init__()
        self.dw = nn.Conv1d(c_in, c_in, 7, stride, 3, groups=c_in, bias=False)
        self.pw = nn.Conv1d(c_in, c_out, 1, bias=False)
        self.bn = nn.BatchNorm1d(c_out)
        self.skip = None if (c_in == c_out and stride == 1) else nn.Sequential(nn.Conv1d(c_in, c_out, 1, stride, bias=False), nn.BatchNorm1d(c_out))

    def forward(self, x):
        y = self.bn(self.pw(self.dw(x)))
        return torch.relu(y + (x if self.skip is None else self.skip(x)))


class LightIO(nn.Module):
    """Depthwise-separable 1D CNN (IMUNet / TinyIO family), ~0.1 M parameters."""
    def __init__(self):
        super().__init__()
        self.stem = nn.Sequential(nn.Conv1d(6, 32, 7, 2, 3, bias=False), nn.BatchNorm1d(32), nn.ReLU())
        self.body = nn.Sequential(SepBlock(32, 48, 2), SepBlock(48, 48, 1), SepBlock(48, 96, 2), SepBlock(96, 96, 1), SepBlock(96, 160, 2), SepBlock(160, 160, 1))
        self.head = nn.Sequential(nn.AdaptiveAvgPool1d(1), nn.Flatten(), nn.Linear(160, 64), nn.ReLU(), nn.Linear(64, 2))

    def forward(self, x):
        return self.head(self.body(self.stem(x)))


# ---------------------------------------------------------------- train / eval

def device():
    return torch.device("mps" if torch.backends.mps.is_available() else "cpu")


def evaluate(net, seqs, dev):
    """Velocity RMSE and integrated-trajectory drift on whole sequences (no augmentation)."""
    net.eval()
    errs, drifts = [], []
    with torch.no_grad():
        for _, feat, pos, _ in seqs:
            ends = np.arange(WIN, len(feat) - HALF_T, 10)
            xb = torch.from_numpy(np.stack([feat[e - WIN:e].T for e in ends])).to(dev)
            pred = torch.cat([net(xb[i:i + 1024]) for i in range(0, len(xb), 1024)]).cpu().numpy()
            c = ends - WIN // 2
            v = (pos[c + HALF_T] - pos[c - HALF_T]) / (2 * HALF_T / RATE)
            errs.append(np.mean(np.sum((pred - v) ** 2, axis=1)))
            traj = np.cumsum(pred * 0.1, axis=0)
            gt = pos[c] - pos[c[0]]
            drifts.append(np.linalg.norm(traj[-1] - gt[-1]) / max(np.sum(np.linalg.norm(np.diff(gt, axis=0), axis=1)), 1.0))
    net.train()
    return float(np.sqrt(np.mean(errs))), float(np.median(drifts) * 100)


def train(root, out, epochs, pocket, per_epoch=200_000):
    seqs = load_idol(root)
    tr = [s for s in seqs if s[0] == "train"]
    va = [s for s in seqs if s[0] == "val"]
    print("IDOL: %d train, %d val sequences, %.1f h" % (len(tr), len(va), sum(len(s[1]) for s in seqs) / RATE / 3600))
    dev = device()
    net = LightIO().to(dev)
    print("parameters: %d" % sum(p.numel() for p in net.parameters()))
    opt = torch.optim.AdamW(net.parameters(), lr=2e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=2e-3, total_steps=epochs * (per_epoch // 512 + 1))
    best = 1e9
    for ep in range(epochs):
        dl = torch.utils.data.DataLoader(Windows(tr, per_epoch, True, pocket, ep), batch_size=512, num_workers=0)
        tot = 0.0
        for xb, vb in dl:
            xb, vb = xb.to(dev), vb.to(dev)
            loss = nn.functional.mse_loss(net(xb), vb)
            opt.zero_grad(); loss.backward(); opt.step(); sched.step()
            tot += loss.item() * len(xb)
        rmse, drift = evaluate(net, va, dev)
        print("epoch %2d  train mse %.4f  val vel rmse %.3f m/s  val end drift %.1f %% of distance" % (ep + 1, tot / per_epoch, rmse, drift), flush=True)
        if rmse < best:
            best = rmse
            torch.save({"model_state_dict": net.state_dict(), "rate": RATE, "win": WIN, "pocket": pocket}, out)
    print("saved", out)


def walk(model_path, walk_path, out):
    """Our recording → 100 Hz world-frame features (same conversion as the RoNIN script) → trajectory."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import ronin_walk as rw
    acc, gyro, rv, _, _, _ = rw.read_walk(walk_path)
    rw.RATE_HZ = RATE
    ts, feat = rw.features(acc, gyro, rv)
    net = LightIO()
    net.load_state_dict(torch.load(model_path, map_location="cpu")["model_state_dict"])
    net.eval()
    ends = np.arange(WIN, len(feat), 10)
    with torch.no_grad():
        pred = torch.cat([net(torch.from_numpy(np.stack([feat[e - WIN:e].T for e in ends[i:i + 1024]]))) for i in range(0, len(ends), 1024)]).numpy()
    centres = ends - WIN // 2
    pos = np.vstack([[0.0, 0.0], np.cumsum(pred * 0.1, axis=0)])
    t = np.concatenate([[ts[centres[0]] - 1e8], ts[centres]])
    with open(out, "w") as f:
        f.write("t_ns,x,y\n")
        for tt, (x, y) in zip(t, pos):
            f.write("%d,%.4f,%.4f\n" % (tt, x, y))
    d = np.sum(np.linalg.norm(np.diff(pos, axis=0), axis=1))
    print("%s: %.1f m, end gap %.1f m (%.1f %%)" % (os.path.basename(walk_path), d, np.linalg.norm(pos[-1]), 100 * np.linalg.norm(pos[-1]) / max(d, 1e-6)))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("train"); t.add_argument("root"); t.add_argument("out"); t.add_argument("--epochs", type=int, default=12); t.add_argument("--pocket", type=float, default=0.5)
    w = sub.add_parser("walk"); w.add_argument("model"); w.add_argument("walk"); w.add_argument("out")
    a = ap.parse_args()
    if a.cmd == "train":
        train(a.root, a.out, a.epochs, a.pocket)
    else:
        walk(a.model, a.walk, a.out)
