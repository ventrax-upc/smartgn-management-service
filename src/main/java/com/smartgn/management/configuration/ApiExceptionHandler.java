package com.smartgn.management.configuration;

import com.smartgn.management.shared.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException ex, HttpServletRequest req) {
        int status = switch (ex.code()) {
            case "FORBIDDEN" -> 403;
            case "NOT_FOUND" -> 404;
            case "CONFLICT", "INVALID_STATE", "STALE_VERSION", "INTEGRITY_CONFLICT", "BUFFER_NOT_DRAINED" -> 409;
            case "DEPENDENCY_UNAVAILABLE" -> 503;
            default -> 400;
        };
        return error(status, ex.code(), ex.getMessage(), req);
    }
    @ExceptionHandler(com.smartgn.management.integration.DependencyFailure.class)
    ResponseEntity<ProblemDetail> dependency(Exception ex, HttpServletRequest req) {
        return error(503, "DEPENDENCY_UNAVAILABLE", "A required service is temporarily unavailable", req);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        var result = error(400, "VALIDATION_ERROR", "Request fields are invalid", req);
        result.getBody().setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldError(e.getField(), e.getDefaultMessage())).toList());
        return result;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class, IllegalArgumentException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ex, HttpServletRequest req) {
        return error(400, "INVALID_REQUEST", "Request format or parameters are invalid", req);
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> missing(Exception ex, HttpServletRequest req) {
        return error(404, "NOT_FOUND", "The requested resource does not exist", req);
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(Exception ex, HttpServletRequest req) {
        return error(405, "METHOD_NOT_ALLOWED", "The HTTP method is not supported for this resource", req);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(Exception ex, HttpServletRequest req) {
        return error(409, "INTEGRITY_CONFLICT", "The operation conflicts with existing resources", req);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest req) {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).error("Request failed: {} correlationId={}", ex.getClass().getSimpleName(), req.getAttribute("correlationId"));
        return error(500, "INTERNAL_ERROR", "An unexpected error occurred", req);
    }
    private ResponseEntity<ProblemDetail> error(int status, String code, String detail, HttpServletRequest req) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        body.setType(URI.create("urn:smartgn:error:" + code.toLowerCase()));
        body.setTitle(code);
        body.setProperty("code", code);
        body.setProperty("correlationId", req.getAttribute("correlationId"));
        return ResponseEntity.status(status).body(body);
    }
    private record FieldError(String field, String message) {}
}
