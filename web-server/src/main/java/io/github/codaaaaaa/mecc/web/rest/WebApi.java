package io.github.codaaaaaa.mecc.web.rest;

import io.github.codaaaaaa.mecc.core.assets.IconService;
import io.github.codaaaaaa.mecc.core.auth.AuthService;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.DeviceView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingService;
import io.github.codaaaaaa.mecc.core.crafting.OrderFilter;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.networks.NetworkService;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.CandidateView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.MemberView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.NetworkSummaryView;
import io.github.codaaaaaa.mecc.core.patterns.PatternDefinition;
import io.github.codaaaaaa.mecc.core.patterns.PatternService;
import io.github.codaaaaaa.mecc.core.patterns.PatternStack;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceQuery;
import io.github.codaaaaaa.mecc.core.resources.ResourceService;
import io.github.codaaaaaa.mecc.core.status.StatusService;
import io.github.codaaaaaa.mecc.web.api.ApiRequest;
import io.github.codaaaaaa.mecc.web.api.ApiResponse;
import io.github.codaaaaaa.mecc.web.api.ApiRoutes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The versioned public HTTP API ({@code /api/v1}, spec section 30). Maps HTTP onto core-domain service
 * ports only; it never sees platform or Minecraft types.
 */
public final class WebApi {
    private static final String V1 = "/api/v1";
    private static final int DEFAULT_PAGE_SIZE = 120;
    private static final int DEFAULT_ORDER_PAGE = 30;
    /** Icon URLs include the asset version, so a cached icon is only stale after an asset change. */
    private static final String ICON_CACHE_CONTROL = "public, max-age=604800, immutable";

    private WebApi() {
    }

    public record Services(StatusService status, AuthService auth, NetworkService networks, ResourceService resources,
                           IconService icons, CraftingService crafting, PatternService patterns) {
        public Services {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(auth, "auth");
            Objects.requireNonNull(networks, "networks");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(icons, "icons");
            Objects.requireNonNull(crafting, "crafting");
            Objects.requireNonNull(patterns, "patterns");
        }
    }

    // Request bodies.
    record PairRequest(String key, String deviceName) {
    }

    record NameRequest(String name) {
    }

    record ClaimRequest(String candidateKey, String displayName) {
    }

    record MemberRequest(String player, String role) {
    }

    record RoleRequest(String role) {
    }

    record PlanRequest(String resourceId, Long amount, String locale) {
    }

    record SubmitRequest(String planId, String cpuId, String locale) {
    }

    record CpuCancelRequest(String jobId) {
    }

    /** A pattern slot: {@code resource} is a resource ID text such as {@code item:minecraft:iron_ingot}. */
    record StackBody(String resource, Long amount) {
    }

    /** {@link PatternDefinition} as JSON; empty slots are {@code null}. */
    record DefinitionBody(String type, List<StackBody> inputs, List<StackBody> outputs, Boolean substitutes,
                          Boolean fluidSubstitutes, String recipeId) {
    }

    record DraftRequest(String name, String description, String networkId, DefinitionBody definition) {
    }

    record ValidateRequest(DefinitionBody definition, String locale) {
    }

    record EncodeRequest(DefinitionBody definition, String draftId, String providerId, String locale) {
    }

    // Response wrappers: named collections keep responses extensible.
    record DeviceList(List<DeviceView> devices) {
    }

    record RevokedCount(int revoked) {
    }

    record NetworkList(List<NetworkSummaryView> networks) {
    }

    record CandidateList(List<CandidateView> candidates) {
    }

    record MemberList(List<MemberView> members) {
    }

