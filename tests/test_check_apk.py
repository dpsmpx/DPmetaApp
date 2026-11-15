"""Самотест проверки APK перед выкладкой (tools/release/check_apk.py, D-158)."""
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("check_apk", pathlib.Path(__file__).parent.parent / "tools" / "release" / "check_apk.py")
check_apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_apk)

SHA = "ab" * 32
SIGNED = f"""Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
Signer #1 certificate DN: CN=dpsmpx, C=RU
Signer #1 certificate SHA-256 digest: {SHA}
"""
BADGING = "package: name='com.dpsmpx.dpai' versionCode='4000038' versionName='0.4.0-dev.38' platformBuildVersionName='16'\nsdkVersion:'30'\ntargetSdkVersion:'36'\n"


class CheckApkTest(unittest.TestCase):
    def test_release_signed_apk_passes(self):
        signer = check_apk.parse_signer(SIGNED)
        badging = check_apk.parse_badging(BADGING)
        self.assertEqual(signer["schemes"], ["v2", "v3"])
        self.assertEqual(badging["package"], "com.dpsmpx.dpai")
        self.assertEqual(check_apk.problems(signer, badging, SHA.upper()), [])

    def test_debug_key_wrong_cert_and_debuggable_fail(self):
        debug = check_apk.parse_signer(SIGNED.replace("CN=dpsmpx, C=RU", "C=US, O=Android, CN=Android Debug"))
        found = check_apk.problems(debug, check_apk.parse_badging(BADGING + "application-debuggable\n"), "cd" * 32)
        self.assertTrue(any("отладочным" in p for p in found))
        self.assertTrue(any("отпечаток" in p for p in found))
        self.assertTrue(any("debuggable" in p for p in found))

    def test_new_build_tools_format(self):
        new = SIGNED.replace("Signer #1 certificate DN:", "V2 Signer: certificate DN:").replace("Signer #1 certificate SHA-256 digest:", "V2 Signer: certificate SHA-256 digest:")
        signer = check_apk.parse_signer(new)
        self.assertEqual((signer["dn"], signer["sha256"]), ("CN=dpsmpx, C=RU", SHA))
        self.assertEqual(check_apk.parse_badging("minSdkVersion:'30'\n")["minSdk"], "30")

    def test_unverified_or_v1_only_fails(self):
        v1 = check_apk.parse_signer("DOES NOT VERIFY\nVerified using v1 scheme (JAR signing): true\n")
        found = check_apk.problems(v1, None, None)
        self.assertTrue(any("не проходит" in p for p in found))
        self.assertTrue(any("v2" in p for p in found))


if __name__ == "__main__":
    unittest.main()
