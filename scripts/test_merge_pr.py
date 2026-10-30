"""Unit tests for the pure parts of merge_pr.py (no gh, no network). Run: python -m unittest discover -s scripts"""

import argparse
import unittest

import merge_pr

HEAD = "49482f6ff2212b2e39088e3a2b05f6f291a1af4b"


class SensitivePathTest(unittest.TestCase):
    def test_sensitive(self):
        for path in (".github/workflows/ci.yml", ".github/CODEOWNERS", "mvnw", "mvnw.cmd",
                     ".mvn/wrapper/maven-wrapper.properties", "pom.xml", "Dockerfile", ".dockerignore",
                     "scripts/merge_pr.py", "src/test/java/FooTest.java", ".zap/rules.tsv", ".gitattributes",
                     ".GitHub/workflows/ci.yml", "DOCKERFILE"):
            with self.subTest(path=path):
                self.assertTrue(merge_pr.is_sensitive(path))

    def test_not_sensitive(self):
        for path in ("src/main/java/App.java", "README.md", "docs/deployment.md", "docker-compose.yml",
                     "src/main/resources/templates/student/check-in.html", "github/x", "mvnw2", "src/testing/x",
                     "docs/scripts/setup-scheduled-jobs.ps1", "sub/pom.xml.txt"):
            with self.subTest(path=path):
                self.assertFalse(merge_pr.is_sensitive(path))


class GuardTest(unittest.TestCase):
    def test_owner_app_change_is_not_guarded(self):
        reasons, sensitive = merge_pr.guard_reasons("SanduCC", False, ["src/main/java/App.java"], 1, 1)
        self.assertEqual(reasons, [])
        self.assertEqual(sensitive, [])

    def test_dependabot_logins_are_trusted(self):
        for login in ("app/dependabot", "dependabot[bot]"):
            with self.subTest(login=login):
                self.assertEqual(merge_pr.guard_reasons(login, False, ["README.md"], 1, 1)[0], [])

    def test_fork_is_guarded(self):
        reasons, _ = merge_pr.guard_reasons("SanduCC", True, ["README.md"], 1, 1)
        self.assertEqual(len(reasons), 1)
        self.assertIn("fork", reasons[0])

    def test_other_author_is_guarded(self):
        reasons, _ = merge_pr.guard_reasons("someone", False, ["README.md"], 1, 1)
        self.assertIn("someone", reasons[0])

    def test_missing_author_is_guarded(self):
        self.assertTrue(merge_pr.guard_reasons("", False, ["README.md"], 1, 1)[0])

    def test_sensitive_path_guards_even_the_owner(self):
        reasons, sensitive = merge_pr.guard_reasons(
            "SanduCC", False, ["src/main/java/App.java", ".github/workflows/ci.yml", "pom.xml"], 3, 3)
        self.assertEqual(sensitive, [".github/workflows/ci.yml", "pom.xml"])
        self.assertEqual(len(reasons), 1)

    def test_renamed_out_of_sensitive_dir_is_guarded(self):
        # changed_files() also returns previous_filename for renames
        _, sensitive = merge_pr.guard_reasons("SanduCC", False, ["old.yml", ".github/workflows/ci.yml"], 1, 1)
        self.assertEqual(sensitive, [".github/workflows/ci.yml"])

    def test_incomplete_file_list_is_guarded(self):
        self.assertTrue(merge_pr.guard_reasons("SanduCC", False, ["README.md"], 1, 2)[0])
        self.assertTrue(merge_pr.guard_reasons("SanduCC", False, ["README.md"] * 3000, 3000, 3000)[0])


class ReviewTest(unittest.TestCase):
    def test_unguarded_needs_no_review(self):
        self.assertIsNone(merge_pr.review_problem([], None, HEAD))

    def test_guarded_needs_matching_sha(self):
        reasons = ["PR comes from a fork"]
        self.assertIn("no --reviewed-sha", merge_pr.review_problem(reasons, None, HEAD))
        self.assertIn("not the current head", merge_pr.review_problem(reasons, "0" * 40, HEAD))
        self.assertIsNone(merge_pr.review_problem(reasons, HEAD, HEAD))
        self.assertIsNone(merge_pr.review_problem(reasons, HEAD, HEAD.upper()))

    def test_full_sha_only(self):
        self.assertEqual(merge_pr.full_sha(HEAD.upper()), HEAD)
        for value in ("49482f6", HEAD + "0", "g" * 40, ""):
            with self.subTest(value=value):
                with self.assertRaises(argparse.ArgumentTypeError):
                    merge_pr.full_sha(value)


if __name__ == "__main__":
    unittest.main()
