package local.outreach;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class LocalBrowserFilter extends OncePerRequestFilter {
    private static final Pattern LOOPBACK_HOST = Pattern.compile("(?:127\\.0\\.0\\.1|localhost)(?::[0-9]{1,5})?", Pattern.CASE_INSENSITIVE);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader("Host");
        if (host == null) host = request.getServerName();
        if (!LOOPBACK_HOST.matcher(host).matches()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)) {
            String origin = request.getHeader("Origin");
            if ((origin != null && !origin.equalsIgnoreCase(request.getScheme() + "://" + host))
                    || "cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
