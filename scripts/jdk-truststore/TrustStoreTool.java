import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Versioned JDK truststore hardening tool.
 *
 * <p>This tool is intentionally a single-file Java program so it can be run in
 * JDK 17 source-file mode without a build step or third-party dependencies.
 * It targets the public eMudhra incident trust anchors by SPKI SHA-256 rather
 * than by subject DN, verifies the result independently, and can prove that a
 * hardened truststore is the effective JSSE default by looking for a public
 * canary trust anchor.
 *
 * <p>The store password below is the public JDK default for {@code cacerts}.
 * It is not a secret and must not be treated as one. The tool never contains
 * or prints private key material.
 */
public final class TrustStoreTool {
    /** Public JDK cacerts password. This is not a secret. */
    private static final char[] STORE_PASSWORD = "changeit".toCharArray();

    private static final String DEFAULT_CANARY_ALIAS = "pixelify-ci-truststore-canary";
    private static final int DEFAULT_MIN_CERTS = 1;
    private static final int DEFAULT_MIN_ISSUERS = 50;
    private static final int PROBE_ATTEMPTS = 3;
    private static final int PROBE_TIMEOUT_MILLIS = 10_000;
    private static final List<String> DEFAULT_PROBE_URLS = List.of(
            "https://repo.maven.apache.org/maven2/",
            "https://plugins.gradle.org/m2/",
            "https://dl.google.com/");

    private static boolean debug;
    private static boolean allowDefaultStore;

    private TrustStoreTool() {
    }

