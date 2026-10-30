import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline PKIX chain-trust probe.
 *
 * <p>Loads a JKS or PKCS12 truststore, parses a PEM chain in leaf-first order,
 * and tries every prefix of the chain as a PKIX certification path at a fixed
 * instant. The program never uses the current time and never performs network
 * access. It is intentionally a single-file Java 17 program so it can be run
 * either in source-file mode or from a compiled classes directory.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 - validation result matched {@code --expect}</li>
 *   <li>1 - validation result did not match {@code --expect}</li>
 *   <li>2 - usage, I/O, store, certificate, or validation setup error</li>
 * </ul>
 *
 * <p>The store password defaults to the public JDK default "changeit". It is
 * not a secret and is never printed.
 */
public final class ChainTrustProbe {
    private static final String DEFAULT_DATE = "2026-10-25T00:00:00Z";
    private static final String DEFAULT_PASSWORD = "changeit";
    private static final String DEFAULT_EXPECT = "accepted";

    private static final String PEM_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String PEM_END = "-----END CERTIFICATE-----";

    private static final String USAGE = """
            ChainTrustProbe - validate a public PEM chain against a JKS/PKCS12 truststore.

            Usage:
              java ChainTrustProbe.java --truststore FILE --chain FILE
                   [--date ISO_INSTANT] [--expect accepted|rejected]
                   [--store-password PASSWORD]

            Options:
              --truststore FILE       JKS (FEEDFEED) or PKCS12 (DER 0x30) truststore.
              --chain FILE            PEM file with the leaf first, then intermediates.
              --date ISO_INSTANT      Validation instant (default: 2026-10-25T00:00:00Z).
              --expect accepted|rejected
                                      Required validation result (default: accepted).
              --store-password PASSWORD
                                      Store password (default: changeit).
              --help, -h              Print this help.

            Every prefix of the chain (1..n certificates) is tried as a PKIX
            certification path with revocation disabled. The fixed date is always
            used; the current time is never consulted. No network access is made.

            Exit codes: 0 = result matched --expect, 1 = result did not match,
            2 = usage or I/O/store/validation setup error.

            The default store password is the public JDK default "changeit"; it
            is not a secret.
            """;

