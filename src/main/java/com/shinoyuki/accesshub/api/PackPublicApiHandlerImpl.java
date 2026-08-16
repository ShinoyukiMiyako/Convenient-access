package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** HTTP serialization for the public modpack pointer and immutable manifests. */
public final class PackPublicApiHandlerImpl implements PackPublicApiHandler {

    private static final String NON_CACHEABLE = "no-cache, no-store";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private final PackPublicService service;

    public PackPublicApiHandlerImpl(PackPublicService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    @Override
    public void handleLatest(HttpServletRequest request, HttpServletResponse response)
            throws IOException, SQLException {
        response.setHeader("Cache-Control", NON_CACHEABLE);
        Optional<PackLatestResponse> latest = service.getLatest();
        if (latest.isEmpty()) {
            ApiSupport.sendJson(response, HttpServletResponse.SC_NOT_FOUND,
                    ApiResponse.notFound("No published pack version"));
            return;
        }

        sendJson(response, HttpServletResponse.SC_OK, latest.get());
    }

    @Override
    public void handleManifest(HttpServletRequest request, HttpServletResponse response, String version)
            throws IOException, SQLException {
        response.setHeader("Cache-Control", "no-store");
        Optional<PackManifestResponse> manifest = service.getManifest(version);
        if (manifest.isEmpty()) {
            ApiSupport.sendJson(response, HttpServletResponse.SC_NOT_FOUND,
                    ApiResponse.notFound("Pack version not found"));
            return;
        }

        response.setHeader("Cache-Control", IMMUTABLE);
        sendJson(response, HttpServletResponse.SC_OK, manifest.get());
    }

    private static void sendJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(GSON.toJson(body));
        response.getWriter().flush();
    }
}
