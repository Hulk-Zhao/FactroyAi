package com.hengce.aps.web.advice;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把框架异常与业务异常统一转换为 {@link Result}，并附带 traceId。
 *
 * <p>安全约束：仅返回面向用户的提示，不返回堆栈、类名或内部细节；5xxxx 业务异常日志记录完整堆栈，
 * 响应只返回错误码默认信息。</p>
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestExceptionHandler.class);

    private static final Map<ErrorCode, HttpStatus> STATUS_MAPPING = Map.ofEntries(
            Map.entry(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED),
            Map.entry(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PUBLISHED_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE),
            Map.entry(ErrorCode.UNPROCESSABLE_ENTITY, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.ADJUSTMENT_INFEASIBLE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCode.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(ErrorCode.NOT_IMPLEMENTED, HttpStatus.NOT_IMPLEMENTED),
            Map.entry(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE));

    /**
     * 业务异常。
     *
     * @param exception 业务异常
     * @return 统一响应
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        HttpStatus status = resolveStatus(errorCode);
        String message = errorCode.isServerError() ? errorCode.getMessage() : exception.getMessage();
        if (status.is5xxServerError()) {
            LOGGER.error("业务异常 code={}", errorCode.getCode(), exception);
        } else {
            LOGGER.warn("业务异常 code={} message={}", errorCode.getCode(), message);
        }
        return build(status, errorCode, message);
    }

    /**
     * 请求体参数校验失败。
     *
     * @param exception 校验异常
     * @return 统一响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    /**
     * 表单/查询参数绑定校验失败。
     *
     * @param exception 绑定异常
     * @return 统一响应
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    /**
     * 方法参数约束校验失败。
     *
     * @param exception 约束异常
     * @return 统一响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String detail = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    /**
     * Spring 6.1 方法参数校验失败。
     *
     * @param exception 校验异常
     * @return 统一响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return badRequest(null);
    }

    /**
     * 请求体不可解析。
     *
     * @param exception 解析异常
     * @return 统一响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadable(HttpMessageNotReadableException exception) {
        return badRequest(null);
    }

    /**
     * 缺少必需的查询参数。
     *
     * @param exception 缺参异常
     * @return 统一响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return badRequest(exception.getParameterName() + "不能为空");
    }

    /**
     * 缺少必需的请求头。
     *
     * @param exception 缺头异常
     * @return 统一响应
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
        return badRequest(exception.getHeaderName() + "不能为空");
    }

    /**
     * 参数类型不匹配。
     *
     * @param exception 类型异常
     * @return 统一响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return badRequest(exception.getName() + "格式不正确");
    }

    /**
     * 路由/静态资源不存在。
     *
     * @param exception 未找到异常
     * @return 统一响应
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(Exception exception) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage());
    }

    /**
     * 请求方法不支持。
     *
     * @param exception 方法异常
     * @return 统一响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                "不支持的请求方法: " + exception.getMethod());
    }

    /**
     * 媒体类型不支持。
     *
     * @param exception 媒体类型异常
     * @return 统一响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage());
    }

    /**
     * 带状态码的框架异常。
     *
     * @param exception 响应状态异常
     * @return 统一响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ErrorCode errorCode = mapStatus(status);
        String message = exception.getReason() == null ? errorCode.getMessage() : exception.getReason();
        return build(status, errorCode, message);
    }

    /**
     * 兜底异常，任何内部细节不外泄。
     *
     * @param exception 未预期异常
     * @return 统一响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception exception) {
        LOGGER.error("未处理异常", exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getMessage());
    }

    private ResponseEntity<Result<Void>> badRequest(String detail) {
        String message = detail == null || detail.isBlank() ? ErrorCode.BAD_REQUEST.getMessage() : detail;
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    private HttpStatus resolveStatus(ErrorCode errorCode) {
        return STATUS_MAPPING.getOrDefault(errorCode, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ErrorCode mapStatus(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> ErrorCode.BAD_REQUEST;
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case CONFLICT -> ErrorCode.STATE_CONFLICT;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case UNPROCESSABLE_ENTITY -> ErrorCode.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> ErrorCode.TOO_MANY_REQUESTS;
            case NOT_IMPLEMENTED -> ErrorCode.NOT_IMPLEMENTED;
            case SERVICE_UNAVAILABLE -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }

    private ResponseEntity<Result<Void>> build(HttpStatus status, ErrorCode errorCode, String message) {
        return ResponseEntity.status(status).body(Result.failure(errorCode, message));
    }
}
