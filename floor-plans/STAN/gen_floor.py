#!/usr/bin/env python3
"""
Collège Stanislas, McEachran wing, level 100 ("Niveau 100"), traced from the evacuation plan photo.

Writes the two files the app reads:
  app/src/main/assets/buildings/STAN/STAN-floor-01.svg      walls (one path, closed rectangles, metres)
  app/src/main/assets/buildings/STAN/floorplan-STAN-1.json  rooms, stairs, exit and corridor graph

Everything below is measured in pixels on the 1600 px wide photo next to this script (niveau-100-photo.jpg),
then converted: SCALE px per metre, rotated so the long corridor runs along its real bearing.
Room names are arbitrary (the plan only says "CLASSE"). Re-run after any edit:
  python3 floor-plans/STAN/gen_floor.py
"""
import json
import math
import os

SCALE = 35.0                 # px per metre: corridor ≈ 3.4 m, classrooms ≈ 10 × 7.7 m
ORIGIN_PX = (870.0, 575.0)   # (0, 0) = middle of the main corridor
# On Earth (0, 0) = 45.51805395604281, -73.61554355694975 (IndoorSite.STANISLAS, picked on a map)
# North arrow on the plan points to the lower right: the photo's "right" faces 315° (north-west).
RIGHT_BEARING_DEG = 315.0

EXT = 0.30                   # exterior wall thickness (m)
INT = 0.15                   # interior wall thickness (m)
DOOR = 0.95                  # door opening (m)

# ---- geometry, photo pixels (x right, y down) ----
X_W, X_E = 160, 1590         # west / east facade
Y_N, Y_S = 240, 905          # top / bottom facade
Y_CN, Y_CS = 515, 635        # corridor north / south walls
Y_COR = (Y_CN + Y_CS) / 2    # corridor centre line
WEST_EXIT = (550, 600)       # double door at the west end of the corridor (y range)

TOP_PARTITIONS = [465, 630, 1100, 1330]
BOTTOM_PARTITIONS = [290, 640, 755, 1105, 1278, 1452]

# (label, name, type, door x on the corridor wall, side) — side "N" = top band, "S" = bottom band
ROOMS = [
    ("M-101", "Salle d'étude", "room", 437, "N"),
    ("M-103", "Bureau des surveillants", "room", 550, "N"),
    ("M-105", "Laboratoire de sciences", "room", 665, "N"),
    ("M-105", "Laboratoire de sciences (porte est)", "room", 1062, "N"),
    ("M-107", "Classe de français", "room", 1145, "N"),
    ("M-109", "Classe de mathématiques", "room", 1528, "N"),
    ("E-M-S", "Escalier sud", "stairs", 225, "S"),
    ("M-102", "Classe d'histoire", "room", 322, "S"),
    ("M-104", "Rangement", "room", 698, "S"),
    ("M-106", "Classe d'anglais", "room", 845, "S"),
    ("M-108", "Toilettes filles", "room", 1250, "S"),
    ("M-110", "Toilettes garçons", "room", 1415, "S"),
    ("E-M-N", "Escalier nord", "stairs", 1505, "S"),
]
# Inner doors between M-104 (storage) and the two classrooms beside it: (wall x, y range)
INNER_DOORS = [(640, (655, 688)), (755, (655, 688))]


def to_m(px, py):
    """Photo pixel -> (east, north) metres, true north."""
    u = (px - ORIGIN_PX[0]) / SCALE          # along the photo's right
    v = (ORIGIN_PX[1] - py) / SCALE          # along the photo's up
    b = math.radians(RIGHT_BEARING_DEG)
    r = (math.sin(b), math.cos(b))           # bearing -> (east, north)
    up = (math.sin(b - math.pi / 2), math.cos(b - math.pi / 2))
    return (u * r[0] + v * up[0], u * r[1] + v * up[1])


walls = []   # rectangles as 4 corners in metres


def wall(p0, p1, thick):
    """Straight wall from p0 to p1 (pixels), thickness in metres."""
    a, b = to_m(*p0), to_m(*p1)
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy)
    if n < 1e-6:
        return
    ox, oy = -dy / n * thick / 2, dx / n * thick / 2
    walls.append([(a[0] + ox, a[1] + oy), (b[0] + ox, b[1] + oy), (b[0] - ox, b[1] - oy), (a[0] - ox, a[1] - oy)])


def wall_with_gaps(fixed, start, end, gaps, thick, horizontal):
    """Wall along x (horizontal) or y, leaving openings; gaps are (centre, width_m) or (lo, hi) in px."""
    spans = sorted(gaps)
    cur = start
    for lo, hi in spans:
        if lo > cur:
            wall((cur, fixed) if horizontal else (fixed, cur), (lo, fixed) if horizontal else (fixed, lo), thick)
        cur = max(cur, hi)
    if cur < end:
        wall((cur, fixed) if horizontal else (fixed, cur), (end, fixed) if horizontal else (fixed, end), thick)


