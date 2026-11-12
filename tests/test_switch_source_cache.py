import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "switch_source_cache", ROOT / "scripts/verify-switch-source-cache.py")
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class SourceCacheTest(unittest.TestCase):
    def test_accepts_public_git_config_and_rejects_credentials(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            git = source / ".git"
            git.mkdir()
            config = git / "config"
            config.write_text('[remote "origin"]\n\turl = https://github.com/pavelgajdo/Hokkaido.git\n')
            self.assertEqual(cache.verify(source), 1)
            config.write_text('[remote "origin"]\n\turl = https://token@github.com/example/repo.git\n')
            with self.assertRaisesRegex(ValueError, "credential material"):
                cache.verify(source)
            config.write_text('[http "https://github.com"]\n\textraheader = AUTHORIZATION: bearer secret\n')
            with self.assertRaisesRegex(ValueError, "credential material"):
                cache.verify(source)
            config.write_text('[remote "origin"]\n\turl = https://github.com/pavelgajdo/Hokkaido.git\n')
            build_config = source / "build/_deps/example-src/.git/config"
            build_config.parent.mkdir(parents=True)
            build_config.write_text('[remote "origin"]\n\turl = https://secret@github.com/example/repo.git\n')
            with self.assertRaisesRegex(ValueError, "credential material"):
                cache.verify(source, source / "build")


if __name__ == "__main__":
    unittest.main()
