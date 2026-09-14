package com.devpilot.common;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.*;
@RestControllerAdvice public class Errors {
    private static final Logger log=LoggerFactory.getLogger(Errors.class);
    public record ApiError(Instant timestamp,int status,String error,String message,String path,List<String> fieldErrors) {
    }
    @ExceptionHandler(Problem.class) ResponseEntity<ApiError> domain(Problem e,HttpServletRequest r) {
        return response(e.status,e.getMessage(),r,List.of());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e,HttpServletRequest r) {
        return response(HttpStatus.BAD_REQUEST,"Validation failed",r,e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).toList());
    }
    @ExceptionHandler( {
        HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class
    }
    ) ResponseEntity<ApiError> invalidInput(Exception e,HttpServletRequest r) {
        return response(HttpStatus.BAD_REQUEST,"Invalid request",r,List.of());
    }
    @ExceptionHandler( {
        DataIntegrityViolationException.class,org.springframework.orm.ObjectOptimisticLockingFailureException.class
    }
    ) ResponseEntity<ApiError> conflict(Exception e,HttpServletRequest r) {
        return response(HttpStatus.CONFLICT,"Resource conflicts with current state",r,List.of());
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) ResponseEntity<ApiError> forbidden(Exception e,HttpServletRequest r) {
        return response(HttpStatus.FORBIDDEN,"Access denied",r,List.of());
    }
    @ExceptionHandler(Exception.class) ResponseEntity<ApiError> unexpected(Exception e,HttpServletRequest r) {
        log.error("request_failed type={}",e.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR,"Unexpected server error",r,List.of());
    }
    private ResponseEntity<ApiError> response(HttpStatus s,String m,HttpServletRequest r,List<String> f) {
        return ResponseEntity.status(s).body(new ApiError(Instant.now(),s.value(),s.name(),m,r.getRequestURI(),f));
    }
}
