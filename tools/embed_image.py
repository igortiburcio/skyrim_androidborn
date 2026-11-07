#!/usr/bin/env python3
"""Turn a Windows PE into a partial PE that the port can map without the file.

The runtime only reads the PE headers, the data sections, the import table,
the export table and the TLS directory from a module; the machine code itself
was already recompiled into gen/.  This tool writes a valid partial PE so the
released APK no longer needs the user's TESV_unpacked.exe or the three DLLs.

.text is kept by default: MSVC leaves jump tables inside the code section and
the recompiled code reads them as data (the memcpy at 0xF52E30 jumps through
a table at 0xF52F38).  --text auto only knows about the loader's own tables,
so it must not be trusted for the game; --text drop is for experiments with
LB_IMAGE_GUARD.

The partial PE is emitted as a C array (gen/image_<symbol>.c) because both
build systems already compile every gen/*.c file.

Usage:
    tools/embed_image.py GAME/TESV_unpacked.exe gen/image_exe.c --symbol exe --reloc drop
    tools/embed_image.py GAME/d3dx9_42.dll      gen/image_d3dx9.c --symbol d3dx9 --reloc keep

Sections are never dropped when the loader (or the game) still reads data
from them.  For example the import and export directories of d3dx9_42.dll
live inside its .text, so that module keeps its .text automatically.
"""
import argparse
import hashlib
import struct
import sys

PE32_MAGIC = 0x10B
SCN_CNT_CODE = 0x00000020


def align_up(value, alignment):
    if alignment <= 1:
        return value
    return (value + alignment - 1) & ~(alignment - 1)


def die(message):
    sys.stderr.write("embed_image: %s\n" % message)
    raise SystemExit(1)


class PE:
    def __init__(self, data, path):
        self.data = data
        self.path = path
        if len(data) < 0x40 or data[:2] != b"MZ":
            die("%s is not a PE file" % path)
        self.pe = struct.unpack_from("<I", data, 0x3C)[0]
        if struct.unpack_from("<I", data, self.pe)[0] != 0x00004550:
            die("%s has no PE signature" % path)
        self.nsec = struct.unpack_from("<H", data, self.pe + 6)[0]
        self.optsz = struct.unpack_from("<H", data, self.pe + 20)[0]
        self.opt = self.pe + 24
        if struct.unpack_from("<H", data, self.opt)[0] != PE32_MAGIC:
            die("%s is not PE32 (only the 32-bit modules are supported)" % path)
        self.sectab = self.opt + self.optsz
        self.image_base = struct.unpack_from("<I", data, self.opt + 28)[0]
        self.file_align = struct.unpack_from("<I", data, self.opt + 36)[0]
        self.image_size = struct.unpack_from("<I", data, self.opt + 56)[0]
        self.header_size = struct.unpack_from("<I", data, self.opt + 60)[0]
        self.entry = struct.unpack_from("<I", data, self.opt + 16)[0]
        self.sections = []
        for i in range(self.nsec):
            at = self.sectab + 40 * i
            name = data[at:at + 8].rstrip(b"\0").decode("latin-1")
            vsz, va, rsz, raw = struct.unpack_from("<IIII", data, at + 8)
            self.sections.append({"name": name, "vsz": vsz, "va": va,
                                  "rsz": rsz, "raw": raw, "index": i})
        if self.header_size < self.sectab + 40 * self.nsec:
            die("%s: SizeOfHeaders does not cover the section table" % path)

    def owner(self, rva):
        for s in self.sections:
            if s["va"] <= rva < s["va"] + max(s["vsz"], s["rsz"]):
                return s["name"]
        return "HEADERS" if rva < self.header_size else "NONE"

    def rva_to_off(self, rva):
        for s in self.sections:
            if s["va"] <= rva < s["va"] + max(s["vsz"], s["rsz"]):
                delta = rva - s["va"]
                if delta >= s["rsz"]:
                    return None
                return s["raw"] + delta
        if rva < self.header_size:
            return rva
        return None

    def u16(self, rva):
        off = self.rva_to_off(rva)
        return None if off is None else struct.unpack_from("<H", self.data, off)[0]

    def u32(self, rva):
        off = self.rva_to_off(rva)
        return None if off is None else struct.unpack_from("<I", self.data, off)[0]

    def directory(self, index):
        return struct.unpack_from("<II", self.data, self.opt + 96 + 8 * index)


