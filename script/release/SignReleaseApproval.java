import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** Java 21 source-file launcher; no dependencies, network, key generation or private-key output in normal mode. */
public class SignReleaseApproval {
    private record Approval(byte[] payload, byte[] signature, String envelope) { }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("--self-test")) {
                selfTest();
                return;
            }
            if (args.length != 11) {
                System.err.println("Usage: java SignReleaseApproval.java private.pem public.pem manifest.json approval.json "
                        + "approvalId approver operator environment PUBLISH|RECOVER|REVOKE expiresAtUTC maxEmbeddingRequests");
                System.exit(2);
            }
            Path privatePath = Path.of(args[0]).toRealPath();
            require(!privatePath.startsWith(repositoryRoot()), "PRIVATE_KEY_MUST_BE_OUTSIDE_REPOSITORY");
            KeyFactory keys = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = keys.generatePrivate(new PKCS8EncodedKeySpec(pem(read(privatePath, 8192), "PRIVATE KEY")));
            PublicKey publicKey = keys.generatePublic(new X509EncodedKeySpec(pem(read(Path.of(args[1]), 8192), "PUBLIC KEY")));
            byte[] manifest = read(Path.of(args[2]), 65_536);
            byte[] payload = payload(manifest, args[4], args[5], args[6], args[7], args[8], args[9], args[10], Instant.now());
            Approval approval = sign(payload, privateKey);
            require(verify(approval, publicKey), "PUBLIC_KEY_DOES_NOT_MATCH_SIGNER");
            Path output = Path.of(args[3]).toAbsolutePath().normalize();
            Files.writeString(output, approval.envelope() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("APPROVAL_WRITTEN=" + output);
            System.out.println("MANIFEST_SHA256=" + sha256(manifest));
            System.out.println("PUBLIC_KEY_SPKI_SHA256=" + sha256(publicKey.getEncoded()));
        } catch (Exception error) {
            System.err.println("APPROVAL_SIGNING_FAILED=" + (error instanceof SigningFailure ? error.getMessage() : "CRYPTO_OR_IO_FAILURE"));
            System.exit(1);
        }
    }

    private static byte[] payload(byte[] manifest, String approvalId, String approver, String operator,
                                  String environment, String operation, String expiry, String budget, Instant now) {
        require(approvalId.matches("[A-Za-z0-9._-]{1,80}"), "INVALID_APPROVAL_ID");
        for (String identity : new String[] { approver, operator }) {
            require(identity.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}"), "INVALID_IDENTITY");
        }
        require(environment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"), "INVALID_ENVIRONMENT");
        require(!approver.equals(operator), "APPROVER_AND_OPERATOR_MUST_DIFFER");
        require(Set.of("PUBLISH", "RECOVER", "REVOKE").contains(operation), "INVALID_OPERATION");
        require(expiry.endsWith("Z"), "EXPIRY_MUST_USE_UTC_Z");
        Instant expiresAt = Instant.parse(expiry);
        require(expiresAt.isAfter(now), "APPROVAL_ALREADY_EXPIRED");
        require(budget.matches("[0-9]{1,10}"), "INVALID_EMBEDDING_BUDGET");
        int requests = Integer.parseInt(budget);
        require(requests >= 0 && (!operation.equals("REVOKE") || requests == 0), "INVALID_EMBEDDING_BUDGET");
        require(manifest.length > 0 && manifest.length <= 65_536, "INVALID_MANIFEST_SIZE");
        // Identity characters are restricted above; only fixed JSON fields are constructed here.
        String json = "{\"approvalId\":\"" + approvalId + "\",\"approver\":\"" + approver
                + "\",\"operator\":\"" + operator + "\",\"environment\":\"" + environment
                + "\",\"manifestSha256\":\"" + sha256(manifest) + "\",\"expiresAt\":\"" + expiresAt
                + "\",\"maxEmbeddingRequests\":" + requests + ",\"allowedOperation\":\"" + operation + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Approval sign(byte[] payload, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(payload);
        byte[] signature = signer.sign();
        String envelope = "{\"payload\":\"" + encode(payload) + "\",\"signature\":\"" + encode(signature) + "\"}";
        return new Approval(payload, signature, envelope);
    }

    private static boolean verify(Approval approval, PublicKey key) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(approval.payload());
        return verifier.verify(approval.signature());
    }

    private static byte[] pem(byte[] bytes, String label) {
        String text = new String(bytes, StandardCharsets.US_ASCII).strip();
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        require(text.startsWith(begin) && text.endsWith(end), "EXPECTED_UNENCRYPTED_PKCS8_OR_SPKI_PEM");
        return Base64.getDecoder().decode(text.substring(begin.length(), text.length() - end.length()).replaceAll("\\s", ""));
    }

    private static byte[] read(Path path, int maximum) throws Exception {
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            require(bytes.length > 0 && bytes.length <= maximum, "INVALID_INPUT_SIZE");
            return bytes;
        }
    }

    private static Path repositoryRoot() throws Exception {
        String source = System.getProperty("jdk.launcher.sourcefile");
        require(source != null, "USE_JAVA_SOURCE_FILE_LAUNCHER");
        for (Path path = Path.of(source).toRealPath().getParent(); path != null; path = path.getParent()) {
            if (Files.exists(path.resolve(".git"))) return path;
        }
        throw new SigningFailure("REPOSITORY_ROOT_NOT_FOUND");
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new SigningFailure("SHA256_UNAVAILABLE"); }
    }

    private static void selfTest() throws Exception {
        repositoryRoot();
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privatePem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        String publicPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pem(privatePem.getBytes(StandardCharsets.US_ASCII), "PRIVATE KEY")));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(pem(publicPem.getBytes(StandardCharsets.US_ASCII), "PUBLIC KEY")));
        byte[] manifest = "{\r\n\"operation\":\"PUBLISH\",\"environment\":\"synthetic\"\r\n}".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.now();
        String expiry = now.plusSeconds(60).toString();
        byte[] payload = payload(manifest, "synthetic-approval", "reviewer-a", "operator-b", "synthetic", "PUBLISH", expiry, "3", now);
        Approval approval = sign(payload, privateKey);
        require(approval.signature().length == 64 && verify(approval, publicKey), "SELF_TEST_SIGNATURE");
        var envelope = java.util.regex.Pattern.compile("\\{\"payload\":\"([A-Za-z0-9_-]+)\",\"signature\":\"([A-Za-z0-9_-]+)\"}").matcher(approval.envelope());
        require(envelope.matches(), "SELF_TEST_CANONICAL_ENCODING");
        byte[] decodedPayload = Base64.getUrlDecoder().decode(envelope.group(1));
        byte[] decodedSignature = Base64.getUrlDecoder().decode(envelope.group(2));
        require(Arrays.equals(payload, decodedPayload) && Arrays.equals(approval.signature(), decodedSignature)
                && verify(new Approval(decodedPayload, decodedSignature, ""), publicKey), "SELF_TEST_ENVELOPE_ROUNDTRIP");
        byte[] changed = payload.clone(); changed[0] ^= 1;
        require(!verify(new Approval(changed, approval.signature(), ""), publicKey), "SELF_TEST_TAMPER");
        require(!verify(approval, KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()), "SELF_TEST_WRONG_KEY");
        require(!sha256(manifest).equals(sha256(Arrays.copyOf(manifest, manifest.length + 1))), "SELF_TEST_RAW_MANIFEST_HASH");
        int rejected = 0;
        for (String[] invalid : new String[][] {
                { "operator-b", "PUBLISH", expiry, "3" },
                { "reviewer-a", "REVOKE", expiry, "1" },
                { "reviewer-a", "PUBLISH", now.toString(), "3" },
                { "reviewer-a", "PUBLISH", expiry, "-1" },
                { "reviewer\"injected", "PUBLISH", expiry, "3" } }) {
            try { payload(manifest, "synthetic-approval", invalid[0], "operator-b", "synthetic", invalid[1], invalid[2], invalid[3], now); }
            catch (SigningFailure expected) { rejected++; }
        }
        payload(manifest, "a".repeat(80), "reviewer-a", "operator-b", "synthetic", "PUBLISH", expiry, "3", now);
        try { payload(manifest, "a".repeat(81), "reviewer-a", "operator-b", "synthetic", "PUBLISH", expiry, "3", now); }
        catch (SigningFailure expected) { rejected++; }
        require(rejected == 6, "SELF_TEST_REJECTIONS");
        System.out.println("SELF_TEST_PASS=signature,canonical_base64url,tamper,wrong_key,raw_manifest_hash,identity_expiry_budget,approval_id_80_81");
        System.out.println("PRIVATE_KEY_FILES_WRITTEN=0");
        System.out.println("EXTERNAL_CALLS=0");
    }

    private static void require(boolean allowed, String code) { if (!allowed) throw new SigningFailure(code); }
    private static final class SigningFailure extends RuntimeException {
        SigningFailure(String code) { super(code); }
    }
}
