package io.github.codaaaaaa.mecc.web.staticfiles;

import io.github.codaaaaaa.mecc.web.staticfiles.StaticAssets.Asset;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * Serves the single-page web UI from memory.
 *
 * <ul>
 *   <li>Hashed Vite output under {@code assets/} is cached as immutable.</li>
 *   <li>Everything else (notably {@code index.html}) is revalidated with an ETag.</li>
 *   <li>Unknown extension-less paths fall back to {@code index.html} so client-side routes work.</li>
 * </ul>
 */
public final class StaticAssetHandler extends Handler.Abstract.NonBlocking {
    private static final String INDEX = "index.html";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String REVALIDATE = "no-cache";

    private final StaticAssets assets;

    public StaticAssetHandler(StaticAssets assets) {
        this.assets = assets;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String method = request.getMethod();
        boolean head = HttpMethod.HEAD.is(method);
        if (!head && !HttpMethod.GET.is(method)) {
            response.getHeaders().put(HttpHeader.ALLOW, "GET, HEAD");
            writeText(response, callback, HttpStatus.METHOD_NOT_ALLOWED_405, "Method Not Allowed");
            return true;
        }
        if (assets.isEmpty()) {
            writeText(response, callback, HttpStatus.SERVICE_UNAVAILABLE_503,
                    "The ME Control Center web UI is not bundled in this build. The API is available under /api/v1/.");
            return true;
        }

        String path = Request.getPathInContext(request);
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Optional<Asset> asset = relative.isEmpty() ? assets.get(INDEX) : assets.get(relative);
        if (asset.isEmpty() && isClientRoute(relative)) {
            asset = assets.get(INDEX);
        }
        if (asset.isEmpty()) {
            writeText(response, callback, HttpStatus.NOT_FOUND_404, "Not Found");
            return true;
        }

        Asset found = asset.get();
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, found.contentType());
        response.getHeaders().put(HttpHeader.ETAG, found.etag());
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, found.path().startsWith("assets/") ? IMMUTABLE : REVALIDATE);

        String ifNoneMatch = request.getHeaders().get(HttpHeader.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.contains(found.etag())) {
            response.setStatus(HttpStatus.NOT_MODIFIED_304);
            response.write(true, BufferUtil.EMPTY_BUFFER, callback);
            return true;
        }

        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, found.bytes().length);
        response.write(true, head ? BufferUtil.EMPTY_BUFFER : ByteBuffer.wrap(found.bytes()).asReadOnlyBuffer(), callback);
        return true;
    }

    /** Client-side routes have no file extension in their last segment (e.g. {@code /network/abc}). */
    private static boolean isClientRoute(String relative) {
        int slash = relative.lastIndexOf('/');
        return relative.indexOf('.', slash + 1) < 0;
    }

    private static void writeText(Response response, Callback callback, int status, String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }
}