def loader_references(pe, relocating):
    """RVAs whose bytes the loader reads, grouped by a human label.

    Export *function* addresses are returned separately: they point at code
    that the recompiled table handles, so the bytes do not have to be there.
    TLS addresses are VAs and are converted to RVAs.
    """
    refs = []
    code_ptrs = []

    irva, _ = pe.directory(1)  # import
    if irva:
        desc = irva
        for _ in range(256):
            oft = pe.u32(desc)
            name = pe.u32(desc + 12)
            ft = pe.u32(desc + 16)
            if not (oft or name or ft):
                break
            if oft:
                refs.append(("import lookup table", oft))
            if ft:
                refs.append(("import address table", ft))
            if name:
                refs.append(("import dll name", name))
            thunk = oft or ft
            for _ in range(65536):
                if not thunk:
                    break
                entry = pe.u32(thunk)
                if not entry:  # the table ends with a zero entry
                    break
                if not (entry & 0x80000000):
                    refs.append(("import hint/name", entry))
                thunk += 4
            desc += 20

    erva, esz = pe.directory(0)  # export
    if erva:
        nfuncs = pe.u32(erva + 20)
        nnames = pe.u32(erva + 24)
        funcs = pe.u32(erva + 28)
        names = pe.u32(erva + 32)
        ords = pe.u32(erva + 36)
        dllname = pe.u32(erva + 12)
        for label, rva in (("export functions", funcs), ("export names", names),
                           ("export ordinals", ords), ("export dll name", dllname)):
            if rva:
                refs.append((label, rva))
        for i in range(nnames or 0):
            rva = pe.u32(names + 4 * i)
            if rva:
                refs.append(("export name", rva))
        for i in range(nfuncs or 0):
            rva = pe.u32(funcs + 4 * i)
            if rva and not (erva <= rva < erva + esz):
                code_ptrs.append(("export function", rva))

    trva, _ = pe.directory(9)  # TLS
    if trva:
        refs.append(("TLS directory", trva))
        for off, label in ((0, "TLS start"), (4, "TLS end"),
                           (8, "TLS index"), (12, "TLS callbacks")):
            va = pe.u32(trva + off)
            if not va:
                continue
            rva = va - pe.image_base
            if 0 <= rva < pe.image_size:
                refs.append((label, rva))
                if off == 12:
                    for i in range(64):
                        cb = pe.u32(rva + 4 * i)
                        if not cb:
                            break
                        cb_rva = cb - pe.image_base
                        if 0 <= cb_rva < pe.image_size:
                            # A callback address is code the recompiled table
                            # handles; only the array itself is read as data.
                            code_ptrs.append(("TLS callback", cb_rva))

    rrva, _ = pe.directory(2)  # resource
    if rrva:
        refs.append(("resource directory", rrva))

    if relocating:
        rva, _ = pe.directory(5)  # base relocations
        if rva:
            refs.append(("relocation directory", rva))

    return refs, code_ptrs


