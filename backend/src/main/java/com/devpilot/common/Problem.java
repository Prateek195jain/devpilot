package com.devpilot.common;
import org.springframework.http.HttpStatus;
public class Problem extends RuntimeException {
    public final HttpStatus status;
    public Problem(HttpStatus s,String m) {
        super(m);
        status=s;
    }
    public static Problem notFound() {
        return new Problem(HttpStatus.NOT_FOUND,"Resource not found");
    }
    public static Problem bad(String m) {
        return new Problem(HttpStatus.BAD_REQUEST,m);
    }
    public static Problem unauthorized() {
        return new Problem(HttpStatus.UNAUTHORIZED,"Authentication required or credentials invalid");
    }
    public static Problem upstream(String s) {
        return new Problem(HttpStatus.BAD_GATEWAY,s+" request failed; check integration settings");
    }
}
