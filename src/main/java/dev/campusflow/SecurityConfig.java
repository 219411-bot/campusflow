package dev.campusflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService users(JdbcTemplate db) {
        return username -> db.query("SELECT username, password_hash, role FROM app_user WHERE username = ?",
            (rs, row) -> User.withUsername(rs.getString("username"))
                .password(rs.getString("password_hash")).roles(rs.getString("role")).build(), username)
            .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.svg", "/error").permitAll()
                .requestMatchers("/api/auth/csrf", "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/*", "/api/stats").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> json(res, 200, "登录成功"))
                .failureHandler((req, res, ex) -> json(res, 401, "用户名或密码错误")))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> json(res, 200, "已退出登录")))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> json(res, 401, "请先登录"))
                .accessDeniedHandler((req, res, ex) -> json(res, 403, "无权限操作或安全令牌失效，请刷新页面")))
            .build();
    }

    private static void json(jakarta.servlet.http.HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
