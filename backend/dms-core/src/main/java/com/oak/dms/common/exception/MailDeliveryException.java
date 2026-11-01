package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * An email the operation depends on (the new-user invitation) could not be sent, so the
 * operation was undone. Maps to 502, code MAIL_DELIVERY_FAILED. The cause (bad SMTP
 * credentials, relay down) is in the server log, not in the message.
 */
public class MailDeliveryException extends ApiException {
    public MailDeliveryException() {
        this("The invitation email could not be sent, so the user was not created. "
                + "Check the mail settings (SMTP) and try again.");
    }

    public MailDeliveryException(String message) {
        super(HttpStatus.BAD_GATEWAY, "MAIL_DELIVERY_FAILED", message);
    }
}
