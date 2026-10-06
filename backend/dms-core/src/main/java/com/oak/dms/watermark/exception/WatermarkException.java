package com.oak.dms.watermark.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Anything the watermark feature refuses to do, carried to the caller by
 * {@link com.oak.dms.common.exception.GlobalExceptionHandler} with a status
 * and a machine-readable code - same shape as {@code FolderException}.
 * <p>
 * The messages here are read by the person who asked for the watermark, so
 * they say what to do about it and never leak a path, a stack trace or a
 * stored key.
 */
public class WatermarkException extends ApiException {

    public WatermarkException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static WatermarkException badRequest(String code, String message) {
        return new WatermarkException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static WatermarkException forbidden(String code, String message) {
        return new WatermarkException(HttpStatus.FORBIDDEN, code, message);
    }

    public static WatermarkException conflict(String code, String message) {
        return new WatermarkException(HttpStatus.CONFLICT, code, message);
    }

    /** The file can't be watermarked as it is - encrypted, signed, corrupt. */
    public static WatermarkException unprocessable(String code, String message) {
        return new WatermarkException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    /** A format Phase 1 doesn't watermark. */
    public static WatermarkException unsupportedType(String message) {
        return new WatermarkException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "WATERMARK_UNSUPPORTED_TYPE", message);
    }

    public static WatermarkException tooLarge(String message) {
        return new WatermarkException(HttpStatus.CONTENT_TOO_LARGE, "WATERMARK_TOO_LARGE", message);
    }

    public static WatermarkException rateLimited(String code, String message) {
        return new WatermarkException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }

    /** The queue is full, or the font probe failed at startup - try again / call an administrator. */
    public static WatermarkException unavailable(String code, String message) {
        return new WatermarkException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
