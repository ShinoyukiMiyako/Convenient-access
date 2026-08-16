package com.shinoyuki.accesshub.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.modpack.oss.OssPutClient;
import com.shinoyuki.accesshub.pack.PackAdminException;
import com.shinoyuki.accesshub.pack.PackAdminService;
import com.shinoyuki.accesshub.pack.PackEntry;
import com.shinoyuki.accesshub.pack.PackEntryRequest;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class PackUploadControllerTest {

    private static final long AWAIT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private com.sun.net.httpserver.HttpServer ossServer;
    private ExecutorService ossExecutor;
    private final AtomicInteger ossRequests = new AtomicInteger();
    private final AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
    private volatile CountDownLatch ossArrivals = new CountDownLatch(0);
    private volatile CountDownLatch ossRelease = new CountDownLatch(0);

    @BeforeEach
    void startOssServer() throws IOException {
        ossExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "pack-upload-test-oss");
            thread.setDaemon(true);
            return thread;
        });
        ossServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ossServer.setExecutor(ossExecutor);
        ossServer.createContext("/", exchange -> {
            ossRequests.incrementAndGet();
            uploadedBody.set(exchange.getRequestBody().readAllBytes());
            ossArrivals.countDown();
            try {
                if (!ossRelease.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    exchange.close();
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        ossServer.start();
    }

    @AfterEach
    void stopOssServer() {
        ossRelease.countDown();
        ossServer.stop(0);
        ossExecutor.shutdownNow();
    }

    @Test
    void startupDeletesOnlyMatchingRegularTemporaryFiles() throws Exception {
        Path directory = uploadDirectory();
        Files.createDirectories(directory);
        Path uploadTemporaryFile = Files.writeString(directory.resolve("pack-upload-stale.tmp"), "stale");
        Path multipartTemporaryFile = Files.writeString(directory.resolve("MultiPart123456"), "stale");
        Path unrelatedFile = Files.writeString(directory.resolve("retained.tmp"), "retained");
        Path uploadNamedDirectory = Files.createDirectory(directory.resolve("pack-upload-directory.tmp"));
        Path multipartNamedDirectory = Files.createDirectory(directory.resolve("MultiPart-directory"));

        PackUploadController controller = new PackUploadController(
                mock(PackAdminService.class), enabledConfig(), directory);
        try {
            assertFalse(Files.exists(uploadTemporaryFile));
            assertFalse(Files.exists(multipartTemporaryFile));
            assertTrue(Files.isRegularFile(unrelatedFile));
            assertTrue(Files.isDirectory(uploadNamedDirectory));
            assertTrue(Files.isDirectory(multipartNamedDirectory));
        } finally {
            controller.close();
        }
    }

    @Test
    void uploadsOnDedicatedWorkerDeletesPartBeforeReturnAndCleansOwnedTemporaryFile() throws Exception {
        blockOssUntilReleased(1);
        PackAdminService service = mock(PackAdminService.class);
        AtomicReference<String> databaseThread = new AtomicReference<>();
        stubSuccessfulAdd(service, databaseThread);
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        byte[] content = "custom-mod".getBytes(StandardCharsets.UTF_8);
        Part file = part("file", "wok-core.jar", content, content.length);
        AsyncRequest request = asyncMultipartRequest(List.of(
                file,
                part("path", null, bytes("mods/wok-core.jar"), 17),
                part("policy", null, bytes("managed"), 7)));
        CapturedResponse response = new CapturedResponse();

        try {
            controller.handleUploadAsync(7L, request.request(), response.response());

            verify(file).delete();
            assertTrue(ossArrivals.await(AWAIT_SECONDS, TimeUnit.SECONDS), "OSS upload did not start");
            assertEquals(1, countUploadFiles());
            verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));

            ossRelease.countDown();
            request.awaitCompletion();

            ArgumentCaptor<PackEntryRequest> entryCaptor = ArgumentCaptor.forClass(PackEntryRequest.class);
            verify(service).addEntry(eq(7L), entryCaptor.capture());
            PackEntryRequest entry = entryCaptor.getValue();
            assertEquals("mods/wok-core.jar", entry.path());
            assertEquals("custom", entry.kind().databaseValue());
            assertEquals("managed", entry.policy().databaseValue());
            assertEquals(content.length, entry.size());
            assertTrue(entry.downloadUrl().startsWith("https://cdn.example.test/wok/files/"));
            assertEquals(1, ossRequests.get());
            assertArrayEquals(content, uploadedBody.get());
            assertTrue(databaseThread.get().startsWith("AccessHub-PackUpload-"));
            assertNotEquals(Thread.currentThread().getName(), databaseThread.get());
            verify(request.asyncContext()).setTimeout(0);
            verify(response.response()).setStatus(HttpServletResponse.SC_CREATED);
            assertTrue(response.body().contains("\"kind\":\"custom\""));
            assertTrue(response.body().contains("\"policy\":\"managed\""));
            assertUploadDirectoryEmpty();
        } finally {
            ossRelease.countDown();
            controller.close();
        }
    }

    @Test
    void rejectsFifthRequestBeforeMultipartParsingWhenTotalCapacityIsFull() throws Exception {
        blockOssUntilReleased(2);
        PackAdminService service = mock(PackAdminService.class);
        stubSuccessfulAdd(service, new AtomicReference<>());
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        List<AsyncRequest> accepted = new ArrayList<>();

        try {
            for (int index = 0; index < 4; index++) {
                byte[] content = bytes("queued-" + index);
                AsyncRequest request = asyncMultipartRequest(List.of(
                        part("file", "queued-" + index + ".jar", content, content.length),
                        part("path", null, bytes("mods/queued-" + index + ".jar"), 17),
                        part("policy", null, bytes("managed"), 7)));
                accepted.add(request);
                controller.handleUploadAsync(7L, request.request(), new CapturedResponse().response());
            }
            assertTrue(ossArrivals.await(AWAIT_SECONDS, TimeUnit.SECONDS), "two uploads did not occupy workers");

            HttpServletRequest rejectedRequest = mock(HttpServletRequest.class);
            CapturedResponse rejectedResponse = new CapturedResponse();
            controller.handleUploadAsync(7L, rejectedRequest, rejectedResponse.response());

            verify(rejectedRequest, never()).getParts();
            verify(rejectedRequest, never()).startAsync();
            verify(rejectedResponse.response()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);

            ossRelease.countDown();
            for (AsyncRequest request : accepted) {
                request.awaitCompletion();
            }
            assertUploadDirectoryEmpty();
        } finally {
            ossRelease.countDown();
            controller.close();
        }
    }

    @Test
    void maxThreadsTwoReservesARequestThreadByRejectingSecondConcurrentMultipartParse() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        AccessHubConfig config = enabledConfig();
        when(config.getMaxThreads()).thenReturn(2);
        PackUploadController controller = new PackUploadController(service, config, uploadDirectory());
        CountDownLatch parserEntered = new CountDownLatch(1);
        CountDownLatch parserRelease = new CountDownLatch(1);
        HttpServletRequest slowRequest = mock(HttpServletRequest.class);
        when(slowRequest.getContentType()).thenReturn("multipart/form-data; boundary=test");
        when(slowRequest.getParts()).thenAnswer(ignored -> {
            parserEntered.countDown();
            if (!parserRelease.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("test multipart parser was not released");
            }
            return List.of();
        });
        CapturedResponse slowResponse = new CapturedResponse();
        AtomicReference<Throwable> slowFailure = new AtomicReference<>();
        Thread slowThread = new Thread(() -> {
            try {
                controller.handleUploadAsync(7L, slowRequest, slowResponse.response());
            } catch (Throwable failure) {
                slowFailure.set(failure);
            }
        }, "pack-upload-test-parser");

        try {
            slowThread.start();
            assertTrue(parserEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "first parser did not start");
            HttpServletRequest rejectedRequest = mock(HttpServletRequest.class);
            CapturedResponse rejectedResponse = new CapturedResponse();

            controller.handleUploadAsync(8L, rejectedRequest, rejectedResponse.response());

            verify(rejectedRequest, never()).getParts();
            verify(rejectedRequest, never()).startAsync();
            verify(rejectedResponse.response()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        } finally {
            parserRelease.countDown();
            slowThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            controller.close();
        }
        assertFalse(slowThread.isAlive(), "slow parser thread did not terminate");
        assertNull(slowFailure.get());
        verify(slowResponse.response()).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));
    }

    @Test
    void closeInterruptsOssWorkerCompletesRequestAndPreventsDatabaseWrite() throws Exception {
        blockOssUntilReleased(1);
        PackAdminService service = mock(PackAdminService.class);
        AtomicBoolean workerInterruptObserved = new AtomicBoolean();
        AtomicBoolean shutdownSawPriorCancellationInterrupt = new AtomicBoolean();
        ThreadPoolExecutor uploadExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                runnable -> new Thread(runnable, "pack-upload-close-order") {
                    @Override
                    public void interrupt() {
                        workerInterruptObserved.set(true);
                        super.interrupt();
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            public List<Runnable> shutdownNow() {
                shutdownSawPriorCancellationInterrupt.set(workerInterruptObserved.get());
                return super.shutdownNow();
            }
        };
        PackUploadController controller = new PackUploadController(
                service, enabledConfig(), uploadDirectory(), uploadExecutor);
        byte[] content = bytes("closing");
        Part file = part("file", "closing.jar", content, content.length);
        AsyncRequest request = asyncMultipartRequest(List.of(
                file,
                part("path", null, bytes("mods/closing.jar"), 16),
                part("policy", null, bytes("managed"), 7)));
        CapturedResponse response = new CapturedResponse();

        controller.handleUploadAsync(7L, request.request(), response.response());
        assertTrue(ossArrivals.await(AWAIT_SECONDS, TimeUnit.SECONDS), "OSS upload did not block");
        CompletableFuture<Void> closeFuture = CompletableFuture.runAsync(controller::close);
        try {
            closeFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            ossRelease.countDown();
            closeFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            throw exception;
        } finally {
            ossRelease.countDown();
        }

        request.awaitCompletion();
        assertTrue(shutdownSawPriorCancellationInterrupt.get(),
                "shutdownNow interrupted the worker before its cancellation reason was recorded");
        verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));
        verify(response.response()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(response.body().contains("\"code\":503"));
        verify(file).delete();
        assertUploadDirectoryEmpty();
    }

    @Test
    void containerErrorCancelsRunningWorkerWithoutWritingAResponseOrDatabaseEntry() throws Exception {
        blockOssUntilReleased(1);
        PackAdminService service = mock(PackAdminService.class);
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        byte[] content = bytes("client-disconnect");
        AsyncRequest request = asyncMultipartRequest(List.of(
                part("file", "disconnect.jar", content, content.length),
                part("path", null, bytes("mods/disconnect.jar"), 19),
                part("policy", null, bytes("managed"), 7)));
        CapturedResponse response = new CapturedResponse();

        try {
            controller.handleUploadAsync(7L, request.request(), response.response());
            assertTrue(ossArrivals.await(AWAIT_SECONDS, TimeUnit.SECONDS), "OSS upload did not block");

            request.listener().onError(new AsyncEvent(request.asyncContext(), new IOException("disconnected")));

            assertTrue(awaitUploadDirectoryEmpty(), "worker did not clean its temporary file after onError");
            verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));
            verify(response.response(), never()).setStatus(anyInt());
            verify(response.response(), never()).getWriter();
            verify(request.asyncContext(), never()).complete();
        } finally {
            ossRelease.countDown();
            controller.close();
        }
    }

    @Test
    void extendsJettyIdleTimeoutAndRestoresItBeforeCompletingAsyncContext() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        stubSuccessfulAdd(service, new AtomicReference<>());
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        Request request = mock(Request.class);
        HttpChannel channel = mock(HttpChannel.class);
        when(request.getHttpChannel()).thenReturn(channel);
        when(channel.getIdleTimeout()).thenReturn(30_000L);
        byte[] content = bytes("idle-timeout");
        AsyncRequest asyncRequest = configureAsyncRequest(request, List.of(
                part("file", "idle.jar", content, content.length),
                part("path", null, bytes("mods/idle.jar"), 13),
                part("policy", null, bytes("managed"), 7)));

        try {
            controller.handleUploadAsync(7L, request, new CapturedResponse().response());
            asyncRequest.awaitCompletion();

            InOrder lifecycle = inOrder(channel, asyncRequest.asyncContext());
            lifecycle.verify(channel).setIdleTimeout(PackUploadController.UPLOAD_CONNECTION_IDLE_TIMEOUT_MILLIS);
            lifecycle.verify(channel).setIdleTimeout(30_000L);
            lifecycle.verify(asyncRequest.asyncContext()).complete();
        } finally {
            controller.close();
        }
    }

    @Test
    void mapsMissingDraftToNotFoundAndCleansAllUploadResources() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        Part file = part("file", "missing.jar", bytes("data"), 4);
        AsyncRequest request = asyncMultipartRequest(List.of(
                file,
                part("path", null, bytes("mods/missing.jar"), 16),
                part("policy", null, bytes("optional"), 8)));
        CapturedResponse response = new CapturedResponse();
        when(service.addEntry(eq(99L), any(PackEntryRequest.class))).thenThrow(
                new PackAdminException(PackAdminException.Reason.NOT_FOUND, "pack version does not exist: 99"));

        try {
            controller.handleUploadAsync(99L, request.request(), response.response());
            request.awaitCompletion();

            verify(response.response()).setStatus(HttpServletResponse.SC_NOT_FOUND);
            assertEquals(1, ossRequests.get());
            verify(file).delete();
            assertUploadDirectoryEmpty();
        } finally {
            controller.close();
        }
    }

    @Test
    void rejectsJettyRequestLimitAsPayloadTooLargeWithoutStartingAsyncWork() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=test");
        when(request.getParts()).thenThrow(
                new IllegalStateException("Request exceeds maxRequestSize (210763776)"));
        CapturedResponse response = new CapturedResponse();

        try {
            controller.handleUploadAsync(7L, request, response.response());

            verify(response.response()).setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            verify(request, never()).startAsync();
            verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));
            assertEquals(0, ossRequests.get());
            assertUploadDirectoryEmpty();
        } finally {
            controller.close();
        }
    }

    @Test
    void rejectsDeclaredFileAboveLimitBeforeReadingAndDeletesPart() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        Part file = part(
                "file",
                "too-large.jar",
                new byte[] { 1 },
                OssPutClient.MAX_FILE_SIZE + 1);
        HttpServletRequest request = multipartRequest(List.of(
                file,
                part("path", null, bytes("mods/too-large.jar"), 18),
                part("policy", null, bytes("managed"), 7)));
        CapturedResponse response = new CapturedResponse();

        try {
            controller.handleUploadAsync(7L, request, response.response());

            verify(response.response()).setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            verify(file, never()).getInputStream();
            verify(file).delete();
            verify(request, never()).startAsync();
            verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));
            assertEquals(0, ossRequests.get());
            assertUploadDirectoryEmpty();
        } finally {
            controller.close();
        }
    }

    @Test
    void rejectsUnsafePathAndCleansPartWithoutUploading() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        PackUploadController controller = new PackUploadController(service, enabledConfig(), uploadDirectory());
        Part file = part("file", "bad.jar", bytes("data"), 4);
        HttpServletRequest request = multipartRequest(List.of(
                file,
                part("path", null, bytes("../mods/bad.jar"), 15),
                part("policy", null, bytes("managed"), 7)));
        CapturedResponse response = new CapturedResponse();

        try {
            controller.handleUploadAsync(7L, request, response.response());

            verify(response.response()).setStatus(HttpServletResponse.SC_BAD_REQUEST);
            verify(file).delete();
            verify(request, never()).startAsync();
            verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));
            assertEquals(0, ossRequests.get());
            assertUploadDirectoryEmpty();
        } finally {
            controller.close();
        }
    }

    @Test
    void disabledOssRejectsBeforeMultipartParsing() throws Exception {
        PackAdminService service = mock(PackAdminService.class);
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.isPackOssEnabled()).thenReturn(false);
        when(config.getMaxThreads()).thenReturn(10);
        PackUploadController controller = new PackUploadController(service, config, uploadDirectory());
        HttpServletRequest request = mock(HttpServletRequest.class);
        CapturedResponse response = new CapturedResponse();

        try {
            controller.handleUploadAsync(7L, request, response.response());

            verify(response.response()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            verify(request, never()).getParts();
            verify(request, never()).startAsync();
            verify(service, never()).addEntry(anyLong(), any(PackEntryRequest.class));
            assertEquals(0, ossRequests.get());
        } finally {
            controller.close();
        }
    }

    private void blockOssUntilReleased(int expectedConcurrentRequests) {
        ossArrivals = new CountDownLatch(expectedConcurrentRequests);
        ossRelease = new CountDownLatch(1);
    }

    private void stubSuccessfulAdd(PackAdminService service, AtomicReference<String> databaseThread)
            throws Exception {
        when(service.addEntry(anyLong(), any(PackEntryRequest.class))).thenAnswer(invocation -> {
            databaseThread.set(Thread.currentThread().getName());
            long versionId = invocation.getArgument(0);
            PackEntryRequest entry = invocation.getArgument(1);
            return new PackEntry(
                    11L, versionId, entry.path(), entry.kind(), entry.policy(), entry.sha1(), entry.size(),
                    entry.downloadUrl(), null, null, null, null);
        });
    }

    private AccessHubConfig enabledConfig() {
        AccessHubConfig config = mock(AccessHubConfig.class);
        when(config.getMaxThreads()).thenReturn(10);
        when(config.isPackOssEnabled()).thenReturn(true);
        when(config.getPackOssEndpoint()).thenReturn(
                "http://127.0.0.1:" + ossServer.getAddress().getPort());
        when(config.getPackOssBucket()).thenReturn("example-bucket");
        when(config.getPackOssAccessKeyId()).thenReturn("test-key");
        when(config.getPackOssAccessKeySecret()).thenReturn("test-secret");
        when(config.getPackOssPublicBaseUrl()).thenReturn("https://cdn.example.test/wok");
        return config;
    }

    private Path uploadDirectory() {
        return tempDir.resolve("upload-tmp");
    }

    private long countUploadFiles() throws IOException {
        try (var files = Files.list(uploadDirectory())) {
            return files.count();
        }
    }

    private void assertUploadDirectoryEmpty() throws IOException {
        assertEquals(0, countUploadFiles());
    }

    private boolean awaitUploadDirectoryEmpty() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (countUploadFiles() == 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return countUploadFiles() == 0;
    }

    private static HttpServletRequest multipartRequest(Collection<Part> parts) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=test");
        when(request.getParts()).thenReturn(parts);
        return request;
    }

    private static AsyncRequest asyncMultipartRequest(Collection<Part> parts) throws Exception {
        return configureAsyncRequest(mock(HttpServletRequest.class), parts);
    }

    private static AsyncRequest configureAsyncRequest(HttpServletRequest request, Collection<Part> parts)
            throws Exception {
        AsyncContext asyncContext = mock(AsyncContext.class);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<AsyncListener> listener = new AtomicReference<>();
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=test");
        when(request.getParts()).thenReturn(parts);
        when(request.startAsync()).thenReturn(asyncContext);
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(asyncContext).addListener(any(AsyncListener.class));
        doAnswer(ignored -> {
            completed.countDown();
            return null;
        }).when(asyncContext).complete();
        return new AsyncRequest(request, asyncContext, completed, listener);
    }

    private static Part part(String name, String fileName, byte[] content, long size) throws Exception {
        Part part = mock(Part.class);
        when(part.getName()).thenReturn(name);
        when(part.getSubmittedFileName()).thenReturn(fileName);
        when(part.getSize()).thenReturn(size);
        when(part.getInputStream()).thenAnswer(ignored -> new ByteArrayInputStream(content));
        return part;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record AsyncRequest(
            HttpServletRequest request,
            AsyncContext asyncContext,
            CountDownLatch completed,
            AtomicReference<AsyncListener> registeredListener) {

        private void awaitCompletion() throws InterruptedException {
            assertTrue(completed.await(AWAIT_SECONDS, TimeUnit.SECONDS), "async request did not complete");
        }

        private AsyncListener listener() {
            AsyncListener listener = registeredListener.get();
            assertTrue(listener != null, "async listener was not registered");
            return listener;
        }
    }

    private static final class CapturedResponse {
        private final HttpServletResponse response = mock(HttpServletResponse.class);
        private final StringWriter body = new StringWriter();

        private CapturedResponse() throws IOException {
            when(response.getWriter()).thenReturn(new PrintWriter(body));
            doAnswer(ignored -> null).when(response).setStatus(anyInt());
        }

        private HttpServletResponse response() {
            return response;
        }

        private String body() {
            return body.toString();
        }
    }
}