    public static ApiRoutes routes(Services services) {
        AuthService auth = services.auth();
        NetworkService networks = services.networks();
        CraftingService crafting = services.crafting();
        PatternService patterns = services.patterns();

        return ApiRoutes.builder()
                .publicGet(V1 + "/status", request -> services.status().currentStatus())

                // Authentication and devices (spec section 27).
                .publicPost(V1 + "/auth/pair", request -> {
                    PairRequest body = request.body(PairRequest.class);
                    return auth.pair(body.key(), body.deviceName(), request.client())
                            .thenCompose(outcome -> auth.me(outcome.session())
                                    .thenApply(me -> ApiResponse.created(me).withDeviceToken(outcome.token())));
                })
                .post(V1 + "/auth/logout", request -> auth.logout(request.session())
                        .thenApply(ignored -> ApiResponse.noContent().clearingDeviceToken()))
                .get(V1 + "/me", request -> auth.me(request.session()))
                .get(V1 + "/devices", request -> auth.listDevices(request.session()).thenApply(DeviceList::new))
                .patch(V1 + "/devices/{deviceId}", request -> auth.renameDevice(request.session(),
                        request.pathParameter("deviceId"), request.body(NameRequest.class).name()))
                .delete(V1 + "/devices/{deviceId}", request -> {
                    String deviceId = request.pathParameter("deviceId");
                    boolean current = deviceId.equals(request.session().device().id());
                    return auth.revokeDevice(request.session(), deviceId).thenApply(ignored -> current
                            ? ApiResponse.noContent().clearingDeviceToken()
                            : ApiResponse.noContent());
                })
                .post(V1 + "/devices/revoke-others", request -> auth.revokeOtherDevices(request.session())
                        .thenApply(RevokedCount::new))

                // Networks (spec sections 28-29).
                .get(V1 + "/networks", request -> networks.list(request.session()).thenApply(NetworkList::new))
                .post(V1 + "/networks", request -> {
                    ClaimRequest body = request.body(ClaimRequest.class);
                    return networks.claim(request.session(), body.candidateKey(), body.displayName())
                            .thenApply(ApiResponse::created);
                })
                .get(V1 + "/networks/candidates", request -> networks.candidates(request.session())
                        .thenApply(CandidateList::new))
                .get(V1 + "/networks/{networkId}", request -> networks.get(request.session(), networkId(request)))
                .patch(V1 + "/networks/{networkId}", request -> networks.rename(request.session(), networkId(request),
                        request.body(NameRequest.class).name()))
                .delete(V1 + "/networks/{networkId}", request -> networks.delete(request.session(), networkId(request))
                        .thenApply(ignored -> ApiResponse.noContent()))
                .get(V1 + "/networks/{networkId}/members", request -> networks.members(request.session(), networkId(request))
                        .thenApply(MemberList::new))
                .post(V1 + "/networks/{networkId}/members", request -> {
                    MemberRequest body = request.body(MemberRequest.class);
                    return networks.putMember(request.session(), networkId(request), body.player(), role(body.role()));
                })
                .patch(V1 + "/networks/{networkId}/members/{playerUuid}", request -> {
                    UUID player = request.uuidParameter("playerUuid", ErrorCode.PLAYER_NOT_FOUND);
                    return networks.putMember(request.session(), networkId(request), player.toString(),
                            role(request.body(RoleRequest.class).role()));
                })
                .delete(V1 + "/networks/{networkId}/members/{playerUuid}", request -> networks.removeMember(
                                request.session(), networkId(request), request.uuidParameter("playerUuid", ErrorCode.PLAYER_NOT_FOUND))
                        .thenApply(ignored -> ApiResponse.noContent()))

                // Resource terminal (spec section 7).
                .get(V1 + "/networks/{networkId}/resources", request ->
                        services.resources().page(request.session(), networkId(request), query(request)))
                .get(V1 + "/networks/{networkId}/resources/detail", request -> services.resources().detail(
                        request.session(), networkId(request), request.queryParameter("id", ""),
                        request.queryParameter("snapshot", null), request.queryParameter("locale", "en_us")))

                // Crafting and CPUs (spec sections 9-12).
                .get(V1 + "/networks/{networkId}/crafting/cpus", request -> crafting.cpus(request.session(),
                        networkId(request), locale(request)))
                .post(V1 + "/networks/{networkId}/crafting/cpus/{cpuId}/cancel", request -> crafting.cancelCpuJob(
                                request.session(), networkId(request), request.pathParameter("cpuId"),
                                request.body(CpuCancelRequest.class).jobId())
                        .thenApply(ignored -> ApiResponse.noContent()))
                .post(V1 + "/networks/{networkId}/crafting/plan", request -> {
                    PlanRequest body = request.body(PlanRequest.class);
                    if (body.amount() == null) {
                        throw MeccException.validation("amount", "amount is required");
                    }
                    return crafting.calculate(request.session(), networkId(request), body.resourceId(), body.amount(),
                            locale(body.locale()));
                })
                .get(V1 + "/networks/{networkId}/crafting/plans/{planId}", request -> crafting.plan(request.session(),
                        networkId(request), request.pathParameter("planId"), locale(request)))
                .post(V1 + "/networks/{networkId}/crafting/orders", request -> {
                    SubmitRequest body = request.body(SubmitRequest.class);
                    String cpuId = body.cpuId() == null || body.cpuId().isBlank() ? null : body.cpuId();
                    return crafting.submit(request.session(), networkId(request), body.planId(), cpuId, locale(body.locale()))
                            .thenApply(ApiResponse::created);
                })
                .get(V1 + "/networks/{networkId}/crafting/orders", request -> crafting.orders(request.session(),
                        networkId(request), request.enumParameter("status", OrderFilter.class, OrderFilter.ACTIVE),
                        request.intParameter("limit", DEFAULT_ORDER_PAGE, 1, 100), request.queryParameter("before", null),
                        locale(request)))
                .get(V1 + "/networks/{networkId}/crafting/orders/{orderId}", request -> crafting.order(request.session(),
                        networkId(request), orderId(request), locale(request)))
                .post(V1 + "/networks/{networkId}/crafting/orders/{orderId}/cancel", request -> crafting.cancel(
                        request.session(), networkId(request), orderId(request), locale(request)))

                // Pattern Studio (spec sections 13-17). Drafts are personal; network routes check the role.
                .get(V1 + "/patterns/drafts", request -> patterns.drafts(request.session(), locale(request)))
                .post(V1 + "/patterns/drafts", request -> patterns.createDraft(request.session(),
                                draftInput(request.body(DraftRequest.class)), locale(request))
                        .thenApply(ApiResponse::created))
                .get(V1 + "/patterns/drafts/{draftId}", request -> patterns.draft(request.session(), draftId(request),
                        locale(request)))
                .patch(V1 + "/patterns/drafts/{draftId}", request -> patterns.updateDraft(request.session(),
                        draftId(request), draftInput(request.body(DraftRequest.class)), locale(request)))
                .delete(V1 + "/patterns/drafts/{draftId}", request -> patterns.deleteDraft(request.session(),
                        draftId(request)).thenApply(ignored -> ApiResponse.noContent()))
                .get(V1 + "/patterns/catalog", request -> patterns.catalog(request.session(), query(request)))
                .get(V1 + "/patterns/recipes", request -> patterns.recipes(request.session(),
                        request.enumParameter("type", PatternType.class, null), request.queryParameter("output", null),
                        request.queryParameter("input", null), locale(request)))
                .get(V1 + "/networks/{networkId}/providers", request -> patterns.providers(request.session(),
                        networkId(request), locale(request)))
                .patch(V1 + "/networks/{networkId}/providers/{providerId}", request -> patterns.renameProvider(
                                request.session(), networkId(request), request.pathParameter("providerId"),
                                request.body(NameRequest.class).name())
                        .thenApply(ignored -> ApiResponse.noContent()))
                .post(V1 + "/networks/{networkId}/patterns/validate", request -> {
                    ValidateRequest body = request.body(ValidateRequest.class);
                    return patterns.validate(request.session(), networkId(request), definition(body.definition()),
                            locale(body.locale()));
                })
                .post(V1 + "/networks/{networkId}/patterns/encode", request -> {
                    EncodeRequest body = request.body(EncodeRequest.class);
                    return patterns.encode(request.session(), networkId(request), definition(body.definition()),
                            optionalUuid(body.draftId(), "draftId"), null, locale(body.locale()))
                            .thenApply(ApiResponse::created);
                })
                .post(V1 + "/networks/{networkId}/patterns/deploy", request -> {
                    EncodeRequest body = request.body(EncodeRequest.class);
                    if (body.providerId() == null || body.providerId().isBlank()) {
                        throw MeccException.validation("providerId", "providerId is required");
                    }
                    return patterns.encode(request.session(), networkId(request), definition(body.definition()),
                            optionalUuid(body.draftId(), "draftId"), body.providerId().strip(), locale(body.locale()))
                            .thenApply(ApiResponse::created);
                })
                .get(V1 + "/networks/{networkId}/patterns/deployments", request -> patterns.deployments(
                        request.session(), networkId(request), locale(request)))

                // Icons are static game assets; they may be cached for a long time because the URL carries
                // the asset version (spec section 48).
                .get(V1 + "/icons", request -> services.icons().icon(request.queryParameter("key", ""))
                        .thenApply(icon -> icon
                                .map(image -> ApiResponse.binary(image.png(), "image/png", image.etag(), ICON_CACHE_CONTROL))
                                .orElseThrow(() -> new MeccException(ErrorCode.ICON_NOT_FOUND,
                                        "No icon is available for this resource"))))
                .build();
    }

