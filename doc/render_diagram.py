import json
import math
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

BASE = Path(__file__).resolve().parent
with open(BASE / "class_diagram_data.json", encoding="utf-8") as f:
    data = json.load(f)

nodes = data["nodes"]                 # name -> {kind, package, file}
external_names = set(data["external_nodes"])
edges = data["edges"]                 # [child, parent, kind]

ROOT_PKG = "red.jackf.chesttracker"

def cluster_key(name):
    if name in external_names:
        return "(vanilla / library)"
    pkg = nodes[name]["package"]
    rel = pkg[len(ROOT_PKG):].lstrip(".") if pkg.startswith(ROOT_PKG) else pkg
    parts = rel.split(".") if rel else []
    # group at 2 levels deep, e.g. api.memory, impl.gui, impl.compat.mods
    if len(parts) >= 3 and parts[0] == "impl" and parts[1] in ("compat", "gui"):
        return ".".join(parts[:3])
    return ".".join(parts[:2]) if parts else "(root)"

all_names = list(nodes.keys()) + [n for n in external_names]
clusters = {}
for name in all_names:
    ck = cluster_key(name)
    clusters.setdefault(ck, []).append(name)

for ck in clusters:
    clusters[ck].sort()

def wrap_label(label, max_chars=16):
    """Wrap a label on a camelCase / word boundary near max_chars."""
    if len(label) <= max_chars:
        return label
    # candidate break points: before an uppercase letter that follows a
    # lowercase letter (camelCase boundary), closest to the midpoint
    best = None
    mid = len(label) / 2
    for i in range(2, len(label) - 1):
        if label[i - 1].islower() and label[i].isupper():
            if best is None or abs(i - mid) < abs(best - mid):
                best = i
    if best is None:
        best = max_chars
    return label[:best] + "\n" + label[best:]

# ---- layout: pack clusters left-to-right, wrapping into rows ----
CELL_W, CELL_H = 3.3, 0.85
CELL_GAP_X, CELL_GAP_Y = 0.35, 0.3
CLUSTER_PAD = 0.45
CLUSTER_GAP_X, CLUSTER_GAP_Y = 1.0, 1.5
MAX_ROW_WIDTH = 42.0  # data units before wrapping to next row of clusters

def cluster_dims(n):
    cols = max(1, min(4, math.ceil(math.sqrt(n))))
    rows = math.ceil(n / cols)
    w = cols * (CELL_W + CELL_GAP_X) - CELL_GAP_X + 2 * CLUSTER_PAD
    h = rows * (CELL_H + CELL_GAP_Y) - CELL_GAP_Y + 2 * CLUSTER_PAD + 0.5
    return cols, rows, w, h

# order clusters: mod clusters first (by size desc), vanilla/library last
ck_order = sorted(
    [k for k in clusters if k != "(vanilla / library)"],
    key=lambda k: (-len(clusters[k]), k),
)
if "(vanilla / library)" in clusters:
    ck_order.append("(vanilla / library)")

positions = {}       # name -> (x, y) center
cluster_boxes = {}   # ck -> (x0, y0, w, h)

LEGEND_RESERVED_H = 4.2  # blank strip at top-left reserved for the manual legend

cursor_x, cursor_y = 0.0, -LEGEND_RESERVED_H
row_height = 0.0
for ck in ck_order:
    members = clusters[ck]
    cols, rows, w, h = cluster_dims(len(members))
    if cursor_x + w > MAX_ROW_WIDTH and cursor_x > 0:
        cursor_x = 0.0
        cursor_y -= row_height + CLUSTER_GAP_Y
        row_height = 0.0
    x0, y0 = cursor_x, cursor_y
    cluster_boxes[ck] = (x0, y0, w, h)
    for i, name in enumerate(members):
        col, row = i % cols, i // cols
        cx = x0 + CLUSTER_PAD + col * (CELL_W + CELL_GAP_X) + CELL_W / 2
        cy = y0 - CLUSTER_PAD - 0.5 - row * (CELL_H + CELL_GAP_Y) - CELL_H / 2
        positions[name] = (cx, cy)
    cursor_x += w + CLUSTER_GAP_X
    row_height = max(row_height, h)

all_x = [x for x, y in positions.values()]
all_y = [y for x, y in positions.values()]
y_top = 1.0
fig_w = (max(all_x) - min(all_x)) / 2.6 + 4
fig_h = (y_top - min(all_y)) / 2.6 + 4

fig, ax = plt.subplots(figsize=(fig_w, fig_h), dpi=150)
ax.set_xlim(min(all_x) - 2, max(all_x) + 2)
ax.set_ylim(min(all_y) - 2, y_top)
ax.set_aspect("equal")
ax.axis("off")