def build(pe, text_mode, drop_reloc, force):
    drop = set()
    if drop_reloc:
        drop.add(".reloc")
    if text_mode in ("auto", "drop"):
        drop.add(".text")

    refs, code_ptrs = loader_references(pe, relocating=not drop_reloc)
    # .text holds code, but a few DLLs also keep their import/export and TLS
    # tables there.  Those modules must keep it; decide that automatically
    # instead of asking the caller to know each layout.
    bad = [(label, rva) for label, rva in refs if pe.owner(rva) == ".text"]
    auto_kept_text = False
    if bad and ".text" in drop:
        if text_mode == "auto" and not force:
            drop.discard(".text")
            auto_kept_text = True
        elif force:
            sys.stderr.write("embed_image: warning: forcing .text out of %s "
                             "(%d references stay behind)\n" % (pe.path, len(bad)))
        else:
            detail = ", ".join("%s@%#x" % (label, rva) for label, rva in bad[:4])
            die("%s still needs data from .text (%s); use --text auto or keep"
                % (pe.path, detail))
    bad = [(label, rva) for label, rva in refs if pe.owner(rva) in drop]
    if bad:
        detail = ", ".join("%s@%#x" % (label, rva) for label, rva in bad[:4])
        die("%s still needs data from a dropped section (%s)" % (pe.path, detail))

    for s in pe.sections:
        if s["name"] == ".bind":
            die("%s looks SteamStub-packed; run Steamless on it first" % pe.path)

    out = bytearray(pe.data[:pe.header_size])
    off = align_up(pe.header_size, pe.file_align)
    kept = 0
    report = []
    for s in pe.sections:
        at = pe.sectab + 40 * s["index"]
        if s["name"] in drop:
            struct.pack_into("<II", out, at + 16, 0, 0)
            report.append((s["name"], 0, "dropped"))
            continue
        keep = min(s["vsz"], s["rsz"]) if s["vsz"] else s["rsz"]
        if keep:
            off = align_up(off, pe.file_align)
            if len(out) < off:
                out += b"\0" * (off - len(out))
            out += pe.data[s["raw"]:s["raw"] + keep]
            struct.pack_into("<II", out, at + 16, keep, off)
            off += keep
            kept += keep
            report.append((s["name"], keep, "kept"))
        else:
            struct.pack_into("<II", out, at + 16, 0, 0)
            report.append((s["name"], 0, "empty"))
    return bytes(out), report, kept, refs, code_ptrs, auto_kept_text


def verify(pe, blob, report):
    """Re-parse the partial PE and prove it still maps the same data."""
    part = PE(blob, "generated image")
    if (part.image_base, part.image_size, part.entry) != (pe.image_base, pe.image_size, pe.entry):
        die("generated image changed ImageBase/SizeOfImage/entry")
    for s, (name, keep, state) in zip(pe.sections, report):
        got = part.sections[s["index"]]
        if (got["name"], got["va"], got["vsz"]) != (name, s["va"], s["vsz"]):
            die("generated section table changed %s" % name)
        if state == "dropped":
            if got["rsz"] or got["raw"]:
                die("dropped section %s still has raw data" % name)
            continue
        if got["rsz"] != keep:
            die("section %s has the wrong raw size" % name)
        if keep and part.data[got["raw"]:got["raw"] + keep] != pe.data[s["raw"]:s["raw"] + keep]:
            die("section %s bytes changed" % name)
    return part


def simulate(pe, blob, dropped):
    """Map the partial image like runtime/main.c does and resolve its tables.

    Catches the failure that a structural check cannot see: a directory that
    survived in the blob but whose strings or thunk arrays were dropped.
    """
    part = PE(blob, "generated image")
    mem = bytearray(part.image_base + part.image_size)
    mem[part.image_base:part.image_base + part.header_size] = blob[:part.header_size]
    for s in part.sections:
        if s["rsz"]:
            at = part.image_base + s["va"]
            mem[at:at + s["rsz"]] = blob[s["raw"]:s["raw"] + s["rsz"]]

    def u32(rva):
        at = part.image_base + rva
        if at + 4 > len(mem):
            die("simulate: read past image at %#x" % rva)
        return struct.unpack_from("<I", mem, at)[0]

    def cstr(rva, limit=256):
        at = part.image_base + rva
        end = mem.find(b"\0", at, min(at + limit, len(mem)))
        if end < 0:
            die("simulate: unterminated string at %#x" % rva)
        return bytes(mem[at:end])

    def in_section(rva):
        return part.owner(rva)

    imports = 0
    irva, _ = part.directory(1)
    if irva:
        desc = irva
        for _ in range(256):
            oft = u32(desc)
            name_rva = u32(desc + 12)
            ft = u32(desc + 16)
            if not (oft or name_rva or ft):
                break
            dll = cstr(name_rva)
            if not dll:
                die("simulate: empty import dll name at %#x" % name_rva)
            thunk = oft or ft
            for _ in range(65536):
                entry = u32(thunk)
                if not entry:
                    break
                if not (entry & 0x80000000):
                    if in_section(entry) in dropped or in_section(entry) == "NONE":
                        die("simulate: import hint/name %#x is not in mapped data" % entry)
                    if not cstr(entry + 2):
                        die("simulate: empty import name at %#x" % entry)
                imports += 1
                thunk += 4
            desc += 20

    exports = 0
    erva, _ = part.directory(0)
    if erva:
        nfuncs = u32(erva + 20)
        nnames = u32(erva + 24)
        for i in range(nnames or 0):
            rva = u32(u32(erva + 32) + 4 * i)
            if not cstr(rva):
                die("simulate: empty export name at %#x" % rva)
            exports += 1
        if nfuncs:
            u32(u32(erva + 28))

    tls = "no"
    trva, _ = part.directory(9)
    if trva:
        for off in (0, 4, 8, 12):
            va = u32(trva + off)
            if va and not (part.image_base <= va < part.image_base + part.image_size):
                die("simulate: TLS field %d points outside the image" % off)
        cb = u32(trva + 12)
        if cb:
            cb_rva = cb - part.image_base
            if in_section(cb_rva) in dropped or in_section(cb_rva) == "NONE":
                die("simulate: TLS callback array %#x is not in mapped data" % cb_rva)
            first = u32(cb_rva)
            if first:
                first_rva = first - part.image_base
                if not (0 <= first_rva < part.image_size):
                    die("simulate: TLS callback points outside the image")
        tls = "yes"

    text = next((s for s in part.sections if s["name"] == ".text"), None)
    if text and not text["rsz"]:
        at = part.image_base + text["va"]
        if any(mem[at:at + text["vsz"]]):
            die("simulate: dropped .text is not zero-filled")
    return imports, exports, tls


