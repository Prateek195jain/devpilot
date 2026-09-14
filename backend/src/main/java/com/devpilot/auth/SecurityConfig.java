package com.devpilot.auth;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.web.cors.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devpilot.common.Errors.ApiError;
import java.time.Instant;
@Configuration public class SecurityConfig {
    @Bean PasswordEncoder passwords() {
        return new BCryptPasswordEncoder(12);
    }
    @Bean SecretKeySpec secret(@Value("${app.jwt-secret}")String s) {
        if(s.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        return new SecretKeySpec(s.getBytes(StandardCharsets.UTF_8),"HmacSHA256");
    }
    @Bean JwtEncoder encoder(SecretKeySpec k) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(k));
    }
    @Bean JwtDecoder decoder(SecretKeySpec k) {
        NimbusJwtDecoder d=NimbusJwtDecoder.withSecretKey(k).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer("devpilot"));
        return d;
    }
    @Bean CorsConfigurationSource cors(@Value("${app.frontend-origin}")String origin) {
        CorsConfiguration c=new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin));
        c.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization","Content-Type","X-Request-ID"));
        c.setExposedHeaders(List.of("X-Request-ID"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource s=new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**",c);
        return s;
    }
    @Bean SecurityFilterChain chain(HttpSecurity http,ObjectMapper mapper)throws Exception {
        JwtAuthenticationConverter converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt->List.of(new SimpleGrantedAuthority("ROLE_"+jwt.getClaimAsString("role"))));
        http.csrf(c->c.disable()).cors(c-> {
        }
        ).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/**","/api/v1/webhooks/github","/actuator/health").permitAll().requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated()).oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((r,s,e)-> {
            s.setStatus(401);s.setContentType("application/json");mapper.writeValue(s.getOutputStream(),new ApiError(Instant.now(),401,"UNAUTHORIZED","Authentication required",r.getRequestURI(),List.of()));
        }
        )).exceptionHandling(e->e.authenticationEntryPoint((r,s,x)-> {
            s.setStatus(401);s.setContentType("application/json");mapper.writeValue(s.getOutputStream(),new ApiError(Instant.now(),401,"UNAUTHORIZED","Authentication required",r.getRequestURI(),List.of()));
        }
        ).accessDeniedHandler((r,s,x)-> {
            s.setStatus(403);s.setContentType("application/json");mapper.writeValue(s.getOutputStream(),new ApiError(Instant.now(),403,"FORBIDDEN","Access denied",r.getRequestURI(),List.of()));
        }
        ));
        return http.build();
    }
}
