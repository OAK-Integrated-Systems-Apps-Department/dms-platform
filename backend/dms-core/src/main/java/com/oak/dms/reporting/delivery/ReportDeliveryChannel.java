package com.oak.dms.reporting.delivery;

import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.settings.EffectiveReportSettings;

import java.util.UUID;

/**
 * One way of getting a finished report to a person, one class per channel, so another (SFTP, Teams) can be added without touching the
 * rest: implement this, name the channel, and allow its name in the settings.
 * <p>
 * A channel is told WHO and WHAT and answers with an {@link Outcome}; it never throws for a delivery that simply did not work - the
 * service records the answer and decides about retries. The two answers that matter: {@link Outcome#retry} for something that may
 * work shortly (the mail server was down), {@link Outcome#failed} for something that never will (no address, attachment too large).
 */
public interface ReportDeliveryChannel {

    /** The channel's name, as it appears in a schedule's {@code delivery_channels}. */
    String channel();

    Outcome deliver(Delivery delivery);

    /**
     * @param target        the person: an internal user, or an external address
     * @param requestedMode the schedule's choice, link or attachment
     * @param hasFile       false for a report that found no data: there is nothing to link to or attach
     * @param alsoByEmail   the same person also gets this run through the email channel (so the in-app channel must not cause a second mail)
     */
    record Delivery(ReportRun run, String title, EffectiveReportSettings settings, Target target, String requestedMode, boolean hasFile,
                    boolean alsoByEmail) {
        public Delivery(ReportRun run, String title, EffectiveReportSettings settings, Target target, String requestedMode, boolean hasFile) {
            this(run, title, settings, target, requestedMode, hasFile, false);
        }
    }

    /** Exactly one of {@code userId} (internal) and {@code address} (external). */
    record Target(UUID userId, String address) {
        public boolean external() {
            return address != null;
        }
    }

    /**
     * @param mode what was actually used: link or attachment - can differ from what was asked (the file was too big to attach)
     * @param note why, when it differs (LINK_FALLBACK_TOO_LARGE ...)
     * @param code the reason, when it did not work
     */
    record Outcome(Status status, String mode, String note, String code) {

        public enum Status { SENT, RETRY, FAILED }

        public static Outcome sent(String mode, String note) {
            return new Outcome(Status.SENT, mode, note, null);
        }

        public static Outcome retry(String code) {
            return new Outcome(Status.RETRY, null, null, code);
        }

        public static Outcome failed(String code) {
            return new Outcome(Status.FAILED, null, null, code);
        }
    }
}
