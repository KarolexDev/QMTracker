"""
Parse all Java source files under src/client/java for ChestTracker and emit a
class-inheritance diagram (extends/implements). External (vanilla/library)
supertypes are included only as leaf nodes when a mod class inherits them.
"""
import re
from pathlib import Path

DOC_DIR = Path(__file__).resolve().parent
ROOT = DOC_DIR.parent / "src" / "client" / "java"
OUT_PNG = DOC_DIR / "class_diagram.png"

TYPE_DECL_RE = re.compile(
    r'(?P<mods>(?:(?:public|private|protected|abstract|final|static|sealed|non-sealed|strictfp)\s+)*)'
    r'(?P<kind>class|interface|enum|record)\s+'
    r'(?P<name>\w+)'
    r'(?:\s*<[^>{]*>)?'
    r'(?:\s*\([^)]*\))?'  # record components
    r'(?P<rest>[^{]*)\{',
)
EXTENDS_RE = re.compile(r'\bextends\s+([\w.]+(?:\s*<[^>]*>)?(?:\s*,\s*[\w.]+(?:\s*<[^>]*>)?)*)')
IMPLEMENTS_RE = re.compile(r'\bimplements\s+([\w.]+(?:\s*<[^>]*>)?(?:\s*,\s*[\w.]+(?:\s*<[^>]*>)?)*)')
PACKAGE_RE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.MULTILINE)
IMPORT_RE = re.compile(r'^\s*import\s+(?:static\s+)?([\w.]+)\s*;', re.MULTILINE)

STRING_OR_COMMENT_RE = re.compile(
    r'"(?:\\.|[^"\\])*"'      # string literals
    r'|\'(?:\\.|[^\'\\])*\''  # char literals
    r'|//[^\n]*'              # line comments
    r'|/\*.*?\*/',            # block comments (incl. javadoc)
    re.DOTALL,
)

def strip_comments_and_strings(text):
    def repl(m):
        s = m.group(0)
        if s.startswith('"') or s.startswith("'"):
            return s  # keep string/char literals verbatim (harmless for our regexes)
        return '\n' * s.count('\n')  # comments -> blank (preserve line count)
    return STRING_OR_COMMENT_RE.sub(repl, text)

def strip_generics(s):
    return re.sub(r'<[^>]*>', '', s).strip()

def split_types(s):
    # split on commas not inside generics
    parts, depth, cur = [], 0, ''
    for ch in s:
        if ch == '<':
            depth += 1
        elif ch == '>':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return [strip_generics(p) for p in parts if strip_generics(p)]

nodes = {}  # simple_name -> dict(kind, package, file)
edges = []  # (child, parent, kind)  kind = 'extends' | 'implements'
name_to_fqn = {}  # simple name -> fully qualified (for mod classes, best-effort)

java_files = list(ROOT.rglob("*.java"))
print(f"Found {len(java_files)} java files")

file_infos = []
for f in java_files:
    text = f.read_text(encoding="utf-8", errors="replace")
    text = strip_comments_and_strings(text)
    pkg_m = PACKAGE_RE.search(text)
    package = pkg_m.group(1) if pkg_m else ""
    imports = IMPORT_RE.findall(text)
    file_infos.append((f, text, package, imports))

# Pass 1: collect all declared type names (mod classes), including nested types.
for f, text, package, imports in file_infos:
    for m in TYPE_DECL_RE.finditer(text):
        name = m.group("name")
        kind = m.group("kind")
        if name in nodes:
            continue
        nodes[name] = {"kind": kind, "package": package, "file": f.name}
        name_to_fqn[name] = f"{package}.{name}"

print(f"Discovered {len(nodes)} mod type declarations")

# Pass 2: collect extends/implements relationships per declared type.
# We match each type declaration block header individually.
for f, text, package, imports in file_infos:
    for m in TYPE_DECL_RE.finditer(text):
        name = m.group("name")
        rest = m.group("rest") or ""
        ext_m = EXTENDS_RE.search(rest)
        impl_m = IMPLEMENTS_RE.search(rest)
        if ext_m:
            for parent in split_types(ext_m.group(1)):
                parent_simple = parent.split(".")[-1]
                edges.append((name, parent_simple, "extends"))
        if impl_m:
            for parent in split_types(impl_m.group(1)):
                parent_simple = parent.split(".")[-1]
                edges.append((name, parent_simple, "implements"))

print(f"Discovered {len(edges)} inheritance edges (pre-filter)")

# Classify parent nodes as mod or external; keep external ones only as leaves.
external_nodes = set()
final_edges = []
for child, parent, kind in edges:
    if child == parent:
        continue
    # sanity: child must be a real declared mod type (drops regex false
    # positives like generic bounds e.g. "<E extends Enum<E>>" misparsed
    # as a top-level type declaration), and both names must look like
    # real Java type identifiers (capitalized).
    if child not in nodes:
        continue
    if not re.match(r'^[A-Z]\w*$', parent):
        continue
    if parent in nodes:
        final_edges.append((child, parent, kind))
    else:
        # external (vanilla/library) leaf node - keep, no further expansion
        external_nodes.add(parent)
        final_edges.append((child, parent, kind))

print(f"External (vanilla/library) leaf supertypes referenced: {len(external_nodes)}")
print(f"Final edges: {len(final_edges)}")

# Persist intermediate data for the plotting step
import json
data = {
    "nodes": nodes,
    "external_nodes": sorted(external_nodes),
    "edges": final_edges,
}
with open(Path(OUT_PNG.parent, "class_diagram_data.json"), "w", encoding="utf-8") as fh:
    json.dump(data, fh, indent=1)

print("Wrote class_diagram_data.json")
