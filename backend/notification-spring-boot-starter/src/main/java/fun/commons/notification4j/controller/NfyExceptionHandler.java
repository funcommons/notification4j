package fun.commons.notification4j.controller;

import fun.commons.framework4j.web.ApiResponse;
import fun.commons.framework4j.accesstoken.exception.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 项目专属异常增强（评审第 4 步 P1：与 fwk4j-web GlobalExceptionHandler 重叠的 handler 全部裁撤——
 * DuplicateKey/IllegalArgument/NoResourceFound/兜底 Exception 均交框架统一
 * （10106/10102/404+10400/500+10001），避免双 advice 无 @Order 的未定义行为；
 * 参数校验/缺参/缺头同样由框架处理（ApiCode.PARAM_ERROR=10100，HTTP 200 信封）。
 *
 * 仅保留 AuthException（fwk 无对应 handler，且其 advice 先于本项目被咨询时会落 500 兜底）：
 * - @Order 最高优先：保证本 advice 先于 fwk-web 被咨询（否则 AuthException 落入框架 Exception 兜底→500）
 * - HTTP 200 + 信封 code=AuthException.code（型别不匹配=10207 等，契约 §2.2「认证失败统一 HTTP 200 信封」）
 * - SecurityException → HTTP 403（tracelog 控制台等非 token 体系鉴权）
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NfyExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(NfyExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleAuth(AuthException ex) {
        log.warn("[Auth] 认证拦截: code={} msg={}", ex.getCode(), ex.getMessage());
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleSecurity(SecurityException ex) {
        log.warn("[Exception] 权限不足: {}", ex.getMessage());
        return ApiResponse.fail(403, "权限不足");
    }
}
