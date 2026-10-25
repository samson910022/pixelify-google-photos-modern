"""Security tests for the JDK truststore hardening tool and shell wrapper.

Run with a JDK 17:

    ./scripts/with-android-env.sh python3 -m unittest tests.test_jdk_truststore -v

The suite is offline and uses only public fixture certificates plus ephemeral
synthetic PKI generated with keytool. All generated stores, keys, classes, and
PEM files live in temporary directories and are removed after the run.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURE_PATH = REPO_ROOT / "tests" / "fixtures" / "emudhra_public_chains.json"
PINS_PATH = REPO_ROOT / "scripts" / "jdk-truststore" / "pins.txt"
CANARY_PATH = REPO_ROOT / "scripts" / "jdk-truststore" / "ci-canary-cert.txt"
TOOL_SOURCE = REPO_ROOT / "scripts" / "jdk-truststore" / "TrustStoreTool.java"
PROBE_SOURCE = REPO_ROOT / "tests" / "java" / "ChainTrustProbe.java"
WRAPPER = REPO_ROOT / "scripts" / "harden-jdk-truststore.sh"

PASSWORD = "changeit"
FIXED_DATE = "2026-10-25T00:00:00Z"
OLD_DATE = "2020-01-01T00:00:00Z"
CANARY_ALIAS = "pixelify-ci-truststore-canary"
SYNTHETIC_CA_SUBJECT = "CN=Pixelify Test Root CA,O=Pixelify Infinity"
SAME_DN_SUBJECT = "CN=emSign Root TLS CA - G3,O=eMudhra Technologies Limited,C=IN"
START_DATE = "2020/01/01 00:00:00"
VALIDITY_DAYS = "7300"

# Avoid the literal marker in repository files while still asserting on it.
PRIVATE_MARKER = "PRIVATE" + " KEY"


def store_type(path: Path) -> str:
    """Return the JKS/PKCS12 store type from the file magic."""
    header = path.read_bytes()[:4]
    if header == b"\xfe\xed\xfe\xed":
        return "JKS"
    if header[:1] == b"\x30":
        return "PKCS12"
    raise AssertionError(f"unrecognized keystore magic {header!r} in {path}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


class JdkTrustStoreTests(unittest.TestCase):
    """End-to-end tests for TrustStoreTool.java, ChainTrustProbe.java, and the wrapper."""

    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        java, jdk_home, java_version = cls._resolve_jdk()
        if not java_version.startswith("17."):
            raise unittest.SkipTest(
                f"JDK 17 is required for these tests; found {java_version!r} at {java}"
            )
        cls.java = java
        cls.jdk_home = jdk_home
        cls.java_version = java_version
        cls.javac = jdk_home / "bin" / "javac"
        cls.keytool = jdk_home / "bin" / "keytool"
        if not cls.javac.is_file():
            raise unittest.SkipTest(f"javac is missing from JDK home {jdk_home}")
        if not cls.keytool.is_file():
            raise unittest.SkipTest(f"keytool is missing from JDK home {jdk_home}")
        cls.real_cacerts = jdk_home / "lib" / "security" / "cacerts"
        if not cls.real_cacerts.is_file():
            raise unittest.SkipTest(f"JDK cacerts is missing: {cls.real_cacerts}")

        cls.shared_dir = Path(tempfile.mkdtemp(prefix="pixelify-jdk-truststore-tests-"))
        cls.addClassCleanup(shutil.rmtree, cls.shared_dir, ignore_errors=True)
        cls.classes_dir = cls.shared_dir / "classes"
        cls.classes_dir.mkdir()
        cls._pem_spki_cache: dict[str, str] = {}

        cls.base_env = os.environ.copy()
        cls.base_env["JAVA_HOME"] = str(jdk_home)
        cls.base_env["PATH"] = f"{jdk_home / 'bin'}{os.pathsep}{cls.base_env.get('PATH', '')}"
        cls.base_env["LC_ALL"] = "C"
        cls.base_env["LANG"] = "C"
        cls.base_env["TZ"] = "UTC"
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
            cls.base_env.pop(key, None)

        cls._compile()
        cls.fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        cls._build_shared_artifacts()

    # ------------------------------------------------------------------
    # JDK discovery and compilation
    # ------------------------------------------------------------------

    @classmethod
    def _resolve_jdk(cls):
        java_home_env = os.environ.get("JAVA_HOME")
        if java_home_env:
            java = Path(java_home_env) / "bin" / "java"
        else:
            found = shutil.which("java")
            if not found:
                raise unittest.SkipTest("no java executable found (set JAVA_HOME or PATH)")
            java = Path(found)
        if not java.is_file():
            raise unittest.SkipTest(f"java executable not found: {java}")

        version_result = subprocess.run(
            [str(java), "-version"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        version_text = version_result.stderr + version_result.stdout
        match = re.search(r'version "([^"]+)"', version_text)
        if not match:
            raise unittest.SkipTest(f"cannot determine Java version from: {version_text!r}")
        java_version = match.group(1)

        properties_result = subprocess.run(
            [str(java), "-XshowSettings:properties", "-version"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        properties = properties_result.stderr + properties_result.stdout
        home_match = re.search(r"^\s*java\.home\s*=\s*(.+?)\s*$", properties, re.MULTILINE)
        if not home_match:
            raise unittest.SkipTest("cannot determine java.home from java -XshowSettings")
        jdk_home = Path(home_match.group(1)).resolve()
        return java.resolve(), jdk_home, java_version

    @classmethod
    def _compile(cls) -> None:
        command = [
            str(cls.javac),
            "-encoding",
            "UTF-8",
            "-d",
            str(cls.classes_dir),
            str(TOOL_SOURCE),
            str(PROBE_SOURCE),
        ]
        result = cls._run(command)
        if result.returncode != 0:
            raise RuntimeError(
                "javac failed:\n"
                f"command: {' '.join(command)}\n"
                f"stdout:\n{result.stdout}\n"
                f"stderr:\n{result.stderr}"
            )

    # ------------------------------------------------------------------
    # Shared subprocess helpers
    # ------------------------------------------------------------------

    @classmethod
    def _run(cls, command, *, env=None, timeout=None):
        return subprocess.run(
            [str(part) for part in command],
            env=env if env is not None else cls.base_env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )

    @classmethod
    def _run_checked(cls, command, *, env=None):
        result = cls._run(command, env=env)
        if result.returncode != 0:
            raise RuntimeError(
                f"command failed with exit {result.returncode}:\n"
                f"command: {' '.join(str(part) for part in command)}\n"
                f"stdout:\n{result.stdout}\n"
                f"stderr:\n{result.stderr}"
            )
        return result

    @classmethod
    def _keytool(cls, *args) -> subprocess.CompletedProcess:
        return cls._run_checked([cls.keytool, *args])

    @classmethod
    def _import_cert(
        cls,
        store: Path,
        alias: str,
        certificate: Path,
        *,
        storetype: str | None = None,
        password: str = PASSWORD,
    ) -> None:
        if store.exists():
            storetype = store_type(store)
        if storetype is None:
            raise AssertionError(f"store type required for new store {store}")
        cls._keytool(
            "-importcert",
            "-noprompt",
            "-alias",
            alias,
            "-file",
            str(certificate),
            "-keystore",
            str(store),
            "-storetype",
            storetype,
            "-storepass",
            password,
        )

    @classmethod
    def _export_cert(cls, store: Path, alias: str, output: Path) -> None:
        cls._keytool(
            "-exportcert",
            "-rfc",
            "-alias",
            alias,
            "-keystore",
            str(store),
            "-storetype",
            store_type(store),
            "-storepass",
            PASSWORD,
            "-file",
            str(output),
        )

    @classmethod
    def _generate_ca(
        cls,
        workdir: Path,
        alias: str,
        distinguished_name: str,
        output_p12: Path,
        output_pem: Path,
    ) -> None:
        cls._keytool(
            "-genkeypair",
            "-alias",
            alias,
            "-keyalg",
            "EC",
            "-groupname",
            "secp256r1",
            "-dname",
            distinguished_name,
            "-validity",
            VALIDITY_DAYS,
            "-startdate",
            START_DATE,
            "-ext",
            "bc:c",
            "-ext",
            "ku:c=keyCertSign,cRLSign",
            "-keystore",
            str(output_p12),
            "-storetype",
            "PKCS12",
            "-storepass",
            PASSWORD,
            "-keypass",
            PASSWORD,
            "-noprompt",
        )
        cls._export_cert(output_p12, alias, output_pem)

    @classmethod
    def _generate_leaf(
        cls,
        workdir: Path,
        ca_p12: Path,
        ca_alias: str,
        output_pem: Path,
    ) -> None:
        leaf_p12 = workdir / "synthetic-leaf.p12"
        leaf_csr = workdir / "synthetic-leaf.csr"
        cls._keytool(
            "-genkeypair",
            "-alias",
            "leaf",
            "-keyalg",
            "EC",
            "-groupname",
            "secp256r1",
            "-dname",
            "CN=Pixelify Test Leaf,O=Pixelify Infinity",
            "-validity",
            VALIDITY_DAYS,
            "-startdate",
            START_DATE,
            "-keystore",
            str(leaf_p12),
            "-storetype",
            "PKCS12",
            "-storepass",
            PASSWORD,
            "-keypass",
            PASSWORD,
            "-noprompt",
        )
        cls._keytool(
            "-certreq",
            "-alias",
            "leaf",
            "-keystore",
            str(leaf_p12),
            "-storetype",
            "PKCS12",
            "-storepass",
            PASSWORD,
            "-file",
            str(leaf_csr),
        )
        cls._keytool(
            "-gencert",
            "-alias",
            ca_alias,
            "-keystore",
            str(ca_p12),
            "-storetype",
            "PKCS12",
            "-storepass",
            PASSWORD,
            "-infile",
            str(leaf_csr),
            "-outfile",
            str(output_pem),
            "-rfc",
            "-validity",
            VALIDITY_DAYS,
            "-startdate",
            START_DATE,
            "-ext",
            "ku:c=digitalSignature",
            "-ext",
            "eku=serverAuth",
        )

    @classmethod
    def _fingerprint(cls, certificate: Path) -> dict:
        result = cls._run_checked(
            [
                cls.java,
                "-cp",
                str(cls.classes_dir),
                "TrustStoreTool",
                "fingerprint",
                "--cert",
                str(certificate),
            ]
        )
        return json.loads(result.stdout)

    @classmethod
    def _list_store(cls, store: Path) -> dict:
        result = cls._run_checked(
            [
                cls.java,
                "-cp",
                str(cls.classes_dir),
                "TrustStoreTool",
                "list",
                "--store",
                str(store),
            ]
        )
        return json.loads(result.stdout)

    # ------------------------------------------------------------------
    # Shared store/PKI construction
    # ------------------------------------------------------------------

    @classmethod
    def _build_shared_artifacts(cls) -> None:
        certs_dir = cls.shared_dir / "certs"
        certs_dir.mkdir()
        cls.root_pems = []
        cls.intermediate_pems = []
        for index, root in enumerate(cls.fixture["roots"]):
            path = certs_dir / f"root-{index}.pem"
            path.write_text(root["pem"], encoding="ascii")
            cls.root_pems.append(path)
        for index, intermediate in enumerate(cls.fixture["intermediates"]):
            path = certs_dir / f"intermediate-{index}.pem"
            path.write_text(intermediate["pem"], encoding="ascii")
            cls.intermediate_pems.append(path)

        pki = cls.shared_dir / "pki"
        pki.mkdir()
        cls.synthetic_ca_p12 = pki / "synthetic-ca.p12"
        cls.synthetic_ca_pem = pki / "synthetic-ca.pem"
        cls._generate_ca(
            pki,
            "synthetic-ca",
            SYNTHETIC_CA_SUBJECT,
            cls.synthetic_ca_p12,
            cls.synthetic_ca_pem,
        )
        cls.synthetic_leaf_pem = pki / "synthetic-leaf.pem"
        cls._generate_leaf(pki, cls.synthetic_ca_p12, "synthetic-ca", cls.synthetic_leaf_pem)

        cls.same_dn_ca_p12 = pki / "same-dn-ca.p12"
        cls.same_dn_ca_pem = pki / "same-dn-ca.pem"
        cls._generate_ca(
            pki,
            "same-dn-ca",
            SAME_DN_SUBJECT,
            cls.same_dn_ca_p12,
            cls.same_dn_ca_pem,
        )

        cls.synthetic_ca_info = cls._fingerprint(cls.synthetic_ca_pem)
        cls.synthetic_leaf_info = cls._fingerprint(cls.synthetic_leaf_pem)
        cls.same_dn_ca_info = cls._fingerprint(cls.same_dn_ca_pem)
        cls.canary_info = cls._fingerprint(CANARY_PATH)

        cls.vulnerable_store = cls.shared_dir / "vulnerable_store.jks"
        for index, path in enumerate(cls.root_pems):
            cls._import_cert(
                cls.vulnerable_store, f"root-{index}", path, storetype="JKS"
            )
        cls._import_cert(
            cls.vulnerable_store, "synthetic-ca", cls.synthetic_ca_pem
        )

        cls.incident_store = cls.shared_dir / "incident_store.jks"
        for index, path in enumerate(cls.root_pems):
            cls._import_cert(
                cls.incident_store, f"root-{index}", path, storetype="JKS"
            )
        for index, path in enumerate(cls.intermediate_pems):
            cls._import_cert(cls.incident_store, f"intermediate-{index}", path)
        cls._import_cert(cls.incident_store, "synthetic-ca", cls.synthetic_ca_pem)

        cls.pkcs12_store = cls.shared_dir / "pkcs12_store.p12"
        cls._keytool(
            "-importkeystore",
            "-srckeystore",
            str(cls.vulnerable_store),
            "-srcstoretype",
            "JKS",
            "-srcstorepass",
            PASSWORD,
            "-destkeystore",
            str(cls.pkcs12_store),
            "-deststoretype",
            "PKCS12",
            "-deststorepass",
            PASSWORD,
            "-noprompt",
        )

        cls.synthetic_store = cls.shared_dir / "synthetic_store.jks"
        cls._import_cert(
            cls.synthetic_store, "synthetic-ca", cls.synthetic_ca_pem, storetype="JKS"
        )

        cls.small_store = cls.shared_dir / "small_store.jks"
        cls._import_cert(
            cls.small_store, "pinned-root", cls.root_pems[0], storetype="JKS"
        )
        cls._import_cert(cls.small_store, "synthetic-ca", cls.synthetic_ca_pem)

        cls.wrong_password_store = cls.shared_dir / "wrong_password_store.jks"
        cls._import_cert(
            cls.wrong_password_store,
            "synthetic-ca",
            cls.synthetic_ca_pem,
            storetype="JKS",
            password="different",
        )

        cls.same_dn_store = cls.shared_dir / "same_dn_store.jks"
        cls._import_cert(
            cls.same_dn_store, "pinned-root", cls.root_pems[0], storetype="JKS"
        )
        cls._import_cert(cls.same_dn_store, "same-dn-ca", cls.same_dn_ca_pem)

        cls.key_entry_store = cls.shared_dir / "key-entry.p12"
        shutil.copy(cls.synthetic_ca_p12, cls.key_entry_store)
        cls._import_cert(
            cls.key_entry_store, "synthetic-leaf-trusted", cls.synthetic_leaf_pem
        )

        cls.vulnerable_jdk_cacerts = cls.shared_dir / "vulnerable-jdk-cacerts"
        shutil.copy(cls.real_cacerts, cls.vulnerable_jdk_cacerts)
        for index, path in enumerate(cls.root_pems):
            cls._import_cert(
                cls.vulnerable_jdk_cacerts, f"incident-root-{index}", path
            )

        cls._sanity_check_shared_artifacts()

    @classmethod
    def _sanity_check_shared_artifacts(cls) -> None:
        expected_counts = {
            cls.vulnerable_store: 5,
            cls.incident_store: 21,
            cls.pkcs12_store: 5,
            cls.synthetic_store: 1,
            cls.small_store: 2,
            cls.same_dn_store: 2,
        }
        for store, expected in expected_counts.items():
            listed = cls._list_store(store)
            if listed["certificateCount"] != expected:
                raise RuntimeError(
                    f"shared store {store.name} has {listed['certificateCount']} "
                    f"certificate entries, expected {expected}"
                )

        key_entry_list = cls._list_store(cls.key_entry_store)
        if key_entry_list["certificateCount"] != 1:
            raise RuntimeError(
                "key-entry store should have exactly one trusted certificate entry, "
                f"found {key_entry_list['certificateCount']}"
            )
        if not any(entry["keyEntry"] for entry in key_entry_list["entries"]):
            raise RuntimeError("key-entry store does not contain a key entry")

        wrong_password = cls._run(
            [
                cls.keytool,
                "-list",
                "-keystore",
                str(cls.wrong_password_store),
                "-storetype",
                "JKS",
                "-storepass",
                "different",
            ]
        )
        if wrong_password.returncode != 0:
            raise RuntimeError(
                "wrong-password store cannot be opened with its own password:\n"
                f"{wrong_password.stderr}"
            )

        jdk_store = cls._list_store(cls.vulnerable_jdk_cacerts)
        if jdk_store["certificateCount"] < 50:
            raise RuntimeError(
                "fake JDK cacerts has fewer than 50 certificate entries: "
                f"{jdk_store['certificateCount']}"
            )
        jdk_spkis = {entry["spkiSha256"] for entry in jdk_store["entries"]}
        missing = {
            root["spkiSha256"] for root in cls.fixture["roots"]
        } - jdk_spkis
        if missing:
            raise RuntimeError(
                f"fake JDK cacerts is missing incident root SPKIs: {sorted(missing)}"
            )

    # ------------------------------------------------------------------
    # Per-test helpers
    # ------------------------------------------------------------------

    def setUp(self) -> None:
        # mkdtemp + tolerant rmtree matches the class-level cleanup. Some
        # scratch filesystems (fuseblk/NTFS) can transiently report ENOTEMPTY
        # during teardown even though the test itself cleaned up; that must not
        # be reported as a test failure.
        self._tmpdir = tempfile.mkdtemp(prefix="pixelify-jdk-truststore-test-")
        self.addCleanup(shutil.rmtree, self._tmpdir, ignore_errors=True)
        self.tmp = Path(self._tmpdir)

    @property
    def fixture(self) -> dict:
        return self.__class__.fixture

    @property
    def pin_spkis(self) -> set[str]:
        return {
            entry["spkiSha256"]
            for entry in self.fixture["roots"] + self.fixture["intermediates"]
        }

    def copy_shared(self, name: str, destination: Path | None = None) -> Path:
        source = self.__class__.shared_dir / name
        target = destination if destination is not None else self.tmp / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(source, target)
        return target

    def write_text(self, path: Path, text: str) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="ascii")
        return path

    def write_chain(self, chain: dict, path: Path | None = None) -> Path:
        target = path or (self.tmp / f"{chain['name']}.pem")
        return self.write_text(
            target, chain["leafPem"] + "".join(chain["intermediatePems"])
        )

    def run_tool(self, *args, env=None, timeout=None) -> subprocess.CompletedProcess:
        return self.__class__._run(
            [
                self.__class__.java,
                "-cp",
                str(self.__class__.classes_dir),
                "TrustStoreTool",
                *args,
            ],
            env=env,
            timeout=timeout,
        )

    def run_probe(self, *args, env=None) -> subprocess.CompletedProcess:
        return self.__class__._run(
            [
                self.__class__.java,
                "-cp",
                str(self.__class__.classes_dir),
                "ChainTrustProbe",
                *args,
            ],
            env=env,
        )

    def run_wrapper(
        self, *args, env=None, timeout=None, wrapper: Path | None = None
    ) -> subprocess.CompletedProcess:
        wrapper_env = (env if env is not None else self.__class__.base_env).copy()
        wrapper_tmp = self.tmp / "wrapper-tmp"
        wrapper_tmp.mkdir(parents=True, exist_ok=True)
        wrapper_env["TMPDIR"] = str(wrapper_tmp)
        return self.__class__._run(
            [wrapper if wrapper is not None else WRAPPER, *args],
            env=wrapper_env,
            timeout=timeout,
        )

    def out_store_path(self, *parts: str) -> Path:
        """Return an out-store path whose parent directory already exists.

        The wrapper intentionally does not create --out-store parents: a mkdir
        on an unpinned path could follow a swapped parent. Tests that expect
        hardening to proceed must pre-create the directory they pass.
        """
        path = self.tmp.joinpath(*parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def make_fake_jdk(
        self, name: str = "fake-jdk", source: Path | None = None
    ) -> Path:
        fake = self.tmp / name
        (fake / "bin").mkdir(parents=True)
        (fake / "lib" / "security").mkdir(parents=True)
        os.symlink(self.__class__.java.resolve(), fake / "bin" / "java")
        shutil.copy(
            source if source is not None else self.__class__.vulnerable_jdk_cacerts,
            fake / "lib" / "security" / "cacerts",
        )
        return fake

    def add_parent_swap_java(self, fake: Path, parent: Path, target: Path) -> dict:
        """Make the fake java rename a parent directory during harden.

        The swap is deterministic: it runs inside the wrapper's harden
        invocation, after the wrapper has validated and pinned the original
        parent directory, and before the real JVM hardens the staged store.
        """
        if not Path("/proc/self/fd").is_dir():
            self.skipTest("directory-fd pinning requires /proc/self/fd (Linux)")
        probe = self.tmp / "parent-swap-symlink-probe"
        try:
            os.symlink(target, probe)
            probe.unlink()
        except OSError as error:
            self.skipTest(f"symlinks are unavailable in this test directory: {error}")
        java_script = fake / "bin" / "java"
        java_script.unlink()
        java_script.write_text(
            "#!/usr/bin/env bash\n"
            'case " $* " in\n'
            '  *" harden "*)\n'
            '    mv -- "$SWAP_PARENT" "$SWAP_PARENT.orig" || exit 90\n'
            '    ln -s -- "$SWAP_TARGET" "$SWAP_PARENT" || exit 91\n'
            "    ;;\n"
            "esac\n"
            'exec "$REAL_JAVA" "$@"\n',
            encoding="ascii",
        )
        java_script.chmod(0o755)
        env = self.__class__.base_env.copy()
        env["REAL_JAVA"] = str(self.__class__.java.resolve())
        env["SWAP_PARENT"] = str(parent)
        env["SWAP_TARGET"] = str(target)
        return env

    def make_repo_filesystem_hardlink(self, source: Path, name: str) -> Path:
        """Create a hardlink to source outside the repository on its filesystem.

        Hardlinks cannot cross filesystems and the suite temp directory may be
        on a different device when TMPDIR is set, so use a short-lived
        directory on the repository's filesystem instead.
        """
        source_device = source.stat().st_dev
        for base in (REPO_ROOT.parent, Path("/var/tmp"), Path(tempfile.gettempdir())):
            try:
                if base.stat().st_dev != source_device:
                    continue
                directory = Path(
                    tempfile.mkdtemp(prefix="pixelify-hardlink-test-", dir=base)
                )
            except OSError:
                continue
            self.addCleanup(shutil.rmtree, directory, ignore_errors=True)
            link = directory / name
            os.link(source, link)
            return link
        self.skipTest("no writable temporary directory on the repository filesystem")

    def jsse_env(self, store: Path, *, storetype: str | None = None) -> dict:
        env = self.__class__.base_env.copy()
        env["JAVA_TOOL_OPTIONS"] = self.jsse_options(store, storetype=storetype)
        return env

    @staticmethod
    def jsse_options(store: Path, *, storetype: str | None = None) -> str:
        resolved_type = storetype or store_type(store)
        return (
            f'-Djavax.net.ssl.trustStore="{store}" '
            f"-Djavax.net.ssl.trustStorePassword={PASSWORD} "
            f"-Djavax.net.ssl.trustStoreType={resolved_type}"
        )

    def read_env_file(self, path: Path) -> str:
        self.assertTrue(path.is_file(), f"env file was not created: {path}")
        lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        self.assertTrue(lines, f"env file is empty: {path}")
        line = lines[-1]
        self.assertTrue(
            line.startswith("JAVA_TOOL_OPTIONS="),
            f"env file does not contain JAVA_TOOL_OPTIONS: {line!r}",
        )
        return line.split("=", 1)[1]

    @staticmethod
    def describe(result: subprocess.CompletedProcess) -> str:
        return (
            f"exit={result.returncode}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}"
        )

    def assert_success(self, result: subprocess.CompletedProcess, message: str = "") -> None:
        self.assertEqual(result.returncode, 0, msg=f"{message}\n{self.describe(result)}")

    def assert_failure(
        self,
        result: subprocess.CompletedProcess,
        contains: str | None = None,
        message: str = "",
    ) -> None:
        self.assertNotEqual(
            result.returncode, 0, msg=f"expected failure\n{message}\n{self.describe(result)}"
        )
        self.assertEqual(
            result.stdout.strip(),
            "",
            msg=f"expected no stdout on failure\n{message}\n{self.describe(result)}",
        )
        if contains is not None:
            self.assertIn(
                contains,
                result.stderr,
                msg=f"expected stderr to contain {contains!r}\n{message}\n{self.describe(result)}",
            )

    def json_from(self, result: subprocess.CompletedProcess) -> dict:
        lines = [line for line in result.stdout.splitlines() if line.strip()]
        self.assertTrue(lines, msg=f"no JSON on stdout\n{self.describe(result)}")
        try:
            return json.loads(lines[-1])
        except json.JSONDecodeError as error:
            self.fail(f"stdout is not JSON ({error})\n{self.describe(result)}")

    def success_json(self, result: subprocess.CompletedProcess, message: str = "") -> dict:
        self.assert_success(result, message)
        return self.json_from(result)

    def assert_clean_failure(self, result: subprocess.CompletedProcess) -> None:
        self.assertNotEqual(result.returncode, 0, msg=self.describe(result))
        self.assertIn("ERROR:", result.stderr, msg=self.describe(result))
        self.assertNotIn(
            "Traceback (most recent call last)", result.stderr, msg=self.describe(result)
        )
        self.assertNotIn("Exception in thread", result.stderr, msg=self.describe(result))
        self.assertNotIn("\tat ", result.stderr, msg=self.describe(result))
        self.assertEqual(result.stdout.strip(), "", msg=self.describe(result))

    def assert_no_tool_temp_files(self) -> None:
        leftovers = [
            path for path in self.tmp.rglob(".truststore-*.tmp") if path.is_file()
        ]
        self.assertEqual(leftovers, [], f"tool temp files left behind: {leftovers}")

    def assert_no_staging_leftovers(self, *directories: Path) -> None:
        leftovers: list[Path] = []
        for directory in directories:
            if not directory.exists():
                continue
            leftovers.extend(sorted(directory.glob("*.tmp.*")))
            leftovers.extend(sorted(directory.glob(".truststore-*.tmp")))
        self.assertEqual(leftovers, [], f"staging files left behind: {leftovers}")

    def assert_owner_only(self, path: Path) -> None:
        """Assert mode 0600 when the filesystem actually honors POSIX modes.

        Some CI scratch filesystems (for example exFAT/NTFS mounts) report 0777
        for every file regardless of chmod. The staging path still calls chmod;
        only the assertion is skipped there.
        """
        probe = self.tmp / "posix-mode-probe"
        probe.write_bytes(b"")
        try:
            probe.chmod(0o600)
            honors_modes = stat.S_IMODE(probe.stat().st_mode) == 0o600
        finally:
            probe.unlink()
        if not honors_modes:
            return
        self.assertEqual(
            stat.S_IMODE(path.stat().st_mode),
            0o600,
            f"{path} is not owner-only",
        )

    def _spki_for_pem(self, pem: str) -> str:
        cache = self.__class__._pem_spki_cache
        key = hashlib.sha256(pem.encode("ascii")).hexdigest()
        if key not in cache:
            path = self.tmp / f"pem-{len(cache)}.pem"
            self.write_text(path, pem)
            cache[key] = self.__class__._fingerprint(path)["spkiSha256"]
        return cache[key]

    # ------------------------------------------------------------------
    # A. Pin and fixture integrity
    # ------------------------------------------------------------------

    def test_pins_match_fixture_unique_spkis(self) -> None:
        roots = self.fixture["roots"]
        intermediates = self.fixture["intermediates"]
        root_spkis = {entry["spkiSha256"] for entry in roots}
        intermediate_spkis = {entry["spkiSha256"] for entry in intermediates}
        expected = root_spkis | intermediate_spkis

        self.assertEqual(len(roots), 4)
        self.assertEqual(len(intermediates), 16)
        self.assertEqual(len(root_spkis), 4)
        self.assertEqual(len(intermediate_spkis), 8)
        self.assertEqual(len(expected), 12)

        pin_lines = [
            line.strip()
            for line in PINS_PATH.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        pins = []
        for line in pin_lines:
            parts = line.split()
            self.assertGreaterEqual(
                len(parts), 2, f"pin line must be '<64-hex> <name>': {line!r}"
            )
            self.assertRegex(parts[0], r"^[0-9A-F]{64}$", f"bad pin: {line!r}")
            pins.append(parts[0])

        self.assertEqual(len(pins), 12, f"expected 12 pins, found {len(pins)}")
        self.assertEqual(len(set(pins)), 12, "pins.txt contains duplicate SPKIs")
        self.assertEqual(set(pins), expected)

    def test_fixture_fingerprints_match_metadata(self) -> None:
        entries = [("root", entry) for entry in self.fixture["roots"]]
        entries += [("intermediate", entry) for entry in self.fixture["intermediates"]]
        for index, (kind, entry) in enumerate(entries):
            with self.subTest(kind=kind, name=entry["name"], index=index):
                path = self.write_text(
                    self.tmp / f"fingerprint-{index}.pem", entry["pem"]
                )
                info = self.__class__._fingerprint(path)
                self.assertEqual(
                    info["certSha256"],
                    entry["sha256"],
                    msg=f"full-cert SHA-256 mismatch for {entry['name']}",
                )
                self.assertEqual(
                    info["spkiSha256"],
                    entry["spkiSha256"],
                    msg=f"SPKI SHA-256 mismatch for {entry['name']}",
                )
                self.assertEqual(
                    info["subject"],
                    entry["subject"],
                    msg=f"subject mismatch for {entry['name']}",
                )

    def test_fixture_and_pins_are_public_only(self) -> None:
        fixture_text = FIXTURE_PATH.read_text(encoding="utf-8")
        pins_text = PINS_PATH.read_text(encoding="utf-8")
        for label, text in (("fixture", fixture_text), ("pins", pins_text)):
            self.assertNotIn(PRIVATE_MARKER, text, f"{label} contains a private-key marker")
            self.assertNotRegex(
                text.lower(),
                r"begin [a-z0-9 ]*private key",
                f"{label} contains a private-key PEM header",
            )
            for local_path in (
                "/home/",
                "/mnt/",
                "/tmp/",
                "/var/",
                "/opt/",
                "/private/",
                "/etc/",
                "/Users/",
                "/root/",
                "file://",
            ):
                self.assertNotIn(local_path, text, f"{label} contains local path {local_path!r}")

        self.assertEqual(self.fixture["source"], "https://emudhra.we-have-a-problem.com/")
        self.assertEqual(self.fixture["validationDate"], FIXED_DATE)
        self.assertEqual(len(self.fixture["chains"]), 4)

        for collection in ("roots", "intermediates"):
            for entry in self.fixture[collection]:
                self.assertTrue(
                    entry["pem"].startswith("-----BEGIN CERTIFICATE-----"),
                    f"{entry['name']} PEM does not start with a certificate marker",
                )
                self.assertTrue(
                    entry["pem"].rstrip().endswith("-----END CERTIFICATE-----"),
                    f"{entry['name']} PEM does not end with a certificate marker",
                )
                self.assertNotIn(PRIVATE_MARKER, entry["pem"])
                # The PEM must decode to a DER SEQUENCE.
                body = "".join(entry["pem"].strip().splitlines()[1:-1])
                self.assertEqual(base64.b64decode(body)[:1], b"\x30")

        for chain in self.fixture["chains"]:
            self.assertTrue(chain["leafPem"].startswith("-----BEGIN CERTIFICATE-----"))
            self.assertNotIn(PRIVATE_MARKER, chain["leafPem"])
            for pem in chain["intermediatePems"]:
                self.assertTrue(pem.startswith("-----BEGIN CERTIFICATE-----"))
                self.assertNotIn(PRIVATE_MARKER, pem)

    # ------------------------------------------------------------------
    # B. Tool hardening
    # ------------------------------------------------------------------

    def test_harden_incident_store_removes_all_pinned_entries(self) -> None:
        store = self.copy_shared("incident_store.jks", self.tmp / "incident.jks")
        result = self.run_tool(
            "harden",
            "--store",
            store,
            "--pins",
            PINS_PATH,
            "--canary-cert",
            CANARY_PATH,
        )
        hardened = self.success_json(result)
        self.assertEqual(hardened["command"], "harden")
        self.assertEqual(hardened["storeType"], "JKS")
        self.assertEqual(hardened["beforeCertCount"], 21)
        self.assertEqual(hardened["removed"], 20)
        self.assertEqual(len(hardened["removedEntries"]), 20)
        self.assertEqual(
            len({entry["name"] for entry in hardened["removedEntries"]}),
            12,
            "the 20 removed certificates must map to 12 unique pinned names",
        )
        pin_names = {
            line.split(maxsplit=1)[1]
            for line in PINS_PATH.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        }
        removed_names = {entry["name"] for entry in hardened["removedEntries"]}
        self.assertEqual(removed_names, pin_names)
        self.assertEqual(hardened["forbiddenRemaining"], [])
        self.assertEqual(hardened["forbiddenNames"], [])
        self.assertTrue(hardened["canaryPresent"])
        self.assertEqual(hardened["canaryAlias"], CANARY_ALIAS)
        self.assertEqual(hardened["canarySha256"], self.__class__.canary_info["certSha256"])
        self.assertEqual(hardened["afterCertCount"], 2)
        self.assertEqual(hardened["certificateCount"], 2)

        listed = self.success_json(self.run_tool("list", "--store", store))
        self.assertEqual(listed["storeType"], "JKS")
        self.assertEqual(listed["certificateCount"], 2)
        aliases = {entry["alias"] for entry in listed["entries"]}
        self.assertEqual(aliases, {"synthetic-ca", CANARY_ALIAS})
        spkis = {entry["spkiSha256"] for entry in listed["entries"]}
        self.assertIn(self.__class__.synthetic_ca_info["spkiSha256"], spkis)
        self.assertIn(self.__class__.canary_info["spkiSha256"], spkis)
        self.assertFalse(spkis & self.pin_spkis)

        verified = self.success_json(
            self.run_tool("verify", "--store", store, "--pins", PINS_PATH)
        )
        self.assertEqual(verified["command"], "verify")
        self.assertEqual(verified["forbiddenRemaining"], [])
        self.assertEqual(verified["certificateCount"], 2)

    def test_harden_preserves_same_subject_different_key(self) -> None:
        store = self.copy_shared("same_dn_store.jks", self.tmp / "same-dn.jks")
        pinned_spki = self.fixture["roots"][0]["spkiSha256"]
        same_dn_spki = self.__class__.same_dn_ca_info["spkiSha256"]
        self.assertNotEqual(pinned_spki, same_dn_spki)

        hardened = self.success_json(
            self.run_tool("harden", "--store", store, "--pins", PINS_PATH)
        )
        self.assertEqual(hardened["removed"], 1)
        self.assertEqual(hardened["removedEntries"][0]["spkiSha256"], pinned_spki)
        self.assertEqual(hardened["afterCertCount"], 1)
        self.assertEqual(hardened["forbiddenRemaining"], [])

        listed = self.success_json(self.run_tool("list", "--store", store))
        self.assertEqual(listed["certificateCount"], 1)
        entry = listed["entries"][0]
        self.assertEqual(entry["spkiSha256"], same_dn_spki)
        self.assertEqual(entry["subject"], SAME_DN_SUBJECT)
        self.assertNotEqual(entry["spkiSha256"], pinned_spki)

    def test_harden_is_idempotent(self) -> None:
        store = self.copy_shared("incident_store.jks", self.tmp / "idempotent.jks")
        args = (
            "harden",
            "--store",
            store,
            "--pins",
            PINS_PATH,
            "--canary-cert",
            CANARY_PATH,
        )
        first = self.success_json(self.run_tool(*args))
        first_count = first["afterCertCount"]
        self.assertEqual(first["removed"], 20)
        self.assertTrue(first["canaryPresent"])

        second = self.success_json(self.run_tool(*args))
        self.assertEqual(second["removed"], 0)
        self.assertEqual(second["removedEntries"], [])
        self.assertTrue(second["canaryPresent"])
        self.assertEqual(second["beforeCertCount"], first_count)
        self.assertEqual(second["afterCertCount"], first_count)
        self.assertEqual(second["certificateCount"], first_count)
        self.assertEqual(second["forbiddenRemaining"], [])

    def test_harden_preserves_jks_and_pkcs12_store_types(self) -> None:
        cases = [
            ("vulnerable_store.jks", "JKS"),
            ("pkcs12_store.p12", "PKCS12"),
        ]
        for source_name, expected_type in cases:
            with self.subTest(store=source_name):
                store = self.copy_shared(source_name, self.tmp / source_name)
                self.assertEqual(store_type(store), expected_type)
                hardened = self.success_json(
                    self.run_tool("harden", "--store", store, "--pins", PINS_PATH)
                )
                self.assertEqual(hardened["storeType"], expected_type)
                self.assertEqual(hardened["removed"], 4)
                self.assertEqual(hardened["afterCertCount"], 1)
                self.assertEqual(hardened["forbiddenRemaining"], [])

                listed = self.success_json(self.run_tool("list", "--store", store))
                self.assertEqual(listed["storeType"], expected_type)
                self.assertEqual(listed["certificateCount"], 1)
                self.assertEqual(
                    listed["entries"][0]["subject"], SYNTHETIC_CA_SUBJECT
                )
                self.assertEqual(
                    listed["entries"][0]["spkiSha256"],
                    self.__class__.synthetic_ca_info["spkiSha256"],
                )

    def test_harden_real_cacerts_preserves_unpinned_entries(self) -> None:
        store = self.tmp / "real-cacerts"
        shutil.copy(self.__class__.real_cacerts, store)
        before = self._list_store(store)
        before_spkis = {entry["spkiSha256"] for entry in before["entries"]}
        unpinned_before = before_spkis - self.pin_spkis

        hardened = self.success_json(
            self.run_tool(
                "harden",
                "--store",
                store,
                "--pins",
                PINS_PATH,
                "--canary-cert",
                CANARY_PATH,
            )
        )
        self.assertEqual(hardened["forbiddenRemaining"], [])
        after = self._list_store(store)
        after_spkis = {entry["spkiSha256"] for entry in after["entries"]}
        self.assertEqual(
            unpinned_before - after_spkis,
            set(),
            "harden removed unpinned certificates from the real JDK cacerts",
        )
        self.assertFalse(after_spkis & self.pin_spkis)
        self.assertEqual(
            after_spkis - before_spkis,
            {self.__class__.canary_info["spkiSha256"]},
            "the only new entry must be the canary certificate",
        )
        self.assertEqual(
            after["certificateCount"],
            len(before["entries"]) - hardened["removed"] + 1,
        )

    def test_harden_min_certs_failure_leaves_store_unchanged(self) -> None:
        store = self.copy_shared("small_store.jks", self.tmp / "small.jks")
        pinned_spki = self.fixture["roots"][0]["spkiSha256"]
        before_list = self.success_json(self.run_tool("list", "--store", store))
        self.assertEqual(before_list["certificateCount"], 2)
        self.assertIn(
            pinned_spki, {entry["spkiSha256"] for entry in before_list["entries"]}
        )
        before = sha256_file(store)
        result = self.run_tool(
            "harden",
            "--store",
            store,
            "--pins",
            PINS_PATH,
            "--min-certs",
            "10",
        )
        self.assert_failure(result, "below --min-certs")
        self.assertEqual(sha256_file(store), before, "store changed despite safety failure")
        after_list = self.success_json(self.run_tool("list", "--store", store))
        self.assertEqual(after_list["certificateCount"], 2)
        self.assertIn(
            pinned_spki,
            {entry["spkiSha256"] for entry in after_list["entries"]},
            "the pinned entry was removed despite the pre-write safety failure",
        )
        self.assert_no_tool_temp_files()

    def test_harden_refuses_symlinked_store_without_touching_target(self) -> None:
        target = self.copy_shared(
            "vulnerable_store.jks", self.tmp / "symlink-target.jks"
        )
        link = self.tmp / "symlink-store.jks"
        os.symlink(target, link)
        before = target.read_bytes()

        result = self.run_tool("harden", "--store", link, "--pins", PINS_PATH)
        self.assert_failure(result, "refusing to harden a symlinked truststore")
        self.assertIn("resolve the link explicitly", result.stderr)
        self.assertTrue(link.is_symlink(), "harden replaced the symlink itself")
        self.assertEqual(
            target.read_bytes(), before, "harden modified the symlink target"
        )
        self.assert_no_tool_temp_files()

        # verify/list remain read-only and may keep following symlinks.
        listed = self.success_json(self.run_tool("list", "--store", link))
        self.assertEqual(listed["certificateCount"], 5)
        verify = self.run_tool("verify", "--store", link, "--pins", PINS_PATH)
        self.assert_failure(verify, "pinned trust anchors remain")
        self.assertNotIn("refusing to harden", verify.stderr)

    def test_harden_refuses_hardlinked_store_without_touching_either_link(self) -> None:
        original = self.copy_shared(
            "vulnerable_store.jks", self.tmp / "hardlink-original.jks"
        )
        alias = self.tmp / "hardlink-alias.jks"
        try:
            os.link(original, alias)
        except OSError as error:
            self.skipTest(f"hardlinks are unavailable in this test directory: {error}")
        before = original.read_bytes()
        self.assertEqual(os.stat(original).st_ino, os.stat(alias).st_ino)

        result = self.run_tool("harden", "--store", alias, "--pins", PINS_PATH)
        self.assert_failure(
            result, "refusing to harden a truststore with multiple hard links"
        )
        self.assertIn("resolve the link explicitly", result.stderr)
        self.assertTrue(original.exists(), "harden removed the original store")
        self.assertTrue(alias.exists(), "harden removed the hardlink")
        self.assertEqual(original.read_bytes(), before, "harden rewrote the original")
        self.assertEqual(alias.read_bytes(), before, "harden rewrote the hardlink")
        self.assertEqual(
            os.stat(original).st_ino,
            os.stat(alias).st_ino,
            "harden broke the hardlink instead of refusing",
        )
        verify = self.run_tool("verify", "--store", original, "--pins", PINS_PATH)
        self.assert_failure(verify, "pinned trust anchors remain")
        self.assert_no_tool_temp_files()

    def test_harden_resolves_parent_directory_symlinks(self) -> None:
        real_dir = self.tmp / "real-store-dir"
        real_dir.mkdir()
        store = real_dir / "cacerts"
        shutil.copy(self.__class__.vulnerable_store, store)
        parent_link = self.tmp / "linked-store-dir"
        os.symlink(real_dir, parent_link)
        through_link = parent_link / "cacerts"

        hardened = self.success_json(
            self.run_tool("harden", "--store", through_link, "--pins", PINS_PATH)
        )
        self.assertEqual(hardened["removed"], 4)
        self.assertEqual(hardened["store"], str(store.resolve()))
        self.assertTrue(parent_link.is_symlink())
        self.assertFalse(through_link.is_symlink())
        listed = self.success_json(self.run_tool("list", "--store", store))
        self.assertEqual(listed["certificateCount"], 1)

    def test_harden_report_store_is_display_only(self) -> None:
        store = self.copy_shared("vulnerable_store.jks", self.tmp / "report.jks")
        before = sha256_file(store)
        for label, report in (("blank", " "), ("directory", str(self.tmp))):
            with self.subTest(case=label):
                result = self.run_tool(
                    "harden",
                    "--store",
                    store,
                    "--pins",
                    PINS_PATH,
                    "--report-store",
                    report,
                )
                self.assert_failure(result, "--report-store")
                self.assertEqual(sha256_file(store), before)

        reported = self.tmp / "reported" / "cacerts"
        hardened = self.success_json(
            self.run_tool(
                "harden",
                "--store",
                store,
                "--pins",
                PINS_PATH,
                "--report-store",
                reported,
            )
        )
        self.assertEqual(hardened["store"], str(reported))
        self.assertEqual(hardened["storeSha256"], sha256_file(store))
        self.assertFalse(reported.exists(), "--report-store created a file")
        self.assert_no_tool_temp_files()

    def test_harden_rejects_zero_min_certs_without_modifying_store(self) -> None:
        store = self.copy_shared("vulnerable_store.jks", self.tmp / "zero.jks")
        before = sha256_file(store)
        result = self.run_tool(
            "harden",
            "--store",
            store,
            "--pins",
            PINS_PATH,
            "--min-certs",
            "0",
        )
        self.assert_failure(result, "--min-certs must be >= 1")
        self.assertEqual(sha256_file(store), before)
        self.assert_no_tool_temp_files()

    def test_harden_refuses_to_write_empty_truststore(self) -> None:
        store = self.tmp / "pinned-only.jks"
        self.__class__._import_cert(
            store,
            "pinned-root",
            self.__class__.root_pems[0],
            storetype="JKS",
        )
        before = sha256_file(store)
        result = self.run_tool("harden", "--store", store, "--pins", PINS_PATH)
        self.assert_failure(result, "refusing to write an empty truststore")
        self.assertEqual(sha256_file(store), before)
        self.assert_no_tool_temp_files()

    def test_harden_refuses_pinned_key_entry(self) -> None:
        store = self.copy_shared("key-entry.p12", self.tmp / "key-entry.p12")
        pins = self.write_text(
            self.tmp / "key-entry-pins.txt",
            f"{self.__class__.synthetic_ca_info['spkiSha256']} synthetic test CA\n",
        )
        before = sha256_file(store)
        result = self.run_tool("harden", "--store", store, "--pins", pins)
        self.assert_failure(result, "refusing to delete pinned key entry")
        self.assertEqual(sha256_file(store), before, "store changed despite key-entry refusal")
        self.assert_no_tool_temp_files()

    def test_harden_fails_cleanly_on_bad_inputs(self) -> None:
        cases = []
        empty = self.tmp / "empty.jks"
        empty.write_bytes(b"")
        cases.append(("empty", empty, "empty truststore"))
        corrupt = self.tmp / "corrupt.jks"
        corrupt.write_bytes(b"this is not a keystore")
        cases.append(("corrupt", corrupt, "unsupported truststore format"))
        corrupt_jks = self.tmp / "corrupt-magic.jks"
        corrupt_jks.write_bytes(b"\xfe\xed\xfe\xed" + b"\x00" * 64)
        cases.append(("corrupt-jks-load", corrupt_jks, "wrong password or corrupt store"))
        corrupt_pkcs12 = self.tmp / "corrupt.p12"
        corrupt_pkcs12.write_bytes(b"\x30\x82\x01\x00" + b"\x00" * 64)
        cases.append(
            ("corrupt-pkcs12-load", corrupt_pkcs12, "wrong password or corrupt store")
        )
        wrong_password = self.copy_shared(
            "wrong_password_store.jks", self.tmp / "wrong-password.jks"
        )
        cases.append(("wrong-password", wrong_password, "wrong password or corrupt store"))

        for label, store, expected_message in cases:
            with self.subTest(case=label):
                before = store.read_bytes()
                result = self.run_tool(
                    "harden", "--store", store, "--pins", PINS_PATH
                )
                self.assert_clean_failure(result)
                self.assertIn(expected_message, result.stderr)
                self.assertEqual(store.read_bytes(), before, f"{label}: store was modified")
        self.assert_no_tool_temp_files()

    # ------------------------------------------------------------------
    # C. Verify and effective JSSE store
    # ------------------------------------------------------------------

    def test_verify_fails_with_pins_and_passes_after_harden(self) -> None:
        store = self.copy_shared("vulnerable_store.jks", self.tmp / "verify.jks")
        before = self.run_tool("verify", "--store", store, "--pins", PINS_PATH)
        self.assert_failure(before, "pinned trust anchors remain")
        self.assertEqual(before.stdout.strip(), "")

        hardened = self.success_json(
            self.run_tool("harden", "--store", store, "--pins", PINS_PATH)
        )
        self.assertEqual(hardened["afterCertCount"], 1)

        after = self.success_json(
            self.run_tool("verify", "--store", store, "--pins", PINS_PATH)
        )
        self.assertEqual(after["forbiddenRemaining"], [])
        self.assertEqual(after["certificateCount"], 1)

        floor = self.run_tool(
            "verify", "--store", store, "--pins", PINS_PATH, "--min-certs", "10"
        )
        self.assert_failure(floor, "below --min-certs")
        self.assertEqual(floor.stdout.strip(), "")

        at_floor = self.success_json(
            self.run_tool(
                "verify", "--store", store, "--pins", PINS_PATH, "--min-certs", "1"
            )
        )
        self.assertEqual(at_floor["certificateCount"], 1)

    def test_verify_effective_hardened_store(self) -> None:
        store = self.copy_shared("vulnerable_store.jks", self.tmp / "effective.jks")
        self.success_json(
            self.run_tool(
                "harden",
                "--store",
                store,
                "--pins",
                PINS_PATH,
                "--canary-cert",
                CANARY_PATH,
            )
        )
        env = self.jsse_env(store)
        result = self.run_tool(
            "verify-effective",
            "--pins",
            PINS_PATH,
            "--canary-cert",
            CANARY_PATH,
            "--min-issuers",
            "2",
            env=env,
        )
        effective = self.success_json(result)
        self.assertEqual(effective["command"], "verify-effective")
        self.assertEqual(effective["store"], str(store))
        self.assertEqual(effective["storeType"], "JKS")
        self.assertEqual(effective["storeSha256"], sha256_file(store))
        self.assertEqual(effective["issuerCount"], 2)
        self.assertTrue(effective["canaryPresent"])
        self.assertEqual(
            effective["canarySha256"], self.__class__.canary_info["certSha256"]
        )
        self.assertEqual(effective["forbiddenRemaining"], [])
        self.assertEqual(effective["forbiddenNames"], [])

    def test_verify_effective_requires_canary(self) -> None:
        store = self.copy_shared("synthetic_store.jks", self.tmp / "no-canary.jks")
        env = self.jsse_env(store)
        result = self.run_tool(
            "verify-effective",
            "--pins",
            PINS_PATH,
            "--canary-cert",
            CANARY_PATH,
            "--min-issuers",
            "1",
            env=env,
        )
        self.assert_failure(result, "canary certificate")
        self.assertIn("absent from the default truststore", result.stderr)
        self.assertNotIn("pinned trust anchors remain", result.stderr)

    def test_verify_effective_missing_store_does_not_fall_back(self) -> None:
        missing = self.tmp / "missing" / "cacerts"
        env = self.__class__.base_env.copy()
        env["JAVA_TOOL_OPTIONS"] = (
            f'-Djavax.net.ssl.trustStore="{missing}" '
            f"-Djavax.net.ssl.trustStorePassword={PASSWORD} "
            "-Djavax.net.ssl.trustStoreType=JKS"
        )
        result = self.run_tool(
            "verify-effective",
            "--pins",
            PINS_PATH,
            "--canary-cert",
            CANARY_PATH,
            "--min-issuers",
            "1",
            env=env,
        )
        self.assert_failure(result, "JSSE would fall back to the JDK default truststore")
        self.assertIn("is not a readable file", result.stderr)

        directory = self.tmp / "not-a-file"
        directory.mkdir()
        env["JAVA_TOOL_OPTIONS"] = (
            f'-Djavax.net.ssl.trustStore="{directory}" '
            f"-Djavax.net.ssl.trustStorePassword={PASSWORD} "
            "-Djavax.net.ssl.trustStoreType=JKS"
        )
        result = self.run_tool(
            "verify-effective",
            "--pins",
            PINS_PATH,
            "--canary-cert",
            CANARY_PATH,
            "--min-issuers",
            "1",
            env=env,
        )
        self.assert_failure(result, "JSSE would fall back to the JDK default truststore")

    def test_verify_effective_default_store_cannot_pass(self) -> None:
        env = self.__class__.base_env.copy()
        env.pop("JAVA_TOOL_OPTIONS", None)

        # With the real pin list the vendor store may fail because it still has
        # a pinned root, or because the canary is absent. Either is a failure.
        result = self.run_tool(
            "verify-effective",
            "--pins",
            PINS_PATH,
            "--canary-cert",
            CANARY_PATH,
            "--min-issuers",
            "1",
            env=env,
        )
        self.assert_failure(result)
        self.assertTrue(
            "canary certificate" in result.stderr
            or "still accepts pinned anchors" in result.stderr,
            msg=self.describe(result),
        )

        # Isolate the canary requirement: a pin that cannot match any vendor
        # anchor must still fail because the canary is absent.
        dummy_pins = self.write_text(
            self.tmp / "dummy-pins.txt",
            f"{'00' * 32} no such anchor\n",
        )
        result = self.run_tool(
            "verify-effective",
            "--pins",
            dummy_pins,
            "--canary-cert",
            CANARY_PATH,
            "--min-issuers",
            "1",
            env=env,
        )
        self.assert_failure(result, "canary certificate")
        self.assertIn("absent from the default truststore", result.stderr)

    # ------------------------------------------------------------------
    # D. Real public chains and probe behavior
    # ------------------------------------------------------------------

    def test_public_incident_chains_accepted_before_and_rejected_after_harden(self) -> None:
        store = self.copy_shared("vulnerable_store.jks", self.tmp / "chains.jks")

        for chain in self.fixture["chains"]:
            with self.subTest(chain=chain["name"], phase="before-harden"):
                chain_path = self.write_chain(chain)
                result = self.run_probe(
                    "--truststore",
                    store,
                    "--chain",
                    chain_path,
                    "--date",
                    FIXED_DATE,
                    "--expect",
                    "accepted",
                )
                probe = self.success_json(result)
                self.assertEqual(probe["command"], "chain-trust-probe")
                self.assertEqual(probe["storeType"], "JKS")
                self.assertEqual(probe["date"], FIXED_DATE)
                self.assertEqual(probe["chainLength"], 1 + len(chain["intermediatePems"]))
                self.assertEqual(probe["trustAnchorCount"], 5)
                self.assertTrue(probe["valid"], msg=self.describe(result))
                self.assertEqual(probe["expect"], "accepted")
                self.assertTrue(probe["matched"])
                self.assertGreater(probe["pathLength"], 0)
                # The probe tries every chain prefix, not just up to the first
                # success, so a longer prefix cannot hide a failure.
                self.assertEqual(
                    len(probe["attempts"]), 1 + len(chain["intermediatePems"])
                )
                successful_attempts = [
                    attempt
                    for attempt in probe["attempts"]
                    if attempt["error"] is None
                ]
                self.assertTrue(
                    successful_attempts,
                    msg=f"no successful attempt in {probe['attempts']}",
                )
                successful_attempt = successful_attempts[0]
                self.assertIsNotNone(successful_attempt["anchorSubject"])
                self.assertEqual(
                    successful_attempt["pathLength"], probe["pathLength"]
                )
                self.assertEqual(successful_attempt["anchorSubject"], probe["anchorSubject"])
                anchor_match = re.search(
                    r"(?:^|,)CN=([^,]+)", probe["anchorSubject"] or ""
                )
                self.assertIsNotNone(
                    anchor_match,
                    msg=f"cannot extract CN from anchor {probe['anchorSubject']!r}",
                )
                self.assertIn(anchor_match.group(1), chain["expectedAnchors"])

                # Prove each prefix validates through the expected root:
                # the 2-certificate attempt ends at emSign Root TLS CA - G3
                # (or AAA Certificate Services), and the 3-certificate
                # attempt follows the cross-signed emSign ECC Root CA - G3.
                if chain["name"].endswith("-direct"):
                    self.assertEqual(len(probe["attempts"]), 3)
                    self.assertEqual(probe["attempts"][1]["pathLength"], 2)
                    self.assertIsNone(probe["attempts"][1]["error"])
                    self.assertIn(
                        "emSign Root TLS CA - G3",
                        probe["attempts"][1]["anchorSubject"],
                    )
                    self.assertEqual(probe["attempts"][2]["pathLength"], 3)
                    self.assertIsNone(probe["attempts"][2]["error"])
                    self.assertIn(
                        "emSign ECC Root CA - G3",
                        probe["attempts"][2]["anchorSubject"],
                    )
                elif chain["name"].endswith("-aaa"):
                    self.assertEqual(len(probe["attempts"]), 2)
                    self.assertEqual(probe["attempts"][1]["pathLength"], 2)
                    self.assertIsNone(probe["attempts"][1]["error"])
                    self.assertIn(
                        "AAA Certificate Services",
                        probe["attempts"][1]["anchorSubject"],
                    )

                # Every non-leaf certificate in the served chain must carry a
                # pinned SPKI, including the cross-signed root certificate.
                for pem in chain["intermediatePems"]:
                    self.assertIn(
                        self._spki_for_pem(pem),
                        self.pin_spkis,
                        msg=f"unpinned intermediate in chain {chain['name']}",
                    )

        hardened = self.success_json(
            self.run_tool(
                "harden",
                "--store",
                store,
                "--pins",
                PINS_PATH,
                "--canary-cert",
                CANARY_PATH,
            )
        )
        self.assertEqual(hardened["removed"], 4)
        self.assertEqual(hardened["forbiddenRemaining"], [])

        listed = self.success_json(self.run_tool("list", "--store", store))
        listed_spkis = {entry["spkiSha256"] for entry in listed["entries"]}
        self.assertFalse(listed_spkis & self.pin_spkis)
        self.assertIn(self.__class__.synthetic_ca_info["spkiSha256"], listed_spkis)

        for chain in self.fixture["chains"]:
            with self.subTest(chain=chain["name"], phase="after-harden"):
                chain_path = self.write_chain(chain)
                result = self.run_probe(
                    "--truststore",
                    store,
                    "--chain",
                    chain_path,
                    "--date",
                    FIXED_DATE,
                    "--expect",
                    "rejected",
                )
                probe = self.success_json(result)
                self.assertFalse(probe["valid"], msg=self.describe(result))
                self.assertEqual(probe["expect"], "rejected")
                self.assertTrue(probe["matched"])
                self.assertEqual(probe["pathLength"], 0)
                self.assertIsNone(probe["anchorSubject"])
                self.assertEqual(
                    len(probe["attempts"]), 1 + len(chain["intermediatePems"])
                )
                for attempt in probe["attempts"]:
                    self.assertIsNone(attempt["anchorSubject"])
                    self.assertTrue(attempt["error"])
                    self.assertIn(
                        "NO_TRUST_ANCHOR",
                        attempt["error"],
                        msg=(
                            "expected rejection from loss of the trust anchor, "
                            f"got {attempt['error']!r}"
                        ),
                    )

    def test_synthetic_leaf_still_accepted_after_harden(self) -> None:
        store = self.copy_shared("vulnerable_store.jks", self.tmp / "positive.jks")
        self.success_json(
            self.run_tool(
                "harden",
                "--store",
                store,
                "--pins",
                PINS_PATH,
                "--canary-cert",
                CANARY_PATH,
            )
        )
        leaf_chain = self.write_text(
            self.tmp / "synthetic-leaf.pem",
            self.__class__.synthetic_leaf_pem.read_text(encoding="ascii"),
        )
        result = self.run_probe(
            "--truststore",
            store,
            "--chain",
            leaf_chain,
            "--date",
            FIXED_DATE,
            "--expect",
            "accepted",
        )
        probe = self.success_json(result)
        self.assertTrue(probe["valid"], msg=self.describe(result))
        self.assertEqual(probe["chainLength"], 1)
        self.assertEqual(probe["pathLength"], 1)
        self.assertEqual(probe["anchorSubject"], SYNTHETIC_CA_SUBJECT)
        self.assertEqual(probe["trustAnchorCount"], 2)
        self.assertEqual(probe["attempts"][0]["error"], None)

    def test_extra_pin_removes_synthetic_ca_and_rejects_leaf(self) -> None:
        store = self.copy_shared("vulnerable_store.jks", self.tmp / "negative.jks")
        pins = self.write_text(
            self.tmp / "extra-pins.txt",
            PINS_PATH.read_text(encoding="utf-8")
            + f"{self.__class__.synthetic_ca_info['spkiSha256']} synthetic test CA\n",
        )
        hardened = self.success_json(
            self.run_tool(
                "harden",
                "--store",
                store,
                "--pins",
                pins,
                "--canary-cert",
                CANARY_PATH,
            )
        )
        self.assertEqual(hardened["removed"], 5)
        self.assertEqual(hardened["afterCertCount"], 1)
        self.assertEqual(hardened["forbiddenRemaining"], [])
        removed_spkis = {entry["spkiSha256"] for entry in hardened["removedEntries"]}
        self.assertIn(self.__class__.synthetic_ca_info["spkiSha256"], removed_spkis)

        leaf_chain = self.write_text(
            self.tmp / "synthetic-leaf.pem",
            self.__class__.synthetic_leaf_pem.read_text(encoding="ascii"),
        )
        result = self.run_probe(
            "--truststore",
            store,
            "--chain",
            leaf_chain,
            "--date",
            FIXED_DATE,
            "--expect",
            "rejected",
        )
        probe = self.success_json(result)
        self.assertFalse(probe["valid"], msg=self.describe(result))
        self.assertEqual(probe["pathLength"], 0)
        self.assertEqual(len(probe["attempts"]), 1)
        self.assertTrue(probe["attempts"][0]["error"])

    def test_probe_rejects_without_incident_anchors_and_before_not_before(self) -> None:
        chain = next(
            item for item in self.fixture["chains"] if item["name"] == "dv-g3b-direct"
        )
        chain_path = self.write_chain(chain)

        synthetic_only = self.copy_shared(
            "synthetic_store.jks", self.tmp / "synthetic-only.jks"
        )
        result = self.run_probe(
            "--truststore",
            synthetic_only,
            "--chain",
            chain_path,
            "--date",
            FIXED_DATE,
            "--expect",
            "rejected",
        )
        probe = self.success_json(result)
        self.assertFalse(probe["valid"], msg=self.describe(result))
        self.assertEqual(probe["trustAnchorCount"], 1)
        self.assertEqual(len(probe["attempts"]), 3)
        for attempt in probe["attempts"]:
            self.assertIsNone(attempt["anchorSubject"])
            self.assertTrue(attempt["error"])

        vulnerable = self.copy_shared(
            "vulnerable_store.jks", self.tmp / "date-check.jks"
        )
        accepted = self.success_json(
            self.run_probe(
                "--truststore",
                vulnerable,
                "--chain",
                chain_path,
                "--date",
                FIXED_DATE,
                "--expect",
                "accepted",
            )
        )
        self.assertTrue(accepted["valid"])

        too_early = self.success_json(
            self.run_probe(
                "--truststore",
                vulnerable,
                "--chain",
                chain_path,
                "--date",
                OLD_DATE,
                "--expect",
                "rejected",
            )
        )
        self.assertFalse(too_early["valid"], msg=f"unexpected probe result: {too_early}")
        self.assertEqual(too_early["date"], OLD_DATE)
        self.assertEqual(too_early["trustAnchorCount"], 5)
        self.assertEqual(len(too_early["attempts"]), 3)
        self.assertTrue(
            any("NOT_YET_VALID" in attempt["error"] for attempt in too_early["attempts"]),
            msg=f"expected a NOT_YET_VALID attempt: {too_early['attempts']}",
        )

    # ------------------------------------------------------------------
    # E. Shell wrapper
    # ------------------------------------------------------------------

    def test_probe_tls_requires_configured_store_unless_allowed(self) -> None:
        env = self.__class__.base_env.copy()
        env.pop("JAVA_TOOL_OPTIONS", None)
        started = time.monotonic()
        result = self.run_tool("probe-tls", env=env, timeout=30)
        elapsed = time.monotonic() - started
        self.assert_failure(result, "--allow-default-store")
        self.assertIn("javax.net.ssl.trustStore", result.stderr)
        self.assertIn("JDK default truststore", result.stderr)
        self.assertNotIn("probe-tls:", result.stderr)
        self.assertLess(
            elapsed, 10.0, f"probe-tls did not fail immediately (took {elapsed:.1f}s)"
        )

        # A configured path that is not a readable file must also fail closed
        # before the gate can reach the network.
        env["JAVA_TOOL_OPTIONS"] = (
            f'-Djavax.net.ssl.trustStore="{self.tmp / "missing-probe-store"}"'
        )
        result = self.run_tool("probe-tls", env=env, timeout=30)
        self.assert_failure(result, "--allow-default-store")
        self.assertIn("is not a readable file", result.stderr)
        self.assertNotIn("probe-tls:", result.stderr)

        # --allow-default-store removes the gate; the http:// rejection proves
        # execution continued past it without any network access.
        result = self.run_tool(
            "probe-tls", "--allow-default-store", "--url", "http://example.invalid/",
            env=env,
            timeout=30,
        )
        self.assert_failure(result, "probe URL must use https")
        self.assertNotIn("--allow-default-store", result.stderr)

    def test_wrapper_probe_tls_gates_before_network_probe(self) -> None:
        fake = self.make_fake_jdk("fake-jdk-probe")
        log = self.tmp / "fake-java-args.log"
        java_script = fake / "bin" / "java"
        java_script.unlink()
        java_script.write_text(
            "#!/usr/bin/env bash\n"
            'printf \'%s\\n\' "$*" >> "$FAKE_JAVA_LOG"\n'
            'case " $* " in\n'
            '  *" verify-effective "*) exit 1 ;;\n'
            "esac\n"
            "exit 0\n",
            encoding="ascii",
        )
        java_script.chmod(0o755)
        configured_store = self.copy_shared(
            "vulnerable_store.jks", self.tmp / "configured-store.jks"
        )
        env = self.jsse_env(configured_store)
        env["FAKE_JAVA_LOG"] = str(log)

        result = self.run_wrapper("--java-home", fake, "--probe-tls", env=env)
        self.assert_failure(result)
        self.assertTrue(log.is_file(), "the wrapper never invoked the fake java")
        invocations = log.read_text(encoding="ascii").splitlines()
        self.assertTrue(
            any("verify-effective" in line for line in invocations),
            msg=f"verify-effective was not invoked before probing: {invocations}",
        )
        self.assertFalse(
            any("probe-tls" in line for line in invocations),
            msg=f"probe-tls ran even though verify-effective failed: {invocations}",
        )

    def test_wrapper_check_only_detects_and_clears_pins(self) -> None:
        fake = self.make_fake_jdk()
        cacerts = fake / "lib" / "security" / "cacerts"
        before = sha256_file(cacerts)

        result = self.run_wrapper("--java-home", fake, "--check-only")
        self.assert_failure(result, "still contains one or more incident trust anchors")
        self.assertIn("This check did not modify the JDK installation", result.stderr)
        self.assertEqual(sha256_file(cacerts), before, "--check-only modified cacerts")

        out_store = self.out_store_path("hardened", "cacerts")
        env_file = self.tmp / "github-env"
        harden = self.run_wrapper(
            "--java-home",
            fake,
            "--out-store",
            out_store,
            "--out-env",
            env_file,
        )
        self.assert_success(harden)
        self.assertTrue(out_store.is_file())
        hardened = self.json_from(harden)
        self.assertTrue(hardened["canaryPresent"])
        self.assertEqual(
            hardened["canarySha256"], self.__class__.canary_info["certSha256"]
        )
        self.assertEqual(hardened["forbiddenRemaining"], [])

        shutil.copy(out_store, cacerts)
        after = self.run_wrapper("--java-home", fake, "--check-only")
        self.assert_success(after)
        self.assertIn("no pinned anchors remain", after.stderr)
        self.assert_no_tool_temp_files()

    def test_wrapper_harden_and_verify_only_round_trip(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("out", "cacerts")
        resolved_out_store = out_store.resolve()
        env_file = self.tmp / "github-env"
        result = self.run_wrapper(
            "--java-home",
            fake,
            "--out-store",
            out_store,
            "--out-env",
            env_file,
        )
        self.assert_success(result)
        self.assertTrue(out_store.is_file())
        self.assertTrue(env_file.is_file())

        expected_type = store_type(out_store)
        options = self.read_env_file(env_file)
        self.assertIn(f'-Djavax.net.ssl.trustStore="{resolved_out_store}"', options)
        self.assertIn(f"-Djavax.net.ssl.trustStorePassword={PASSWORD}", options)
        self.assertIn(f"-Djavax.net.ssl.trustStoreType={expected_type}", options)

        hardened = self.json_from(result)
        self.assertEqual(hardened["command"], "harden")
        self.assertEqual(
            hardened["store"],
            str(resolved_out_store),
            "harden JSON reported the staging path instead of the installed store",
        )
        self.assertEqual(hardened["storeSha256"], sha256_file(out_store))
        self.assertTrue(hardened["canaryPresent"])
        self.assertEqual(
            hardened["canarySha256"], self.__class__.canary_info["certSha256"]
        )
        self.assertEqual(hardened["forbiddenRemaining"], [])
        self.assertGreaterEqual(hardened["certificateCount"], 50)

        env = self.__class__.base_env.copy()
        env["JAVA_TOOL_OPTIONS"] = options
        verify = self.run_wrapper("--verify-only", env=env)
        effective = self.success_json(verify)
        self.assertEqual(effective["command"], "verify-effective")
        self.assertEqual(effective["store"], str(resolved_out_store))
        self.assertEqual(effective["storeType"], expected_type)
        self.assertEqual(effective["storeSha256"], sha256_file(out_store))
        self.assertTrue(effective["canaryPresent"])
        self.assertEqual(effective["forbiddenRemaining"], [])
        self.assertGreaterEqual(effective["issuerCount"], 50)
        self.assert_no_tool_temp_files()

    def test_wrapper_verify_only_uses_last_truststore_definition(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("out", "cacerts")
        result = self.run_wrapper(
            "--java-home", fake, "--out-store", out_store, "--print-env"
        )
        self.assert_success(result)
        missing = self.tmp / "missing-store"
        storetype = store_type(out_store)
        password_option = f"-Djavax.net.ssl.trustStorePassword={PASSWORD}"
        type_option = f"-Djavax.net.ssl.trustStoreType={storetype}"
        cases = {
            "quoted-first-unquoted-last": (
                f'-Djavax.net.ssl.trustStore="{missing}" '
                f"-Djavax.net.ssl.trustStore={out_store} "
                f"{password_option} {type_option}"
            ),
            "unquoted-first-quoted-last": (
                f"-Djavax.net.ssl.trustStore={missing} "
                f'-Djavax.net.ssl.trustStore="{out_store}" '
                f"{password_option} {type_option}"
            ),
        }
        for label, options in cases.items():
            with self.subTest(case=label):
                env = self.__class__.base_env.copy()
                env["JAVA_TOOL_OPTIONS"] = options
                verify = self.run_wrapper("--verify-only", env=env)
                effective = self.success_json(verify)
                self.assertEqual(effective["command"], "verify-effective")
                self.assertEqual(
                    effective["store"],
                    str(out_store),
                    "the wrapper used an earlier -Djavax.net.ssl.trustStore value",
                )
                self.assertEqual(effective["storeType"], storetype)
                self.assertTrue(effective["canaryPresent"])
                self.assertEqual(effective["forbiddenRemaining"], [])

    def test_wrapper_out_store_with_spaces(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("dir with spaces", "out", "cacerts")
        resolved_out_store = out_store.resolve()
        env_file = self.tmp / "env file with spaces"
        result = self.run_wrapper(
            "--java-home",
            fake,
            "--out-store",
            out_store,
            "--out-env",
            env_file,
        )
        self.assert_success(result)
        options = self.read_env_file(env_file)
        self.assertIn(f'-Djavax.net.ssl.trustStore="{resolved_out_store}"', options)

        env = self.__class__.base_env.copy()
        env["JAVA_TOOL_OPTIONS"] = options
        verify = self.run_wrapper("--verify-only", env=env)
        effective = self.success_json(verify)
        self.assertEqual(effective["store"], str(resolved_out_store))
        self.assertTrue(effective["canaryPresent"])
        self.assertEqual(effective["forbiddenRemaining"], [])

    def test_wrapper_refuses_unsafe_or_conflicting_options(self) -> None:
        fake = self.make_fake_jdk()

        inside = fake / "lib" / "security" / "hardened.jks"
        result = self.run_wrapper("--java-home", fake, "--out-store", inside)
        self.assert_failure(result, "refusing to write inside JAVA_HOME")
        self.assertFalse(inside.exists(), "wrapper wrote inside JAVA_HOME")

        target = self.tmp / "symlink-target.jks"
        shutil.copy(self.__class__.vulnerable_store, target)
        link = self.tmp / "outlink.jks"
        os.symlink(target, link)
        before = sha256_file(target)
        result = self.run_wrapper("--java-home", fake, "--out-store", link)
        self.assert_failure(result, "refusing to write to a symlink path")
        self.assertTrue(link.is_symlink())
        self.assertEqual(sha256_file(target), before, "wrapper wrote through a symlink")

        result = self.run_wrapper("--verify-only", "--check-only")
        self.assert_failure(result, "cannot be combined")

        result = self.run_wrapper("--verify-only", "--out-store", self.tmp / "x")
        self.assert_failure(result, "--out-store is only valid when hardening")

        env = self.__class__.base_env.copy()
        env.pop("JAVA_TOOL_OPTIONS", None)
        result = self.run_wrapper("--verify-only", env=env)
        self.assert_failure(result, "requires JAVA_TOOL_OPTIONS")

    def test_wrapper_failure_leaves_existing_out_store_unchanged(self) -> None:
        small_jdk = self.make_fake_jdk(
            "fake-jdk-small", source=self.__class__.small_store
        )
        out_store = self.out_store_path("out", "cacerts")
        original = b"previously hardened store bytes\n"
        out_store.write_bytes(original)

        result = self.run_wrapper("--java-home", small_jdk, "--out-store", out_store)
        self.assert_failure(result, "below --min-certs")
        self.assertEqual(
            out_store.read_bytes(),
            original,
            "failed harden replaced the existing out-store",
        )
        leftovers = sorted(out_store.parent.glob(out_store.name + ".tmp.*"))
        self.assertEqual(leftovers, [], f"staging files left behind: {leftovers}")
        self.assert_no_tool_temp_files()

        # The success path still installs the hardened store at the final path.
        good_jdk = self.make_fake_jdk("fake-jdk-good")
        env_file = self.tmp / "github-env-success"
        success = self.run_wrapper(
            "--java-home",
            good_jdk,
            "--out-store",
            out_store,
            "--out-env",
            env_file,
        )
        self.assert_success(success)
        hardened = self.json_from(success)
        self.assertTrue(hardened["canaryPresent"])
        self.assertEqual(hardened["forbiddenRemaining"], [])
        self.assertGreaterEqual(hardened["certificateCount"], 50)
        listed = self._list_store(out_store)
        self.assertEqual(listed["certificateCount"], hardened["certificateCount"])
        self.assertEqual(
            store_type(out_store),
            store_type(good_jdk / "lib" / "security" / "cacerts"),
        )
        leftovers = sorted(out_store.parent.glob(out_store.name + ".tmp.*"))
        self.assertEqual(leftovers, [], f"staging files left behind: {leftovers}")

    def test_wrapper_refuses_out_env_inside_repository(self) -> None:
        fake = self.make_fake_jdk()
        relative_pins = os.path.relpath(PINS_PATH, Path.cwd())
        for candidate in (PINS_PATH, relative_pins):
            with self.subTest(path=str(candidate)):
                before = sha256_file(PINS_PATH)
                result = self.run_wrapper(
                    "--java-home", fake, "--out-env", candidate
                )
                self.assert_failure(result, "inside the repository")
                self.assertIn("refusing to write out-env", result.stderr)
                self.assertEqual(sha256_file(PINS_PATH), before)

    def test_wrapper_refuses_out_store_hardlink_to_repository_file(self) -> None:
        fake = self.make_fake_jdk()
        link = self.make_repo_filesystem_hardlink(PINS_PATH, "pins-hardlink.txt")
        before = sha256_file(PINS_PATH)
        result = self.run_wrapper("--java-home", fake, "--out-store", link)
        self.assert_failure(result, "hardlink to a repository file")
        self.assertEqual(sha256_file(PINS_PATH), before)
        self.assertTrue(link.exists())
        self.assertEqual(os.stat(link).st_ino, os.stat(PINS_PATH).st_ino)

    def test_wrapper_refuses_out_store_that_is_the_wrapper(self) -> None:
        fake = self.make_fake_jdk()
        before = sha256_file(WRAPPER)
        result = self.run_wrapper("--java-home", fake, "--out-store", WRAPPER)
        self.assert_failure(result, "inside the repository")
        self.assertEqual(sha256_file(WRAPPER), before)

    def test_wrapper_refuses_same_out_store_and_out_env(self) -> None:
        fake = self.make_fake_jdk()
        path = self.tmp / "same-output"
        result = self.run_wrapper(
            "--java-home", fake, "--out-store", path, "--out-env", path
        )
        self.assert_failure(result, "must not be the same path")
        self.assertFalse(path.exists())

    def test_wrapper_refuses_symlinked_out_env(self) -> None:
        fake = self.make_fake_jdk()
        target = self.tmp / "env-target"
        target.write_text("EXISTING=1\n", encoding="ascii")
        link = self.tmp / "env-link"
        os.symlink(target, link)
        result = self.run_wrapper("--java-home", fake, "--out-env", link)
        self.assert_failure(result, "refusing to write to a symlink path")
        self.assertTrue(link.is_symlink())
        self.assertEqual(target.read_text(encoding="ascii"), "EXISTING=1\n")

    def test_wrapper_refuses_out_env_hardlink_to_repository_file(self) -> None:
        fake = self.make_fake_jdk()
        link = self.make_repo_filesystem_hardlink(PINS_PATH, "pins-env-hardlink.txt")
        before = sha256_file(PINS_PATH)
        result = self.run_wrapper("--java-home", fake, "--out-env", link)
        self.assert_failure(result, "hardlink to a repository file")
        self.assertEqual(sha256_file(PINS_PATH), before)
        self.assertTrue(link.exists())
        self.assertEqual(os.stat(link).st_ino, os.stat(PINS_PATH).st_ino)

    def test_wrapper_refuses_out_env_directory(self) -> None:
        fake = self.make_fake_jdk()
        directory = self.tmp / "env-dir"
        directory.mkdir()
        result = self.run_wrapper("--java-home", fake, "--out-env", directory)
        self.assert_failure(result, "out-env is a directory")
        self.assertTrue(directory.is_dir())

    def test_wrapper_refuses_out_env_inside_java_home_or_jdk_cacerts(self) -> None:
        fake = self.make_fake_jdk()
        cacerts = fake / "lib" / "security" / "cacerts"
        before = sha256_file(cacerts)
        for candidate in (cacerts, fake / "lib" / "security" / "env", fake / "env"):
            with self.subTest(path=str(candidate)):
                result = self.run_wrapper("--java-home", fake, "--out-env", candidate)
                self.assert_failure(result, "refusing to write inside JAVA_HOME")
                self.assertEqual(sha256_file(cacerts), before)
                self.assertFalse(
                    candidate.exists() and candidate != cacerts,
                    f"wrapper created {candidate} inside JAVA_HOME",
                )

        # A hardlink outside JAVA_HOME still aliases the JDK cacerts.
        hardlink = self.tmp / "jdk-cacerts-hardlink"
        try:
            os.link(cacerts, hardlink)
        except OSError as error:
            self.skipTest(f"hardlinks are unavailable in this test directory: {error}")
        result = self.run_wrapper("--java-home", fake, "--out-env", hardlink)
        self.assert_failure(result, "out-env is the JDK truststore (same file)")
        self.assertEqual(sha256_file(cacerts), before, "wrapper modified the JDK cacerts")
        self.assertTrue(hardlink.exists(), "wrapper removed the hardlink")
        self.assertEqual(os.stat(hardlink).st_ino, os.stat(cacerts).st_ino)
        self.assert_no_staging_leftovers(self.tmp)

    def test_wrapper_refuses_out_env_fifo_without_hanging(self) -> None:
        if not hasattr(os, "mkfifo"):
            self.skipTest("os.mkfifo is unavailable on this platform")
        fake = self.make_fake_jdk()
        fifo = self.tmp / "env-fifo"
        try:
            os.mkfifo(fifo)
        except OSError as error:
            self.skipTest(f"cannot create a FIFO in this test directory: {error}")
        self.assertTrue(stat.S_ISFIFO(os.stat(fifo).st_mode))
        try:
            result = self.run_wrapper(
                "--java-home", fake, "--out-env", fifo, timeout=15
            )
        except subprocess.TimeoutExpired:
            self.fail("wrapper hung on a FIFO --out-env instead of rejecting it")
        self.assert_failure(result, "out-env is not a regular file")
        self.assertTrue(
            stat.S_ISFIFO(os.stat(fifo).st_mode), "wrapper replaced or removed the FIFO"
        )
        self.assert_no_staging_leftovers(self.tmp)

    def test_wrapper_refuses_out_env_symlink_swapped_during_hardening(self) -> None:
        """Deterministic TOCTOU: the env path becomes a symlink during harden."""
        fake = self.make_fake_jdk("fake-jdk-env-swap")
        real_java = self.__class__.java.resolve()
        java_script = fake / "bin" / "java"
        java_script.unlink()
        java_script.write_text(
            "#!/usr/bin/env bash\n"
            'case " $* " in\n'
            '  *" harden "*) ln -sfn "$SWAP_TARGET" "$SWAP_PATH" ;;\n'
            "esac\n"
            'exec "$REAL_JAVA" "$@"\n',
            encoding="ascii",
        )
        java_script.chmod(0o755)

        victim = self.tmp / "swap-victim"
        victim.write_text("DO NOT TOUCH\n", encoding="ascii")
        env_file = self.tmp / "github-env"
        out_store = self.out_store_path("out", "cacerts")
        env = self.__class__.base_env.copy()
        env["REAL_JAVA"] = str(real_java)
        env["SWAP_PATH"] = str(env_file)
        env["SWAP_TARGET"] = str(victim)

        result = self.run_wrapper(
            "--java-home", fake, "--out-store", out_store, "--out-env", env_file,
            env=env,
        )
        self.assert_failure(
            result, "destination became a symlink or directory"
        )
        self.assertTrue(env_file.is_symlink(), "the swapped symlink was replaced")
        self.assertEqual(
            victim.read_text(encoding="ascii"),
            "DO NOT TOUCH\n",
            "the wrapper wrote through the swapped symlink",
        )
        self.assert_no_staging_leftovers(self.tmp)

    def test_wrapper_refuses_out_store_symlink_swapped_during_hardening(self) -> None:
        """Deterministic TOCTOU: the store path becomes a symlink during harden."""
        fake = self.make_fake_jdk("fake-jdk-store-swap")
        real_java = self.__class__.java.resolve()
        java_script = fake / "bin" / "java"
        java_script.unlink()
        java_script.write_text(
            "#!/usr/bin/env bash\n"
            'case " $* " in\n'
            '  *" harden "*) ln -sfn "$SWAP_TARGET" "$SWAP_PATH" ;;\n'
            "esac\n"
            'exec "$REAL_JAVA" "$@"\n',
            encoding="ascii",
        )
        java_script.chmod(0o755)

        victim = self.tmp / "swap-store-victim"
        victim.write_bytes(b"DO NOT TOUCH\n")
        out_store = self.out_store_path("out", "cacerts")
        env = self.__class__.base_env.copy()
        env["REAL_JAVA"] = str(real_java)
        env["SWAP_PATH"] = str(out_store)
        env["SWAP_TARGET"] = str(victim)

        result = self.run_wrapper("--java-home", fake, "--out-store", out_store, env=env)
        self.assert_failure(result, "destination became a symlink or directory")
        self.assertTrue(out_store.is_symlink(), "the swapped symlink was replaced")
        self.assertEqual(
            victim.read_bytes(), b"DO NOT TOUCH\n", "the wrapper wrote through the symlink"
        )
        self.assert_no_staging_leftovers(out_store.parent)
        self.assert_no_tool_temp_files()

    def test_wrapper_refuses_out_env_parent_swap_to_jdk_cacerts(self) -> None:
        """A parent rename+symlink during harden cannot replace the JDK cacerts."""
        fake = self.make_fake_jdk("fake-jdk-env-parent-swap")
        cacerts = fake / "lib" / "security" / "cacerts"
        before_hash = sha256_file(cacerts)
        before_size = cacerts.stat().st_size

        parent = self.tmp / "env-parent"
        parent.mkdir()
        env_file = parent / "cacerts"
        out_store = self.out_store_path("out", "cacerts")
        env = self.add_parent_swap_java(fake, parent, fake / "lib" / "security")

        result = self.run_wrapper(
            "--java-home",
            fake,
            "--out-store",
            out_store,
            "--out-env",
            env_file,
            env=env,
            timeout=120,
        )
        self.assert_failure(result, "parent directory changed")
        self.assertEqual(
            sha256_file(cacerts), before_hash, "the wrapper modified the JDK cacerts"
        )
        self.assertEqual(
            cacerts.stat().st_size, before_size, "the JDK cacerts size changed"
        )
        self.assertNotIn(
            b"JAVA_TOOL_OPTIONS=",
            cacerts.read_bytes(),
            "the wrapper appended an env line to the JDK cacerts",
        )
        self.assertTrue(parent.is_symlink(), "the parent swap was not exercised")
        self.assert_no_staging_leftovers(
            parent.with_name(parent.name + ".orig"), fake / "lib" / "security"
        )
        self.assert_no_tool_temp_files()

    def test_wrapper_refuses_out_env_parent_swap_to_repository_copy(self) -> None:
        """A parent swap cannot redirect the env write onto a repository file."""
        fake = self.make_fake_jdk("fake-jdk-env-parent-swap-repo")
        victim_dir = self.tmp / "repo-copy"
        victim_dir.mkdir()
        victim = victim_dir / "pins.txt"
        shutil.copy(PINS_PATH, victim)
        before_hash = sha256_file(victim)
        before_pins_hash = sha256_file(PINS_PATH)

        parent = self.tmp / "env-parent-repo"
        parent.mkdir()
        env_file = parent / "pins.txt"
        out_store = self.out_store_path("out", "cacerts")
        env = self.add_parent_swap_java(fake, parent, victim_dir)

        result = self.run_wrapper(
            "--java-home",
            fake,
            "--out-store",
            out_store,
            "--out-env",
            env_file,
            env=env,
            timeout=120,
        )
        self.assert_failure(result, "parent directory changed")
        self.assertEqual(
            sha256_file(victim), before_hash, "the wrapper modified the repository copy"
        )
        self.assertEqual(
            sha256_file(PINS_PATH),
            before_pins_hash,
            "the wrapper modified the real repository file",
        )
        self.assertNotIn(
            b"JAVA_TOOL_OPTIONS=",
            victim.read_bytes(),
            "the wrapper appended an env line to the repository copy",
        )
        self.assertTrue(parent.is_symlink(), "the parent swap was not exercised")
        self.assert_no_staging_leftovers(
            parent.with_name(parent.name + ".orig"), victim_dir
        )
        self.assert_no_tool_temp_files()

    def test_wrapper_refuses_out_store_parent_swap(self) -> None:
        """A parent rename+symlink during harden cannot replace a victim file."""
        fake = self.make_fake_jdk("fake-jdk-store-parent-swap")
        victim_dir = self.tmp / "store-victim"
        victim_dir.mkdir()
        victim = victim_dir / "cacerts"
        victim.write_bytes(b"DO NOT TOUCH\n")
        before_hash = sha256_file(victim)

        parent = self.tmp / "store-parent"
        parent.mkdir()
        out_store = parent / "cacerts"
        env = self.add_parent_swap_java(fake, parent, victim_dir)

        result = self.run_wrapper(
            "--java-home", fake, "--out-store", out_store, env=env, timeout=120
        )
        self.assert_failure(result, "parent directory changed")
        self.assertEqual(
            sha256_file(victim), before_hash, "the wrapper modified the victim store"
        )
        self.assertEqual(victim.read_bytes(), b"DO NOT TOUCH\n")
        self.assertTrue(parent.is_symlink(), "the parent swap was not exercised")
        self.assert_no_staging_leftovers(victim_dir)
        self.assert_no_staging_leftovers(parent.with_name(parent.name + ".orig"))
        self.assert_no_tool_temp_files()

    def test_wrapper_out_env_missing_parent_fails_before_hardening(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("out", "cacerts")
        missing_env = self.tmp / "missing" / "dir" / "env"
        result = self.run_wrapper(
            "--java-home", fake, "--out-store", out_store, "--out-env", missing_env
        )
        self.assert_failure(result, "out-env parent directory does not exist")
        self.assertFalse(out_store.exists(), "out-store was hardened before validation")
        self.assertFalse(missing_env.exists())
        self.assert_no_staging_leftovers(self.tmp / "out", self.tmp / "missing")

    def test_wrapper_out_store_missing_parent_fails_without_creating_it(self) -> None:
        """A missing out-store parent fails closed instead of being created.

        Creating it with mkdir before the parent directory fd is pinned would
        follow a parent symlink swapped in after validation.
        """
        fake = self.make_fake_jdk()
        out_store = self.tmp / "missing-out" / "nested" / "cacerts"
        result = self.run_wrapper("--java-home", fake, "--out-store", out_store)
        self.assert_failure(result, "out-store parent directory does not exist")
        self.assertFalse(out_store.exists())
        self.assertFalse(
            (self.tmp / "missing-out").exists(),
            "the wrapper created the out-store parent directory",
        )
        self.assert_no_staging_leftovers(self.tmp)
        self.assert_no_tool_temp_files()

    def test_wrapper_probe_tls_requires_configured_truststore(self) -> None:
        env = self.__class__.base_env.copy()
        env.pop("JAVA_TOOL_OPTIONS", None)
        result = self.run_wrapper("--probe-tls", env=env)
        self.assert_failure(result, "--probe-tls requires JAVA_TOOL_OPTIONS")
        self.assertNotIn("probe-tls:", result.stderr)
        self.assertNotIn("verify-effective:", result.stderr)

        env["JAVA_TOOL_OPTIONS"] = "-Xmx64m"
        result = self.run_wrapper("--probe-tls", env=env)
        self.assert_failure(result, "does not set -Djavax.net.ssl.trustStore")
        self.assertNotIn("probe-tls:", result.stderr)

    def test_wrapper_print_env_stdout_is_eval_safe(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("print-env", "cacerts")
        resolved_out_store = out_store.resolve()
        result = self.run_wrapper(
            "--java-home", fake, "--out-store", out_store, "--print-env"
        )
        self.assert_success(result)
        lines = [line for line in result.stdout.splitlines() if line.strip()]
        self.assertEqual(len(lines), 1, msg=self.describe(result))
        for line in lines:
            self.assertTrue(line.startswith("export "), msg=line)
        self.assertIn('"command":"harden"', result.stderr)
        self.assertIn(str(resolved_out_store), result.stderr)

        eval_env = self.__class__.base_env.copy()
        eval_env["EXPORT_LINE"] = lines[0]
        evaluated = self.__class__._run(
            ["bash", "-c", 'eval "$EXPORT_LINE"; printf "%s" "$JAVA_TOOL_OPTIONS"'],
            env=eval_env,
        )
        self.assert_success(evaluated)
        options = evaluated.stdout
        self.assertIn(f'-Djavax.net.ssl.trustStore="{resolved_out_store}"', options)
        self.assertIn(
            f"-Djavax.net.ssl.trustStoreType={store_type(out_store)}", options
        )
        self.assert_no_tool_temp_files()

    def test_wrapper_out_env_appends_newline_before_export(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("out", "cacerts")
        env_file = self.tmp / "github-env"
        env_file.write_bytes(b"EXISTING=1")
        result = self.run_wrapper(
            "--java-home", fake, "--out-store", out_store, "--out-env", env_file
        )
        self.assert_success(result)
        data = env_file.read_bytes()
        self.assertTrue(
            data.startswith(b"EXISTING=1\nJAVA_TOOL_OPTIONS="),
            msg=f"env file was concatenated: {data!r}",
        )
        lines = data.decode("utf-8").splitlines()
        self.assertEqual(len(lines), 2)
        self.assertEqual(lines[0], "EXISTING=1")
        self.assertTrue(lines[1].startswith("JAVA_TOOL_OPTIONS="))
        self.assert_no_tool_temp_files()

    def test_wrapper_out_env_is_staged_and_replaced_atomically(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("out", "cacerts")
        resolved_out_store = out_store.resolve()
        env_file = self.tmp / "github-env"
        env_file.write_text("EXISTING=1\n", encoding="ascii")
        result = self.run_wrapper(
            "--java-home", fake, "--out-store", out_store, "--out-env", env_file
        )
        self.assert_success(result)
        hardened = self.json_from(result)
        self.assertTrue(hardened["canaryPresent"])
        self.assertEqual(hardened["forbiddenRemaining"], [])
        self.assertTrue(env_file.is_file(), "out-env is not a regular file")
        self.assertFalse(env_file.is_symlink(), "out-env was replaced by a symlink")
        self.assert_owner_only(env_file)
        data = env_file.read_bytes()
        self.assertTrue(
            data.startswith(b"EXISTING=1\nJAVA_TOOL_OPTIONS="),
            msg=f"existing env content was not preserved: {data!r}",
        )
        lines = data.decode("utf-8").splitlines()
        self.assertEqual(len(lines), 2)
        self.assertEqual(lines[0], "EXISTING=1")
        self.assertIn(f'-Djavax.net.ssl.trustStore="{resolved_out_store}"', lines[1])
        self.assert_no_staging_leftovers(self.tmp)
        self.assert_no_tool_temp_files()

        # The staged content is what lands: an existing file without a trailing
        # newline still yields exactly one appended assignment.
        second_env = self.tmp / "github-env-no-newline"
        second_env.write_bytes(b"EXISTING=2")
        result = self.run_wrapper(
            "--java-home",
            fake,
            "--out-store",
            self.out_store_path("out2", "cacerts"),
            "--out-env",
            second_env,
        )
        self.assert_success(result)
        self.assertEqual(
            second_env.read_bytes().splitlines()[0], b"EXISTING=2"
        )
        self.assert_no_staging_leftovers(self.tmp)

    def test_wrapper_out_store_directory_destination_leaves_no_staging(self) -> None:
        fake = self.make_fake_jdk()
        out_store = self.out_store_path("out", "cacerts")
        out_store.mkdir(parents=True)
        marker = out_store / "keep.txt"
        marker.write_text("keep\n", encoding="ascii")
        result = self.run_wrapper("--java-home", fake, "--out-store", out_store)
        self.assert_failure(result, "out-store is a directory")
        self.assertTrue(out_store.is_dir())
        self.assertEqual(sorted(out_store.iterdir()), [marker])
        self.assert_no_staging_leftovers(out_store.parent)
        self.assert_no_tool_temp_files()

    def test_wrapper_rejects_invalid_out_store_path_before_writing(self) -> None:
        fake = self.make_fake_jdk()
        for label, name in (("quote", 'bad"name'), ("newline", "bad\nname")):
            with self.subTest(case=label):
                out_store = self.tmp / name
                result = self.run_wrapper(
                    "--java-home", fake, "--out-store", out_store
                )
                self.assert_failure(
                    result,
                    "must not contain newlines, carriage returns, or double quotes",
                )
                self.assertFalse(
                    out_store.exists(), "wrapper created an invalid out-store path"
                )
                leftovers = sorted(
                    path for path in self.tmp.iterdir() if ".tmp." in path.name
                )
                self.assertEqual(
                    leftovers, [], f"staging files left behind: {leftovers}"
                )

    def test_wrapper_invocation_through_symlink(self) -> None:
        link = self.tmp / "harden-link"
        os.symlink(WRAPPER, link)
        result = self.run_wrapper("--help", wrapper=link)
        self.assert_success(result)
        self.assertIn("Usage:", result.stdout)
        self.assertIn("--out-env", result.stdout)

        # --help is static, so also run a mode that needs the tool, pins, and
        # canary beside the real script rather than beside the symlink.
        fake = self.make_fake_jdk()
        result = self.run_wrapper("--java-home", fake, "--check-only", wrapper=link)
        self.assert_failure(result, "still contains one or more incident trust anchors")
        self.assertNotIn("truststore tool is missing", result.stderr)
        self.assertNotIn("pin file is missing", result.stderr)

    def test_wrapper_help_documents_modes_and_options(self) -> None:
        result = self.run_wrapper("--help")
        self.assert_success(result)
        self.assertEqual(result.stderr, "")
        for token in (
            "Usage:",
            "(default)",
            "--verify-only",
            "--check-only",
            "--probe-tls",
            "--java-home",
            "--out-store",
            "--out-env",
            "--print-env",
        ):
            self.assertIn(token, result.stdout, f"help text is missing {token!r}")
        for phrase in (
            "inspects only the selected JDK's cacerts and ignores",
            "same requirements as --verify-only",
            "must be outside the repository",
            "staged beside the final path",
            "outside JAVA_HOME",
            "Their parent directories must already exist",
            "the wrapper does not create",
            "must name a regular file",
            "pinned with a directory file",
            "/proc/self/fd",
            "fails closed where it is unavailable",
            "tool JSON followed by",
            "GitHub Actions env file",
            "not for shell sourcing",
            "eval-safe",
        ):
            self.assertIn(phrase, result.stdout, f"help text is missing {phrase!r}")

        short = self.run_wrapper("-h")
        self.assert_success(short)
        self.assertEqual(short.stdout, result.stdout)


if __name__ == "__main__":
    unittest.main()
