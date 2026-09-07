#!/usr/bin/env python3
"""Exercise the semantic anchor resolver against actual javac/javap output."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


RESOLVER = Path(__file__).with_name("anchor_resolve.py")


@unittest.skipUnless(shutil.which("javac") and shutil.which("javap"), "JDK tools required")
class AnchorResolverTest(unittest.TestCase):
    def resolve(self, body, debug=True):
        source = (
            "public class AnchorFixture {\n"
            "  public void flush() {\n"
            "    createCheckpoint();\n"
            + body
            + "  }\n"
            "  private void createCheckpoint() {}\n"
            "  private void refresh() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory(prefix="archive-anchor-") as directory:
            path = Path(directory) / "AnchorFixture.java"
            path.write_text(source, encoding="utf-8")
            subprocess.run(
                ["javac", "-g:lines" if debug else "-g:none", str(path)],
                check=True, capture_output=True, text=True,
            )
            environment = dict(os.environ, HSA_NAME="fixture.flush", HSA_JAR=directory,
                               HSA_FILE=str(path), HSA_REL=path.name,
                               HSA_CLASS="AnchorFixture", HSA_METHOD="flush",
                               HSA_AFTER=r"createCheckpoint\(\);", HSA_MATCH=r"refresh\(\);")
            result = subprocess.run(
                [sys.executable, str(RESOLVER)], env=environment,
                check=True, capture_output=True, text=True,
            )
        return result.stdout.strip()

    def test_resolves_current_jdk_line_table(self):
        self.assertEqual(
            "OK class=AnchorFixture jarline=4 srcline=4 delta=0 "
            "src=AnchorFixture.java owners=flush",
            self.resolve("    refresh();\n"),
        )

    def test_resolves_compiler_generated_lambda_owner(self):
        self.assertEqual(
            "OK class=AnchorFixture jarline=4 srcline=4 delta=0 "
            "src=AnchorFixture.java owners=flush,lambda$flush$0",
            self.resolve("    Runnable action = () -> refresh();\n    action.run();\n"),
        )

    def test_rejects_missing_debug_line_table(self):
        result = self.resolve("    refresh();\n", debug=False)
        self.assertTrue(result.startswith("FAIL descriptor=fixture.flush "), result)
        self.assertIn("empty LineNumberTable", result)

    def test_rejects_ambiguous_source_statement(self):
        result = self.resolve("    refresh();\n    refresh();\n")
        self.assertTrue(result.startswith("FAIL descriptor=fixture.flush "), result)
        self.assertIn("matched 2 lines", result)


if __name__ == "__main__":
    unittest.main()