    private static UUID draftId(ApiRequest request) {
        return request.uuidParameter("draftId", ErrorCode.DRAFT_NOT_FOUND);
    }

    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw MeccException.validation(field, field + " is not a valid ID");
        }
    }

    private static PatternService.DraftInput draftInput(DraftRequest body) {
        return new PatternService.DraftInput(body.name(), body.description(), optionalUuid(body.networkId(), "networkId"),
                definition(body.definition()));
    }

    /** Parses a pattern definition, naming the offending field in {@code VALIDATION_FAILED} errors. */
    static PatternDefinition definition(DefinitionBody body) {
        if (body == null) {
            throw MeccException.validation("definition", "definition is required");
        }
        PatternType type;
        try {
            type = PatternType.valueOf(body.type() == null ? "" : body.type().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw MeccException.validation("definition.type",
                    "type must be one of CRAFTING, PROCESSING, SMITHING, STONECUTTING");
        }
        return new PatternDefinition(type, stacks(body.inputs(), "definition.inputs"),
                stacks(body.outputs(), "definition.outputs"), Boolean.TRUE.equals(body.substitutes()),
                Boolean.TRUE.equals(body.fluidSubstitutes()), body.recipeId());
    }

    private static List<PatternStack> stacks(List<StackBody> bodies, String field) {
        if (bodies == null) {
            return List.of();
        }
        if (bodies.size() > 256) {
            throw MeccException.validation(field, "Too many slots");
        }
        List<PatternStack> stacks = new ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i++) {
            StackBody body = bodies.get(i);
            String slot = field + "[" + i + "]";
            if (body == null || body.resource() == null || body.resource().isBlank()) {
                stacks.add(null);
                continue;
            }
            ResourceId resource = ResourceId.parse(body.resource().strip())
                    .orElseThrow(() -> MeccException.validation(slot + ".resource", "Not a valid resource ID"));
            long amount = body.amount() == null ? 1 : body.amount();
            if (amount < 1) {
                throw MeccException.validation(slot + ".amount", "The amount must be at least 1");
            }
            stacks.add(new PatternStack(resource, amount));
        }
        return stacks;
    }

    private static UUID orderId(ApiRequest request) {
        return request.uuidParameter("orderId", ErrorCode.ORDER_NOT_FOUND);
    }

    private static String locale(ApiRequest request) {
        return locale(request.queryParameter("locale", "en_us"));
    }

    private static String locale(String requested) {
        return requested == null || requested.isBlank() ? "en_us" : requested;
    }

    private static UUID networkId(ApiRequest request) {
        return request.uuidParameter("networkId", ErrorCode.NETWORK_NOT_FOUND);
    }

    private static ResourceQuery query(ApiRequest request) {
        return new ResourceQuery(
                request.queryParameter("q", ""),
                request.enumParameter("sort", ResourceQuery.Sort.class, ResourceQuery.Sort.NAME),
                request.boolParameter("desc", false),
                request.enumParameter("type", ResourceQuery.TypeFilter.class, ResourceQuery.TypeFilter.ALL),
                request.intParameter("offset", 0, 0, Integer.MAX_VALUE),
                request.intParameter("limit", DEFAULT_PAGE_SIZE, 1, ResourceQuery.MAX_LIMIT),
                request.queryParameter("snapshot", null),
                request.queryParameter("locale", "en_us"));
    }

    private static NetworkRole role(String value) {
        if (value == null) {
            throw MeccException.validation("role", "role is required");
        }
        try {
            return NetworkRole.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw MeccException.validation("role", "role must be one of VIEWER, OPERATOR, MANAGER, OWNER");
        }
    }
}
