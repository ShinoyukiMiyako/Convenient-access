package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.sql.SQLException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Public, unauthenticated modpack read endpoints. */
public interface PackPublicApiHandler {

    void handleLatest(HttpServletRequest request, HttpServletResponse response)
            throws IOException, SQLException;

    void handleManifest(HttpServletRequest request, HttpServletResponse response, String version)
            throws IOException, SQLException;
}
