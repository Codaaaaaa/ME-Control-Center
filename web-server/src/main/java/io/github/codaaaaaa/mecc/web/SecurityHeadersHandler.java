package io.github.codaaaaaa.mecc.web;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/** Adds baseline security headers to every response (spec section 36). */
public final class SecurityHeadersHandler extends Handler.Wrapper {
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    public SecurityHeadersHandler(Handler handler) {
        super(handler);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        HttpFields.Mutable headers = response.getHeaders();
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Frame-Options", "DENY");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        return super.handle(request, response, callback);
    }
}
