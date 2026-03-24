import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

/**
 * GenerateJWT.java
 * ----------------
 * Generates a signed RS256 JWT assertion for the Salesforce JWT Bearer Token flow.
 *
 * No external libraries required — uses Java built-in java.security APIs.
 *
 * Compile:
 *   javac GenerateJWT.java
 *
 * Run using config file (recommended):
 *   java GenerateJWT --config jwt.properties
 *
 * Run (Dev org):
 *   java GenerateJWT \
 *       --consumer-key YOUR_ECA_CONSUMER_KEY \
 *       --username your.user@yourcompany.com \
 *       --private-key server.key \
 *       --org dev
 *
 * Run (QA org):
 *   java GenerateJWT \
 *       --consumer-key YOUR_ECA_CONSUMER_KEY \
 *       --username your.user@yourcompany.com.qa \
 *       --private-key server.key \
 *       --org qa
 */
public class GenerateJWT {

    // Salesforce token endpoint base URLs
    static final String PROD_AUD    = "https://login.salesforce.com";
    static final String SANDBOX_AUD = "https://test.salesforce.com";

    public static void main(String[] args) throws Exception {

        // ── Parse arguments ──────────────────────────────────────────────────
        String consumerKey    = null;
        String username       = null;
        String privateKeyPath = null;
        String org            = null;   // null = let config or audience drive it
        String audienceOverride = null;
        int    expiry         = 180;
        boolean verbose       = false;
        String configFile     = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":        case "-c": configFile       = args[++i]; break;
                case "--consumer-key":  case "-k": consumerKey      = args[++i]; break;
                case "--username":      case "-u": username         = args[++i]; break;
                case "--private-key":   case "-p": privateKeyPath   = args[++i]; break;
                case "--org":           case "-o": org              = args[++i]; break;
                case "--expiry":        case "-e": expiry           = Integer.parseInt(args[++i]); break;
                case "--verbose":       case "-v": verbose          = true; break;
                case "--help":          case "-h": printHelp(); return;
            }
        }

        // ── Load config file if provided (CLI args override config values) ────
        if (configFile != null) {
            Properties props = new Properties();
            File cfg = new File(configFile);
            if (!cfg.exists()) {
                System.err.println("ERROR: Config file not found: " + configFile);
                System.exit(1);
            }
            try (FileInputStream fis = new FileInputStream(cfg)) {
                props.load(fis);
            }
            // Only apply config values if not already set by CLI args
            if (consumerKey    == null) consumerKey    = props.getProperty("consumer_key");
            if (username       == null) username       = props.getProperty("username");
            if (privateKeyPath == null) privateKeyPath = props.getProperty("private_key");
            if (org            == null) org            = props.getProperty("org");
            audienceOverride = props.getProperty("audience"); // explicit audience in config
            String expiryStr = props.getProperty("expiry");
            if (expiryStr != null && !expiryStr.isEmpty()) expiry = Integer.parseInt(expiryStr.trim());
        }

        // ── Validate required arguments ───────────────────────────────────────
        if (consumerKey == null || username == null || privateKeyPath == null) {
            System.err.println("ERROR: --consumer-key, --username, and --private-key are required.");
            System.err.println("       Run with --help for usage.");
            System.exit(1);
        }
        if (expiry > 180) {
            System.err.println("ERROR: Salesforce enforces a maximum JWT expiry of 180 seconds (3 minutes).");
            System.exit(1);
        }
        if (expiry < 1) {
            System.err.println("ERROR: Expiry must be at least 1 second.");
            System.exit(1);
        }

        // ── Resolve audience from explicit value, org type, or default ───────
        String audience;
        if (audienceOverride != null && !audienceOverride.isEmpty()) {
            // Use audience directly from config file
            audience = audienceOverride.trim();
        } else {
            String orgResolved = (org != null) ? org : "prod";
            switch (orgResolved.toLowerCase()) {
                case "dev":     audience = PROD_AUD;    break;
                case "prod":    audience = PROD_AUD;    break;
                case "qa":      audience = SANDBOX_AUD; break;
                case "sandbox": audience = SANDBOX_AUD; break;
                default:
                    System.err.println("ERROR: Unknown org type '" + orgResolved + "'. Use: dev, qa, prod, or sandbox.");
                    System.exit(1);
                    return;
            }
        }

        // ── Load private key ──────────────────────────────────────────────────
        PrivateKey privateKey = loadPrivateKey(privateKeyPath);

        // ── Build JWT ─────────────────────────────────────────────────────────
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + expiry;

        String header  = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(String.format(
            "{\"iss\":\"%s\",\"sub\":\"%s\",\"aud\":\"%s\",\"exp\":%d}",
            consumerKey, username, audience, expiresAt
        ));

        String signingInput = header + "." + payload;

        // ── Sign with RS256 ───────────────────────────────────────────────────
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(signingInput.getBytes("UTF-8"));
        byte[] signatureBytes = signer.sign();
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);

        String jwt = signingInput + "." + signature;

        // ── Verbose output (to stderr so JWT remains clean on stdout) ─────────
        if (verbose) {
            System.err.println("[JWT] Org target   : " + (org != null ? org.toUpperCase() : "CONFIG") + " (" + audience + ")");
            System.err.println("[JWT] Issuer  (iss): " + consumerKey);
            System.err.println("[JWT] Subject (sub): " + username);
            System.err.println("[JWT] Audience(aud): " + audience);
            System.err.println("[JWT] Issued at    : " + issuedAt);
            System.err.println("[JWT] Expires at   : " + expiresAt + " (+" + expiry + "s)");
            System.err.println("[JWT] Algorithm    : RS256");
            System.err.println();
        }

        // ── Output JWT ────────────────────────────────────────────────────────
        System.out.println(jwt);
    }

    /**
     * Loads an RS256 PEM private key from the given file path.
     * Supports PKCS#8 (BEGIN PRIVATE KEY) and PKCS#1 (BEGIN RSA PRIVATE KEY) formats.
     */
    static PrivateKey loadPrivateKey(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            System.err.println("ERROR: Private key file not found: " + path);
            System.exit(1);
        }

        String pem = new String(Files.readAllBytes(file.toPath()))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(pem);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        // Try PKCS#8 first (most common for PEM exported from OpenSSL)
        try {
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (InvalidKeySpecException e) {
            // Fall back to PKCS#1 if needed
            // Wrap PKCS#1 bytes in a PKCS#8 envelope
            byte[] pkcs8 = wrapPkcs1InPkcs8(keyBytes);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }
    }

    /**
     * Wraps a raw PKCS#1 RSA key in a PKCS#8 envelope so Java's KeyFactory can load it.
     */
    static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        // PKCS#8 header for RSA (OID 1.2.840.113549.1.1.1)
        byte[] prefix = new byte[] {
            0x30, (byte)0x82, 0x00, 0x00, // SEQUENCE (length filled below)
            0x02, 0x01, 0x00,             // INTEGER version = 0
            0x30, 0x0d,                   // SEQUENCE
            0x06, 0x09,                   // OID length
            0x2a, (byte)0x86, 0x48, (byte)0x86, (byte)0xf7, 0x0d, 0x01, 0x01, 0x01, // OID rsaEncryption
            0x05, 0x00,                   // NULL
            0x04, (byte)0x82, 0x00, 0x00  // OCTET STRING (length filled below)
        };

        int totalLen = prefix.length + pkcs1.length - 4; // -4 for outer SEQUENCE tag+len
        prefix[2] = (byte)((totalLen >> 8) & 0xff);
        prefix[3] = (byte)(totalLen & 0xff);

        int octetLen = pkcs1.length;
        prefix[prefix.length - 2] = (byte)((octetLen >> 8) & 0xff);
        prefix[prefix.length - 1] = (byte)(octetLen & 0xff);

        byte[] result = new byte[prefix.length + pkcs1.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(pkcs1, 0, result, prefix.length, pkcs1.length);
        return result;
    }

    /** Base64URL-encodes a UTF-8 string without padding. */
    static String base64Url(String input) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
               .encodeToString(input.getBytes("UTF-8"));
    }

    static void printHelp() {
        System.out.println("Usage: java GenerateJWT [options]");
        System.out.println();
        System.out.println("Config file (recommended):");
        System.out.println("  --config,        -c  Path to .properties file (e.g. jwt.properties)");
        System.out.println();
        System.out.println("Required (if not using config file):");
        System.out.println("  --consumer-key,  -k  ECA Consumer Key (JWT issuer / client_id)");
        System.out.println("  --username,      -u  Salesforce integration username (JWT subject)");
        System.out.println("  --private-key,   -p  Path to RS256 PEM private key file");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --org,           -o  dev | qa | prod | sandbox  (default: prod)");
        System.out.println("                         dev  → login.salesforce.com");
        System.out.println("                         qa   → test.salesforce.com");
        System.out.println("  --expiry,        -e  Lifetime in seconds, max 180  (default: 180)");
        System.out.println("  --verbose,       -v  Print decoded claims to stderr");
        System.out.println("  --help,          -h  Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java GenerateJWT --config jwt.properties");
        System.out.println("  java GenerateJWT --config jwt.properties --verbose");
        System.out.println("  java GenerateJWT --consumer-key YOUR_KEY... --username user@yourcompany.com --private-key server.key --org dev");
        System.out.println("  java GenerateJWT -k YOUR_KEY... -u user@yourcompany.com.qa -p server.key -o qa --verbose");
    }
}