KIND_COLOR = {
    "class": "#cfe3ff",
    "interface": "#ffe9b3",
    "enum": "#d6f5d6",
    "record": "#f0d9ff",
}
KIND_LABEL = {"class": "", "interface": "«interface» ", "enum": "«enum» ", "record": "«record» "}

# cluster background boxes + labels
for ck, (x0, y0, w, h) in cluster_boxes.items():
    is_external = ck == "(vanilla / library)"
    box = FancyBboxPatch(
        (x0, y0 - h), w, h,
        boxstyle="round,pad=0.02,rounding_size=0.15",
        linewidth=1.4,
        edgecolor="#888888" if is_external else "#33507a",
        facecolor="#f2f2f2" if is_external else "#eef4ff",
        linestyle="dashed" if is_external else "solid",
        zorder=1,
    )
    ax.add_patch(box)
    ax.text(x0 + 0.15, y0 + 0.15, ck, fontsize=9, fontweight="bold",
             color="#555555" if is_external else "#1a3a66", va="bottom", ha="left", zorder=2)

# edges
for child, parent, kind in edges:
    if child not in positions or parent not in positions:
        continue
    x1, y1 = positions[child]
    x2, y2 = positions[parent]
    style = "-" if kind == "extends" else "--"
    color = "#1a1a1a" if kind == "extends" else "#8a4b00"
    ax.annotate(
        "", xy=(x2, y2), xytext=(x1, y1),
        arrowprops=dict(arrowstyle="-|>", color=color, lw=0.9, linestyle=style,
                         shrinkA=14, shrinkB=14, alpha=0.75,
                         connectionstyle="arc3,rad=0.05"),
        zorder=3,
    )

# nodes
for name, (x, y) in positions.items():
    is_external = name in external_names
    kind = nodes.get(name, {}).get("kind", "class") if not is_external else "external"
    face = "#e0e0e0" if is_external else KIND_COLOR.get(kind, "#cfe3ff")
    box = FancyBboxPatch(
        (x - CELL_W / 2, y - CELL_H / 2), CELL_W, CELL_H,
        boxstyle="round,pad=0.02,rounding_size=0.08",
        linewidth=1.0,
        edgecolor="#555555" if is_external else "#2b2b2b",
        facecolor=face,
        linestyle="dashed" if is_external else "solid",
        zorder=4,
    )
    ax.add_patch(box)
    prefix = KIND_LABEL.get(kind, "") if not is_external else ""
    label = wrap_label(prefix + name, max_chars=20)
    ax.text(x, y, label, fontsize=6.5, ha="center", va="center", zorder=5, linespacing=1.3)

# ---- manual legend, drawn in the reserved top-left strip ----
lx0, ly0 = 0.0, 0.0  # top-left corner of the reserved strip
ax.text(lx0, ly0 - 0.05, "Legend", fontsize=11, fontweight="bold", va="top", ha="left")

legend_kinds = [
    ("class", KIND_COLOR["class"], "solid"),
    ("interface", KIND_COLOR["interface"], "solid"),
    ("enum", KIND_COLOR["enum"], "solid"),
    ("record", KIND_COLOR["record"], "solid"),
    ("vanilla / library (leaf only)", "#e0e0e0", "dashed"),
]
sw, sh = 0.5, 0.28
row_y = ly0 - 0.55
for i, (lbl, color, ls) in enumerate(legend_kinds):
    ry = row_y - i * (sh + 0.12)
    ax.add_patch(FancyBboxPatch((lx0, ry - sh), sw, sh, boxstyle="round,pad=0.01,rounding_size=0.04",
                                 linewidth=1.0, edgecolor="#2b2b2b", facecolor=color, linestyle=ls))
    ax.text(lx0 + sw + 0.15, ry - sh / 2, lbl, fontsize=8, va="center", ha="left")

line_y = row_y - len(legend_kinds) * (sh + 0.12) - 0.15
ax.plot([lx0, lx0 + sw], [line_y, line_y], color="#1a1a1a", lw=1.2, linestyle="-")
ax.text(lx0 + sw + 0.15, line_y, "extends", fontsize=8, va="center", ha="left")
line_y2 = line_y - 0.3
ax.plot([lx0, lx0 + sw], [line_y2, line_y2], color="#8a4b00", lw=1.2, linestyle="--")
ax.text(lx0 + sw + 0.15, line_y2, "implements", fontsize=8, va="center", ha="left")

ax.set_title(
    "Chest Tracker (1.21.11) — mod class diagram\n"
    f"{len([n for n in nodes])} mod types, {len(external_names)} vanilla/library leaf supertypes, {len(edges)} inheritance edges",
    fontsize=13, fontweight="bold",
)

out = BASE / "class_diagram.png"
fig.savefig(out, bbox_inches="tight")
print("wrote", out, "size", fig_w, "x", fig_h, "inches")