    public static void main(String[] args) {
        List<String> arguments = new ArrayList<>(Arrays.asList(args));
        debug = arguments.removeIf("--debug"::equals);
        allowDefaultStore = arguments.removeIf("--allow-default-store"::equals);

        if (arguments.contains("--help") || arguments.contains("-h") || arguments.contains("help")) {
            printUsage(System.out);
            return;
        }
        if (arguments.isEmpty()) {
            printUsage(System.err);
            System.exit(1);
        }

        String command = arguments.remove(0);
        try {
            switch (command) {
                case "harden" -> harden(arguments);
                case "verify" -> verify(arguments);
                case "verify-effective" -> verifyEffective(arguments);
                case "fingerprint" -> fingerprint(arguments);
                case "list" -> list(arguments);
                case "probe-tls" -> probeTls(arguments);
                default -> fail("unknown command: " + command + " (try --help)");
            }
        } catch (ToolException e) {
            System.err.println("ERROR: " + e.getMessage());
            if (debug) {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        } catch (Exception e) {
            String message = e.getMessage();
            System.err.println("ERROR: " + (message == null || message.isBlank()
                    ? e.getClass().getSimpleName()
                    : message));
            if (debug) {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                TrustStoreTool - harden and verify a Java JKS/PKCS12 truststore.

                Usage:
                  java TrustStoreTool.java harden --store FILE --pins FILE [--canary-cert FILE] [--canary-alias ALIAS] [--min-certs N] [--report-store FILE]
                  java TrustStoreTool.java verify --store FILE --pins FILE [--min-certs N]
                  java TrustStoreTool.java verify-effective --pins FILE --canary-cert FILE [--min-issuers N]
                  java TrustStoreTool.java fingerprint --cert FILE
                  java TrustStoreTool.java list --store FILE
                  java TrustStoreTool.java probe-tls [--url URL ...] [--allow-default-store]

                Commands:
                  harden            Remove every certificate entry whose SPKI SHA-256 is pinned,
                                    optionally add a public canary trust anchor, and atomically
                                    replace the target store with the hardened copy.
                                    --report-store overrides only the path printed in the
                                    harden JSON (display-only; the file actually hardened
                                    is still --store).
                  verify            Fail if any pinned SPKI remains in a store and enforce a
                                    minimum certificate count.
                  verify-effective  Inspect the default JSSE TrustManagerFactory, fail if any
                                    pinned SPKI remains, require the canary certificate, and
                                    enforce a minimum accepted-issuer count.
                  fingerprint       Print full-certificate and SPKI SHA-256 values for a PEM or
                                    DER certificate.
                  list              List certificate entries without printing private material.
                  probe-tls         Make HEAD requests with the default SSL context, accepting any
                                    HTTP status; TLS/IO failures after three attempts are fatal.
                                    Requires javax.net.ssl.trustStore to name a readable file
                                    unless --allow-default-store is given.

                Global options:
                  --debug                 Print stack traces on failure.
                  --allow-default-store   Let probe-tls use the JDK default truststore when
                                          javax.net.ssl.trustStore is not configured. Without
                                          this flag a missing or unreadable configured store is
                                          a hard failure, so the vendor default is never probed
                                          by accident.
                  --help                  Print this help.

                The store password is the public JDK default "changeit". Only JKS
                (FEEDFEED) and PKCS12 (DER SEQUENCE 0x30) stores are supported.
                harden refuses a symlinked --store, refuses a store with multiple
                hard links, and canonicalizes the accepted path before replacing
                it; verify and list follow symlinks read-only.
                """);
    }

    private static void harden(List<String> args) {
        Options options = parseOptions(args,
                Set.of("--store", "--pins", "--canary-cert", "--canary-alias", "--min-certs",
                        "--report-store"),
                Set.of());
        Path storePath = requireFile(options, "--store");
        // Resolve only the parent directory. A full toRealPath() here would
        // follow a final-component symlink created between the check and the
        // call, and the later atomic replace would then overwrite the link
        // target instead of the path the caller named. Keep the final
        // component literal for the symlink check; the atomic move below
        // replaces that directory entry, never a symlink target.
        Path storeName = storePath.getFileName();
        if (storeName == null
                || storeName.toString().equals(".")
                || storeName.toString().equals("..")) {
            fail("refusing to harden a path that does not name a file: " + storePath);
        }
        Path storeParent = storePath.getParent();
        if (storeParent == null) {
            storeParent = Path.of(".");
        }
        try {
            storeParent = storeParent.toRealPath();
        } catch (IOException e) {
            throw new ToolException("cannot resolve truststore parent directory " + storeParent
                    + ": " + e.getMessage(), e);
        }
        storePath = storeParent.resolve(storeName);
        if (Files.isSymbolicLink(storePath)) {
            fail("refusing to harden a symlinked truststore: " + storePath
                    + " (resolve the link explicitly)");
        }
        refuseMultipleHardLinks(storePath);
        // The wrapper hardens a staging file and then installs it at the final
        // path. --report-store lets the JSON describe that final path without
        // changing which file is actually hardened.
        Path reportStorePath = storePath;
        String reportStoreOption = options.get("--report-store");
        if (reportStoreOption != null) {
            if (reportStoreOption.isBlank()) {
                fail("--report-store must not be blank");
            }
            reportStorePath = Path.of(reportStoreOption);
            if (Files.isDirectory(reportStorePath)) {
                fail("--report-store must not be a directory: " + reportStorePath);
            }
        }
        Path pinsPath = requireFile(options, "--pins");
        int minCerts = parsePositiveInt(
                options.getOrDefault("--min-certs", Integer.toString(DEFAULT_MIN_CERTS)),
                "--min-certs");

        String canaryOption = options.get("--canary-cert");
        Path canaryPath = canaryOption == null ? null : requireReadableFile(Path.of(canaryOption), "--canary-cert");
        String canaryAlias = options.getOrDefault("--canary-alias", DEFAULT_CANARY_ALIAS);
        if (canaryAlias.isBlank()) {
            fail("--canary-alias must not be blank");
        }

        List<Pin> pins = loadPins(pinsPath);
        Map<String, Pin> pinsBySpki = pinMap(pins);
        LoadedStore loaded = loadStore(storePath);
        List<CertInfo> entries = certificateEntries(loaded.store());
        List<CertInfo> forbidden = findForbidden(entries, pinsBySpki);

        X509Certificate canary = canaryPath == null ? null : loadCertificate(canaryPath);
        String canarySha256 = canary == null ? null : certInfo(canaryAlias, canary).certSha256();
        if (canary != null) {
            String canarySpki = spkiSha256(canary);
            if (pinsBySpki.containsKey(canarySpki)) {
                fail("canary certificate is itself pinned and cannot be added: " + canarySpki);
            }
        }
        // Re-running harden with the same canary must be idempotent. Treat an
        // existing alias with exactly the expected public certificate as already
        // present; an alias holding any other certificate is a hard failure.
        boolean canaryAlreadyPresent = false;
        if (canary != null) {
            final Certificate existing;
            try {
                existing = loaded.store().getCertificate(canaryAlias);
            } catch (KeyStoreException e) {
                throw new ToolException("cannot inspect canary alias " + canaryAlias + ": " + e.getMessage(), e);
            }
            if (existing != null) {
                if (isKeyEntry(loaded.store(), canaryAlias)) {
                    fail("canary alias already exists as a key entry: " + canaryAlias);
                }
                if (!(existing instanceof X509Certificate existingX509)) {
                    throw new ToolException("canary alias already exists with a non-X.509 certificate: " + canaryAlias);
                }
                final boolean same;
                try {
                    same = Arrays.equals(existingX509.getEncoded(), canary.getEncoded());
                } catch (CertificateEncodingException e) {
                    throw new ToolException("cannot compare canary certificate: " + e.getMessage(), e);
                }
                if (!same) {
                    fail("canary alias already exists with a different certificate: " + canaryAlias);
                }
                canaryAlreadyPresent = true;
            }
        }

        List<String> aliasesToRemove = new ArrayList<>();
        for (CertInfo info : forbidden) {
            if (isKeyEntry(loaded.store(), info.alias())) {
                fail("refusing to delete pinned key entry " + info.alias()
                        + "; a truststore should contain certificate entries only");
            }
            aliasesToRemove.add(info.alias());
        }
        if (!forbidden.isEmpty() && aliasesToRemove.size() != forbidden.size()) {
            fail("internal error: not all pinned aliases were selected for removal");
        }

        for (String alias : aliasesToRemove) {
            try {
                loaded.store().deleteEntry(alias);
            } catch (KeyStoreException e) {
                throw new ToolException("cannot delete alias " + alias + ": " + e.getMessage(), e);
            }
        }

        // Independently re-enumerate the in-memory store before writing anything.
        List<CertInfo> afterDeletion = certificateEntries(loaded.store());
        List<CertInfo> stillPresent = findForbidden(afterDeletion, pinsBySpki);
        if (!stillPresent.isEmpty()) {
            fail("pinned anchors remain in memory after deletion: "
                    + describeForbidden(stillPresent, pinsBySpki));
        }

        if (canary != null && !canaryAlreadyPresent) {
            try {
                loaded.store().setCertificateEntry(canaryAlias, canary);
            } catch (KeyStoreException e) {
                throw new ToolException("cannot add canary certificate " + canaryAlias + ": " + e.getMessage(), e);
            }
        }

        // Re-check the fully mutated in-memory store before touching the target
        // file. A failed safety check must leave the original store
        // byte-identical; the post-write checks below are defense in depth.
        List<CertInfo> afterMutation = certificateEntries(loaded.store());
        List<CertInfo> remainingBeforeWrite = findForbidden(afterMutation, pinsBySpki);
        if (!remainingBeforeWrite.isEmpty()) {
            fail("pinned anchors remain in memory before write: "
                    + describeForbidden(remainingBeforeWrite, pinsBySpki));
        }
        final int inMemoryCertificateCount;
        try {
            inMemoryCertificateCount = countCertificateEntries(loaded.store());
        } catch (GeneralSecurityException e) {
            throw new ToolException("cannot enumerate hardened truststore in memory: " + e.getMessage(), e);
        }
        if (inMemoryCertificateCount == 0) {
            fail("refusing to write an empty truststore");
        }
        if (inMemoryCertificateCount < minCerts) {
            fail("hardened truststore would have " + inMemoryCertificateCount
                    + " certificate(s), below --min-certs " + minCerts);
        }
        if (canary != null) {
            final Certificate inMemoryCanary;
            try {
                inMemoryCanary = loaded.store().getCertificate(canaryAlias);
            } catch (KeyStoreException e) {
                throw new ToolException("cannot read canary alias " + canaryAlias
                        + " before write: " + e.getMessage(), e);
            }
            if (!(inMemoryCanary instanceof X509Certificate inMemoryX509)) {
                throw new ToolException("canary certificate is missing before write: " + canaryAlias);
            }
            final boolean same;
            try {
                same = Arrays.equals(inMemoryX509.getEncoded(), canary.getEncoded());
            } catch (CertificateEncodingException e) {
                throw new ToolException("cannot compare in-memory canary certificate: " + e.getMessage(), e);
            }
            if (!same) {
                fail("canary certificate differs before write: " + canaryAlias);
            }
        }

        // Write to a sibling temp file and atomically replace the target. The
        // source bytes are never modified in place.
        Path parent = storePath.toAbsolutePath().getParent();
        if (parent == null) {
            fail("cannot determine parent directory of " + storePath);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".truststore-", ".tmp");
            setOwnerOnly(temporary);
            try (OutputStream output = Files.newOutputStream(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                loaded.store().store(output, STORE_PASSWORD);
            } catch (GeneralSecurityException e) {
                throw new ToolException("cannot write " + loaded.type() + " truststore: " + e.getMessage(), e);
            }
            setOwnerOnly(temporary);
            moveAtomically(temporary, storePath);
            temporary = null;
        } catch (IOException e) {
            throw new ToolException("cannot write hardened truststore " + storePath + ": " + e.getMessage(), e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the original error is more important.
                }
            }
        }
        try {
            setOwnerOnly(storePath);
        } catch (IOException e) {
            throw new ToolException("cannot set permissions on " + storePath + ": " + e.getMessage(), e);
        }

        // Reload the exact bytes that were written and verify independently.
        LoadedStore reloaded = loadStore(storePath);
        if (!reloaded.type().equals(loaded.type())) {
            fail("truststore type changed after write: expected " + loaded.type()
                    + ", found " + reloaded.type());
        }
        List<CertInfo> after = certificateEntries(reloaded.store());
        List<CertInfo> remaining = findForbidden(after, pinsBySpki);
        if (!remaining.isEmpty()) {
            fail("pinned anchors remain after write: " + describeForbidden(remaining, pinsBySpki));
        }
        if (reloaded.certificateCount() < minCerts) {
            fail("hardened truststore has " + reloaded.certificateCount()
                    + " certificate(s), below --min-certs " + minCerts);
        }

        boolean canaryPresent = false;
        if (canary != null) {
            final Certificate written;
            try {
                written = reloaded.store().getCertificate(canaryAlias);
            } catch (KeyStoreException e) {
                throw new ToolException("cannot read canary alias " + canaryAlias
                        + " after write: " + e.getMessage(), e);
            }
            if (!(written instanceof X509Certificate writtenX509)) {
                throw new ToolException("canary certificate is missing after write: " + canaryAlias);
            }
            final boolean same;
            try {
                same = Arrays.equals(writtenX509.getEncoded(), canary.getEncoded());
            } catch (CertificateEncodingException e) {
                throw new ToolException("cannot compare written canary certificate: " + e.getMessage(), e);
            }
            if (!same) {
                fail("canary certificate differs after write: " + canaryAlias);
            }
            canaryPresent = true;
        }

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("command").value("harden");
        json.name("store").value(reportStorePath.toString());
        json.name("storeType").value(reloaded.type());
        json.name("beforeCertCount").value(loaded.certificateCount());
        json.name("afterCertCount").value(reloaded.certificateCount());
        json.name("certificateCount").value(reloaded.certificateCount());
        json.name("removed").value(forbidden.size());
        json.name("removedEntries").beginArray();
        for (CertInfo info : forbidden) {
            writeCertInfo(json, info, pinsBySpki);
        }
        json.endArray();
        json.name("forbiddenRemaining").beginArray();
        for (CertInfo info : remaining) {
            writeCertInfo(json, info, pinsBySpki);
        }
        json.endArray();
        json.name("forbiddenNames").beginArray();
        for (String name : forbiddenNames(remaining, pinsBySpki)) {
            json.value(name);
        }
        json.endArray();
        json.name("canaryAlias").value(canary == null ? null : canaryAlias);
        json.name("canarySha256").value(canarySha256);
        json.name("canaryPresent").value(canaryPresent);
        json.name("minCerts").value(minCerts);
        json.name("storeSha256").value(sha256File(storePath));
        writeEnvironment(json);
        json.endObject();
        System.out.println(json);

        System.err.println("harden: removed " + forbidden.size() + " pinned entr"
                + (forbidden.size() == 1 ? "y" : "ies") + "; "
                + reloaded.certificateCount() + " certificate(s) remain"
                + (canaryPresent ? "; canary present" : ""));
    }

    private static void verify(List<String> args) {
        Options options = parseOptions(args, Set.of("--store", "--pins", "--min-certs"), Set.of());
        Path storePath = requireFile(options, "--store");
        Path pinsPath = requireFile(options, "--pins");
        int minCerts = parsePositiveInt(
                options.getOrDefault("--min-certs", Integer.toString(DEFAULT_MIN_CERTS)),
                "--min-certs");

        List<Pin> pins = loadPins(pinsPath);
        Map<String, Pin> pinsBySpki = pinMap(pins);
        LoadedStore loaded = loadStore(storePath);
        List<CertInfo> entries = certificateEntries(loaded.store());
        List<CertInfo> forbidden = findForbidden(entries, pinsBySpki);
        if (!forbidden.isEmpty()) {
            fail("pinned trust anchors remain in " + storePath + ": "
                    + describeForbidden(forbidden, pinsBySpki));
        }
        if (loaded.certificateCount() < minCerts) {
            fail("truststore has " + loaded.certificateCount()
                    + " certificate(s), below --min-certs " + minCerts);
        }

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("command").value("verify");
        json.name("store").value(storePath.toString());
        json.name("storeType").value(loaded.type());
        json.name("beforeCertCount").value(loaded.certificateCount());
        json.name("afterCertCount").value(loaded.certificateCount());
        json.name("certificateCount").value(loaded.certificateCount());
        json.name("removed").value(0);
        json.name("removedEntries").beginArray();
        json.endArray();
        json.name("forbiddenRemaining").beginArray();
        for (CertInfo info : forbidden) {
            writeCertInfo(json, info, pinsBySpki);
        }
        json.endArray();
        json.name("forbiddenNames").beginArray();
        for (String name : forbiddenNames(forbidden, pinsBySpki)) {
            json.value(name);
        }
        json.endArray();
        json.name("canaryAlias").value((String) null);
        json.name("canarySha256").value((String) null);
        json.name("canaryPresent").value(false);
        json.name("minCerts").value(minCerts);
        json.name("storeSha256").value(sha256File(storePath));
        writeEnvironment(json);
        json.endObject();
        System.out.println(json);

        System.err.println("verify: " + loaded.certificateCount()
                + " certificate(s), no pinned anchors remain");
    }

    private static void verifyEffective(List<String> args) {
        Options options = parseOptions(args, Set.of("--pins", "--canary-cert", "--min-issuers"), Set.of());
        Path pinsPath = requireFile(options, "--pins");
        Path canaryPath = requireFile(options, "--canary-cert");
        int minIssuers = parseNonNegativeInt(
                options.getOrDefault("--min-issuers", Integer.toString(DEFAULT_MIN_ISSUERS)),
                "--min-issuers");

        List<Pin> pins = loadPins(pinsPath);
        Map<String, Pin> pinsBySpki = pinMap(pins);
        X509Certificate canary = loadCertificate(canaryPath);
        String canarySha256 = certInfo(null, canary).certSha256();

        String trustStoreProperty = System.getProperty("javax.net.ssl.trustStore");
        if (trustStoreProperty != null && !trustStoreProperty.isBlank()) {
            final Path configuredStore;
            try {
                configuredStore = Path.of(trustStoreProperty);
            } catch (RuntimeException e) {
                throw new ToolException("invalid javax.net.ssl.trustStore path: "
                        + trustStoreProperty, e);
            }
            if (!Files.isRegularFile(configuredStore) || !Files.isReadable(configuredStore)) {
                fail("javax.net.ssl.trustStore is set but is not a readable file: "
                        + trustStoreProperty
                        + " (JSSE would fall back to the JDK default truststore)");
            }
        }

        List<X509Certificate> acceptedIssuers = new ArrayList<>();
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    Collections.addAll(acceptedIssuers, x509.getAcceptedIssuers());
                }
            }
        } catch (GeneralSecurityException e) {
            throw new ToolException("cannot initialize default TrustManagerFactory: "
                    + e.getMessage(), e);
        }
        if (acceptedIssuers.isEmpty()) {
            fail("default TrustManagerFactory returned no accepted issuers");
        }

        Map<String, CertInfo> uniqueIssuers = new LinkedHashMap<>();
        for (X509Certificate issuer : acceptedIssuers) {
            CertInfo info = certInfo(null, issuer);
            uniqueIssuers.putIfAbsent(info.certSha256(), info);
        }
        List<CertInfo> issuers = new ArrayList<>(uniqueIssuers.values());
        List<CertInfo> forbidden = findForbidden(issuers, pinsBySpki);
        boolean canaryPresent = uniqueIssuers.containsKey(canarySha256);

        if (!forbidden.isEmpty()) {
            fail("default truststore still accepts pinned anchors: "
                    + describeForbidden(forbidden, pinsBySpki));
        }
        if (!canaryPresent) {
            fail("canary certificate " + canarySha256
                    + " is absent from the default truststore; "
                    + "the effective JSSE truststore is not the hardened copy");
        }
        if (issuers.size() < minIssuers) {
            fail("default truststore has " + issuers.size()
                    + " accepted issuer(s), below --min-issuers " + minIssuers);
        }

        String trustStoreTypeProperty = System.getProperty("javax.net.ssl.trustStoreType");
        String storeSha256 = null;
        if (trustStoreProperty != null && !trustStoreProperty.isBlank()) {
            Path configuredStore = Path.of(trustStoreProperty);
            if (Files.isRegularFile(configuredStore) && Files.isReadable(configuredStore)) {
                storeSha256 = sha256File(configuredStore);
            }
        }

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("command").value("verify-effective");
        json.name("store").value(trustStoreProperty);
        json.name("storeType").value(trustStoreTypeProperty);
        json.name("storeSha256").value(storeSha256);
        json.name("issuerCount").value(issuers.size());
        json.name("minIssuers").value(minIssuers);
        json.name("canaryPresent").value(true);
        json.name("canarySha256").value(canarySha256);
        json.name("forbiddenRemaining").beginArray();
        for (CertInfo info : forbidden) {
            writeCertInfo(json, info, pinsBySpki);
        }
        json.endArray();
        json.name("forbiddenNames").beginArray();
        for (String name : forbiddenNames(forbidden, pinsBySpki)) {
            json.value(name);
        }
        json.endArray();
        writeEnvironment(json);
        json.endObject();
        System.out.println(json);

        System.err.println("verify-effective: " + issuers.size()
                + " accepted issuer(s), canary present, no pinned anchors");
    }

    private static void fingerprint(List<String> args) {
        Options options = parseOptions(args, Set.of("--cert"), Set.of());
        Path certPath = requireFile(options, "--cert");
        X509Certificate certificate = loadCertificate(certPath);
        CertInfo info = certInfo(certPath.toString(), certificate);

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("command").value("fingerprint");
        json.name("cert").value(certPath.toString());
        json.name("certSha256").value(info.certSha256());
        json.name("spkiSha256").value(info.spkiSha256());
        json.name("subject").value(info.subject());
        json.name("issuer").value(info.issuer());
        json.name("serialNumber").value(certificate.getSerialNumber().toString(16).toUpperCase(Locale.ROOT));
        json.name("notBefore").value(certificate.getNotBefore().toInstant().toString());
        json.name("notAfter").value(certificate.getNotAfter().toInstant().toString());
        writeEnvironment(json);
        json.endObject();
        System.out.println(json);
    }

    private static void list(List<String> args) {
        Options options = parseOptions(args, Set.of("--store"), Set.of());
        Path storePath = requireFile(options, "--store");
        LoadedStore loaded = loadStore(storePath);
        List<CertInfo> entries = certificateEntries(loaded.store());

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("command").value("list");
        json.name("store").value(storePath.toString());
        json.name("storeType").value(loaded.type());
        json.name("certificateCount").value(loaded.certificateCount());
        json.name("storeSha256").value(sha256File(storePath));
        json.name("entries").beginArray();
        for (CertInfo entry : entries) {
            json.beginObject();
            json.name("alias").value(entry.alias());
            json.name("subject").value(entry.subject());
            json.name("issuer").value(entry.issuer());
            json.name("spkiSha256").value(entry.spkiSha256());
            json.name("certSha256").value(entry.certSha256());
            json.name("keyEntry").value(isKeyEntry(loaded.store(), entry.alias()));
            json.endObject();
        }
        json.endArray();
        writeEnvironment(json);
        json.endObject();
        System.out.println(json);

        System.err.println("list: " + entries.size() + " certificate entr"
                + (entries.size() == 1 ? "y" : "ies") + " in " + loaded.type() + " store");
    }

    /**
     * Fail closed before any network access unless the caller explicitly opted
     * into the vendor default store. JSSE silently falls back to the JDK
     * cacerts when {@code javax.net.ssl.trustStore} is unset or unreadable, so a
     * probe without a configured hardened store would test the vulnerable
     * default and report success.
     */
    private static void requireConfiguredStoreForProbe() {
        if (allowDefaultStore) {
            return;
        }
        String trustStoreProperty = System.getProperty("javax.net.ssl.trustStore");
        if (trustStoreProperty == null || trustStoreProperty.isBlank()) {
            fail("probe-tls requires -Djavax.net.ssl.trustStore to name the hardened store;"
                    + " JSSE would otherwise use the JDK default truststore"
                    + " (pass --allow-default-store to probe the default explicitly)");
        }
        final Path configuredStore;
        try {
            configuredStore = Path.of(trustStoreProperty);
        } catch (RuntimeException e) {
            throw new ToolException("invalid javax.net.ssl.trustStore path: "
                    + trustStoreProperty
                    + " (pass --allow-default-store to probe the JDK default explicitly)", e);
        }
        if (!Files.isRegularFile(configuredStore) || !Files.isReadable(configuredStore)) {
            fail("javax.net.ssl.trustStore is set but is not a readable file: "
                    + trustStoreProperty
                    + " (JSSE would fall back to the JDK default truststore;"
                    + " pass --allow-default-store to probe it explicitly)");
        }
    }

    private static void probeTls(List<String> args) {
        requireConfiguredStoreForProbe();
        Options options = parseOptions(args, Set.of(), Set.of("--url"));
        List<String> urls = options.list("--url");
        if (urls.isEmpty()) {
            urls = DEFAULT_PROBE_URLS;
        }

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("command").value("probe-tls");
        json.name("results").beginArray();
        for (String urlText : urls) {
            final URL url;
            try {
                url = new URL(urlText);
            } catch (MalformedURLException e) {
                throw new ToolException("invalid probe URL " + urlText + ": " + e.getMessage(), e);
            }
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                fail("probe URL must use https: " + urlText);
            }

            int status = -1;
            int attempts = 0;
            IOException lastFailure = null;
            for (attempts = 1; attempts <= PROBE_ATTEMPTS; attempts++) {
                HttpsURLConnection connection = null;
                try {
                    connection = (HttpsURLConnection) url.openConnection();
                    connection.setRequestMethod("HEAD");
                    connection.setConnectTimeout(PROBE_TIMEOUT_MILLIS);
                    connection.setReadTimeout(PROBE_TIMEOUT_MILLIS);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "Pixelify-TrustStore-Probe/1");
                    status = connection.getResponseCode();
                    lastFailure = null;
                    break;
                } catch (IOException e) {
                    lastFailure = e;
                    if (attempts < PROBE_ATTEMPTS) {
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new ToolException("interrupted while probing " + urlText, interrupted);
                        }
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
            if (lastFailure != null) {
                throw new ToolException("TLS probe failed for " + urlText + " after "
                        + PROBE_ATTEMPTS + " attempts: " + lastFailure.getMessage(), lastFailure);
            }

            System.err.println("probe-tls: " + urlText + " -> HTTP " + status
                    + " (attempt " + attempts + ")");
            json.beginObject();
            json.name("url").value(urlText);
            json.name("httpStatus").value(status);
            json.name("attempts").value(attempts);
            json.endObject();
        }
        json.endArray();
        writeEnvironment(json);
        json.endObject();
        System.out.println(json);
    }

    private static Options parseOptions(List<String> args,
                                        Set<String> valueOptions,
                                        Set<String> repeatableOptions) {
        Options options = new Options();
        for (int i = 0; i < args.size(); i++) {
            String argument = args.get(i);
            if (!argument.startsWith("--")) {
                fail("unexpected argument: " + argument);
            }
            String name;
            String value;
            int equals = argument.indexOf('=');
            if (equals >= 0) {
                name = argument.substring(0, equals);
                value = argument.substring(equals + 1);
            } else {
                name = argument;
                if (i + 1 >= args.size()) {
                    fail("missing value for " + name);
                }
                value = args.get(++i);
            }
            if (!valueOptions.contains(name) && !repeatableOptions.contains(name)) {
                fail("unknown option: " + name);
            }
            if (repeatableOptions.contains(name)) {
                options.lists.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            } else {
                if (options.values.containsKey(name)) {
                    fail("duplicate option: " + name);
                }
                options.values.put(name, value);
            }
        }
        return options;
    }

    private static Path requireFile(Options options, String option) {
        return requireReadableFile(Path.of(options.require(option)), option);
    }

    /**
     * Refuse to harden a store that has more than one directory entry.
     *
     * <p>The hardened bytes replace only the named directory entry, so any other
     * hard link would keep pointing at the unhardened inode. There is no way to
     * rewrite every link without opening a path we were not given, so this is a
     * hard failure instead of a silent partial fix. Non-Unix filesystems have no
     * link count to inspect and are left to the symlink checks.
     */
    private static void refuseMultipleHardLinks(Path storePath) {
        final Object linkCount;
        try {
            linkCount = Files.getAttribute(storePath, "unix:nlink");
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return;
        } catch (IOException e) {
            throw new ToolException("cannot inspect hard link count for " + storePath
                    + ": " + e.getMessage(), e);
        }
        if (linkCount instanceof Number number && number.longValue() > 1) {
            fail("refusing to harden a truststore with multiple hard links: " + storePath
                    + " (resolve the link explicitly)");
        }
    }

    private static Path requireReadableFile(Path path, String description) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            fail(description + " does not exist: " + absolute);
        }
        if (!Files.isRegularFile(absolute)) {
            fail(description + " is not a regular file: " + absolute);
        }
        if (!Files.isReadable(absolute)) {
            fail(description + " is not readable: " + absolute);
        }
        return absolute;
    }

    private static int parseNonNegativeInt(String value, String option) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            fail(option + " must be an integer: " + value);
            return -1;
        }
        if (parsed < 0) {
            fail(option + " must be >= 0: " + value);
        }
        return parsed;
    }

    private static int parsePositiveInt(String value, String option) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            fail(option + " must be an integer: " + value);
            return -1;
        }
        if (parsed < 1) {
            fail(option + " must be >= 1: " + value);
        }
        return parsed;
    }

    private static List<Pin> loadPins(Path path) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("cannot read pin file " + path + ": " + e.getMessage(), e);
        }

        List<Pin> pins = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                fail("invalid pin line " + (i + 1) + " in " + path
                        + ": expected '<64-hex> <name>'");
            }
            String pin = parts[0].toUpperCase(Locale.ROOT);
            if (!pin.matches("[0-9A-F]{64}")) {
                fail("invalid SPKI SHA-256 on line " + (i + 1) + " in " + path
                        + ": " + parts[0]);
            }
            if (!seen.add(pin)) {
                fail("duplicate SPKI SHA-256 on line " + (i + 1) + " in " + path
                        + ": " + pin);
            }
            String name = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
            pins.add(new Pin(pin, name));
        }
        if (pins.isEmpty()) {
            fail("pin file contains no pins: " + path);
        }
        return pins;
    }

    private static Map<String, Pin> pinMap(List<Pin> pins) {
        Map<String, Pin> map = new LinkedHashMap<>();
        for (Pin pin : pins) {
            map.put(pin.spkiSha256(), pin);
        }
        return map;
    }

    private static String detectStoreType(Path path) {
        final byte[] header;
        try (InputStream input = Files.newInputStream(path)) {
            header = input.readNBytes(4);
        } catch (IOException e) {
            throw new ToolException("cannot read truststore " + path + ": " + e.getMessage(), e);
        }
        if (header.length == 0) {
            throw new ToolException("empty truststore: " + path);
        }
        if (header.length == 4
                && (header[0] & 0xff) == 0xfe
                && (header[1] & 0xff) == 0xed
                && (header[2] & 0xff) == 0xfe
                && (header[3] & 0xff) == 0xed) {
            return "JKS";
        }
        if ((header[0] & 0xff) == 0x30) {
            return "PKCS12";
        }
        String magic = HexFormat.of().withUpperCase().formatHex(header);
        throw new ToolException("unsupported truststore format in " + path
                + ": magic 0x" + magic
                + " (expected JKS FEEDFEED or PKCS12 DER 0x30)");
    }

    private static LoadedStore loadStore(Path path) {
        String type = detectStoreType(path);
        final KeyStore store;
        try {
            store = KeyStore.getInstance(type);
        } catch (KeyStoreException e) {
            throw new ToolException("cannot create " + type + " KeyStore: " + e.getMessage(), e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, STORE_PASSWORD);
        } catch (IOException e) {
            throw new ToolException("cannot load " + type + " truststore " + path
                    + " (wrong password or corrupt store): " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new ToolException("cannot load " + type + " truststore " + path
                    + ": " + e.getMessage(), e);
        }

        final int certificateCount;
        try {
            certificateCount = countCertificateEntries(store);
        } catch (GeneralSecurityException e) {
            throw new ToolException("cannot enumerate truststore " + path
                    + ": " + e.getMessage(), e);
        }
        if (certificateCount == 0) {
            throw new ToolException("truststore contains no certificate entries: " + path);
        }
        return new LoadedStore(path, type, store, certificateCount);
    }

    private static int countCertificateEntries(KeyStore store) throws GeneralSecurityException {
        int count = 0;
        for (String alias : Collections.list(store.aliases())) {
            if (store.isCertificateEntry(alias)) {
                count++;
            }
        }
        return count;
    }

    private static List<CertInfo> certificateEntries(KeyStore store) {
        final List<String> aliases;
        try {
            aliases = Collections.list(store.aliases());
        } catch (KeyStoreException e) {
            throw new ToolException("cannot enumerate truststore aliases: " + e.getMessage(), e);
        }

        List<CertInfo> entries = new ArrayList<>();
        for (String alias : aliases) {
            final Certificate certificate;
            try {
                certificate = store.getCertificate(alias);
            } catch (KeyStoreException e) {
                throw new ToolException("cannot read certificate entry " + alias
                        + ": " + e.getMessage(), e);
            }
            if (certificate == null) {
                continue;
            }
            if (certificate instanceof X509Certificate x509) {
                entries.add(certInfo(alias, x509));
            } else {
                throw new ToolException("non-X.509 certificate entry: " + alias);
            }
        }
        return entries;
    }

    private static CertInfo certInfo(String alias, X509Certificate certificate) {
        final String spki;
        final String certSha;
        try {
            spki = spkiSha256(certificate);
            certSha = sha256Hex(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new ToolException("cannot encode certificate "
                    + (alias == null ? "" : alias) + ": " + e.getMessage(), e);
        }
        return new CertInfo(
                alias,
                certificate,
                spki,
                certSha,
                certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName());
    }

    private static String spkiSha256(X509Certificate certificate) {
        byte[] encoded = certificate.getPublicKey().getEncoded();
        if (encoded == null || encoded.length == 0) {
            throw new ToolException("certificate has no encoded public key: "
                    + certificate.getSubjectX500Principal().getName());
        }
        return sha256Hex(encoded);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String sha256File(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (IOException e) {
            throw new ToolException("cannot hash truststore " + path + ": " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static X509Certificate loadCertificate(Path path) {
        final byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ToolException("cannot read certificate " + path + ": " + e.getMessage(), e);
        }
        byte[] encoded = extractPem(data);
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate certificate = factory.generateCertificate(new ByteArrayInputStream(encoded));
            if (certificate instanceof X509Certificate x509) {
                return x509;
            }
            throw new ToolException("not an X.509 certificate: " + path);
        } catch (CertificateException e) {
            throw new ToolException("cannot parse X.509 certificate " + path
                    + ": " + e.getMessage(), e);
        }
    }

    private static byte[] extractPem(byte[] data) {
        String text = new String(data, StandardCharsets.US_ASCII);
        String beginMarker = "-----BEGIN CERTIFICATE-----";
        String endMarker = "-----END CERTIFICATE-----";
        int begin = text.indexOf(beginMarker);
        if (begin < 0) {
            return data;
        }
        int end = text.indexOf(endMarker, begin + beginMarker.length());
        if (end < 0) {
            throw new ToolException("unterminated PEM certificate block");
        }
        end += endMarker.length();
        return text.substring(begin, end).getBytes(StandardCharsets.US_ASCII);
    }

    private static List<CertInfo> findForbidden(List<CertInfo> entries,
                                                Map<String, Pin> pinsBySpki) {
        List<CertInfo> forbidden = new ArrayList<>();
        for (CertInfo entry : entries) {
            if (pinsBySpki.containsKey(entry.spkiSha256())) {
                forbidden.add(entry);
            }
        }
        return forbidden;
    }

    private static List<String> forbiddenNames(List<CertInfo> forbidden,
                                               Map<String, Pin> pinsBySpki) {
        Set<String> names = new LinkedHashSet<>();
        for (CertInfo info : forbidden) {
            Pin pin = pinsBySpki.get(info.spkiSha256());
            if (pin != null) {
                names.add(pin.name());
            }
        }
        return new ArrayList<>(names);
    }

    private static String describeForbidden(List<CertInfo> forbidden,
                                            Map<String, Pin> pinsBySpki) {
        List<String> parts = new ArrayList<>();
        for (CertInfo info : forbidden) {
            Pin pin = pinsBySpki.get(info.spkiSha256());
            String name = pin == null ? "unpinned" : pin.name();
            String alias = info.alias() == null ? "default" : info.alias();
            parts.add(name + " (alias " + alias + ", SPKI " + info.spkiSha256() + ")");
        }
        return String.join("; ", parts);
    }

    private static boolean isKeyEntry(KeyStore store, String alias) {
        try {
            return store.isKeyEntry(alias);
        } catch (KeyStoreException e) {
            throw new ToolException("cannot inspect alias " + alias + ": " + e.getMessage(), e);
        }
    }

    private static void setOwnerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem; permissions are best effort there.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeCertInfo(JsonWriter json, CertInfo info,
                                      Map<String, Pin> pinsBySpki) {
        Pin pin = pinsBySpki.get(info.spkiSha256());
        json.beginObject();
        json.name("alias").value(info.alias());
        json.name("name").value(pin == null ? null : pin.name());
        json.name("spkiSha256").value(info.spkiSha256());
        json.name("certSha256").value(info.certSha256());
        json.name("subject").value(info.subject());
        json.name("issuer").value(info.issuer());
        json.endObject();
    }

    private static void writeEnvironment(JsonWriter json) {
        json.name("jdkVersion").value(System.getProperty("java.runtime.version"));
        json.name("javaHome").value(System.getProperty("java.home"));
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private static void fail(String message) {
        throw new ToolException(message);
    }

    private record Pin(String spkiSha256, String name) {
    }

    private record CertInfo(
            String alias,
            X509Certificate certificate,
            String spkiSha256,
            String certSha256,
            String subject,
            String issuer) {
    }

    private record LoadedStore(
            Path path,
            String type,
            KeyStore store,
            int certificateCount) {
    }

    private static final class Options {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, List<String>> lists = new LinkedHashMap<>();

        String get(String name) {
            return values.get(name);
        }

        String require(String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                fail("missing required option: " + name);
            }
            return value;
        }

        String getOrDefault(String name, String fallback) {
            String value = values.get(name);
            return value == null ? fallback : value;
        }

        List<String> list(String name) {
            return lists.getOrDefault(name, List.of());
        }
    }

    private static final class ToolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message);
        }

        ToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Minimal streaming JSON writer. It emits one compact line and escapes all
     * non-ASCII characters so logs remain safe regardless of console encoding.
     */
    private static final class JsonWriter {
        private static final int OBJECT = 1;
        private static final int ARRAY = 2;

        private final StringBuilder out = new StringBuilder();
        private final Deque<Integer> stack = new ArrayDeque<>();
        private final Deque<Integer> counts = new ArrayDeque<>();
        private boolean expectingValue;

        JsonWriter beginObject() {
            beforeValue();
            out.append('{');
            push(OBJECT);
            return this;
        }

        JsonWriter endObject() {
            finish(OBJECT);
            out.append('}');
            return this;
        }

        JsonWriter beginArray() {
            beforeValue();
            out.append('[');
            push(ARRAY);
            return this;
        }

        JsonWriter endArray() {
            finish(ARRAY);
            out.append(']');
            return this;
        }

        JsonWriter name(String name) {
            if (stack.isEmpty() || stack.peek() != OBJECT) {
                throw new IllegalStateException("JSON name outside object");
            }
            if (expectingValue) {
                throw new IllegalStateException("JSON name while expecting value");
            }
            if (counts.peek() > 0) {
                out.append(',');
            }
            out.append(quote(name)).append(':');
            counts.push(counts.pop() + 1);
            expectingValue = true;
            return this;
        }

        JsonWriter value(String value) {
            beforeValue();
            out.append(value == null ? "null" : quote(value));
            return this;
        }

        JsonWriter value(long value) {
            beforeValue();
            out.append(value);
            return this;
        }

        JsonWriter value(boolean value) {
            beforeValue();
            out.append(value);
            return this;
        }

        private void beforeValue() {
            if (stack.isEmpty()) {
                return;
            }
            if (stack.peek() == OBJECT) {
                if (!expectingValue) {
                    throw new IllegalStateException("JSON object value without name");
                }
                expectingValue = false;
            } else {
                if (counts.peek() > 0) {
                    out.append(',');
                }
                counts.push(counts.pop() + 1);
            }
        }

        private void push(int type) {
            stack.push(type);
            counts.push(0);
            expectingValue = false;
        }

        private void finish(int expectedType) {
            if (stack.isEmpty() || stack.peek() != expectedType) {
                throw new IllegalStateException("mismatched JSON container");
            }
            if (expectingValue) {
                throw new IllegalStateException("JSON object ended while expecting value");
            }
            stack.pop();
            counts.pop();
        }

        @Override
        public String toString() {
            if (!stack.isEmpty()) {
                throw new IllegalStateException("unclosed JSON container");
            }
            return out.toString();
        }
    }
}
