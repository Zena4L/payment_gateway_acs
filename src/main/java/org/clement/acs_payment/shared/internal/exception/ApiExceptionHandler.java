package org.clement.acs_payment.shared.internal.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.clement.acs_payment.shared.ApiException;
import org.clement.acs_payment.shared.CorrelationId;
import org.clement.acs_payment.shared.internal.dto.FieldErrorDetail;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String VALIDATION_FAILED = "validation-failed";

    private final Clock clock;

    @ExceptionHandler(ApiException.class)
    @Nullable
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return problem(ex, ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @Nullable
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        var errors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDetail(leafName(violation.getPropertyPath()), violation.getMessage()))
                .toList();
        return validationProblem(ex, errors, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    @Nullable
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @Nullable
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(ex, HttpStatus.FORBIDDEN, "forbidden", "You are not allowed to perform this action", request);
    }

    @ExceptionHandler(Exception.class)
    @Nullable
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected error", request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDetail(error.getField(), message(error)));
        var globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> new FieldErrorDetail(error.getObjectName(), message(error)));
        return validationProblem(ex, Stream.concat(fieldErrors, globalErrors).toList(), request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                                      HttpHeaders headers,
                                                                                      HttpStatusCode status,
                                                                                      WebRequest request) {
        var errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldErrorDetail(fieldName(result.getMethodParameter().getParameterName(), error), message(error))))
                .toList();
        return validationProblem(ex, errors, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                                       @Nullable Object body,
                                                                       HttpHeaders headers,
                                                                       HttpStatusCode statusCode,
                                                                       WebRequest request) {
        var problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(statusCode);
        enrich(problem, statusCode, request);
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    private @Nullable ResponseEntity<Object> validationProblem(Exception ex, List<FieldErrorDetail> errors, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("code", VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    private @Nullable ResponseEntity<Object> problem(Exception ex, HttpStatus status, String code, String detail, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }

    private void enrich(ProblemDetail problem, HttpStatusCode status, WebRequest request) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", defaultCode(status));
        }
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        problem.setProperty("timestamp", Instant.now(clock));
        CorrelationId.current().ifPresent(id -> problem.setProperty("correlationId", id));
    }

    private String defaultCode(HttpStatusCode status) {
        var resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "error" : resolved.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String fieldName(@Nullable String parameterName, MessageSourceResolvable error) {
        if (error instanceof FieldError fieldError) {
            return fieldError.getField();
        }
        return parameterName == null ? "request" : parameterName;
    }

    private String message(MessageSourceResolvable error) {
        var message = error.getDefaultMessage();
        return message == null ? "is invalid" : message;
    }

    private String leafName(Path path) {
        String leaf = null;
        for (var node : path) {
            leaf = node.getName();
        }
        return leaf == null ? "request" : leaf;
    }
}
