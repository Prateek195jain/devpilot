package com.devpilot.common;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
@Component @Order(-200) public class RequestFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException {
        String id=UUID.randomUUID().toString();
        r.setAttribute("requestId",id);
        s.setHeader("X-Request-ID",id);
        s.setHeader("Cache-Control","no-store");
        MDC.put("requestId",id);
        try {
            if(r.getContentLengthLong()>1_048_576) {
                s.sendError(413);
                return;
            }
            chain.doFilter(r,s);
        }
        finally {
            MDC.remove("requestId");
        }
    }
}
