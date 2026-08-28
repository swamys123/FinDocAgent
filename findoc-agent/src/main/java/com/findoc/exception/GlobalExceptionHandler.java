package com.findoc.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(java.util.NoSuchElementException.class)
    ProblemDetail notFound(Exception ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(Exception ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    ProblemDetail unauthorized(Exception ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage()); }
}
