import unittest

from check_apk_native import check_links, parse_symbols, parse_version_needs


class ApkNativeTest(unittest.TestCase):
    def test_android_sherpa_symbol_version_mismatch_is_rejected(self):
        libraries = {
            "libsherpa-onnx-jni.so": {
                "imports": {"OrtGetApiBase@VERS_1.17.1"}, "exports": set(),
                "needs": {"VERS_1.17.1": "libonnxruntime.so"}},
            "libonnxruntime.so": {
                "imports": set(), "exports": {"OrtGetApiBase@VERS_1.20.0"}, "needs": {}},
        }
        errors, checked = check_links(libraries)
        self.assertEqual(1, len(errors))
        self.assertIn("missing OrtGetApiBase@VERS_1.17.1", errors[0])
        self.assertEqual([], checked)
        libraries["libonnxruntime.so"]["exports"] = {"OrtGetApiBase@VERS_1.17.1"}
        errors, checked = check_links(libraries)
        self.assertEqual([], errors)
        self.assertEqual(1, len(checked))

    def test_readelf_default_export_and_required_import_normalize_equally(self):
        imports, exports = parse_symbols("""
  1: 0000000000000000 0 FUNC GLOBAL DEFAULT UND OrtGetApiBase@VERS_1.17.1 (3)
  2: 000000000042d8dc 20 FUNC GLOBAL DEFAULT 13 OrtGetApiBase@@VERS_1.17.1
  3: 0000000000000000 0 NOTYPE WEAK DEFAULT UND optional_symbol
""")
        self.assertEqual({"OrtGetApiBase@VERS_1.17.1"}, imports)
        self.assertEqual(imports, exports)

    def test_only_version_needs_are_associated_with_providers(self):
        needs = parse_version_needs("""Version definition section '.gnu.version_d' contains 1 entry:
  0: Rev: 1 Name: VERS_1.20.0
Version needs section '.gnu.version_r' contains 2 entries:
  000000: Version: 1 File: libonnxruntime.so Cnt: 1
  0x0040: Name: VERS_1.17.1 Flags: none Version: 3
  0x0010: Version: 1 File: libc.so Cnt: 1
  0x0050: Name: LIBC Flags: none Version: 4
""")
        self.assertEqual({"VERS_1.17.1": "libonnxruntime.so", "LIBC": "libc.so"}, needs)


if __name__ == "__main__":
    unittest.main()
