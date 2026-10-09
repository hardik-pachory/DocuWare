package com.docuware.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document could not be found");
    }
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_DOCUMENT_STATE", message);
    }
}
