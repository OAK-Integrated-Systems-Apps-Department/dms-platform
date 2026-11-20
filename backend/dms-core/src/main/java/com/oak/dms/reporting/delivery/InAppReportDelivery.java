package com.oak.dms.reporting.delivery;

import org.springframework.stereotype.Component;

/**
 * An inbox notification with a link to the signed-in download page (never the file, never a signed URL). The person's own
 * notification preferences for "Report ready" decide whether the platform also emails it.
 */
@Component
public class InAppReportDelivery implements ReportDeliveryChannel {

    private final ReportNotifier notifier;

    public InAppReportDelivery(ReportNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public String channel() {
        return "in_app";
    }

    @Override
    public Outcome deliver(Delivery d) {
        if (d.target().external()) {
            return Outcome.failed("IN_APP_NEEDS_AN_ACCOUNT"); // an address has no inbox
        }
        boolean written = d.hasFile()
                ? notifier.ready(d.run().getTenantId(), d.target().userId(), d.run().getRunId(), d.title(), d.alsoByEmail())
                : notifier.readyEmpty(d.run().getTenantId(), d.target().userId(), d.run().getRunId(), d.title(), d.alsoByEmail());
        return written ? Outcome.sent("link", null) : Outcome.retry("NOTIFICATION_FAILED");
    }
}
