package io.mvndockerhub.resolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.TransportListener;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.transfer.TransferCancelledException;

final class DockerHubTransporter implements Transporter {
    private static final String DEFAULT_REGISTRY = "docker.io";
    private static final String DEFAULT_ROOT = "/maven-repository";
    private static final int BUFFER_SIZE = 32 * 1024;

    private final DockerClient docker;
    private final DockerRepository repository;
    private final Map<String, Boolean> pulled = new ConcurrentHashMap<>();

    DockerHubTransporter(RemoteRepository remoteRepository) {
        this.repository = DockerRepository.from(remoteRepository.getUrl());
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.docker = DockerClientBuilder.getInstance(config).build();
    }

    @Override
    public int classify(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResourceMissingException || current instanceof NotFoundException) {
                return ERROR_NOT_FOUND;
            }
            current = current.getCause();
        }
        return ERROR_OTHER;
    }

    @Override
    public void peek(PeekTask task) throws Exception {
        Resource resource = resource(task.getLocation());
        withContainer(resource.image, resource.containerPath, stream -> true);
    }

    @Override
    public void get(GetTask task) throws Exception {
        Resource resource = resource(task.getLocation());
        withContainer(resource.image, resource.containerPath, stream -> {
            try (OutputStream output = task.newOutputStream()) {
                copyFirstRegularFile(stream, output, task.getListener());
            }
            return true;
        });
    }

    @Override
    public void put(PutTask task) throws Exception {
        throw new UnsupportedOperationException(
                "dockerhub:// repositories are read-only. Publish by staging Maven repository files into an image.");
    }

    @Override
    public void close() {
        try {
            docker.close();
        } catch (IOException ignored) {
            // Close is best effort; Maven cannot recover from close failures here.
        }
    }

    private Resource resource(URI location) {
        MavenPath mavenPath = MavenPath.parse(location);
        String image = repository.imageFor(mavenPath);
        String containerPath = repository.rootPath + "/" + mavenPath.relativePath;
        return new Resource(image, containerPath);
    }

    private <T> T withContainer(String image, String path, ArchiveReader<T> reader) throws Exception {
        ensureImagePresent(image);
        String containerId = null;
        try {
            CreateContainerResponse container = docker.createContainerCmd(image)
                    .withCmd("mvn-docker-hub-resolver-noop")
                    .exec();
            containerId = container.getId();
            try (InputStream archive = docker.copyArchiveFromContainerCmd(containerId, path).exec()) {
                return reader.read(archive);
            }
        } catch (NotFoundException e) {
            throw new ResourceMissingException("No Maven resource at " + path + " in " + image, e);
        } finally {
            if (containerId != null) {
                try {
                    docker.removeContainerCmd(containerId).withForce(true).exec();
                } catch (RuntimeException ignored) {
                    // Best effort cleanup; the original transfer error is more useful to Maven.
                }
            }
        }
    }

    private void ensureImagePresent(String image) throws InterruptedException {
        if (Boolean.TRUE.equals(pulled.get(image))) {
            return;
        }
        synchronized (pulled) {
            if (Boolean.TRUE.equals(pulled.get(image))) {
                return;
            }
            try {
                docker.inspectImageCmd(image).exec();
            } catch (NotFoundException missingLocally) {
                ImageReference ref = ImageReference.parse(image);
                docker.pullImageCmd(ref.repository)
                        .withTag(ref.tag)
                        .start()
                        .awaitCompletion(Duration.ofMinutes(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            pulled.put(image, Boolean.TRUE);
        }
    }

    private static void copyFirstRegularFile(InputStream archive, OutputStream output, TransportListener listener)
            throws IOException, TransferCancelledException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                long size = entry.getSize();
                listener.transportStarted(0, size);
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = tar.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    listener.transportProgressed(ByteBuffer.wrap(buffer, 0, read).asReadOnlyBuffer());
                }
                return;
            }
        }
        throw new ResourceMissingException("Archive did not contain a regular file");
    }

    private interface ArchiveReader<T> {
        T read(InputStream archive) throws Exception;
    }

    private static final class Resource {
        final String image;
        final String containerPath;

        Resource(String image, String containerPath) {
            this.image = image;
            this.containerPath = containerPath;
        }
    }

    private static final class ImageReference {
        final String repository;
        final String tag;

        ImageReference(String repository, String tag) {
            this.repository = repository;
            this.tag = tag;
        }

        static ImageReference parse(String image) {
            int tagStart = image.lastIndexOf(':');
            if (tagStart < 0 || tagStart < image.lastIndexOf('/')) {
                throw new IllegalArgumentException("Image reference has no tag: " + image);
            }
            return new ImageReference(image.substring(0, tagStart), image.substring(tagStart + 1));
        }
    }

    private static final class DockerRepository {
        final String registry;
        final String namespace;
        final String rootPath;
        final RepoLayout layout;

        DockerRepository(String registry, String namespace, String rootPath, RepoLayout layout) {
            this.registry = registry;
            this.namespace = namespace;
            this.rootPath = rootPath;
            this.layout = layout;
        }

        static DockerRepository from(String url) {
            URI uri = URI.create(url);
            String registry = emptyToDefault(uri.getHost(), DEFAULT_REGISTRY);
            String[] segments = segments(uri.getPath());
            String namespace = segments.length == 0 ? null : segments[0];
            Query query = Query.parse(uri.getRawQuery());
            RepoLayout layout = RepoLayout.from(query.get("layout", "artifact"));
            String root = query.get("root", DEFAULT_ROOT);
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException(
                        "dockerhub:// repository URL must include a namespace, for example dockerhub://docker.io/acme");
            }
            return new DockerRepository(registry, namespace, root, layout);
        }

        String imageFor(MavenPath path) {
            String repo = layout.repositoryName(path);
            String prefix = DEFAULT_REGISTRY.equals(registry) ? "" : registry + "/";
            return prefix + namespace + "/" + repo + ":" + path.tag();
        }

        private static String emptyToDefault(String value, String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }

    private enum RepoLayout {
        ARTIFACT {
            @Override
            String repositoryName(MavenPath path) {
                return dockerName(path.artifactId);
            }
        },
        GROUP_ARTIFACT {
            @Override
            String repositoryName(MavenPath path) {
                return dockerName(path.groupId.replace('.', '-') + "--" + path.artifactId);
            }
        };

        abstract String repositoryName(MavenPath path);

        static RepoLayout from(String value) {
            if ("artifact".equals(value)) {
                return ARTIFACT;
            }
            if ("group-artifact".equals(value)) {
                return GROUP_ARTIFACT;
            }
            throw new IllegalArgumentException("Unsupported dockerhub repo layout: " + value);
        }
    }

    private static final class MavenPath {
        final String groupId;
        final String artifactId;
        final String version;
        final String relativePath;

        MavenPath(String groupId, String artifactId, String version, String relativePath) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.relativePath = relativePath;
        }

        static MavenPath parse(URI location) {
            String raw = Objects.requireNonNull(location, "location").getPath();
            String path = raw.startsWith("/") ? raw.substring(1) : raw;
            String[] parts = path.split("/");
            if (parts.length < 3) {
                throw new ResourceMissingException("Not a Maven artifact path: " + location);
            }
            String fileName = parts[parts.length - 1];
            boolean metadata = "maven-metadata.xml".equals(fileName)
                    || fileName.startsWith("maven-metadata.xml.");
            int artifactIndex = metadata ? parts.length - 2 : parts.length - 3;
            if (artifactIndex <= 0) {
                throw new ResourceMissingException("Cannot infer Maven coordinates from " + location);
            }
            String artifactId = parts[artifactIndex];
            String version = metadata ? null : parts[parts.length - 2];
            StringBuilder group = new StringBuilder();
            for (int i = 0; i < artifactIndex; i++) {
                if (i > 0) {
                    group.append('.');
                }
                group.append(parts[i]);
            }
            return new MavenPath(group.toString(), artifactId, version, path);
        }

        String tag() {
            if (version == null) {
                return "mvn-metadata";
            }
            if (version.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,123}")) {
                return "mvn-" + version;
            }
            return "mvn-b64-" + java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static final class Query {
        private final Map<String, String> values;

        private Query(Map<String, String> values) {
            this.values = values;
        }

        static Query parse(String query) {
            Map<String, String> values = new java.util.HashMap<>();
            if (query != null && !query.isBlank()) {
                for (String pair : query.split("&")) {
                    int equals = pair.indexOf('=');
                    String key = equals < 0 ? pair : pair.substring(0, equals);
                    String value = equals < 0 ? "" : pair.substring(equals + 1);
                    values.put(decode(key), decode(value));
                }
            }
            return new Query(values);
        }

        String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        private static String decode(String value) {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String dockerName(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String clean = lower.replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[._-]+", "")
                .replaceAll("[._-]+$", "");
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Cannot map value to Docker repository name: " + value);
        }
        return clean;
    }

    private static String[] segments(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return new String[0];
        }
        String normalized = Paths.get(path).normalize().toString().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? new String[0] : normalized.split("/");
    }

    private static final class ResourceMissingException extends RuntimeException {
        ResourceMissingException(String message) {
            super(message);
        }

        ResourceMissingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