    private ChainTrustProbe() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (UsageException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            System.exit(2);
        } catch (ToolException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            String message = e.getMessage();
            System.err.println("ERROR: " + (message == null || message.isBlank()
                    ? e.getClass().getSimpleName()
                    : message));
            System.exit(2);
        }
    }

    private static void run(String[] args) {
        String truststoreOption = null;
        String chainOption = null;
        String dateOption = DEFAULT_DATE;
        String expectOption = DEFAULT_EXPECT;
        String passwordOption = DEFAULT_PASSWORD;
        Set<String> seenOptions = new LinkedHashSet<>();

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if ("--help".equals(argument) || "-h".equals(argument)) {
                System.out.print(USAGE);
                return;
            }
            if (!argument.startsWith("--")) {
                throw new UsageException("unexpected argument: " + argument);
            }
            String name;
            String value;
            int equals = argument.indexOf('=');
            if (equals >= 0) {
                name = argument.substring(0, equals);
                value = argument.substring(equals + 1);
            } else {
                name = argument;
                if (i + 1 >= args.length) {
                    throw new UsageException("missing value for " + name);
                }
                value = args[++i];
            }
            if (!seenOptions.add(name)) {
                throw new UsageException("duplicate option: " + name);
            }
            switch (name) {
                case "--truststore" -> truststoreOption = value;
                case "--chain" -> chainOption = value;
                case "--date" -> dateOption = value;
                case "--expect" -> expectOption = value;
                case "--store-password" -> passwordOption = value;
                default -> throw new UsageException("unknown option: " + name);
            }
        }

        if (truststoreOption == null || truststoreOption.isBlank()) {
            throw new UsageException("missing required option: --truststore");
        }
        if (chainOption == null || chainOption.isBlank()) {
            throw new UsageException("missing required option: --chain");
        }
        if (!"accepted".equals(expectOption) && !"rejected".equals(expectOption)) {
            throw new UsageException("--expect must be 'accepted' or 'rejected': " + expectOption);
        }

        final Instant instant;
        try {
            instant = Instant.parse(dateOption);
        } catch (DateTimeParseException e) {
            throw new UsageException("--date is not an ISO-8601 instant: " + dateOption);
        }

        Path truststorePath = requireReadableFile(Path.of(truststoreOption), "--truststore");
        Path chainPath = requireReadableFile(Path.of(chainOption), "--chain");

        LoadedStore loaded = loadTruststore(truststorePath, passwordOption.toCharArray());
        List<X509Certificate> certificates = loadChain(chainPath);
        Set<TrustAnchor> anchors = trustAnchors(loaded.store(), truststorePath);
        if (anchors.isEmpty()) {
            throw new ToolException("truststore contains no certificate entries: " + truststorePath);
        }

        final CertPathValidator validator;
        final CertificateFactory certificateFactory;
        try {
            validator = CertPathValidator.getInstance("PKIX");
            certificateFactory = CertificateFactory.getInstance("X.509");
        } catch (GeneralSecurityException e) {
            throw new ToolException("cannot initialize PKIX validation: " + e.getMessage(), e);
        }

        List<Attempt> attempts = new ArrayList<>();
        boolean valid = false;
        int pathLength = 0;
        String anchorSubject = null;

        for (int length = 1; length <= certificates.size(); length++) {
            List<Certificate> prefix = new ArrayList<>(certificates.subList(0, length));
            try {
                CertPath path = certificateFactory.generateCertPath(prefix);
                PKIXParameters parameters = new PKIXParameters(anchors);
                parameters.setRevocationEnabled(false);
                parameters.setDate(Date.from(instant));
                PKIXCertPathValidatorResult result =
                        (PKIXCertPathValidatorResult) validator.validate(path, parameters);
                String matchedAnchor = describeAnchor(result.getTrustAnchor());
                attempts.add(new Attempt(length, matchedAnchor, null));
                if (!valid) {
                    valid = true;
                    pathLength = length;
                    anchorSubject = matchedAnchor;
                }
            } catch (CertPathValidatorException e) {
                attempts.add(new Attempt(length, null, describeValidationFailure(e)));
            } catch (GeneralSecurityException e) {
                attempts.add(new Attempt(length, null, describeValidationFailure(e)));
            }
        }

        boolean expectAccepted = "accepted".equals(expectOption);
        boolean matched = valid == expectAccepted;

        System.out.println(toJson(
                truststorePath,
                chainPath,
                instant,
                loaded.type(),
                certificates.size(),
                valid,
                expectOption,
                matched,
                pathLength,
                anchorSubject,
                anchors.size(),
                attempts));

        String outcome = valid ? "accepted" : "rejected";
        System.err.println("chain-trust-probe: " + chainPath + " -> " + outcome
                + " at " + instant + " against " + loaded.type() + " store "
                + truststorePath + " (" + anchors.size() + " trust anchor(s), "
                + attempts.size() + " attempt(s), path length " + pathLength + ")");
        if (!matched) {
            System.err.println("MISMATCH: expected " + expectOption + " but validation was " + outcome);
            System.exit(1);
        }
    }

    private static Path requireReadableFile(Path path, String option) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new ToolException(option + " does not exist: " + absolute);
        }
        if (!Files.isRegularFile(absolute)) {
            throw new ToolException(option + " is not a regular file: " + absolute);
        }
        if (!Files.isReadable(absolute)) {
            throw new ToolException(option + " is not readable: " + absolute);
        }
        return absolute;
    }

    private static LoadedStore loadTruststore(Path path, char[] password) {
        final byte[] header;
        try (InputStream input = Files.newInputStream(path)) {
            header = input.readNBytes(4);
        } catch (IOException e) {
            throw new ToolException("cannot read truststore " + path + ": " + e.getMessage(), e);
        }
        if (header.length == 0) {
            throw new ToolException("empty truststore: " + path);
        }
        final String type;
        if (header.length == 4
                && (header[0] & 0xff) == 0xfe
                && (header[1] & 0xff) == 0xed
                && (header[2] & 0xff) == 0xfe
                && (header[3] & 0xff) == 0xed) {
            type = "JKS";
        } else if ((header[0] & 0xff) == 0x30) {
            type = "PKCS12";
        } else {
            throw new ToolException("unsupported truststore format in " + path
                    + " (expected JKS FEEDFEED or PKCS12 DER 0x30)");
        }

        final KeyStore store;
        try {
            store = KeyStore.getInstance(type);
        } catch (KeyStoreException e) {
            throw new ToolException("cannot create " + type + " KeyStore: " + e.getMessage(), e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        } catch (IOException e) {
            throw new ToolException("cannot load " + type + " truststore " + path
                    + " (wrong password or corrupt store): " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new ToolException("cannot load " + type + " truststore " + path
                    + ": " + e.getMessage(), e);
        }
        return new LoadedStore(type, store);
    }

    private static Set<TrustAnchor> trustAnchors(KeyStore store, Path path) {
        Set<TrustAnchor> anchors = new LinkedHashSet<>();
        try {
            for (String alias : Collections.list(store.aliases())) {
                if (!store.isCertificateEntry(alias)) {
                    continue;
                }
                Certificate certificate = store.getCertificate(alias);
                if (certificate instanceof X509Certificate x509) {
                    anchors.add(new TrustAnchor(x509, null));
                } else if (certificate != null) {
                    throw new ToolException("non-X.509 certificate entry " + alias
                            + " in truststore " + path);
                }
            }
        } catch (KeyStoreException e) {
            throw new ToolException("cannot enumerate truststore " + path + ": " + e.getMessage(), e);
        }
        return anchors;
    }

    private static List<X509Certificate> loadChain(Path path) {
        final String text;
        try {
            text = Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new ToolException("cannot read chain " + path + ": " + e.getMessage(), e);
        }

        final CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new ToolException("cannot initialize X.509 parser: " + e.getMessage(), e);
        }

        List<X509Certificate> certificates = new ArrayList<>();
        int offset = 0;
        while (true) {
            int begin = text.indexOf(PEM_BEGIN, offset);
            if (begin < 0) {
                break;
            }
            int end = text.indexOf(PEM_END, begin + PEM_BEGIN.length());
            if (end < 0) {
                throw new ToolException("unterminated PEM certificate block in " + path);
            }
            end += PEM_END.length();
            String block = text.substring(begin, end);
            try {
                Certificate certificate = factory.generateCertificate(
                        new ByteArrayInputStream(block.getBytes(StandardCharsets.US_ASCII)));
                if (!(certificate instanceof X509Certificate x509)) {
                    throw new ToolException("not an X.509 certificate in " + path);
                }
                certificates.add(x509);
            } catch (CertificateException e) {
                throw new ToolException("cannot parse certificate "
                        + (certificates.size() + 1) + " in " + path + ": " + e.getMessage(), e);
            }
            offset = end;
        }
        if (certificates.isEmpty()) {
            throw new ToolException("chain file contains no PEM certificates: " + path);
        }
        return certificates;
    }

    private static String describeAnchor(TrustAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        X509Certificate trusted = anchor.getTrustedCert();
        if (trusted != null) {
            return trusted.getSubjectX500Principal().getName();
        }
        return anchor.getCAName();
    }

    private static String describeValidationFailure(Exception exception) {
        String message = exception.getMessage();
        String reason = exception instanceof CertPathValidatorException validatorException
                ? String.valueOf(validatorException.getReason())
                : exception.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return reason;
        }
        return reason + ": " + message;
    }

    private static String toJson(Path truststore,
                                 Path chain,
                                 Instant instant,
                                 String storeType,
                                 int chainLength,
                                 boolean valid,
                                 String expect,
                                 boolean matched,
                                 int pathLength,
                                 String anchorSubject,
                                 int trustAnchorCount,
                                 List<Attempt> attempts) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "command", "chain-trust-probe");
        json.append(',');
        field(json, "truststore", truststore.toString());
        json.append(',');
        field(json, "chain", chain.toString());
        json.append(',');
        field(json, "storeType", storeType);
        json.append(',');
        field(json, "date", instant.toString());
        json.append(',');
        json.append(quote("chainLength")).append(':').append(chainLength).append(',');
        json.append(quote("valid")).append(':').append(valid).append(',');
        field(json, "expect", expect);
        json.append(',');
        json.append(quote("matched")).append(':').append(matched).append(',');
        json.append(quote("pathLength")).append(':').append(pathLength).append(',');
        field(json, "anchorSubject", anchorSubject);
        json.append(',');
        json.append(quote("trustAnchorCount")).append(':').append(trustAnchorCount).append(',');
        json.append(quote("attempts")).append(":[");
        for (int i = 0; i < attempts.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Attempt attempt = attempts.get(i);
            json.append('{');
            json.append(quote("pathLength")).append(':').append(attempt.pathLength()).append(',');
            field(json, "anchorSubject", attempt.anchorSubject());
            json.append(',');
            field(json, "error", attempt.error());
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append(quote(name)).append(':').append(quote(value));
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
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private record Attempt(int pathLength, String anchorSubject, String error) {
    }

    private record LoadedStore(String type, KeyStore store) {
    }

    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
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
}
