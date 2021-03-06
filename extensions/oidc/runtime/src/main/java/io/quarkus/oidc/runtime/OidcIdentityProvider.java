package io.quarkus.oidc.runtime;

import static io.quarkus.oidc.runtime.OidcUtils.validateAndCreateIdentity;

import java.security.Principal;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.OidcTenantConfig.Roles.Source;
import io.quarkus.oidc.OidcTokenCredential;
import io.quarkus.oidc.common.runtime.OidcConstants;
import io.quarkus.runtime.BlockingOperationControl;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TokenAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

@ApplicationScoped
public class OidcIdentityProvider implements IdentityProvider<TokenAuthenticationRequest> {

    static final String CODE_FLOW_ACCESS_TOKEN = "access_token";
    static final String REFRESH_TOKEN_GRANT_RESPONSE = "refresh_token_grant_response";
    static final String NEW_AUTHENTICATION = "new_authentication";

    private static final Uni<TokenVerificationResult> NULL_CODE_ACCESS_TOKEN_UNI = Uni.createFrom().nullItem();
    private static final Uni<JsonObject> NULL_USER_INFO_UNI = Uni.createFrom().nullItem();
    private static final String CODE_ACCESS_TOKEN_RESULT = "code_flow_access_token_result";

    @Inject
    DefaultTenantConfigResolver tenantResolver;

