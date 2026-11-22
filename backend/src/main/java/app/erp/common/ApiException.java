package app.erp.common;

import java.util.Map;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    /** Non-disclosing: same answer for "does not exist" and "belongs to someone else". */
    public static ApiException notFound(String entity) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", entity + " not found");
    }
    public static ApiException forbidden(String msg) { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", msg); }
    public static ApiException conflict(String msg) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", msg); }
    public static ApiException invalidState(String msg) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE", msg); }
    public static ApiException badRequest(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg); }
    public static ApiException field(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg, Map.of(field, msg));
    }
}
