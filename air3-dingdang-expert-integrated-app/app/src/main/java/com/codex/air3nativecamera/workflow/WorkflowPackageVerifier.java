package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

public final class WorkflowPackageVerifier {
    private static final int MAX_EXECUTION_PACKAGE_BYTES = 256 * 1024;

    private final WorkflowPublicKeySource publicKeys;
    private final int appVersionCode;
    private final int supportedSchemaVersion;
    private final Set<String> capabilities;

    public WorkflowPackageVerifier(
            WorkflowPublicKeySource publicKeys,
            int appVersionCode,
            int supportedSchemaVersion,
            Set<String> capabilities
    ) {
        if (publicKeys == null || appVersionCode < 1 || supportedSchemaVersion < 1 || capabilities == null) {
            throw new IllegalArgumentException("workflow verifier configuration is invalid");
        }
        this.publicKeys = publicKeys;
        this.appVersionCode = appVersionCode;
        this.supportedSchemaVersion = supportedSchemaVersion;
        this.capabilities = new LinkedHashSet<>(capabilities);
    }

    public WorkflowPackageVerification verify(JSONObject envelope) {
        if (envelope == null) return reject(WorkflowPackageVerification.Error.INVALID_ENVELOPE, "missing_envelope");
        JSONObject executionPackage = envelope.optJSONObject("executionPackage");
        int schemaVersion = exactPositiveInt(envelope.opt("schemaVersion"));
        int minAppVersionCode = exactPositiveInt(envelope.opt("minAppVersionCode"));
        String contentSha256 = text(envelope, "contentSha256");
        String innerContentSha256 = executionPackage == null ? "" : text(executionPackage, "contentSha256");
        String packageSignature = text(envelope, "packageSignature");
        String signatureKeyId = text(envelope, "signatureKeyId");
        Set<String> requiredCapabilities = stringSet(envelope.optJSONArray("requiredCapabilities"));
        Set<String> innerCapabilities = executionPackage == null
                ? null
                : stringSet(executionPackage.optJSONArray("requiredCapabilities"));
        if (executionPackage == null
                || executionPackage.toString().getBytes(StandardCharsets.UTF_8).length > MAX_EXECUTION_PACKAGE_BYTES
                || schemaVersion < 1 || minAppVersionCode < 1
                || contentSha256.isEmpty() || !contentSha256.matches("^[0-9a-f]{64}$")
                || packageSignature.isEmpty() || signatureKeyId.isEmpty()
                || requiredCapabilities == null || innerCapabilities == null) {
            return reject(WorkflowPackageVerification.Error.INVALID_ENVELOPE, "envelope_contract_invalid");
        }
        if (schemaVersion > supportedSchemaVersion
                || exactPositiveInt(executionPackage.opt("schemaVersion")) != schemaVersion) {
            return reject(WorkflowPackageVerification.Error.INCOMPATIBLE_SCHEMA, "schema_version_incompatible");
        }
        if (minAppVersionCode > appVersionCode) {
            return reject(WorkflowPackageVerification.Error.INCOMPATIBLE_APP_VERSION, "app_version_incompatible");
        }
        if (!requiredCapabilities.equals(innerCapabilities)) {
            return reject(WorkflowPackageVerification.Error.INVALID_ENVELOPE, "capability_metadata_mismatch");
        }
        for (String capability : requiredCapabilities) {
            if (!capabilities.contains(capability)) {
                return reject(WorkflowPackageVerification.Error.MISSING_CAPABILITY, capability);
            }
        }
        if (!contentSha256.equals(innerContentSha256)) {
            return reject(WorkflowPackageVerification.Error.CONTENT_DIGEST_MISMATCH, "inner_digest_mismatch");
        }

        String actualDigest;
        try {
            actualDigest = sha256(WorkflowCanonicalJson.unsignedExecutionPackage(executionPackage));
        } catch (RuntimeException exception) {
            return reject(WorkflowPackageVerification.Error.PACKAGE_INVALID, "canonicalization_failed");
        }
        if (!constantTimeEquals(contentSha256, actualDigest)) {
            return reject(WorkflowPackageVerification.Error.CONTENT_DIGEST_MISMATCH, "calculated_digest_mismatch");
        }

        PublicKey publicKey;
        try {
            publicKey = publicKeys.find(signatureKeyId);
        } catch (RuntimeException exception) {
            publicKey = null;
        }
        if (publicKey == null) {
            return reject(WorkflowPackageVerification.Error.UNKNOWN_SIGNATURE_KEY, signatureKeyId);
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(contentSha256.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(packageSignature))) {
                return reject(WorkflowPackageVerification.Error.SIGNATURE_INVALID, "signature_verification_failed");
            }
        } catch (Exception exception) {
            return reject(WorkflowPackageVerification.Error.SIGNATURE_INVALID, "signature_invalid");
        }

        try {
            return WorkflowPackageVerification.accepted(
                    WorkflowPackage.parseVerified(envelope, executionPackage));
        } catch (RuntimeException exception) {
            return reject(WorkflowPackageVerification.Error.PACKAGE_INVALID, exception.getMessage());
        }
    }

    private static WorkflowPackageVerification reject(WorkflowPackageVerification.Error error, String detail) {
        return WorkflowPackageVerification.rejected(error, detail);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) output.append(String.format("%02x", item & 0xff));
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static int exactPositiveInt(Object value) {
        if (!(value instanceof Number)) return -1;
        Number number = (Number) value;
        double decimal = number.doubleValue();
        int integer = number.intValue();
        return Double.isFinite(decimal) && decimal == integer && integer > 0 ? integer : -1;
    }

    private static String text(JSONObject value, String key) {
        String result = value.optString(key, "");
        return result == null ? "" : result.trim();
    }

    private static Set<String> stringSet(JSONArray values) {
        if (values == null || values.length() < 1 || values.length() > 100) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index += 1) {
            String value = values.optString(index, "").trim();
            if (value.isEmpty() || value.length() > 160 || !result.add(value)) return null;
        }
        return result;
    }
}
