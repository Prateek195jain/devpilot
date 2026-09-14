package com.devpilot.auth;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @Order(-190) public class AuthRateFilter extends OncePerRequestFilter {
    private record Bucket(long minute,int count) {
    }
    private final Map<String,Bucket> buckets=new HashMap<>();
    private synchronized boolean allow(String key) {
        long minute=System.currentTimeMillis()/60000;
        buckets.entrySet().removeIf(e->e.getValue().minute<minute);
        Bucket b=buckets.get(key);
        if(b==null) {
            if(buckets.size()>=10000)return false;
            buckets.put(key,new Bucket(minute,1));
            return true;
        }
        if(b.count>=60)return false;
        buckets.put(key,new Bucket(minute,b.count+1));
        return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws IOException,ServletException {
        if(r.getMethod().equals("POST")&&(r.getRequestURI().equals("/api/v1/auth/login")||r.getRequestURI().equals("/api/v1/auth/register"))&&!allow(r.getRemoteAddr())) {
            s.setStatus(429);
            s.setHeader("Retry-After","60");
            s.setContentType("application/json");
            s.getWriter().write("{\"status\":429,\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Too many authentication requests; retry in one minute\"}");
            return;
        }
        chain.doFilter(r,s);
    }
}
