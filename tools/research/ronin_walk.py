"""
RoNIN on our own walks (research only: RoNIN weights are licensed for non-commercial research).

  ronin_walk.py <walk.txt.gz> <out.csv> [--model checkpoint.pt] [--arch resnet18]

Reads a hackdecouverte walk recording, builds RoNIN's input (gyro + accelerometer incl. gravity,
rotated into the world frame by the game rotation vector, 200 Hz, 1 s windows), predicts the
horizontal velocity every 10 frames and integrates it. Writes `t_ns,x,y` (metres, arbitrary yaw:
the comparison fits the rotation). Without --model, a random network checks the pipeline only.
"""
import argparse
import gzip
import os
import sys

import numpy as np
import quaternion
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
# RoNIN code (GPL-3.0, github.com/Sachini/ronin) is kept outside this repository.
sys.path.insert(0, os.environ.get("RONIN_CODE", os.path.join(HERE, "code", "source")))
from model_resnet1d import BasicBlock1D, FCOutputModule, ResNet1D  # noqa: E402

TYPE_ACC, TYPE_GYRO, TYPE_GAME_RV = 1, 4, 15
RATE_HZ, WINDOW, STEP = 200, 200, 10


def read_walk(path):
    acc, gyro, rv = [], [], []
    with gzip.open(path, "rt") as f:
        f.readline()  # JSON header
        for line in f:
            if not line.startswith("S,"):
                continue
            p = line.rstrip("\n").split(",")
            typ = int(p[1])
            if typ not in (TYPE_ACC, TYPE_GYRO, TYPE_GAME_RV):
                continue
            row = [int(p[2])] + [float(v) for v in p[6:]]
            (acc if typ == TYPE_ACC else gyro if typ == TYPE_GYRO else rv).append(row)
    return [np.array(sorted(a, key=lambda r: r[0])) for a in (acc, gyro, rv)]


def rv_to_quat(v):
    """Android rotation vector [x, y, z(, w)] → unit quaternion (w, x, y, z), device → world."""
    x, y, z = v[:, 0], v[:, 1], v[:, 2]
    w = v[:, 3] if v.shape[1] >= 4 else np.sqrt(np.clip(1 - x * x - y * y - z * z, 0, 1))
    q = quaternion.from_float_array(np.stack([w, x, y, z], axis=1))
    return np.normalized(q) if hasattr(np, "normalized") else q / np.abs(q)


def features(acc, gyro, rv):
    t0 = max(acc[0, 0], gyro[0, 0], rv[0, 0])
    t1 = min(acc[-1, 0], gyro[-1, 0], rv[-1, 0])
    ts = np.arange(t0, t1, 1e9 / RATE_HZ)
    a = np.stack([np.interp(ts, acc[:, 0], acc[:, i]) for i in (1, 2, 3)], axis=1)
    g = np.stack([np.interp(ts, gyro[:, 0], gyro[:, i]) for i in (1, 2, 3)], axis=1)
    q_src = rv_to_quat(rv[:, 1:5])
    # Keep the quaternion sign continuous before slerping.
    arr = quaternion.as_float_array(q_src)
    for i in range(1, len(arr)):
        if np.dot(arr[i], arr[i - 1]) < 0:
            arr[i] = -arr[i]
    q_src = quaternion.from_float_array(arr)
    q = quaternion.squad(q_src, (rv[:, 0] - t0) / 1e9, (ts - t0) / 1e9)
    def to_world(v):
        vq = quaternion.from_float_array(np.concatenate([np.zeros((len(v), 1)), v], axis=1))
        return quaternion.as_float_array(q * vq * q.conj())[:, 1:]
    return ts, np.concatenate([to_world(g), to_world(a)], axis=1).astype(np.float32)


def model(arch, path):
    fc = {"fc_dim": 512, "in_dim": WINDOW // 32 + 1, "dropout": 0.5, "trans_planes": 128}
    blocks = {"resnet18": [2, 2, 2, 2], "resnet50": [3, 4, 6, 3]}[arch]
    if arch == "resnet50":
        fc["fc_dim"] = 1024
    net = ResNet1D(6, 2, BasicBlock1D, blocks, base_plane=64, output_block=FCOutputModule, kernel_size=3, **fc)
    if path:
        ck = torch.load(path, map_location="cpu", weights_only=False)
        net.load_state_dict(ck.get("model_state_dict", ck))
    else:
        torch.manual_seed(0)
    return net.eval()


def run(walk, out, model_path=None, arch="resnet18"):
    acc, gyro, rv = read_walk(walk)
    ts, feat = features(acc, gyro, rv)
    net = model(arch, model_path)
    ends = np.arange(WINDOW, len(ts), STEP)
    batch = np.stack([feat[e - WINDOW:e].T for e in ends])
    with torch.no_grad():
        vel = torch.cat([net(torch.from_numpy(batch[i:i + 512])) for i in range(0, len(batch), 512)]).numpy()
    dt = STEP / RATE_HZ
    pos = np.vstack([[0.0, 0.0], np.cumsum(vel * dt, axis=0)])
    t_out = np.concatenate([[ts[ends[0] - STEP]], ts[ends]])
    with open(out, "w") as f:
        f.write("t_ns,x,y\n")
        for t, (x, y) in zip(t_out, pos):
            f.write("%d,%.4f,%.4f\n" % (t, x, y))
    dist = np.sum(np.linalg.norm(np.diff(pos, axis=0), axis=1))
    print("%s: %d windows, %.1f s, %.1f m walked, end gap %.1f m (%.1f %%), mean speed %.2f m/s" % (
        os.path.basename(walk), len(ends), (ts[-1] - ts[0]) / 1e9, dist, np.linalg.norm(pos[-1]),
        100 * np.linalg.norm(pos[-1]) / max(dist, 1e-6), np.mean(np.linalg.norm(vel, axis=1))))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("walk")
    ap.add_argument("out")
    ap.add_argument("--model")
    ap.add_argument("--arch", default="resnet18")
    a = ap.parse_args()
    run(a.walk, a.out, a.model, a.arch)
