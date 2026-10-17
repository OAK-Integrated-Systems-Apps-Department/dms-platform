package com.oak.dms.document;

import com.oak.dms.document.dto.ReviewFieldResponse;
import com.oak.dms.document.dto.SaveReviewRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what a set of reviewer decisions means for a document: which
 * value each field ends up with, and whether the document still needs a
 * human (Phase 4d).
 * <p>
 * A static helper with no Spring wiring, for the same reason as
 * {@link ReviewQueueMapper}: it is the part worth testing exhaustively,
 * and it should be testable without a database.
 * <p>
 * The interesting decision is {@link #unsettled}. Marking a document
 * reviewed the moment anything is saved would empty the queue without
 * anyone having looked at the thing that put the document in it -- and
 * the queue would then be lying, which is worse than the queue being
 * long. So a document leaves the queue only when every field that
 * actually needed attention has been settled.
 */
public final class ReviewDecisionApplier {

    private ReviewDecisionApplier() {
    }

    /** The reviewer's final value per field, for fields they decided on. */
    public static Map<String, String> settledValues(
            List<ReviewFieldResponse> fields, List<SaveReviewRequest.Decision> decisions) {

        Map<String, ReviewFieldResponse> byKey = index(fields);
        Map<String, String> out = new LinkedHashMap<>();
        for (SaveReviewRequest.Decision decision : decisions) {
            ReviewFieldResponse field = byKey.get(decision.fieldKey());
            if (field == null) {
                // A decision about a field this document does not have is
                // dropped rather than written. The alternative is storing
                // an accuracy observation about nothing.
                continue;
            }
            out.put(decision.fieldKey(), valueFor(field, decision));
        }
        return out;
    }

    /**
     * What the reviewer settled on. For an acceptance this is OCR's own
     * value, taken from the server's copy rather than whatever the client
     * echoed back -- a client that echoed it wrongly would otherwise
     * record a correction as an acceptance, which is exactly the
     * measurement this table exists to get right.
     */
    static String valueFor(ReviewFieldResponse field, SaveReviewRequest.Decision decision) {
        if (DocumentOcrCorrection.ACTION_ACCEPTED.equals(decision.action())) {
            return asText(field.value());
        }
        return decision.value();
    }

    /**
     * A correction is only a correction if the value actually changed.
     * Saving "corrected" for a value identical to OCR's would inflate the
     * error rate with edits that were really confirmations.
     */
    public static String effectiveAction(ReviewFieldResponse field, SaveReviewRequest.Decision decision) {
        if (!DocumentOcrCorrection.ACTION_CORRECTED.equals(decision.action())) {
            return DocumentOcrCorrection.ACTION_ACCEPTED;
        }
        String before = asText(field.value());
        String after = decision.value() == null ? "" : decision.value();
        return before.equals(after)
                ? DocumentOcrCorrection.ACTION_ACCEPTED
                : DocumentOcrCorrection.ACTION_CORRECTED;
    }

    /**
     * Fields that still need attention after these decisions.
     * <p>
     * A field needs attention when it is mandatory and empty, or when OCR
     * itself was unsure of it ({@code low_confidence}) or found nothing
     * ({@code not_found}). A decision settles it -- except a decision that
     * leaves a mandatory field empty, which settles nothing: the document
     * is still missing a value it is required to have.
     * <p>
     * Neither status V45 added belongs in {@link #needsAttention}, for
     * opposite reasons. A {@code not_extractable} field was never looked
     * for, so there is nothing for a reviewer to check -- and when it is
     * mandatory the "mandatory and empty" rule above already catches it,
     * which is the right reason to ask for it rather than a false claim
     * that OCR missed it. A {@code defaulted} field has a real value the
     * tenant default supplied, and the default existing is the decision;
     * flagging every one of them would queue every document that does not
     * print its currency.
     */
    public static List<String> unsettled(
            List<ReviewFieldResponse> fields, List<SaveReviewRequest.Decision> decisions) {

        Map<String, String> settled = settledValues(fields, decisions);
        Set<String> decided = new LinkedHashSet<>(settled.keySet());

        List<String> out = new ArrayList<>();
        for (ReviewFieldResponse field : fields) {
            String finalValue = decided.contains(field.fieldKey())
                    ? settled.get(field.fieldKey())
                    : asText(field.value());
            boolean empty = finalValue == null || finalValue.isBlank();

            if (field.mandatory() && empty) {
                out.add(field.fieldKey());
                continue;
            }
            if (!decided.contains(field.fieldKey()) && needsAttention(field)) {
                out.add(field.fieldKey());
            }
        }
        return out;
    }

    private static boolean needsAttention(ReviewFieldResponse field) {
        return "low_confidence".equals(field.status()) || "not_found".equals(field.status());
    }

    private static Map<String, ReviewFieldResponse> index(List<ReviewFieldResponse> fields) {
        Map<String, ReviewFieldResponse> byKey = new LinkedHashMap<>();
        for (ReviewFieldResponse field : fields) {
            byKey.put(field.fieldKey(), field);
        }
        return byKey;
    }

    /** A multi_select field's value is a list; everything else is a string. */
    static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }
}
