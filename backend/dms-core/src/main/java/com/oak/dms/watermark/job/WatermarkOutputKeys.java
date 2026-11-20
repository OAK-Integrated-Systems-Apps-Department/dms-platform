package com.oak.dms.watermark.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Where a watermark job's output lives in staging storage, and the one check that a stored key really is inside that
 * place before anything is deleted (DOC-20).
 * <p>
 * A new output is always {@code <tenantId>/tmp/watermarks/<jobId>/output}: made of two ids and a fixed word, so nothing a
 * user typed can reach it. Until DOC-20 the last segment was the document's original filename - stored exactly as the
 * client sent it - so a name such as {@code ../../x.pdf} made the key leave the job's directory, and the sweeper and the
 * worker's cleanup then deleted whatever that key pointed at. Jobs written before the fix keep their old keys in
 * {@code output_object_key} until they expire (24 hours by default), so cleanup must tell a legitimate old key from a
 * hostile one without trusting the storage adapter to normalise it: {@link #isInsideJobNamespace} accepts a key only
 * when it starts with this job's own prefix and nothing after the prefix is empty, a {@code .} or {@code ..} segment
 * (split on both {@code /} and {@code \}, so the answer does not depend on the host's path rules) or a control character.
 * The same check guards every use of a stored key: the worker's and the sweeper's deletes, and the download's read.
 */
public final class WatermarkOutputKeys {

    private static final String LAST_SEGMENT = "output";

    private WatermarkOutputKeys() {
    }

    static String namespacePrefix(UUID tenantId, UUID jobId) {
        return tenantId + "/tmp/watermarks/" + jobId + "/";
    }

    /** The key a new output is stored under: no user-supplied text. */
    static String keyFor(UUID tenantId, UUID jobId) {
        return namespacePrefix(tenantId, jobId) + LAST_SEGMENT;
    }

    /** True only for a key that is this job's own output - new or legacy - and cannot point anywhere else. */
    public static boolean isInsideJobNamespace(UUID tenantId, UUID jobId, String key) {
        if (key == null || tenantId == null || jobId == null) {
            return false;
        }
        String prefix = namespacePrefix(tenantId, jobId);
        if (!key.startsWith(prefix)) {
            return false;                       // also rejects an absolute path, another tenant's or another job's key
        }
        String rest = key.substring(prefix.length());
        if (rest.isEmpty()) {
            return false;
        }
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;                   // no control characters, NUL included
            }
        }
        for (String segment : rest.split("[/\\\\]", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * What to log about a key that was refused: its length and a short hash, never the key itself (it can hold a user's
     * file name and another tenant's ids, and a hostile one can hold anything). The key stays on the job row for
     * whoever investigates.
     */
    public static String describe(String key) {
        if (key == null) {
            return "null";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "length=" + key.length() + " sha256=" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "length=" + key.length();
        }
    }
}
