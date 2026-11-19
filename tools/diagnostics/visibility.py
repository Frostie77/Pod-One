"""Locate candidate Racer camera and scene-visibility routines in an existing build."""
from collections import Counter, defaultdict
from pathlib import Path
import re
import sys

# N64Recomp emits each instruction address and its disassembly in a C comment.
FUNCTION = re.compile(r"^\s*RECOMP_FUNC\s+void\s+(\w+)\s*\(")
INSTRUCTION = re.compile(r"^\s*//\s*0x([0-9a-fA-F]{8}):\s*(\S+)\s*(.*?)\s*$")
MEMORY = re.compile(r"(-?(?:0x[\da-f]+|\d+))\((\w+)\)$")
PROJECT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = PROJECT / "build" / "Pod-One-Visibility.txt"


def number(text):
    try:
        return int(text, 0)
    except ValueError:
        return None


def read_build(directory):
    symbols = {}
    instructions = {}
    for path in sorted(directory.glob("funcs_*.c")):
        with path.open(encoding="utf-8") as src:
            name = None
            for line in src:
                fn = FUNCTION.match(line)
                if fn:
                    name = fn[1]
                    continue
                match = INSTRUCTION.match(line)
                if not match:
                    continue
                pc = int(match[1], 16)
                if name is not None:
                    symbols[pc] = name
                    name = None
                args = match[3].partition("#")[0].lower().replace("$", "").strip()
                instructions[pc] = (match[2].lower(), [x.strip() for x in args.split(",") if x.strip()])
    if not instructions:
        raise ValueError(f"No N64 instructions found in {directory}. Run the Pod One build first.")

    # A generated C body can also contain several original leaf routines.
    starts = set(symbols)
    for op, args in instructions.values():
        if op == "jal" and args:
            target = number(args[0])
            if target in instructions:
                starts.add(target)
    functions = defaultdict(list)
    current = None
    for pc in sorted(instructions):
        if pc in starts or current is None:
            current = pc
        functions[current].append((pc, *instructions[pc]))
    return symbols, functions


