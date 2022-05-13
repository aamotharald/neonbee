package io.neonbee.internal.verticle;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import io.neonbee.NeonBee;
import io.neonbee.config.EndpointConfig;
import io.neonbee.config.ServerConfig;
import io.neonbee.config.ServerConfig.SessionHandling;
import io.neonbee.endpoint.Endpoint;
import io.neonbee.endpoint.MountableEndpoint;
import io.neonbee.handler.ErrorHandler;
import io.neonbee.internal.handler.CacheControlHandler;
import io.neonbee.internal.handler.ChainAuthHandler;
import io.neonbee.internal.handler.CorrelationIdHandler;
import io.neonbee.internal.handler.DefaultErrorHandler;
import io.neonbee.internal.handler.InstanceInfoHandler;
import io.neonbee.internal.handler.LoggerHandler;
import io.neonbee.internal.handler.NotFoundHandler;
import io.neonbee.internal.helper.AsyncHelper;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import io.vertx.ext.web.sstore.ClusteredSessionStore;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;

/**
 * The {@link ServerVerticle} handles exposing all {@linkplain Endpoint endpoints} currently using the HTTP(S) protocol.
 *
 * This verticle handles the {@linkplain #config()} JSON object and parses it as a {@linkplain ServerConfig}.
 */
public class ServerVerticle extends AbstractVerticle {

    /**
     * Key where the ServerConfig is stored, if NeonBee is started with WEB profile.
     */
    public static final String SERVER_CONFIG_KEY = "__ServerVerticleConfig__";

    @VisibleForTesting
    static final String DEFAULT_ERROR_HANDLER_CLASS_NAME = DefaultErrorHandler.class.getName();

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private HttpServer httpServer;

