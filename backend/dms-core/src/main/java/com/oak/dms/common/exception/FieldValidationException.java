package com.oak.dms.common.exception;

import com.oak.dms.common.dto.ErrorResponse;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A request-field validation failure detected in the service layer rather
 * than by Bean Validation on the request body - e.g. OCR label hints
 * ({@code labelAliases}), whose rules (length after whitespace
 * normalization, at most 20 after case-insensitive de-duplication) can
 * only be checked once the list has been normalized, which a plain
 * {@code @Size}/{@code @Pattern} annotation can't express.
 * <p>
 * {@link GlobalExceptionHandler} renders this with exactly the same 400
 * body as {@code MethodArgumentNotValidException} ({@code "Validation
 * failed"} plus {@code fieldErrors}), so API consumers see one validation
 * error shape whichever layer caught the problem - the frontend's form
 * error mapping keys off {@code fieldErrors[].field} either way.
 */
public class FieldValidationException extends ApiException {

    private final List<ErrorResponse.FieldValidationError> fieldErrors;

    public FieldValidationException(List<ErrorResponse.FieldValidationError> fieldErrors) {
        super(HttpStatus.BAD_REQUEST, fieldErrors.stream()
                .map(e -> e.field() + ": " + e.message())
                .collect(Collectors.joining("; ")));
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<ErrorResponse.FieldValidationError> getFieldErrors() {
        return fieldErrors;
    }
}
