package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.modpack.oss.OssPutClient;
import com.shinoyuki.accesshub.modpack.oss.OssPutConfig;
import com.shinoyuki.accesshub.pack.PackAdminException;
import com.shinoyuki.accesshub.pack.PackAdminService;
import com.shinoyuki.accesshub.pack.PackEntry;
import com.shinoyuki.accesshub.pack.PackEntryKind;
import com.shinoyuki.accesshub.pack.PackEntryPolicy;
import com.shinoyuki.accesshub.pack.PackEntryRequest;
import com.shinoyuki.accesshub.pack.PackPathValidator;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

/** 管理端自研文件上传边界：解析 multipart、上传 OSS，再写入草稿条目。 */
public final class PackUploadController implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PackUploadController.class);
    private static final int MAX_PATH_BYTES = 4096;
    private static final int MAX_POLICY_BYTES = 32;
    private static final String MULTIPART_FILE_GLOB = "MultiPart*";
    private static final String TEMPORARY_FILE_GLOB = "pack-upload-*.tmp";
    private static final String TEMPORARY_FILE_PREFIX = "pack-upload-";
    static final int MAX_CONCURRENT_UPLOADS = 2;
    static final int MAX_QUEUED_UPLOADS = 2;
    static final long UPLOAD_CONNECTION_IDLE_TIMEOUT_MILLIS = Duration.ofMinutes(35).toMillis();
    private static final long SHUTDOWN_AWAIT_SECONDS = 30;

    private final PackAdminService packAdminService;
    private final AccessHubConfig config;
    private final Path temporaryDirectory;
    private final ThreadPoolExecutor uploadExecutor;
    private final Semaphore uploadAdmissions = new Semaphore(
            MAX_CONCURRENT_UPLOADS + MAX_QUEUED_UPLOADS, true);
    private final Semaphore multipartAdmissions;
    private final AtomicBoolean acceptingUploads = new AtomicBoolean(true);
    private final Set<UploadTask> pendingUploads = ConcurrentHashMap.newKeySet();

    public PackUploadController(PackAdminService packAdminService,
                                AccessHubConfig config,
                                Path temporaryDirectory) throws IOException {
        this(packAdminService, config, temporaryDirectory, createUploadExecutor());
    }

    PackUploadController(PackAdminService packAdminService,
                         AccessHubConfig config,
                         Path temporaryDirectory,
                         ThreadPoolExecutor uploadExecutor) throws IOException {
        this.packAdminService = Objects.requireNonNull(packAdminService, "packAdminService");
        this.config = Objects.requireNonNull(config, "config");
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory")
                .toAbsolutePath()
                .normalize();
        this.uploadExecutor = Objects.requireNonNull(uploadExecutor, "uploadExecutor");
        int multipartConcurrency = Math.max(
                1,
                Math.min(MAX_CONCURRENT_UPLOADS, config.getMaxThreads() - 1));
        this.multipartAdmissions = new Semaphore(multipartConcurrency, true);
        Files.createDirectories(this.temporaryDirectory);
        cleanupStaleTemporaryFiles(this.temporaryDirectory);
    }

    public void handleUploadAsync(long versionId,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        if (!acceptingUploads.get()) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "文件上传服务正在关闭");
            return;
        }

        IdleTimeoutLease idleTimeoutLease = IdleTimeoutLease.NOOP;
        AdmissionPermit admissionPermit = AdmissionPermit.NOOP;
        AdmissionPermit multipartPermit = AdmissionPermit.NOOP;
        Path temporaryFile = null;
        Part filePart = null;
        boolean ownershipTransferred = false;
        try {
            if (!uploadAdmissions.tryAcquire()) {
                sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "文件上传队列已满，请稍后重试");
                return;
            }
            admissionPermit = new AdmissionPermit(uploadAdmissions);

            idleTimeoutLease = extendIdleTimeout(request);
            if (!acceptingUploads.get()) {
                sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "文件上传服务正在关闭");
                return;
            }
            if (!config.isPackOssEnabled()) {
                sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "OSS 文件上传未启用");
                return;
            }

            OssPutClient ossClient;
            try {
                ossClient = createOssClient();
            } catch (RuntimeException exception) {
                logger.error("OSS 上传配置无效，请检查 pack.oss 配置", exception);
                sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "OSS 文件上传配置无效");
                return;
            }

            if (!multipartAdmissions.tryAcquire()) {
                sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "正在解析的上传请求已达上限，请稍后重试");
                return;
            }
            multipartPermit = new AdmissionPermit(multipartAdmissions);

            UploadForm form = parseForm(request);
            filePart = form.file();
            PackPathValidator.requireValid(form.path());
            PackEntryPolicy policy = PackEntryPolicy.fromDatabase(form.policy());

            temporaryFile = Files.createTempFile(temporaryDirectory, TEMPORARY_FILE_PREFIX, ".tmp");
            copyFilePart(form.file(), temporaryFile);
            deletePart(filePart);
            filePart = null;
            multipartPermit.release();
            multipartPermit = AdmissionPermit.NOOP;

            if (!acceptingUploads.get()) {
                sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "文件上传服务正在关闭");
                return;
            }

            AsyncContext asyncContext = null;
            try {
                asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
            } catch (IllegalStateException exception) {
                logger.error("无法启动整合包异步上传", exception);
                sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "文件上传异步处理不可用");
                if (asyncContext != null) {
                    deleteTemporaryFile(temporaryFile);
                    temporaryFile = null;
                    admissionPermit.release();
                    admissionPermit = AdmissionPermit.NOOP;
                    idleTimeoutLease.restore();
                    completeQuietly(asyncContext);
                }
                return;
            }

            PreparedUpload preparedUpload = new PreparedUpload(
                    versionId, form.path(), policy, temporaryFile, ossClient, admissionPermit);
            UploadState state = new UploadState(asyncContext, response, idleTimeoutLease);
            UploadTask task = new UploadTask(preparedUpload, state);
            pendingUploads.add(task);
            try {
                asyncContext.addListener(new UploadListener(task, state));
            } catch (RuntimeException exception) {
                pendingUploads.remove(task);
                logger.error("无法注册整合包异步上传监听器", exception);
                state.trySendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "文件上传异步处理不可用");
                deleteTemporaryFile(temporaryFile);
                temporaryFile = null;
                admissionPermit.release();
                admissionPermit = AdmissionPermit.NOOP;
                idleTimeoutLease.restore();
                state.completeLifecycle();
                return;
            }
            ownershipTransferred = true;

            try {
                if (!acceptingUploads.get()) {
                    throw new RejectedExecutionException("Pack upload service is stopping");
                }
                uploadExecutor.execute(task);
            } catch (RejectedExecutionException exception) {
                boolean stopping = !acceptingUploads.get();
                task.rejectBeforeStart(
                        stopping ? CancellationReason.SERVICE_STOPPING : CancellationReason.OVERLOADED,
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        stopping ? "文件上传服务正在关闭" : "文件上传队列已满，请稍后重试");
            }
        } catch (UploadTooLargeException exception) {
            sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, exception.getMessage());
        } catch (BadMultipartException | ServletException exception) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, exception.getMessage());
        } catch (IOException exception) {
            logger.error("处理整合包上传临时文件失败", exception);
            if (!response.isCommitted()) {
                int status = acceptingUploads.get()
                        ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                        : HttpServletResponse.SC_SERVICE_UNAVAILABLE;
                String message = acceptingUploads.get() ? "处理上传文件失败" : "文件上传服务正在关闭";
                sendError(response, status, message);
            }
        } finally {
            deletePart(filePart);
            if (!ownershipTransferred) {
                deleteTemporaryFile(temporaryFile);
                multipartPermit.release();
                admissionPermit.release();
                idleTimeoutLease.restore();
            }
        }
    }

    private static IdleTimeoutLease extendIdleTimeout(HttpServletRequest request) {
        Request baseRequest = Request.getBaseRequest(request);
        if (baseRequest == null) {
            return IdleTimeoutLease.NOOP;
        }

        HttpChannel channel = baseRequest.getHttpChannel();
        long previousTimeout = channel.getIdleTimeout();
        if (previousTimeout <= 0 || previousTimeout >= UPLOAD_CONNECTION_IDLE_TIMEOUT_MILLIS) {
            return new IdleTimeoutLease(channel, previousTimeout, false);
        }

        channel.setIdleTimeout(UPLOAD_CONNECTION_IDLE_TIMEOUT_MILLIS);
        return new IdleTimeoutLease(channel, previousTimeout, true);
    }

    @Override
    public void close() {
        if (!acceptingUploads.getAndSet(false)) {
            return;
        }

        for (UploadTask task : List.copyOf(pendingUploads)) {
            task.cancel(CancellationReason.SERVICE_STOPPING);
        }
        List<Runnable> queuedTasks = uploadExecutor.shutdownNow();
        for (Runnable queuedTask : queuedTasks) {
            if (queuedTask instanceof UploadTask task) {
                task.rejectBeforeStart(
                        CancellationReason.SERVICE_STOPPING,
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "文件上传服务正在关闭");
            }
        }

        try {
            if (!uploadExecutor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("等待进行中的文件上传停止超时，剩余任务数: {}", pendingUploads.size());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadPoolExecutor createUploadExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        return new ThreadPoolExecutor(
                MAX_CONCURRENT_UPLOADS,
                MAX_CONCURRENT_UPLOADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_UPLOADS),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "AccessHub-PackUpload-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private OssPutClient createOssClient() {
        return new OssPutClient(new OssPutConfig(
                URI.create(config.getPackOssEndpoint()),
                config.getPackOssBucket(),
                config.getPackOssAccessKeyId(),
                config.getPackOssAccessKeySecret(),
                URI.create(config.getPackOssPublicBaseUrl())));
    }

    private static UploadForm parseForm(HttpServletRequest request)
            throws IOException, ServletException, BadMultipartException, UploadTooLargeException {
        String contentType = request.getContentType();
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if (!"multipart/form-data".equalsIgnoreCase(mediaType)) {
            throw new BadMultipartException("Content-Type 必须是 multipart/form-data");
        }

        final Collection<Part> parts;
        try {
            parts = request.getParts();
        } catch (IllegalStateException exception) {
            if (isMultipartLimitError(exception)) {
                throw new UploadTooLargeException("上传请求超过 200 MB 文件上限", exception);
            }
            throw new BadMultipartException("multipart 请求格式无效", exception);
        }
        if (parts == null) {
            throw new BadMultipartException("multipart 请求未包含任何字段");
        }

        try {
            Part file = null;
            String path = null;
            String policy = null;
            for (Part part : parts) {
                if (part == null || part.getName() == null) {
                    throw new BadMultipartException("multipart 包含无效字段");
                }
                switch (part.getName()) {
                    case "file" -> {
                        if (file != null) {
                            throw new BadMultipartException("file 字段只能出现一次");
                        }
                        if (part.getSubmittedFileName() == null || part.getSubmittedFileName().isBlank()) {
                            throw new BadMultipartException("file 字段必须包含文件");
                        }
                        file = part;
                        if (part.getSize() > OssPutClient.MAX_FILE_SIZE) {
                            throw new UploadTooLargeException("文件超过 200 MB 上传上限");
                        }
                    }
                    case "path" -> {
                        if (path != null) {
                            throw new BadMultipartException("path 字段只能出现一次");
                        }
                        path = readTextPart(part, MAX_PATH_BYTES, "path");
                    }
                    case "policy" -> {
                        if (policy != null) {
                            throw new BadMultipartException("policy 字段只能出现一次");
                        }
                        policy = readTextPart(part, MAX_POLICY_BYTES, "policy");
                    }
                    default -> throw new BadMultipartException("不支持的 multipart 字段: " + part.getName());
                }
            }

            if (file == null || path == null || policy == null) {
                throw new BadMultipartException("缺少必需字段: file, path, policy");
            }
            return new UploadForm(file, path, policy);
        } catch (IOException | BadMultipartException | UploadTooLargeException | RuntimeException exception) {
            deleteUploadedParts(parts);
            throw exception;
        }
    }

    private static String readTextPart(Part part, int maxBytes, String field)
            throws IOException, BadMultipartException {
        if (part.getSubmittedFileName() != null) {
            throw new BadMultipartException(field + " 必须是文本字段");
        }
        if (part.getSize() > maxBytes) {
            throw new BadMultipartException(field + " 字段过长");
        }
        try (InputStream input = part.getInputStream()) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new BadMultipartException(field + " 字段过长");
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new BadMultipartException(field + " 必须使用有效 UTF-8 编码", exception);
            }
        }
    }

    private static void copyFilePart(Part part, Path target)
            throws IOException, UploadTooLargeException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (InputStream input = part.getInputStream();
             OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > OssPutClient.MAX_FILE_SIZE - read) {
                    throw new UploadTooLargeException("文件超过 200 MB 上传上限");
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
    }

    private static boolean isMultipartLimitError(IllegalStateException exception) {
        String message = exception.getMessage();
        return message != null
                && (message.contains("exceeds max filesize") || message.contains("maxRequestSize"));
    }

    private static void deletePart(Part part) {
        if (part == null) {
            return;
        }
        try {
            part.delete();
        } catch (IOException exception) {
            logger.warn("清理 Jetty multipart 临时文件失败", exception);
        }
    }

    private static void deleteUploadedParts(Collection<Part> parts) {
        for (Part part : parts) {
            if (part != null && part.getSubmittedFileName() != null) {
                deletePart(part);
            }
        }
    }

    private static void deleteTemporaryFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            logger.warn("清理整合包上传临时文件失败", exception);
        }
    }

    private static void cleanupStaleTemporaryFiles(Path directory) throws IOException {
        cleanupStaleTemporaryFiles(directory, TEMPORARY_FILE_GLOB);
        cleanupStaleTemporaryFiles(directory, MULTIPART_FILE_GLOB);
    }

    private static void cleanupStaleTemporaryFiles(Path directory, String glob) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, glob)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException exception) {
                    logger.warn("清理遗留整合包上传临时文件失败: {}", file, exception);
                }
            }
        }
    }

    private static void sendError(HttpServletResponse response, int status, String message) throws IOException {
        ApiSupport.sendJson(response, status, ApiResponse.error(status, message));
    }

    private static void completeQuietly(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (IllegalStateException exception) {
            logger.debug("整合包异步上传上下文已经结束", exception);
        }
    }

    private final class UploadTask implements Runnable {
        private final PreparedUpload upload;
        private final UploadState state;
        private final AtomicBoolean executionClaimed = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();

        private UploadTask(PreparedUpload upload, UploadState state) {
            this.upload = upload;
            this.state = state;
        }

        @Override
        public void run() {
            if (!executionClaimed.compareAndSet(false, true)) {
                return;
            }

            Thread runner = Thread.currentThread();
            state.registerRunner(runner);
            try {
                if (stopIfRequired()) {
                    return;
                }

                OssPutClient.UploadResult result = upload.ossClient().upload(upload.temporaryFile());
                if (stopIfRequired()) {
                    return;
                }

                PackEntry entry = packAdminService.addEntry(upload.versionId(), new PackEntryRequest(
                        upload.path(),
                        PackEntryKind.CUSTOM,
                        upload.policy(),
                        result.sha1(),
                        result.size(),
                        result.downloadUrl(),
                        null,
                        null,
                        null,
                        null));
                if (stopIfRequired()) {
                    return;
                }
                state.trySendSuccess(entry);
            } catch (HttpTimeoutException exception) {
                logger.warn("OSS 文件上传超时: versionId={}, path={}", upload.versionId(), upload.path());
                state.trySendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "OSS 文件上传超时，请重试");
            } catch (InterruptedException exception) {
                state.cancel(acceptingUploads.get()
                        ? CancellationReason.INTERRUPTED
                        : CancellationReason.SERVICE_STOPPING);
                state.trySendCancellation();
            } catch (PackAdminException exception) {
                int status = exception.reason() == PackAdminException.Reason.NOT_FOUND
                        ? HttpServletResponse.SC_NOT_FOUND
                        : HttpServletResponse.SC_CONFLICT;
                state.trySendError(status, exception.getMessage());
            } catch (SQLException exception) {
                logger.error("写入整合包上传条目失败: versionId={}, path={}",
                        upload.versionId(), upload.path(), exception);
                state.trySendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "保存整合包条目失败");
            } catch (IOException exception) {
                logger.error("OSS 文件上传失败: versionId={}, path={}",
                        upload.versionId(), upload.path(), exception);
                state.trySendError(HttpServletResponse.SC_BAD_GATEWAY, "OSS 文件上传失败");
            } catch (RuntimeException exception) {
                logger.error("处理整合包异步上传失败: versionId={}, path={}",
                        upload.versionId(), upload.path(), exception);
                state.trySendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "处理文件上传失败");
            } finally {
                state.clearRunner(runner);
                finishLifecycle();
                Thread.interrupted();
            }
        }

        private boolean stopIfRequired() {
            if (!acceptingUploads.get()) {
                state.cancel(CancellationReason.SERVICE_STOPPING);
            } else if (Thread.currentThread().isInterrupted()) {
                state.cancel(CancellationReason.INTERRUPTED);
            }
            if (!state.shouldStop()) {
                return false;
            }
            state.trySendCancellation();
            return true;
        }

        private void cancel(CancellationReason reason) {
            state.cancel(reason);
        }

        private void cancelFromContainer(CancellationReason reason) {
            state.cancel(reason);
            if (uploadExecutor.remove(this) && executionClaimed.compareAndSet(false, true)) {
                finishLifecycle();
            }
        }

        private void rejectBeforeStart(CancellationReason reason, int status, String message) {
            state.cancel(reason);
            if (!executionClaimed.compareAndSet(false, true)) {
                return;
            }
            state.trySendError(status, message);
            finishLifecycle();
        }

        private void finishLifecycle() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            deleteTemporaryFile(upload.temporaryFile());
            upload.admissionPermit().release();
            pendingUploads.remove(this);
            state.completeLifecycle();
        }
    }

    private static final class UploadState {
        private final AsyncContext asyncContext;
        private final HttpServletResponse response;
        private final IdleTimeoutLease idleTimeoutLease;
        private final AtomicReference<CancellationReason> cancellation = new AtomicReference<>();
        private final AtomicReference<Thread> runner = new AtomicReference<>();
        private final AtomicBoolean responseClaimed = new AtomicBoolean();
        private final AtomicBoolean completionRequested = new AtomicBoolean();
        private final AtomicBoolean containerCompleted = new AtomicBoolean();

        private UploadState(AsyncContext asyncContext,
                            HttpServletResponse response,
                            IdleTimeoutLease idleTimeoutLease) {
            this.asyncContext = asyncContext;
            this.response = response;
            this.idleTimeoutLease = idleTimeoutLease;
        }

        private void registerRunner(Thread thread) {
            runner.set(thread);
            if (cancellation.get() != null) {
                thread.interrupt();
            }
        }

        private void clearRunner(Thread thread) {
            runner.compareAndSet(thread, null);
        }

        private void cancel(CancellationReason reason) {
            if (cancellation.compareAndSet(null, reason)) {
                Thread activeRunner = runner.get();
                if (activeRunner != null) {
                    activeRunner.interrupt();
                }
            }
        }

        private boolean shouldStop() {
            return cancellation.get() != null || Thread.currentThread().isInterrupted();
        }

        private void trySendCancellation() {
            CancellationReason reason = cancellation.get();
            if (reason == null || reason == CancellationReason.CLIENT_ERROR) {
                return;
            }
            if (reason == CancellationReason.TIMEOUT) {
                trySendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "文件上传处理超时，请重试");
            } else if (reason == CancellationReason.INTERRUPTED) {
                trySendError(HttpServletResponse.SC_BAD_GATEWAY, "文件上传已中断，请重试");
            } else {
                trySendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        reason == CancellationReason.OVERLOADED
                                ? "文件上传队列已满，请稍后重试"
                                : "文件上传服务正在关闭");
            }
        }

        private void trySendSuccess(PackEntry entry) {
            if (containerCompleted.get() || cancellation.get() != null
                    || !responseClaimed.compareAndSet(false, true)) {
                return;
            }
            try {
                ApiSupport.sendJsonWithNulls(response, HttpServletResponse.SC_CREATED,
                        ApiResponse.success(entry, "整合包文件上传成功"));
            } catch (IOException | RuntimeException exception) {
                logger.debug("写出整合包上传成功响应失败", exception);
            }
        }

        private void trySendError(int status, String message) {
            if (containerCompleted.get() || !responseClaimed.compareAndSet(false, true)) {
                return;
            }
            try {
                if (!response.isCommitted()) {
                    sendError(response, status, message);
                }
            } catch (IOException | RuntimeException exception) {
                logger.debug("写出整合包上传错误响应失败", exception);
            }
        }

        private void completeLifecycle() {
            if (!completionRequested.compareAndSet(false, true)) {
                return;
            }
            idleTimeoutLease.restore();
            if (!containerCompleted.get()) {
                completeQuietly(asyncContext);
            }
        }

        private boolean markContainerCompleted() {
            containerCompleted.set(true);
            idleTimeoutLease.restore();
            if (completionRequested.get()) {
                return false;
            }
            cancel(CancellationReason.CLIENT_ERROR);
            return true;
        }

        private void markContainerError() {
            containerCompleted.set(true);
            idleTimeoutLease.restore();
            cancel(CancellationReason.CLIENT_ERROR);
        }
    }

    private final class UploadListener implements AsyncListener {
        private final UploadTask task;
        private final UploadState state;

        private UploadListener(UploadTask task, UploadState state) {
            this.task = task;
            this.state = state;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            if (state.markContainerCompleted()) {
                task.cancelFromContainer(CancellationReason.CLIENT_ERROR);
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            state.cancel(CancellationReason.TIMEOUT);
            state.trySendCancellation();
            task.cancelFromContainer(CancellationReason.TIMEOUT);
            state.completeLifecycle();
        }

        @Override
        public void onError(AsyncEvent event) {
            state.markContainerError();
            task.cancelFromContainer(CancellationReason.CLIENT_ERROR);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    private record PreparedUpload(
            long versionId,
            String path,
            PackEntryPolicy policy,
            Path temporaryFile,
            OssPutClient ossClient,
            AdmissionPermit admissionPermit) {
    }

    private enum CancellationReason {
        CLIENT_ERROR,
        INTERRUPTED,
        OVERLOADED,
        SERVICE_STOPPING,
        TIMEOUT
    }

    private static final class AdmissionPermit {
        private static final AdmissionPermit NOOP = new AdmissionPermit(null);

        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private AdmissionPermit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        private void release() {
            if (semaphore != null && released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }

    private static final class IdleTimeoutLease {
        private static final IdleTimeoutLease NOOP = new IdleTimeoutLease(null, 0, false);

        private final HttpChannel channel;
        private final long previousTimeout;
        private final boolean changed;
        private final AtomicBoolean restored = new AtomicBoolean();

        private IdleTimeoutLease(HttpChannel channel, long previousTimeout, boolean changed) {
            this.channel = channel;
            this.previousTimeout = previousTimeout;
            this.changed = changed;
        }

        private void restore() {
            if (!changed || !restored.compareAndSet(false, true)) {
                return;
            }
            try {
                channel.setIdleTimeout(previousTimeout);
            } catch (RuntimeException exception) {
                logger.warn("恢复 Jetty 连接闲置超时失败", exception);
            }
        }
    }

    private record UploadForm(Part file, String path, String policy) {
    }

    private static final class BadMultipartException extends Exception {
        private BadMultipartException(String message) {
            super(message);
        }

        private BadMultipartException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class UploadTooLargeException extends Exception {
        private UploadTooLargeException(String message) {
            super(message);
        }

        private UploadTooLargeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
