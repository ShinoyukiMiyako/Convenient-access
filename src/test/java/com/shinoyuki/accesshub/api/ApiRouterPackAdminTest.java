package com.shinoyuki.accesshub.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import com.shinoyuki.accesshub.auth.AdminAuthService;
import com.shinoyuki.accesshub.auth.AdminUser;
import com.shinoyuki.accesshub.config.AccessHubConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class ApiRouterPackAdminTest {

    @Test
    void packAdministrationRoutesRemainAuthenticated() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.isAuthEnabled()).thenReturn(true);
        ApiRouter router = router(controller, config);
        HttpServletRequest request = request("GET", "/api/v1/pack/versions");
        StringWriter body = new StringWriter();
        HttpServletResponse response = response(body);

        router.handleRequest(request, response);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(controller, never()).handleListVersions(request, response);
        assertTrue(body.toString().contains("Unauthorized"));
    }

    @Test
    void backendApiKeyCannotAuthorizePackAdministration() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.isAuthEnabled()).thenReturn(true);
        when(config.getApiToken()).thenReturn("backend-token");
        ApiRouter router = router(controller, config);
        HttpServletRequest request = request("GET", "/api/v1/pack/versions");
        when(request.getHeader("X-API-Key")).thenReturn("backend-token");
        StringWriter body = new StringWriter();
        HttpServletResponse response = response(body);

        router.handleRequest(request, response);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(controller, never()).handleListVersions(request, response);
        assertTrue(body.toString().contains("Unauthorized"));
    }

    @Test
    void administratorJwtAuthorizesPackAdministration() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.isAuthEnabled()).thenReturn(true);
        AdminAuthService authService = mock(AdminAuthService.class);
        AdminAuthController authController = mock(AdminAuthController.class);
        AdminUser admin = new AdminUser();
        when(authController.getAdminAuthService()).thenReturn(authService);
        when(authService.validateToken("admin-jwt")).thenReturn(admin);
        ApiRouter router = router(controller, null, authController, config);
        HttpServletRequest request = request("GET", "/api/v1/pack/versions");
        when(request.getHeader("Authorization")).thenReturn("Bearer admin-jwt");
        HttpServletResponse response = response(new StringWriter());

        router.handleRequest(request, response);

        verify(request).setAttribute("currentUser", admin);
        verify(controller).handleListVersions(request, response);
    }

    @Test
    void getRoutesAreMatchedExactly() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        ApiRouter router = router(controller, openConfig());

        invoke(router, "GET", "/api/v1/pack/versions");
        invoke(router, "GET", "/api/v1/pack/versions/7/entries");
        invoke(router, "GET", "/api/v1/pack/versions/7/diff");

        verify(controller).handleListVersions(anyRequest(), anyResponse());
        verify(controller).handleListEntries(org.mockito.ArgumentMatchers.eq("7"), anyRequest(), anyResponse());
        verify(controller).handleDiff(org.mockito.ArgumentMatchers.eq("7"), anyRequest(), anyResponse());
    }

    @Test
    void postRoutesAreMatchedExactly() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        ApiRouter router = router(controller, openConfig());

        invoke(router, "POST", "/api/v1/pack/versions");
        invoke(router, "POST", "/api/v1/pack/versions/7/entries");
        invoke(router, "POST", "/api/v1/pack/versions/7/publish");
        invoke(router, "POST", "/api/v1/pack/versions/7/rollback");

        verify(controller).handleCreateVersion(anyRequest(), anyResponse());
        verify(controller).handleAddEntry(org.mockito.ArgumentMatchers.eq("7"), anyRequest(), anyResponse());
        verify(controller).handlePublish(org.mockito.ArgumentMatchers.eq("7"), anyRequest(), anyResponse());
        verify(controller).handleRollback(org.mockito.ArgumentMatchers.eq("7"), anyRequest(), anyResponse());
    }

    @Test
    void uploadRouteParsesAPositiveVersionId() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        PackUploadController uploadController = mock(PackUploadController.class);
        ApiRouter router = router(controller, uploadController, openConfig());

        invoke(router, "POST", "/api/v1/pack/versions/7/upload");

        verify(uploadController).handleUploadAsync(
                org.mockito.ArgumentMatchers.eq(7L), anyRequest(), anyResponse());
    }

    @Test
    void invalidUploadIdReturns400BeforeTheUploadController() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        PackUploadController uploadController = mock(PackUploadController.class);
        ApiRouter router = router(controller, uploadController, openConfig());
        StringWriter body = new StringWriter();
        HttpServletRequest request = request("POST", "/api/v1/pack/versions/not-a-number/upload");
        HttpServletResponse response = response(body);

        router.handleRequest(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(uploadController, never()).handleUploadAsync(
                org.mockito.ArgumentMatchers.anyLong(), anyRequest(), anyResponse());
    }

    @Test
    void putAndDeleteEntryRoutesAreMatchedExactly() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        ApiRouter router = router(controller, openConfig());

        invoke(router, "PUT", "/api/v1/pack/versions/7");
        invoke(router, "PUT", "/api/v1/pack/entries/9");
        invoke(router, "DELETE", "/api/v1/pack/entries/9");

        verify(controller).handleUpdateVersion(org.mockito.ArgumentMatchers.eq("7"), anyRequest(), anyResponse());
        verify(controller).handleUpdateEntry(org.mockito.ArgumentMatchers.eq("9"), anyRequest(), anyResponse());
        verify(controller).handleDeleteEntry(org.mockito.ArgumentMatchers.eq("9"), anyRequest(), anyResponse());
    }

    @Test
    void trailingSlashDoesNotMatchAnAdministrativeRoute() throws Exception {
        PackAdminApiController controller = mock(PackAdminApiController.class);
        ApiRouter router = router(controller, openConfig());
        StringWriter body = new StringWriter();
        HttpServletRequest request = request("GET", "/api/v1/pack/versions/7/entries/");
        HttpServletResponse response = response(body);

        router.handleRequest(request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(controller, never()).handleListEntries(
                org.mockito.ArgumentMatchers.anyString(), anyRequest(), anyResponse());
    }

    private static HttpServletRequest anyRequest() {
        return org.mockito.ArgumentMatchers.any(HttpServletRequest.class);
    }

    private static HttpServletResponse anyResponse() {
        return org.mockito.ArgumentMatchers.any(HttpServletResponse.class);
    }

    private static void invoke(ApiRouter router, String method, String path) throws Exception {
        router.handleRequest(request(method, path), response(new StringWriter()));
    }

    private static AccessHubConfig openConfig() {
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.isAuthEnabled()).thenReturn(false);
        return config;
    }

    private static HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getPathInfo()).thenReturn(path);
        return request;
    }

    private static HttpServletResponse response(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private static ApiRouter router(PackAdminApiController controller, AccessHubConfig config) {
        return router(controller, null, config);
    }

    private static ApiRouter router(PackAdminApiController controller,
                                    PackUploadController uploadController,
                                    AccessHubConfig config) {
        return router(controller, uploadController, null, config);
    }

    private static ApiRouter router(PackAdminApiController controller,
                                    PackUploadController uploadController,
                                    AdminAuthController authController,
                                    AccessHubConfig config) {
        return new ApiRouter(null, null, null, null, null, null, null, null, null,
                null, controller, uploadController, authController, config);
    }
}
