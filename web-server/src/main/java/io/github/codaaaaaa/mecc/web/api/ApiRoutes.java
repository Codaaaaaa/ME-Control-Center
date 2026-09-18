package io.github.codaaaaaa.mecc.web.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable table of API endpoints keyed by path template and HTTP method.
 *
 * <p>Templates use {@code {name}} for a single path segment, e.g. {@code /api/v1/networks/{networkId}}.
 * When several templates match, the one with more literal segments wins, so
 * {@code /networks/candidates} takes precedence over {@code /networks/{networkId}}.
 *
 * <p>Routes are authenticated unless registered through a {@code public*} method.
 */
public final class ApiRoutes {
    public static final String API_PREFIX = "/api/";

    public enum Access {
        PUBLIC,
        AUTHENTICATED
    }

    public record Route(String method, String template, Access access, ApiEndpoint endpoint) {
    }

    /**
     * @param routes     routes of the matched template, keyed by method
     * @param parameters decoded path parameters
     */
    public record Match(Map<String, Route> routes, Map<String, String> parameters) {
    }

    private final List<Template> templates;

    private ApiRoutes(List<Template> templates) {
        this.templates = templates;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<Match> match(String path) {
        String[] segments = split(path);
        Template best = null;
        Map<String, String> bestParameters = null;
        for (Template template : templates) {
            Map<String, String> parameters = template.match(segments);
            if (parameters != null && (best == null || template.literalCount > best.literalCount)) {
                best = template;
                bestParameters = parameters;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Match(best.routes, bestParameters));
    }

    private static String[] split(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.split("/", -1);
    }

    private static final class Template {
        private final String text;
        private final String[] segments;
        private final int literalCount;
        private final Map<String, Route> routes;

        private Template(String text, Map<String, Route> routes) {
            this.text = text;
            this.segments = split(text);
            int literals = 0;
            for (String segment : segments) {
                if (!isParameter(segment)) {
                    literals++;
                }
            }
            this.literalCount = literals;
            this.routes = routes;
        }

        private Map<String, String> match(String[] path) {
            if (path.length != segments.length) {
                return null;
            }
            Map<String, String> parameters = new HashMap<>();
            for (int i = 0; i < segments.length; i++) {
                if (isParameter(segments[i])) {
                    if (path[i].isEmpty()) {
                        return null;
                    }
                    parameters.put(segments[i].substring(1, segments[i].length() - 1), path[i]);
                } else if (!segments[i].equals(path[i])) {
                    return null;
                }
            }
            return parameters;
        }

        private static boolean isParameter(String segment) {
            return segment.length() > 2 && segment.startsWith("{") && segment.endsWith("}");
        }
    }

    public static final class Builder {
        private final Map<String, Map<String, Route>> routes = new LinkedHashMap<>();

        public Builder get(String path, ApiEndpoint endpoint) {
            return add("GET", path, Access.AUTHENTICATED, endpoint);
        }

        public Builder post(String path, ApiEndpoint endpoint) {
            return add("POST", path, Access.AUTHENTICATED, endpoint);
        }

        public Builder patch(String path, ApiEndpoint endpoint) {
            return add("PATCH", path, Access.AUTHENTICATED, endpoint);
        }

        public Builder delete(String path, ApiEndpoint endpoint) {
            return add("DELETE", path, Access.AUTHENTICATED, endpoint);
        }

        public Builder publicGet(String path, ApiEndpoint endpoint) {
            return add("GET", path, Access.PUBLIC, endpoint);
        }

        public Builder publicPost(String path, ApiEndpoint endpoint) {
            return add("POST", path, Access.PUBLIC, endpoint);
        }

        public Builder add(String method, String path, Access access, ApiEndpoint endpoint) {
            if (!path.startsWith(API_PREFIX)) {
                throw new IllegalArgumentException("API paths must start with " + API_PREFIX + ": " + path);
            }
            Map<String, Route> methods = routes.computeIfAbsent(path, p -> new LinkedHashMap<>());
            if (methods.putIfAbsent(method, new Route(method, path, access, endpoint)) != null) {
                throw new IllegalArgumentException("Duplicate route " + method + " " + path);
            }
            return this;
        }

        public ApiRoutes build() {
            List<Template> templates = new ArrayList<>();
            routes.forEach((path, methods) ->
                    templates.add(new Template(path, Collections.unmodifiableMap(new LinkedHashMap<>(methods)))));
            return new ApiRoutes(List.copyOf(templates));
        }
    }
}