    @Override
    public void start(Promise<Void> startPromise) {
        NeonBee.get(vertx).getLocalMap().put(SERVER_CONFIG_KEY, config());

        ServerConfig config = new ServerConfig(config());
        createRouter(config).compose(router -> {
            Optional<ChainAuthHandler> defaultAuthHandler =
                    Optional.ofNullable(config.getAuthChainConfig()).map(c -> ChainAuthHandler.create(vertx, c));
            return mountEndpoints(router, config.getEndpointConfigs(), defaultAuthHandler).onSuccess(v -> {
                // the NotFoundHandler fails the routing context finally.
                // To ensure that no handler will be added after it, it is added here.
                router.route().handler(new NotFoundHandler());
            }).compose(v -> createHttpServer(router, config));
        }).<Void>mapEmpty().onComplete(startPromise);
    }

    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        // Vert.x would close the HTTP server for us, however we would like to do some additional logging
        (httpServer != null ? httpServer.close().onComplete(result -> {
            LOGGER.info("HTTP server was stopped");
        }) : succeededFuture().<Void>mapEmpty()).onComplete(stopPromise);
    }

    private Future<Router> createRouter(ServerConfig config) {
        // the main router of the server verticle
        Router router = Router.router(vertx);

        // instead of creating new routes, vert.x recommends to add multiple handlers to one route instead. to prevent
        // sequence issues, block scope the variable to prevent using it after the endpoints have been mounted
        Route rootRoute = router.route();

        return createErrorHandler(config.getErrorHandlerClassName(), vertx).compose(errorHandler -> {
            rootRoute.failureHandler(errorHandler);
            rootRoute.handler(new LoggerHandler());
            rootRoute.handler(new InstanceInfoHandler());
            rootRoute.handler(new CorrelationIdHandler(config.getCorrelationStrategy()));
            rootRoute.handler(
                    TimeoutHandler.create(SECONDS.toMillis(config.getTimeout()), config.getTimeoutStatusCode()));
            createSessionStore(vertx, config.getSessionHandling()).map(SessionHandler::create)
                    .ifPresent(sessionHandler -> rootRoute
                            .handler(sessionHandler.setSessionCookieName(config.getSessionCookieName())));
            rootRoute.handler(new CacheControlHandler());
            rootRoute.handler(BodyHandler.create(false /* do not handle file uploads */));

            return succeededFuture(router);
        }).onFailure(e -> LOGGER.error("Router could not be created", e));
    }

    @VisibleForTesting
    // reason for PMD.EmptyCatchBlock empty cache block is "path-through" to fallback implementation and for
    // PMD.SignatureDeclareThrowsException it does not matter, if this private method does not expose any concrete
    // exceptions as the ServerVerticle start method anyways catches all exceptions in a generic try-catch block
    @SuppressWarnings({ "PMD.EmptyCatchBlock", "PMD.SignatureDeclareThrowsException" })
    static Future<ErrorHandler> createErrorHandler(String className, Vertx vertx) {
        try {
            Class<?> classObject = Class.forName(Optional.ofNullable(className).filter(Predicate.not(String::isBlank))
                    .orElse(DEFAULT_ERROR_HANDLER_CLASS_NAME));
            ErrorHandler eh = (ErrorHandler) classObject.getConstructor().newInstance();
            return eh.initialize(NeonBee.get(vertx))
                    .onFailure(t -> LOGGER.error("ErrorHandler could not be initialized", t));
        } catch (NoSuchMethodException e) {
            // do nothing here, if there is no such constructor, assume
            // the custom error handler class doesn't accept templates
            LOGGER.error("The custom ErrorHandler must offer a default constructor.", e);
            return failedFuture(e);
        } catch (Exception e) {
            return failedFuture(e);
        }
    }

    private Future<HttpServer> createHttpServer(Router router, ServerConfig config) {
        // Use the port passed via command line options, instead the configured one.
        Optional.ofNullable(NeonBee.get(vertx).getOptions().getServerPort()).ifPresent(config::setPort);

        return vertx.createHttpServer(config /* ServerConfig is a HttpServerOptions subclass */)
                .exceptionHandler(throwable -> {
                    LOGGER.error("HTTP Socket Exception", throwable);
                }).requestHandler(router).listen().onSuccess(httpServer -> {
                    this.httpServer = httpServer;
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("HTTP server started on port {}", httpServer.actualPort());
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("HTTP server configured with routes: {}",
                                router.getRoutes().stream().map(Route::toString).collect(Collectors.joining(",")));
                    }
                }).onFailure(cause -> LOGGER.error("HTTP server could not be started", cause));
    }

    /**
     * Mounts all endpoints as sub routers to the given router. How the authentication handlers are generated and
     * deployed is explained {@link MountableEndpoint#mount here}.
     *
     * @param router             the main router of the server verticle
     * @param endpointConfigs    a list of endpoint configurations to mount
     * @param defaultAuthHandler the fallback auth. handler in case no auth. handler is specified by the endpoint
     * @return a {@link Future}, succeeded if all endpoints are loaded and mounted successfully, failing otherwise.
     */
    @VisibleForTesting
    protected Future<Void> mountEndpoints(Router router, List<EndpointConfig> endpointConfigs,
            Optional<ChainAuthHandler> defaultAuthHandler) {
        if (endpointConfigs.isEmpty()) {
            LOGGER.warn("No endpoints configured");
            return succeededFuture();
        }

        // iterate the endpoint configurations, as order is important here!
        List<Future<MountableEndpoint>> mountableEndpoints =
                endpointConfigs.stream().map(ec -> MountableEndpoint.create(vertx, ec)).collect(Collectors.toList());
        return AsyncHelper.allComposite(mountableEndpoints).onSuccess(v -> {
            for (Future<MountableEndpoint> endpointFuture : mountableEndpoints) {
                endpointFuture.result().mount(vertx, router, defaultAuthHandler);
            }
            // all endpoints have been mounted in correct order successfully
        }).mapEmpty();
    }

    /**
     * Creates a {@linkplain SessionStore} based on the given {@linkplain ServerConfig} to use either local or clustered
     * session handling. If no session handling should be used, an empty optional is returned.
     *
     * @param vertx           the Vert.x instance to create the {@linkplain SessionStore} for
     * @param sessionHandling the session handling type
     * @return a optional session store, suitable for the given Vert.x instance and based on the provided config value
     *         (none/local/clustered). In case the session handling is set to clustered, but Vert.x does not run in
     *         clustered mode, fallback to the local session handling.
     */
    @VisibleForTesting
    static Optional<SessionStore> createSessionStore(Vertx vertx, SessionHandling sessionHandling) {
        switch (sessionHandling) {
        case LOCAL: // sessions are stored locally in a shared local map and only available on this instance
            return Optional.of(LocalSessionStore.create(vertx));
        case CLUSTERED: // sessions are stored in a distributed map which is accessible across the Vert.x cluster
            if (!vertx.isClustered()) { // Behaves like clustered in case that instance isn't clustered
                return Optional.of(LocalSessionStore.create(vertx));
            }
            return Optional.of(ClusteredSessionStore.create(vertx));
        default: /* nothing to do here, no session handling, so neither add a cookie, nor a session handler */
            return Optional.empty();
        }
    }
}