def inspect(rows):
    calls, memory, constants, targets = [], [], [], []
    sums, differences = set(), set()
    ops = Counter()
    for pc, op, args in rows:
        ops[op] += 1
        if not args:
            continue
        if op in {"add.s", "add.d", "sub.s", "sub.d"} and len(args) == 3:
            pair = tuple(sorted(args[1:]))
            (sums if op.startswith("add.") else differences).add(pair)
        imm = number(args[-1])
        if op == "jal" and imm is not None:
            calls.append((pc, imm))
        elif op in {"li", "lui", "ori", "addiu", "addi", "andi", "sltiu"} and imm is not None:
            constants.append((pc, op, imm, args[0]))
        elif op.startswith(("b", "j")) and args[-1].startswith("l_"):
            try:
                targets.append(int(args[-1][2:], 16))
            except ValueError:
                pass
        match = MEMORY.fullmatch(args[-1])
        if match and match[2] not in {"sp", "29"}:
            memory.append((pc, op, number(match[1]), match[2], args[0]))
    upper = {value for _, op, value, _ in constants if op == "lui"}
    immediates = {value for _, _, value, _ in constants}
    reads = defaultdict(set)
    stores = defaultdict(set)
    for _, op, offset, base, _ in memory:
        if op in {"lwc1", "ldc1", "lw"}:
            reads[base].add(offset)
        elif op in {"swc1", "sdc1", "sw"}:
            stores[base].add(offset)
    fp_compare = sum(count for op, count in ops.items() if op.startswith("c."))
    fp_mul = ops["mul.s"] + ops["mul.d"]
    fp_div = ops["div.s"] + ops["div.d"]
    sh = ops["sh"]
    # 146 and 876 are the N64 depth scale and offset in the supplied race frames.
    viewport = 7 * ({146, 876} <= immediates) + 2 * (sh >= 2) + (146 in immediates or 876 in immediates)
    # Upper halfwords of IEEE-754 5, 45, 90, 2500 and 5000.
    camera = 3 * len(upper & {0x40a0, 0x42b4, 0x451c, 0x459c}) + (0x4234 in upper)
    camera += (0x3f80 in upper and 0xbf80 in upper) + (len(memory) >= 8)
    matrix = 4 * bool(sums & differences) + 2 * min(fp_div, 4) + 2 * bool(ops["sub.s"] or ops["sub.d"]) + bool(fp_mul) + min(3, max((len(s) // 4 for s in stores.values()), default=0))
    # Box tests commonly read packed min/max XYZ or compute signed clip outcodes.
    box = any(set(range(off, off + 24, 4)) <= fields for fields in reads.values() for off in fields)
    mask_bits = len(immediates & {1, 2, 4, 8, 16, 32})
    cull = 3 * box + min(4, fp_compare) + 2 * (fp_mul >= 3) + min(3, mask_bits) + bool(targets)
    return dict(calls=calls, memory=memory, constants=constants, ops=ops, reads=reads, stores=stores,
                viewport=viewport, camera=camera, matrix=matrix, cull=cull, compare=fp_compare, near_far_pairs=sums & differences)


def make_report(directory):
    symbols, funcs = read_build(directory)
    info = {entry: inspect(rows) for entry, rows in funcs.items()}
    callers = defaultdict(set)
    for entry, data in info.items():
        for _, target in data["calls"]:
            callers[target].add(entry)
    sections = {}
    # Text and RSP data may be included at the end of the recompilation section.
    # Only rank routines with a symbol or a direct call to their entry point.
    def ranked(key, minimum, limit, seeds=()):
        def score(entry):
            data = info[entry]
            connected = bool(callers[entry].intersection(seeds) or {t for _, t in data["calls"]}.intersection(seeds))
            return data[key] + 2 * connected
        return sorted((a for a in info if info[a][key] >= minimum and (a in symbols or callers[a])),
                      key=lambda a: (-score(a), a))[:limit]

    sections["Viewport depth constants"] = ranked("viewport", 8, 8)
    sections["Camera initialization"] = ranked("camera", 6, 12, sections["Viewport depth constants"])
    seeds = set(sections["Viewport depth constants"] + sections["Camera initialization"])
    camera_neighbors = seeds | {c for a in seeds for c in callers[a]}
    sections["Perspective matrix candidates"] = ranked("matrix", 8, 16, camera_neighbors)
    sections["Box and outcode candidates"] = ranked("cull", 9, 24, camera_neighbors)
    # Some AABB checks loop over planes and have just one static comparison.
    # Keep these separately from unrolled tests with many comparisons.
    loop_tests = []
    for entry, rows in funcs.items():
        d = info[entry]
        backward = any(args and args[-1].startswith("l_") and
                       re.fullmatch(r"l_[0-9a-f]{8}", args[-1]) and int(args[-1][2:], 16) < pc
                       for pc, op, args in rows if op.startswith("b"))
        if backward and d["compare"] and d["ops"]["mul.s"] >= 3 and len(rows) <= 320:
            loop_tests.append(entry)
    sections["Looped plane-test candidates"] = sorted(loop_tests, key=lambda a: (-info[a]["cull"], a))[:16]
    roots = list(dict.fromkeys(a for group in sections.values() for a in group))
    # Include neighbors to locate parameter setters and traversal callers even
    # when they only forward a value and perform no matrix math themselves.
    projection_roots = {a for a in sections["Perspective matrix candidates"] if info[a]["near_far_pairs"]}
    adjacent = {c for a in seeds | projection_roots for c in callers[a]}
    adjacent.update(t for a in seeds for _, t in info[a]["calls"] if t in info)
    neighbors = {t for a in adjacent for _, t in info[a]["calls"] if t in info}
    relevant = set(roots) | adjacent | neighbors
    lines = ["Pod One visibility analysis", f"{len(funcs)} guest routines, {sum(map(len, funcs.values()))} instruction addresses.",
             "Static candidates. A match alone does not establish what runs in a race."]
    for label, entries in sections.items():
        lines += ["", label, " ".join(f"{a:08X}" for a in entries) or "(none)"]
    lines += ["", "Candidate and neighboring routines"]
    for a in sorted(relevant):
        d = info[a]
        lines += [f"\n{a:08X} {symbols.get(a, '(direct call target)')} words={len(funcs[a])}",
                  "called by " + " ".join(f"{c:08X}" for c in sorted(callers[a])),
                  "calls " + " ".join(f"{pc:08X}>{t:08X}" for pc,t in d["calls"])]
        for key in ("reads", "stores"):
            for base, fields in sorted(d[key].items()):
                lines.append(f"{key} {base} " + " ".join(hex(f) for f in sorted(fields)))
        if a in roots or a in adjacent:
            # Record projection constants, field accesses and comparisons with
            # their instruction sites. Omit code bodies, assets and ROM bytes.
            for pc, op, value, dest in d["constants"]:
                if (op == "lui" and value in {0x3f00, 0x3f80, 0x4000, 0x40a0, 0x4120, 0x4234, 0x42b4, 0x447a, 0x451c, 0x459c, 0xbf80}) or value in (146, 876):
                    lines.append(f"constant {pc:08X} {op} {dest} {value:#x}")
            for pc, op, offset, base, reg in d["memory"]:
                if op in {"lwc1", "swc1", "ldc1", "sdc1", "sh"}:
                    lines.append(f"field {pc:08X} {op} {reg} {base} {offset:#x}")
            for pc, op, args in funcs[a]:
                if (op.startswith(("c.", "bc1")) or op in {"div.s", "div.d", "mtc1"}
                        or a in sections["Perspective matrix candidates"] and op in {"add.s", "sub.s", "mul.s", "add.d", "sub.d", "mul.d"}):
                    lines.append(f"math {pc:08X} {op} " + " ".join(args))
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    output = Path(sys.argv[1]) if len(sys.argv) == 2 else DEFAULT_OUTPUT
    try:
        report = make_report(PROJECT / "RecompiledFuncs")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(report, encoding="utf-8")
        print(f"Ready: {output}")
    except (OSError, ValueError) as exc:
        sys.exit(str(exc))
