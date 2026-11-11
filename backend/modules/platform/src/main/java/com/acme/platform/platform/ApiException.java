package com.acme.platform.platform;

/**
 * 业务异常：由 Problem Details advice 统一转成 RFC 9457 响应。
 *
 * <p>{@code code} 是稳定的机器可读标识（前端与集成方按它分支），
 * {@code detail} 才给人看，不包含内部表名、SQL 或密钥。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(400, code, detail);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(403, code, detail);
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(404, code, detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(409, code, detail);
    }

    public static ApiException unprocessable(String code, String detail) {
        return new ApiException(422, code, detail);
    }
}
