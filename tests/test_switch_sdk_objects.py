import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/build-switch-sdk-objects.py"
spec = importlib.util.spec_from_file_location("switch_objects", SCRIPT)
objects = importlib.util.module_from_spec(spec)
spec.loader.exec_module(objects)


class SwitchObjectsTest(unittest.TestCase):
    def test_selects_switch_one_objects_without_accepting_switch_two_outputs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            own = root / "source"
            build = root / "build"
            own.mkdir()
            (build / "pocket-switch-sdk").mkdir(parents=True)
            entries = []
            for name in sorted(objects.OWNED):
                source = own / name
                source.write_text("void f() {}\n")
                entries.append({"file": str(source), "directory": str(build),
                                "command": f"c++ -o CMakeFiles/pocket_switch.dir/{name}.o -c {source}"})
            native = build / "pocket-switch-sdk/native.cpp"
            native.write_text("void g() {}\n")
            entries.append({"file": str(native), "directory": str(build),
                            "command": f"c++ -o CMakeFiles/pocket_switch.dir/native.cpp.o -c {native}"})
            commands = root / "compile_commands.json"
            commands.write_text(json.dumps(entries))
            self.assertEqual(len(objects.object_targets(commands, own, build, "pocket_switch")), 5)
            with self.assertRaisesRegex(ValueError, "outside Pocket Console"):
                objects.object_targets(commands, own, build)
            ninja = root / "ninja"
            ninja.write_text("#!/bin/sh\nexit 0\n")
            ninja.chmod(0o755)
            subprocess.run([sys.executable, str(SCRIPT), str(commands), str(own),
                            str(build), str(ninja), "pocket_switch"], check=True)

    def test_selects_owned_objects_and_rejects_foreign_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            own = root / "source"
            build = root / "build"
            own.mkdir()
            (build / "pocket-switch-sdk").mkdir(parents=True)
            entries = []
            for name in sorted(objects.OWNED):
                source = own / name
                source.write_text("void f() {}\n")
                output = f"CMakeFiles/pocket_switch2.dir/{name}.o"
                entries.append({"file": str(source), "directory": str(build),
                                "command": f"c++ -o {output} -c {source}"})
            native = build / "pocket-switch-sdk/native.cpp"
            native.write_text("void g() {}\n")
            entries.append({"file": str(native), "directory": str(build),
                            "command": f"c++ -o CMakeFiles/pocket_switch2.dir/native.cpp.o -c {native}"})
            commands = root / "compile_commands.json"
            commands.write_text(json.dumps(entries))
            selected = objects.object_targets(commands, own, build)
            self.assertEqual(len(selected), 5)
            entries[0]["command"] = f"c++ -o {root / 'outside.o'} -c {own / sorted(objects.OWNED)[0]}"
            commands.write_text(json.dumps(entries))
            with self.assertRaisesRegex(ValueError, "outside Pocket Console target"):
                objects.object_targets(commands, own, build)


if __name__ == "__main__":
    unittest.main()