def emit_c(blob, symbol, source_name):
    out = []
    out.append("/* Generated by tools/embed_image.py from %s - do not edit. */\n" % source_name)
    out.append("#include <stdint.h>\n\n")
    out.append("const unsigned char g_image_%s[] = {\n" % symbol)
    for i in range(0, len(blob), 16):
        out.append("    " + "".join("0x%02x," % b for b in blob[i:i + 16]) + "\n")
    out.append("};\n")
    out.append("const unsigned int g_image_%s_len = (unsigned int)sizeof g_image_%s;\n"
               % (symbol, symbol))
    return "".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--symbol", required=True,
                    help="C symbol suffix: g_image_<symbol>, g_image_<symbol>_len")
    ap.add_argument("--text", choices=("auto", "drop", "keep"), default="keep",
                    help="keep .text (default: the lifted code reads jump tables "
                         "embedded there), drop it unless the loader reads tables "
                         "from it (auto), or always drop it (drop)")
    ap.add_argument("--reloc", choices=("keep", "drop"), default="drop",
                    help="keep the relocation directory when the module loads at a "
                         "different base (DLLs); drop it for the exe (default: drop)")
    ap.add_argument("--force", action="store_true",
                    help="allow dropping sections that still hold referenced data")
    args = ap.parse_args()

    raw = open(args.input, "rb").read()
    pe = PE(raw, args.input)
    blob, report, kept, refs, code_ptrs, auto_kept_text = build(
        pe, text_mode=args.text, drop_reloc=args.reloc == "drop",
        force=args.force)
    verify(pe, blob, report)
    imports, exports, tls = simulate(pe, blob, {name for name, _, state in report
                                                if state == "dropped"})

    with open(args.output, "w") as f:
        f.write(emit_c(blob, args.symbol, args.input.split("/")[-1]))

    print("%s -> %s" % (args.input, args.output))
    for name, size, state in report:
        print("  %-10s %9d  %s" % (name, size, state))
    if auto_kept_text:
        print("  .text kept: the import/export/TLS tables live inside it")
    print("  input %d, image %d bytes (%.1f%%), sha1 %s"
          % (len(raw), len(blob), 100.0 * len(blob) / len(raw),
             hashlib.sha1(blob).hexdigest()[:12]))
    print("  loader simulation: %d imports, %d exports, TLS %s"
          % (imports, exports, tls))
    if code_ptrs:
        print("  %d code addresses point into .text (fine: the recompiled table "
              "handles them)" % len(code_ptrs))


if __name__ == "__main__":
    main()
