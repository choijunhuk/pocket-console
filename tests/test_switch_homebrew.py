import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest


script = Path(__file__).resolve().parents[1] / "scripts/verify-switch-homebrew.py"
spec = importlib.util.spec_from_file_location("verify_switch_homebrew", script)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class HomebrewHeaderTest(unittest.TestCase):
    def test_accepts_valid_original_nro_header_and_rejects_malformed_size(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "smoke.nro"
            header = bytearray(0x80)
            header[0x10:0x14] = b"NRO0"
            struct.pack_into("<I", header, 0x18, len(header))
            path.write_bytes(header)
            self.assertEqual(verifier.verify(path), len(header))
            struct.pack_into("<I", header, 0x18, len(header) + 1)
            path.write_bytes(header)
            with self.assertRaisesRegex(ValueError, "outside"):
                verifier.verify(path)
            header[0x10:0x14] = b"BAD!"
            path.write_bytes(header)
            with self.assertRaisesRegex(ValueError, "header"):
                verifier.verify(path)


if __name__ == "__main__":
    unittest.main()
