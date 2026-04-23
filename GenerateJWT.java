import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.Properties;

/**
 * GenerateJWT.java
 * ----------------
 * Generates a signed JWT assertion for the Salesforce JWT Bearer Token flow.
 * No external dependencies — uses only the Java standard library.
 *
 * Usage:
 *   javac GenerateJWT.java
 *   java GenerateJWT --config jwt.properties
 *
 * The jwt.properties file must contain:
 *   consumer_key=<ECA_CONSUMER_KEY>
 *   username=<SF_USERNAME>
 *   audience=<MY_DOMAIN_URL>
 *   private_key=<path/to/private.key>
 *   expiry=180
 *
 * Notes:
 *   - The private key must be in PEM format (PKCS#8).
 *   - Salesforce enforces a maximum JWT expiry of 3 minutes (180 seconds).
 *   - The script outputs the JWT assertion to stdout so it can be piped or captured.
 */
public class GenerateJWT {

    public static void main(String[] args) {
        try {
            // ---------------------------------------------------------------
            // Parse CLI arguments
            // ---------------------------------------------------------------
            String configPath = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config":
                        if (i + 1 < args.length) configPath = args[++i];
                        break;
                    case "--help":
                    case "-h":
                        printHelp();
                        return;
                    default:
                        System.err.println("ERROR: Unknown argument: " + args[i]);
                        printHelp();
                        System.exit(1);
                }
            }
            if (configPath == null) {
                System.err.println("ERROR: --config <path> is required.");
                printHelp();
                System.exit(1);
            }

            // ---------------------------------------------------------------
            // Load properties
            // ---------------------------------------------------------------
            Properties props = new Properties();
            Path propsPath = Paths.get(configPath).toAbsolutePath();
            try (InputStream in = Files.newInputStream(propsPath)) {
                props.load(in);
            }

            String consumerKey = requireProp(props, "consumer_key");
            String username    = requireProp(props, "username");
            String audience    = requireProp(props, "audience");
            String keyFile     = props.getProperty("private_key", "private.key");
            int    expiry      = Integer.parseInt(props.getProperty("expiry", "180"));

            if (expiry > 180) {
                System.err.println("ERROR: Salesforce enforces a maximum JWT expiry of 180 seconds (3 minutes).");
                System.exit(1);
            }
            if (expiry < 1) {
                System.err.println("ERROR: expiry must be at least 1 second.");
                System.exit(1);
            }

            // Resolve key path relative to properties file
            Path keyPath = Paths.get(keyFile);
            if (!keyPath.isAbsolute()) {
                keyPath = propsPath.getParent().resolve(keyPath);
            }

            // ---------------------------------------------------------------
            // Load private key (PEM → PKCS8)
            // ---------------------------------------------------------------
            PrivateKey privateKey = loadPemPrivateKey(keyPath);

            // ---------------------------------------------------------------
            // Build JWT
            // ---------------------------------------------------------------
            String token = buildJwt(consumerKey, username, audience, privateKey, expiry);
            System.out.println(token);

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // JWT construction
    // -----------------------------------------------------------------------

    private static String buildJwt(String consumerKey, String username,
                                   String audience, PrivateKey privateKey,
                                   int expirySeconds) throws Exception {

        // Header
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";

        // Payload
        long now = System.currentTimeMillis() / 1000;
        String payload = String.format(
            "{\"iss\":\"%s\",\"sub\":\"%s\",\"aud\":\"%s\",\"exp\":%d}",
            consumerKey, username, audience, now + expirySeconds
        );

        // Unsigned token
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String unsignedToken =
            encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        // Sign with RS256
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = sig.sign();

        return unsignedToken + "." + encoder.encodeToString(signatureBytes);
    }

    // -----------------------------------------------------------------------
    // PEM key loading
    // -----------------------------------------------------------------------

    private static PrivateKey loadPemPrivateKey(Path keyPath) throws Exception {
        if (!Files.exists(keyPath)) {
            throw new FileNotFoundException("Private key file not found: " + keyPath);
        }

        String pem = new String(Files.readAllBytes(keyPath), StandardCharsets.UTF_8);

        // Handle both PKCS#8 and PKCS#1 PEM formats
        if (pem.contains("BEGIN PRIVATE KEY")) {
            // PKCS#8 format
            String base64Key = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);

        } else if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS#1 format — wrap in PKCS#8 envelope
            String base64Key = pem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] pkcs1Bytes = Base64.getDecoder().decode(base64Key);

            // Build PKCS#8 wrapper around PKCS#1 key
            byte[] pkcs8Bytes = wrapPkcs1InPkcs8(pkcs1Bytes);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8Bytes);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);

        } else {
            throw new IllegalArgumentException(
                "Unrecognized PEM format. Expected BEGIN PRIVATE KEY or BEGIN RSA PRIVATE KEY.");
        }
    }

    /**
     * Wraps a PKCS#1 RSA private key in a PKCS#8 envelope so Java's
     * KeyFactory can parse it.
     */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1Bytes) throws IOException {
        // RSA OID: 1.2.840.113549.1.1.1
        byte[] rsaOid = {
            0x30, 0x0d,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        };

        // Wrap the PKCS#1 key in an OCTET STRING
        byte[] octetString = wrapInTag((byte) 0x04, pkcs1Bytes);

        // Build the SEQUENCE: version(0) + algorithmIdentifier + privateKey
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        // Version 0
        inner.write(new byte[]{0x02, 0x01, 0x00});
        inner.write(rsaOid);
        inner.write(octetString);

        return wrapInTag((byte) 0x30, inner.toByteArray());
    }

    private static byte[] wrapInTag(byte tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write((byte) 0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write((byte) 0x82);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write((byte) 0x83);
            out.write((length >> 16) & 0xff);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private static String requireProp(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            System.err.println("ERROR: " + key + " is missing from properties file.");
            System.exit(1);
        }
        return value.trim();
    }

    private static void printHelp() {
        System.out.println(
            "\nUsage:\n" +
            "  javac GenerateJWT.java\n" +
            "  java GenerateJWT --config jwt.properties\n" +
            "\nOptions:\n" +
            "  --config <path>   Path to jwt.properties file           [required]\n" +
            "  --help            Show this help message\n" +
            "\njwt.properties format:\n" +
            "  consumer_key=<ECA_CONSUMER_KEY>\n" +
            "  username=<SALESFORCE_USERNAME>\n" +
            "  audience=<MY_DOMAIN_URL>\n" +
            "  private_key=private.key\n" +
            "  expiry=180\n"
        );
    }
}
