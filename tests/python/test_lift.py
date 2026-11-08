"""Unit tests for the lifter helpers (tools/lift.py), without a game file."""
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import capstone  # noqa: E402
from capstone import x86  # noqa: E402

import lift  # noqa: E402

BASE = 0x400000


def decoder():
    md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    md.detail = True
    return md


def instruction(md, code, base=BASE):
    insns = list(md.disasm(code, base))
    if len(insns) != 1:
        raise AssertionError("expected one instruction, got %d" % len(insns))
    return insns[0]


class ImportNameTest(unittest.TestCase):
    def test_a_direct_iat_slot_returns_the_name(self):
        self.assertEqual(
            lift.import_name({0x5000: "CreateFileA"}, b"", BASE, 0x5000),
            "CreateFileA")

    def test_a_jmp_iat_stub_is_followed(self):
        image = bytearray(0x2000)
        stub = BASE + 0x1000
        image[0x1000:0x1006] = b"\xff\x25\x00\x50\x40\x00"  # jmp [0x405000]

        self.assertEqual(
            lift.import_name({0x405000: "Sleep"}, bytes(image), BASE, stub),
            "Sleep")

    def test_unknown_slots_and_out_of_range_addresses_are_empty(self):
        self.assertEqual(lift.import_name({}, b"\x00" * 32, BASE, BASE), "")
        self.assertEqual(lift.import_name({}, b"\x00" * 32, BASE, 0x100), "")

        # a stub whose slot is not in the table
        image = b"\xff\x25\x00\x60\x40\x00" + b"\x00" * 16
        self.assertEqual(lift.import_name({}, image, BASE, BASE), "")


class DecodeBytesTest(unittest.TestCase):
    def test_decodes_from_a_guest_address(self):
        md = decoder()
        code = b"\xb8\x01\x00\x00\x00\x01\xc8"  # mov eax, 1 ; add eax, ecx

        insns = lift.decode_bytes(md, code, BASE, BASE, BASE + len(code))

        self.assertEqual([i.mnemonic for i in insns], ["mov", "add"])
        self.assertEqual(insns[0].operands[0].reg, x86.X86_REG_EAX)
        self.assertEqual(insns[0].operands[1].imm, 1)
        self.assertEqual(insns[1].operands[1].reg, x86.X86_REG_ECX)

    def test_a_slice_starts_at_the_requested_address(self):
        md = decoder()
        code = b"\xb8\x01\x00\x00\x00\x01\xc8"

        insns = lift.decode_bytes(md, code, BASE, BASE + 5, BASE + 7)

        self.assertEqual([i.mnemonic for i in insns], ["add"])
        self.assertEqual(insns[0].address, BASE + 5)


class StackOpTest(unittest.TestCase):
    def setUp(self):
        self.md = decoder()

    def test_esp_operands_are_the_thread_stack(self):
        i = instruction(self.md, b"\x8b\x44\x24\x04")  # mov eax, [esp+4]
        self.assertTrue(lift.stack_op(i, i.operands[1], False))
        self.assertTrue(lift.stack_op(i, i.operands[1], True))

    def test_ebp_is_the_stack_only_inside_a_frame(self):
        i = instruction(self.md, b"\x8b\x45\x04")  # mov eax, [ebp+4]
        self.assertFalse(lift.stack_op(i, i.operands[1], False))
        self.assertTrue(lift.stack_op(i, i.operands[1], True))

    def test_fs_operands_are_the_teb(self):
        i = instruction(self.md, b"\x64\xa1\x00\x00\x00\x00")  # mov eax, fs:[0]
        self.assertTrue(lift.stack_op(i, i.operands[1], False))

    def test_other_memory_is_not_the_stack(self):
        i = instruction(self.md, b"\x8b\x00")  # mov eax, [eax]
        self.assertFalse(lift.stack_op(i, i.operands[1], False))


class SharedLoadTest(unittest.TestCase):
    def setUp(self):
        self.md = decoder()
        self.old = lift.MEMORDER
        lift.MEMORDER = True

    def tearDown(self):
        lift.MEMORDER = self.old

    def test_a_plain_load_is_shared(self):
        i = instruction(self.md, b"\x8b\x01")  # mov eax, [ecx]
        self.assertTrue(lift.shared_load(i, False))

    def test_stack_teb_and_lea_loads_are_not_shared(self):
        i = instruction(self.md, b"\x8b\x44\x24\x04")  # [esp+4]
        self.assertFalse(lift.shared_load(i, False))

        i = instruction(self.md, b"\x64\xa1\x00\x00\x00\x00")  # fs:[0]
        self.assertFalse(lift.shared_load(i, False))

        i = instruction(self.md, b"\x8d\x01")  # lea eax, [ecx]
        self.assertFalse(lift.shared_load(i, False))

        i = instruction(self.md, b"\x8b\xc1")  # mov eax, ecx
        self.assertFalse(lift.shared_load(i, False))

    def test_nothing_is_shared_when_ordering_is_off(self):
        lift.MEMORDER = False
        i = instruction(self.md, b"\x8b\x01")
        self.assertFalse(lift.shared_load(i, False))


class MemOrderTest(unittest.TestCase):
    def setUp(self):
        self.md = decoder()
        self.old = lift.MEMORDER
        lift.MEMORDER = True

    def tearDown(self):
        lift.MEMORDER = self.old

    def test_a_store_to_shared_memory_gets_a_release_fence(self):
        i = instruction(self.md, b"\x89\x01")  # mov [ecx], eax
        self.assertEqual(lift.mem_order(i, ["body"]), ["ST_FENCE();", "body"])

    def test_a_store_to_the_stack_does_not(self):
        i = instruction(self.md, b"\x89\x04\x24")  # mov [esp], eax
        self.assertEqual(lift.mem_order(i, ["body"]), ["body"])

    def test_locked_and_xchg_operations_are_full_fences(self):
        i = instruction(self.md, b"\xf0\x01\x01")  # lock add [ecx], eax
        self.assertEqual(lift.mem_order(i, ["body"]),
                         ["FULL_FENCE();", "body", "FULL_FENCE();"])

        i = instruction(self.md, b"\x87\x01")  # xchg [ecx], eax
        self.assertEqual(lift.mem_order(i, ["body"]),
                         ["FULL_FENCE();", "body", "FULL_FENCE();"])

    def test_ordering_off_leaves_the_body_alone(self):
        lift.MEMORDER = False
        i = instruction(self.md, b"\x89\x01")
        lines = ["body"]
        self.assertIs(lift.mem_order(i, lines), lines)


class WriteIfChangedTest(unittest.TestCase):
    def test_only_rewrites_when_the_text_differs(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "out.c")

            def read():
                with open(path) as f:
                    return f.read()

            lift.write_if_changed(path, "a")
            self.assertEqual(read(), "a")

            lift.write_if_changed(path, "a")
            self.assertEqual(read(), "a")

            lift.write_if_changed(path, "b")
            self.assertEqual(read(), "b")


class ModuleImportTest(unittest.TestCase):
    def test_importing_the_lifter_does_not_need_arguments_or_a_game_file(self):
        self.assertIsNone(lift.EXE)
        self.assertIsInstance(lift.md, capstone.Cs)
        self.assertEqual(lift.BASE, 0)
        self.assertEqual(lift.img, b"")


if __name__ == "__main__":
    unittest.main()
