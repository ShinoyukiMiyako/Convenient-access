package com.shinoyuki.accesshub.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.api.ApiRouter;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.modpack.oss.OssPutClient;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP 服务器
 *
 * 使用 Jetty 提供 RESTful API 入口, 所有请求统一路由到 ApiRouter.
 *
 * v2 起完全摆脱 Bukkit Plugin 依赖, 通过 AccessHubConfig 接口获取运行配置,
 * 通过 SLF4J 直接输出日志.
 */
public class HttpServer {

    public static final long MAX_MULTIPART_FILE_SIZE = OssPutClient.MAX_FILE_SIZE;
    public static final long MAX_MULTIPART_REQUEST_SIZE = MAX_MULTIPART_FILE_SIZE + 1024L * 1024;

    private static final int MULTIPART_FILE_SIZE_THRESHOLD = 1024 * 1024;
    static final int MIN_HTTP_THREADS = 10;
    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final AccessHubConfig config;
    private final ApiRouter apiRouter;
    private final Path multipartTempDirectory;
    private MultipartConfigElement multipartConfig;
    private Server server;
    private ServerConnector connector;

    public HttpServer(AccessHubConfig config, ApiRouter apiRouter) {
        this(
                config,
                apiRouter,
                Path.of(System.getProperty("java.io.tmpdir"), "shinoyuki-accesshub", "multipart"));
    }

    public HttpServer(AccessHubConfig config, ApiRouter apiRouter, Path multipartTempDirectory) {
        this.config = Objects.requireNonNull(config, "config");
        this.apiRouter = apiRouter;
        this.multipartTempDirectory = Objects.requireNonNull(multipartTempDirectory, "multipartTempDirectory");
    }

    public void start() throws Exception {
        int port = config.getHttpPort();
        String host = config.getHttpHost();
        int maxThreads = Math.max(config.getMaxThreads(), MIN_HTTP_THREADS);
        long idleTimeout = Math.max(0L, config.getTimeout());
        multipartConfig = createMultipartConfig(multipartTempDirectory);

        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, 2);
        threadPool.setName("AccessHub-HTTP");

        server = new Server(threadPool);
        server.setHandler(new ApiHandler());

        connector = new ServerConnector(
                server,
                new HttpConnectionFactory(createHttpConfiguration(idleTimeout)));
        connector.setHost(host);
        connector.setPort(port);
        connector.setIdleTimeout(idleTimeout);

        server.addConnector(connector);
        server.start();

        // 预加载关服期才用到的 Jetty 内部类, 规避 SecureJar 关闭阶段惰性加载失败 (崩关服)
        preloadJettyShutdownClasses();

        logger.info("HTTP 服务器已启动: http://{}:{}",
                "0.0.0.0".equals(host) ? "localhost" : host, port);
    }

    public void stop() {
        if (server != null) {
            try {
                server.stop();
                logger.info("HTTP 服务器已停止");
            } catch (Throwable t) {
                // catch Throwable 而非 Exception: Forge SecureJar 下 server.stop() 可能抛
                // NoClassDefFoundError (relocate 的 Jetty 关闭期内部类惰性加载失败, 见 ManagedSelector$CloseConnections),
                // Error 不是 Exception, 不能让它逃逸崩掉关服流程。
                logger.warn("停止 HTTP 服务器时发生错误 (不影响关服)", t);
            }
        }
    }

    /**
     * 预加载 Jetty 仅在 stop() 才惰性首次加载的内部类.
     * Forge SecureJar 的 ModuleClassLoader 在关服阶段对"运行期从未加载过"的类做惰性加载可能抛
     * ClassNotFoundException (即便类就在 jar 内), 导致 stop() 抛 NoClassDefFoundError。趁启动期 (jar 健康)
     * 先载入缓存, 关服时直接命中。relocate 后的真实包名从 Server 类动态推导, 不硬编码 relocate 路径。
     */
    private void preloadJettyShutdownClasses() {
        ClassLoader cl = Server.class.getClassLoader();
        String serverPkg = Server.class.getPackageName();                  // ...libs.jetty.server
        String base = serverPkg.substring(0, serverPkg.lastIndexOf('.'));  // ...libs.jetty
        String[] names = {
                base + ".io.ManagedSelector$CloseConnections",
                base + ".io.ManagedSelector$StopSelector",
                base + ".io.ManagedSelector$DestroyEndPoint",
        };
        for (String name : names) {
            try {
                Class.forName(name, true, cl);
            } catch (Throwable t) {
                logger.debug("预加载 Jetty 关闭类失败 (不影响运行): {}", name);
            }
        }
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    int getLocalPort() {
        return connector == null ? -1 : connector.getLocalPort();
    }

    /**
     * API 请求处理器, 所有路径统一交给 ApiRouter.
     *
     * v1 曾有 ApiManager 备用分支, 但实际请求路径全部被 ApiRouter 覆盖, 备用分支为 dead code, v2 删除.
     */
    private class ApiHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                          HttpServletResponse response) throws IOException {

            baseRequest.setHandled(true);
            baseRequest.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, multipartConfig);

            try {
                setCorsHeaders(response);

                if ("OPTIONS".equals(request.getMethod())) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                if (apiRouter == null) {
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"success\":false,\"error\":\"API router not initialized\"}");
                    return;
                }

                apiRouter.handleRequest(request, response);

            } catch (Exception e) {
                logger.error("处理 HTTP 请求时发生错误", e);

                try {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"success\":false,\"error\":\"Internal Server Error\"}");
                    response.getWriter().flush();
                } catch (IOException ioException) {
                    logger.error("写入错误响应时发生异常", ioException);
                }
            }
        }

        private void setCorsHeaders(HttpServletResponse response) {
            if (config.isCorsEnabled()) {
                String allowedOrigins = String.join(",", config.getAllowedOrigins());
                if (allowedOrigins.contains("*")) {
                    response.setHeader("Access-Control-Allow-Origin", "*");
                } else {
                    response.setHeader("Access-Control-Allow-Origin", allowedOrigins);
                }

                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers",
                        "Content-Type, Authorization, X-API-Key, X-Requested-With");
                response.setHeader("Access-Control-Max-Age", "3600");
            }
        }
    }

    static MultipartConfigElement createMultipartConfig(Path tempDirectory) throws IOException {
        Files.createDirectories(tempDirectory);
        return new MultipartConfigElement(
                tempDirectory.toAbsolutePath().normalize().toString(),
                MAX_MULTIPART_FILE_SIZE,
                MAX_MULTIPART_REQUEST_SIZE,
                MULTIPART_FILE_SIZE_THRESHOLD);
    }

    static HttpConfiguration createHttpConfiguration(long idleTimeout) {
        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setIdleTimeout(Math.max(0L, idleTimeout));
        return configuration;
    }
}