    @Override
    public Class<TokenAuthenticationRequest> getRequestType() {
        return TokenAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(TokenAuthenticationRequest request,
            AuthenticationRequestContext context) {
        OidcTokenCredential credential = (OidcTokenCredential) request.getToken();
        RoutingContext vertxContext = credential.getRoutingContext();
        vertxContext.put(AuthenticationRequestContext.class.getName(), context);

        Uni<TenantConfigContext> tenantConfigContext = tenantResolver.resolveContext(vertxContext);

        return tenantConfigContext.onItem()
                .transformToUni(new Function<TenantConfigContext, Uni<? extends SecurityIdentity>>() {
                    @Override
                    public Uni<SecurityIdentity> apply(TenantConfigContext tenantConfigContext) {
                        return Uni.createFrom().deferred(new Supplier<Uni<? extends SecurityIdentity>>() {
                            @Override
                            public Uni<SecurityIdentity> get() {
                                return authenticate(request, vertxContext, tenantConfigContext);
                            }
                        });
                    }
                });
    }

    private Uni<SecurityIdentity> authenticate(TokenAuthenticationRequest request,
            RoutingContext vertxContext,
            TenantConfigContext resolvedContext) {
        if (resolvedContext.oidcConfig.publicKey.isPresent()) {
            return validateTokenWithoutOidcServer(request, resolvedContext);
        } else {
            return validateAllTokensWithOidcServer(vertxContext, request, resolvedContext);
        }
    }

    private Uni<SecurityIdentity> validateAllTokensWithOidcServer(RoutingContext vertxContext,
            TokenAuthenticationRequest request,
            TenantConfigContext resolvedContext) {

        Uni<TokenVerificationResult> codeAccessTokenUni = verifyCodeFlowAccessTokenUni(vertxContext, request, resolvedContext);

        return codeAccessTokenUni.onItem().transformToUni(
                new Function<TokenVerificationResult, Uni<? extends SecurityIdentity>>() {
                    @Override
                    public Uni<SecurityIdentity> apply(TokenVerificationResult codeAccessToken) {
                        return validateTokenWithOidcServer(vertxContext, request, resolvedContext, codeAccessToken);
                    }
                });
    }

    private Uni<SecurityIdentity> validateTokenWithOidcServer(RoutingContext vertxContext, TokenAuthenticationRequest request,
            TenantConfigContext resolvedContext, TokenVerificationResult codeAccessTokenResult) {

        if (codeAccessTokenResult != null) {
            vertxContext.put(CODE_ACCESS_TOKEN_RESULT, codeAccessTokenResult);
        }

        Uni<JsonObject> userInfo = getUserInfoUni(vertxContext, request, resolvedContext);

        return userInfo.onItem().transformToUni(
                new Function<JsonObject, Uni<? extends SecurityIdentity>>() {
                    @Override
                    public Uni<SecurityIdentity> apply(JsonObject userInfo) {
                        return createSecurityIdentityWithOidcServer(vertxContext, request, resolvedContext, userInfo);
                    }
                });
    }

    private Uni<SecurityIdentity> createSecurityIdentityWithOidcServer(RoutingContext vertxContext,
            TokenAuthenticationRequest request, TenantConfigContext resolvedContext, final JsonObject userInfo) {
        Uni<TokenVerificationResult> codeFlowTokenUni = verifyTokenUni(resolvedContext,
                request.getToken().getToken());

        return codeFlowTokenUni.onItem()
                .transformToUni(new Function<TokenVerificationResult, Uni<? extends SecurityIdentity>>() {
                    @Override
                    public Uni<SecurityIdentity> apply(TokenVerificationResult result) {
                        // Token has been verified, as a JWT or an opaque token, possibly involving
                        // an introspection request.
                        final TokenCredential tokenCred = request.getToken();

                        JsonObject tokenJson = result.localVerificationResult;
                        if (tokenJson == null) {
                            // JSON token representation may be null not only if it is an opaque access token
                            // but also if it is JWT and no JWK with a matching kid is available, asynchronous
                            // JWK refresh has not finished yet, but the fallback introspection request has succeeded.
                            tokenJson = OidcUtils.decodeJwtContent(tokenCred.getToken());
                        }
                        if (tokenJson != null) {
                            OidcUtils.validatePrimaryJwtTokenType(resolvedContext.oidcConfig.token, tokenJson);
                            JsonObject rolesJson = getRolesJson(vertxContext, resolvedContext, tokenCred, tokenJson,
                                    userInfo);
                            try {
                                SecurityIdentity securityIdentity = validateAndCreateIdentity(vertxContext, tokenCred,
                                        resolvedContext.oidcConfig,
                                        tokenJson, rolesJson, userInfo);
                                if (tokenAutoRefreshPrepared(tokenJson, vertxContext, resolvedContext.oidcConfig)) {
                                    return Uni.createFrom().failure(new TokenAutoRefreshException(securityIdentity));
                                } else {
                                    return Uni.createFrom().item(securityIdentity);
                                }
                            } catch (Throwable ex) {
                                return Uni.createFrom().failure(ex);
                            }
                        } else if (tokenCred instanceof IdTokenCredential
                                || tokenCred instanceof AccessTokenCredential
                                        && !((AccessTokenCredential) tokenCred).isOpaque()) {
                            return Uni.createFrom()
                                    .failure(new AuthenticationFailedException("JWT token can not be converted to JSON"));
                        } else {
                            // Opaque Bearer Access Token
                            QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
                            builder.addCredential(tokenCred);
                            OidcUtils.setSecurityIdentityUserInfo(builder, userInfo);

                            // getRolesJson: make sure the introspection is picked up correctly
                            // OidcRuntimeClient.verifyCodeToken - set the introspection there - which may be ambiguous
                            if (result.introspectionResult.containsKey("username")) {
                                final String userName = result.introspectionResult.getString("username");
                                builder.setPrincipal(new Principal() {
                                    @Override
                                    public String getName() {
                                        return userName;
                                    }
                                });
                            }
                            if (result.introspectionResult.containsKey(OidcConstants.TOKEN_SCOPE)) {
                                for (String role : result.introspectionResult.getString(OidcConstants.TOKEN_SCOPE).split(" ")) {
                                    builder.addRole(role.trim());
                                }
                            }
                            if (userInfo != null) {
                                OidcUtils.setSecurityIdentityRoles(builder, resolvedContext.oidcConfig, userInfo);
                            }
                            OidcUtils.setBlockinApiAttribute(builder, vertxContext);
                            OidcUtils.setTenantIdAttribute(builder, resolvedContext.oidcConfig);
                            return Uni.createFrom().item(builder.build());
                        }
                    }
                });
    }

    @Deprecated
    private static boolean tokenAutoRefreshPrepared(JsonObject tokenJson, RoutingContext vertxContext,
            OidcTenantConfig oidcConfig) {
        if (tokenJson != null
                && oidcConfig.token.refreshExpired
                && (oidcConfig.token.getRefreshTokenTimeSkew().isPresent() || oidcConfig.token.autoRefreshInterval.isPresent())
                && vertxContext.get(REFRESH_TOKEN_GRANT_RESPONSE) != Boolean.TRUE
                && vertxContext.get(NEW_AUTHENTICATION) != Boolean.TRUE) {
            final long refreshTokenTimeSkew = (oidcConfig.token.getRefreshTokenTimeSkew()
                    .orElse(oidcConfig.token.autoRefreshInterval.get())).getSeconds();
            final long expiry = tokenJson.getLong("exp");
            final long now = System.currentTimeMillis() / 1000;
            return now + refreshTokenTimeSkew > expiry;
        }
        return false;
    }

    private static JsonObject getRolesJson(RoutingContext vertxContext, TenantConfigContext resolvedContext,
            TokenCredential tokenCred,
            JsonObject tokenJson, JsonObject userInfo) {
        JsonObject rolesJson = tokenJson;
        if (resolvedContext.oidcConfig.roles.source.isPresent()) {
            if (resolvedContext.oidcConfig.roles.source.get() == Source.userinfo) {
                rolesJson = userInfo;
            } else if (tokenCred instanceof IdTokenCredential
                    && resolvedContext.oidcConfig.roles.source.get() == Source.accesstoken) {
                rolesJson = ((TokenVerificationResult) vertxContext.get(CODE_ACCESS_TOKEN_RESULT)).localVerificationResult;
                if (rolesJson == null) {
                    // JSON token representation may be null not only if it is an opaque access token
                    // but also if it is JWT and no JWK with a matching kid is available, asynchronous
                    // JWK refresh has not finished yet, but the fallback introspection request has succeeded.
                    rolesJson = OidcUtils.decodeJwtContent((String) vertxContext.get(CODE_FLOW_ACCESS_TOKEN));
                }
            }
        }
        return rolesJson;
    }

    private Uni<TokenVerificationResult> verifyCodeFlowAccessTokenUni(RoutingContext vertxContext,
            TokenAuthenticationRequest request,
            TenantConfigContext resolvedContext) {
        if (request.getToken() instanceof IdTokenCredential
                && (resolvedContext.oidcConfig.authentication.verifyAccessToken
                        || resolvedContext.oidcConfig.roles.source.orElse(null) == Source.accesstoken)) {
            final String codeAccessToken = (String) vertxContext.get(CODE_FLOW_ACCESS_TOKEN);
            return verifyTokenUni(resolvedContext, codeAccessToken);
        } else {
            return NULL_CODE_ACCESS_TOKEN_UNI;
        }
    }

    private Uni<TokenVerificationResult> verifyTokenUni(
            TenantConfigContext resolvedContext,
            String token) {
        if (OidcUtils.isOpaqueToken(token)) {
            // remote introspection is required, a blocking call
            return Uni.createFrom().emitter(
                    new Consumer<UniEmitter<? super TokenVerificationResult>>() {
                        @Override
                        public void accept(UniEmitter<? super TokenVerificationResult> uniEmitter) {
                            if (BlockingOperationControl.isBlockingAllowed()) {
                                resolvedContext.client.verifyToken(uniEmitter, resolvedContext, token);
                            } else {
                                tenantResolver.getBlockingExecutor().execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        resolvedContext.client.verifyToken(uniEmitter, resolvedContext,
                                                token);
                                    }
                                });
                            }
                        }
                    });
        } else {
            return Uni.createFrom().emitter(new Consumer<UniEmitter<? super TokenVerificationResult>>() {
                @Override
                public void accept(UniEmitter<? super TokenVerificationResult> uniEmitter) {
                    resolvedContext.client.verifyToken(uniEmitter, resolvedContext, token);
                }
            });
        }
    }

    private static Uni<SecurityIdentity> validateTokenWithoutOidcServer(TokenAuthenticationRequest request,
            TenantConfigContext resolvedContext) {
        JsonObject tokenJson = null;
        try {
            tokenJson = resolvedContext.client.validateTokenWithoutOidcServer(request.getToken().getToken());
        } catch (Throwable ex) {
            return Uni.createFrom().failure(new AuthenticationFailedException(ex));
        }
        try {
            return Uni.createFrom()
                    .item(validateAndCreateIdentity(null, request.getToken(), resolvedContext.oidcConfig, tokenJson,
                            tokenJson,
                            null));
        } catch (Throwable ex) {
            return Uni.createFrom().failure(new AuthenticationFailedException(ex));
        }
    }

    private Uni<JsonObject> getUserInfoUni(RoutingContext vertxContext, TokenAuthenticationRequest request,
            TenantConfigContext resolvedContext) {
        if (resolvedContext.oidcConfig.authentication.isUserInfoRequired()) {
            return Uni.createFrom().emitter(
                    new Consumer<UniEmitter<? super JsonObject>>() {
                        @Override
                        public void accept(UniEmitter<? super JsonObject> uniEmitter) {
                            if (BlockingOperationControl.isBlockingAllowed()) {
                                resolvedContext.client.createUserInfoToken(uniEmitter, vertxContext, request);
                            } else {
                                tenantResolver.getBlockingExecutor().execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        resolvedContext.client.createUserInfoToken(uniEmitter, vertxContext, request);
                                    }
                                });
                            }
                        }
                    });
        } else {
            return NULL_USER_INFO_UNI;
        }
    }

}
