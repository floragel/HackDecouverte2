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
import json
import os
import sys

import numpy as np
import quaternion
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
# RoNIN code (GPL-3.0, github.com/Sachini/ronin) is kept outside this repository.
sys.path.insert(0, os.environ.get("RONIN_CODE", os.path.join(HERE, "code", "source")))

TYPE_ACC, TYPE_MAG, TYPE_GYRO, TYPE_GAME_RV, TYPE_RV = 1, 2, 4, 15, 11
RATE_HZ, WINDOW, STEP = 200, 200, 10


def read_walk(path):
    acc, gyro, rv, mag_rv, mag = [], [], [], [], []
    with gzip.open(path, "rt") as f:
        header = json.loads(f.readline())
        for line in f:
            if not line.startswith("S,"):
                continue
            p = line.rstrip("\n").split(",")
            typ = int(p[1])
            if typ not in (TYPE_ACC, TYPE_MAG, TYPE_GYRO, TYPE_GAME_RV, TYPE_RV):
                continue
            row = [int(p[2])] + [float(v) for v in p[6:]][:4]
            {TYPE_ACC: acc, TYPE_MAG: mag, TYPE_GYRO: gyro, TYPE_GAME_RV: rv, TYPE_RV: mag_rv}[typ].append(row)
    arrays = [np.array(sorted(a, key=lambda r: r[0])) for a in (acc, gyro, rv, mag_rv, mag)]
    # Magnetometer recorded twice (pipeline and extra stream) on newer walks: keep one sample per time.
    if len(arrays[4]):
        _, keep = np.unique(arrays[4][:, 0], return_index=True)
        arrays[4] = arrays[4][keep]
    return arrays + [header]


def yaw_of(rv):
    """Heading (rad) of a rotation vector series: twist about the world vertical."""
    q = rv_to_quat(rv[:, 1:5])
    a = quaternion.as_float_array(q)
    w, x, y, z = a[:, 0], a[:, 1], a[:, 2], a[:, 3]
    return np.arctan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))


def field_ok(t, mag, tol=0.15):
    """Same gate as the PDR (B6): |B| within ±tol of its median (clamped to the Earth's 25–65 µT) around t."""
    if len(mag) < 20:
        return np.ones(len(t), bool)
    b = np.linalg.norm(mag[:, 1:4], axis=1)
    ref = np.clip(np.median(b), 25.0, 65.0)
    bad = np.abs(b - ref) > tol * ref
    # A compass sample is used only if every |B| within ±0.5 s of it is normal.
    idx_lo = np.searchsorted(mag[:, 0], t - 5e8)
    idx_hi = np.searchsorted(mag[:, 0], t + 5e8)
    cum = np.concatenate([[0], np.cumsum(bad)])
    return (cum[idx_hi] - cum[idx_lo]) == 0


def compass_offset(ts, rv, mag_rv, mag=None, window_s=20.0, declination_deg=0.0):
    """Compass yaw − gyro yaw over a sliding window (the gyro drifts, the compass is noisy), field-gated."""
    if len(mag_rv) < 10:
        return np.zeros(len(ts))
    g = np.unwrap(yaw_of(rv)); m = np.unwrap(yaw_of(mag_rv))
    d = np.interp(mag_rv[:, 0], rv[:, 0], g)
    # Yaw counter-clockwise from East; true azimuth = magnetic + declination → subtract it here.
    off = np.angle(np.exp(1j * (m - d - np.radians(declination_deg))))
    ok = field_ok(mag_rv[:, 0], mag) if mag is not None else np.ones(len(off), bool)
    if ok.sum() >= 10:
        mag_rv, off = mag_rv[ok], off[ok]
    out = np.empty(len(ts))
    half = window_s * 1e9 / 2
    for i, t in enumerate(ts):
        sel = np.abs(mag_rv[:, 0] - t) <= half
        o = off[sel] if sel.any() else off
        out[i] = np.angle(np.mean(np.exp(1j * o)))
    return out


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
    from model_resnet1d import BasicBlock1D, FCOutputModule, ResNet1D  # RoNIN code, imported only when used
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


def run(walk, out, model_path=None, arch="resnet18", compass=False):
    acc, gyro, rv, mag_rv, mag, header = read_walk(walk)
    ts, feat = features(acc, gyro, rv)
    net = model(arch, model_path)
    ends = np.arange(WINDOW, len(ts), STEP)
    batch = np.stack([feat[e - WINDOW:e].T for e in ends])
    with torch.no_grad():
        vel = torch.cat([net(torch.from_numpy(batch[i:i + 512])) for i in range(0, len(batch), 512)]).numpy()
    if compass:
        # Same correction as the PDR's compass pull: rotate each velocity by the drift of the gyro heading.
        off = compass_offset(ts[ends], rv, mag_rv, mag, declination_deg=header.get("declinationDeg", 0.0))
        c, sn = np.cos(off), np.sin(off)
        # Velocity (x, y) in a z-up frame; a heading offset rotates it about z.
        vel = np.stack([c * vel[:, 0] - sn * vel[:, 1], sn * vel[:, 0] + c * vel[:, 1]], axis=1)
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
    ap.add_argument("--compass", action="store_true", help="correct the gyro heading drift with the compass")
    a = ap.parse_args()
    run(a.walk, a.out, a.model, a.arch, a.compass)
