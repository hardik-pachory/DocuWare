package com.docuware.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e, HttpServletRequest r) {
        return response(e.status.value(), e.code, e.getMessage(), r);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).distinct().sorted()
            .reduce((a, b) -> a + "; " + b).orElse("Invalid request");
        return response(400, "VALIDATION_ERROR", message, r);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(Exception e, HttpServletRequest r) {
        return response(413, "FILE_TOO_LARGE", "Maximum file size is 20 MB", r);
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedContentType(Exception e, HttpServletRequest r) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported request content type. For document uploads, send the metadata part as application/json.", r);
    }
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<ApiError> conflict(Exception e, HttpServletRequest r) {
        return response(409, "CONFLICT", "Resource already exists or was modified concurrently", r);
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(Exception e, HttpServletRequest r) {
        return response(403, "FORBIDDEN", "Access denied", r);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e, HttpServletRequest r) {
        if (e instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            return response(status, "INVALID_REQUEST", status == 404 ? "Endpoint not found" : "Invalid request", r);
        }
        if (e instanceof IllegalArgumentException || e instanceof jakarta.validation.ConstraintViolationException) {
            return response(400, "INVALID_REQUEST", "Invalid request parameters", r);
        }
        log.error("Request failed at {}", r.getRequestURI(), e);
        return response(500, "INTERNAL_ERROR", "An unexpected error occurred", r);
    }
    private ResponseEntity<ApiError> response(int status, String code, String message, HttpServletRequest r) {
        return ResponseEntity.status(status).body(ApiError.of(status, code, message, r.getRequestURI()));
    }
}
