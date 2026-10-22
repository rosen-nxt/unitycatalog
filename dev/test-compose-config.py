"""Check the local Compose contract without requiring a running Docker daemon."""

import json
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parent.parent


class ComposeConfigTest(unittest.TestCase):
    def test_shared_path_must_be_provided(self):
        env = dict(os.environ)
        env.pop("UC_SHARED_DATA_DIR", None)
        result = subprocess.run(
            ["docker", "compose", "config", "--format", "json"],
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("UC_SHARED_DATA_DIR", result.stderr)

    def test_shared_paths_and_persistent_server_state(self):
        shared = ROOT / ".compose" / "data"
        env = dict(os.environ, UC_SHARED_DATA_DIR=str(shared), UC_HOST_UID="1234", UC_HOST_GID="1235")
        result = subprocess.run(
            ["docker", "compose", "config", "--format", "json"],
            cwd=ROOT,
            env=env,
            check=True,
            capture_output=True,
            text=True,
        )
        server = json.loads(result.stdout)["services"]["server"]
        self.assertEqual(server["build"]["args"], {"UC_HOST_UID": "1234", "UC_HOST_GID": "1235"})
        self.assertEqual(server["environment"]["storage-root.tables"], f"file:{shared}/tables")
        self.assertEqual(server["environment"]["storage-root.models"], f"file:{shared}/models")
        self.assertIn(
            {"type": "volume", "source": "unitycatalog_state", "target": "/home/unitycatalog/etc"},
            server["volumes"],
        )
        self.assertIn(
            {
                "type": "bind",
                "source": str(shared),
                "target": str(shared),
                "bind": {"create_host_path": False},
            },
            server["volumes"],
        )

    def test_runtime_files_are_not_in_image_context(self):
        excludes = (ROOT / ".dockerignore").read_text().splitlines()
        self.assertIn("etc/db/*", excludes)
        self.assertIn("etc/data/**", excludes)


if __name__ == "__main__":
    unittest.main()
