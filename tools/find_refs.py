#!/usr/bin/env python3
"""Finds where the game image uses a string, so the lifted C can be read.

The recompiler emits string addresses as immediates (`0x10A5430u`), and the
image is embedded from the user's own executable, so a string that the engine
paths through (a FaceGen path, for example) leads straight to the generated
function that builds it:

    tools/find_refs.py GAME/TESV_unpacked.exe FaceTint FaceGenData

Each argument is an ASCII substring (or a 0x-prefixed guest address). For every
match the tool prints the string's guest address and each `gen/*.c` line that
loads it, with the enclosing lifted function, so `gen/` can be read even after
`tools/regen_gen.sh` moves everything around.

Only the local executable is read; no game data leaves the machine.
"""
import glob
import os
import struct
import sys

IMAGE_BASE, SECTIONS = 0, []


def load(path):
    """Reads the section table so a file offset can be turned into a VA."""
    global IMAGE_BASE, SECTIONS
    with open(path, "rb") as f:
        data = f.read()
    if data[:2] != b"MZ":
        raise SystemExit("%s is not a PE file" % path)
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise SystemExit("%s is not a PE file" % path)
    coff = pe + 4
    count = struct.unpack_from("<H", data, coff + 2)[0]
    opt = coff + 20
    opt_size = struct.unpack_from("<H", data, coff + 16)[0]
    IMAGE_BASE = struct.unpack_from("<I", data, opt + 28)[0]
    SECTIONS = []
    for i in range(count):
        o = opt + opt_size + 40 * i
        vsize, vaddr, rsize, raddr = struct.unpack_from("<IIII", data, o + 8)
        SECTIONS.append((vaddr, vsize, raddr, rsize))
    return data


def va_of(data, offset):
    for vaddr, vsize, raddr, rsize in SECTIONS:
        if raddr <= offset < raddr + rsize:
            return IMAGE_BASE + vaddr + (offset - raddr)
    return None


def data_at(data, va, limit=120):
    rva = va - IMAGE_BASE
    for vaddr, vsize, raddr, rsize in SECTIONS:
        if vaddr <= rva < vaddr + rsize:
            start = raddr + rva - vaddr
            chunk = data[start:start + limit].split(b"\0")[0]
            return "".join(chr(c) if 32 <= c < 127 else "." for c in chunk)
    return ""


def string_head(data, va):
    """The address where the string that contains `va` starts."""
    rva = va - IMAGE_BASE
    for vaddr, vsize, raddr, rsize in SECTIONS:
        if vaddr <= rva < vaddr + rsize:
            at = raddr + rva - vaddr
            while at > raddr and data[at - 1] != 0:
                at -= 1
            return IMAGE_BASE + vaddr + (at - raddr)
    return va


def functions_in(path):
    """Maps the line number of every lifted function header."""
    starts, name = [], ""
    with open(path, "r", errors="replace") as f:
        for number, line in enumerate(f, 1):
            if line.startswith("void f_") and line.rstrip().endswith("(CPU *c) {"):
                name = line.split("(")[0][7:]
                starts.append((number, name))
    return starts


def enclosing(starts, line):
    lo, hi = 0, len(starts) - 1
    found = "?"
    while lo <= hi:
        mid = (lo + hi) // 2
        if starts[mid][0] <= line:
            found = starts[mid][1]
            lo = mid + 1
        else:
            hi = mid - 1
    return found


def strings(data, needle):
    out, start = [], 0
    low = needle.lower().encode()
    hay = data.lower()
    while True:
        at = hay.find(low, start)
        if at < 0:
            return out
        va = va_of(data, at)
        if va is not None:
            out.append(va)
        start = at + 1


def refs(gen, va):
    """Every `0xVA` immediate in gen/*.c, with its function."""
    needle = "0x%Xu" % va
    hits = []
    for path in sorted(glob.glob(os.path.join(gen, "*.c"))):
        starts = None
        with open(path, "r", errors="replace") as f:
            for number, line in enumerate(f, 1):
                if needle in line:
                    if starts is None:
                        starts = functions_in(path)
                    hits.append((path, number, enclosing(starts, number)))
    return hits


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    exe = argv[0]
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    gen = os.environ.get("GEN_DIR", os.path.join(root, "gen"))
    data = load(exe)
    for arg in argv[1:]:
        if arg.lower().startswith("0x"):
            vas = [int(arg, 16)]
        else:
            vas = sorted(set(strings(data, arg)))
        if not vas:
            print("%s: not in the image" % arg)
            continue
        for va in vas:
            text = data_at(data, va)
            print("%s: 0x%X %r" % (arg, va, text))
            found = refs(gen, va)
            head = string_head(data, va)
            if not found and head != va:
                print("    (that address is the middle of the string; the start is 0x%X)" % head)
                found = refs(gen, head)
            if not found:
                print("    (no immediate reference in %s; the address may be read through a table)" % gen)
            for path, line, fn in found:
                print("    %s:%d in f_%s" % (path, line, fn))


if __name__ == "__main__":
    main(sys.argv[1:])
