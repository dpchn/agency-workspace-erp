package app.erp.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Error envelope: code, message, correlationId, fieldErrors. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorBody(String code, String message, String correlationId, Map<String, String> fieldErrors) {}

    private ResponseEntity<ErrorBody> body(HttpStatus status, String code, String msg, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ErrorBody(code, msg, MDC.get("correlationId"), fields));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), e.getFieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorBody> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request: " + rootMessage(e), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action", null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorBody> optimistic(ObjectOptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "STALE_VERSION", "Record was changed by someone else; reload and retry", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException e) {
        String msg = rootMessage(e);
        String friendly;
        if (msg.contains("ex_assignment_overlap")) friendly = "Employee already has an overlapping shift assignment";
        else if (msg.contains("ex_deployment_permanent")) friendly = "Employee already has a permanent deployment in this date range; transfer instead";
        else if (msg.contains("duplicate key")) friendly = "A record with the same unique value already exists";
        else if (msg.contains("foreign key")) friendly = "Referenced record does not exist";
        else friendly = "Data integrity violation";
        return body(HttpStatus.CONFLICT, "CONFLICT", friendly, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Unexpected error", null);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? e.getClass().getSimpleName() : t.getMessage();
    }
}
