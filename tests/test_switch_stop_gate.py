from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class StopGateTest(unittest.TestCase):
    def test_stop_during_blocked_initialization_is_sticky(self):
        with tempfile.TemporaryDirectory() as temp:
            binary = Path(temp) / "switch-stop-gate"
            subprocess.run(["c++", "-std=c++20", "-pthread", "-I",
                            str(ROOT / "switch/native"),
                            str(ROOT / "tests/native/switch_stop_gate.cpp"),
                            "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)


if __name__ == "__main__":
    unittest.main()