half_door = DOOR * SCALE / 2
north_doors = [(x - half_door, x + half_door) for (_, _, _, x, side) in ROOMS if side == "N"]
south_doors = [(x - half_door, x + half_door) for (_, _, _, x, side) in ROOMS if side == "S"]

# facades
wall((X_W, Y_N), (X_E, Y_N), EXT)
wall((X_W, Y_S), (X_E, Y_S), EXT)
wall_with_gaps(X_W, Y_N, Y_S, [WEST_EXIT], EXT, horizontal=False)
wall((X_E, Y_N), (X_E, Y_S), EXT)
# corridor walls with the doors
wall_with_gaps(Y_CN, X_W, X_E, north_doors, INT, horizontal=True)
wall_with_gaps(Y_CS, X_W, X_E, south_doors, INT, horizontal=True)
# partitions
for x in TOP_PARTITIONS:
    wall((x, Y_N), (x, Y_CN), INT)
for x in BOTTOM_PARTITIONS:
    gaps = [rng for (wx, rng) in INNER_DOORS if wx == x]
    wall_with_gaps(x, Y_CS, Y_S, gaps, INT, horizontal=False)

# ---- graph ----
nodes, edges = [], []


def add_node(nid, ntype, px, py, label="", name="", group=None):
    e, n = to_m(px, py)
    nodes.append({
        "id": nid, "type": ntype, "label": label, "name": name, "vertical_group_id": group,
        "east_m": round(e, 3), "north_m": round(n, 3),
    })


corridor_xs = sorted({X_W + 60, X_E - 20, *[x for (_, _, _, x, _) in ROOMS]})
for i, x in enumerate(corridor_xs):
    add_node(f"STAN-1-c{i:02d}", "corridor", x, Y_COR)
    if i:
        edges.append({"from": f"STAN-1-c{i - 1:02d}", "to": f"STAN-1-c{i:02d}", "accessible": True})

for k, (label, name, ntype, x, side) in enumerate(ROOMS):
    nid = f"STAN-1-r{k:02d}"
    y = Y_CN if side == "N" else Y_CS
    group = "stairs_" + label.lower().replace("-", "_") if ntype == "stairs" else None
    add_node(nid, ntype, x, y, label, name, group)
    edges.append({"from": f"STAN-1-c{corridor_xs.index(x):02d}", "to": nid, "accessible": ntype != "stairs"})

add_node("STAN-1-x00", "exit", X_W + 40, sum(WEST_EXIT) / 2, "Sortie", "Porte du bout du corridor (côté escalier sud)")
edges.append({"from": "STAN-1-c00", "to": "STAN-1-x00", "accessible": True})

# ---- bounds and output ----
pts = [p for w in walls for p in w]
margin = 4.0
min_e = math.floor(min(p[0] for p in pts) - margin)
max_e = math.ceil(max(p[0] for p in pts) + margin)
min_n = math.floor(min(p[1] for p in pts) - margin)
max_n = math.ceil(max(p[1] for p in pts) + margin)
width, height = max_e - min_e, max_n - min_n

root = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(root, "..", "..", "app", "src", "main", "assets", "buildings", "STAN")
os.makedirs(out, exist_ok=True)

d = " ".join(
    "M " + " L ".join(f"{e:.3f} {-n:.3f}" for (e, n) in w) + " Z" for w in walls
)
svg = (
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{min_e} {-max_n} {width} {height}" '
    f'width="{width * 40}" height="{height * 40}">\n'
    f'<title>Collège Stanislas, aile McEachran, niveau 100 (metres, north up)</title>\n'
    f'<rect x="{min_e}" y="{-max_n}" width="{width}" height="{height}" fill="#ffffff"/>\n'
    f'<g id="walls-and-text" transform="matrix(1 0 0 1 0 0)">\n'
    f'<path fill="#222222" d="{d}"/>\n'
    f'</g>\n</svg>\n'
)
with open(os.path.join(out, "STAN-floor-01.svg"), "w", encoding="utf-8") as f:
    f.write(svg)

plan = {
    "floor_id": "STAN-1",
    "svg_asset": "buildings/STAN/STAN-floor-01.svg",
    "coordinate_note": "east_m / north_m are true-north metres from the middle of the main corridor. "
                       "Generated by floor-plans/STAN/gen_floor.py; edit that script, not this file.",
    "estimated_dimensions_meters": {"width": width, "height": height},
    "nodes": nodes,
    "edges": edges,
}
with open(os.path.join(out, "floorplan-STAN-1.json"), "w", encoding="utf-8") as f:
    json.dump(plan, f, ensure_ascii=False, indent=1)
    f.write("\n")

print(f"{len(walls)} walls, {len(nodes)} nodes, {len(edges)} edges, {width}x{height} m -> {os.path.normpath(out)}")
