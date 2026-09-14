package com.devpilot.auth;
import com.devpilot.common.*;
import com.devpilot.user.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
@RestController @RequestMapping("/api/v1") public class AuthController {
    private final AuthService auth;
    private final UserStore users;
    private final boolean secure;
    private final String origin;
    public AuthController(AuthService a,UserStore u,@Value("${app.secure-cookies}")boolean s,@Value("${app.frontend-origin}")String o) {
        auth=a;
        users=u;
        secure=s;
        origin=o;
    }
    public record Registration(@NotBlank @Size(max=100)String name,@Email @NotBlank @Size(max=255)String email,@NotBlank @Size(min=12,max=72)String password) {
    }
    public record Login(@Email @NotBlank @Size(max=255)String email,@NotBlank @Size(max=72)String password) {
    }
    public record Response(String accessToken,UserAccountView user) {
    }
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED) Response register(@Valid @RequestBody Registration r,HttpServletResponse out) {
        return respond(auth.register(r.name(),r.email(),r.password()),out);
    }
    @PostMapping("/auth/login") Response login(@Valid @RequestBody Login r,HttpServletResponse out) {
        return respond(auth.login(r.email(),r.password()),out);
    }
    @PostMapping("/auth/refresh") Response refresh(@CookieValue(name="devpilot_refresh",defaultValue="")String token,HttpServletRequest req,HttpServletResponse out) {
        checkOrigin(req);
        return respond(auth.rotate(token),out);
    }
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT) void logout(@CookieValue(name="devpilot_refresh",defaultValue="")String token,HttpServletRequest req,HttpServletResponse out) {
        checkOrigin(req);
        auth.logout(token);
        cookie(out,"",0);
    }
    @GetMapping("/users/me") UserAccountView me() {
        return UserAccountView.of(users.findById(CurrentUser.id()).orElseThrow(Problem::unauthorized));
    }
    private void checkOrigin(HttpServletRequest r) {
        if(!origin.equals(r.getHeader("Origin")))throw new Problem(HttpStatus.FORBIDDEN,"Origin is not permitted");
    }
    private Response respond(AuthService.Session s,HttpServletResponse out) {
        cookie(out,s.refreshToken(),604800);
        return new Response(s.accessToken(),s.user());
    }
    private void cookie(HttpServletResponse out,String value,long age) {
        out.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("devpilot_refresh",value).httpOnly(true).secure(secure).sameSite("Strict").path("/api/v1/auth").maxAge(age).build().toString());
    }
}
