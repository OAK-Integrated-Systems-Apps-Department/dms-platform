package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A bulk import file refused at intake by the virus scan (infected or unscannable). Maps to 422. */
public class ImportFileBlockedException extends ApiException {

    public ImportFileBlockedException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "IMPORT_FILE_BLOCKED", message);
    }
}
