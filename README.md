# Maven Docker Hub Resolver

A Maven Resolver transport that resolves Maven artifacts from Docker images.

The goal is JitPack-like ergonomics for consumers: keep writing normal Maven
coordinates, while the resolver translates those coordinates to Docker Hub
images behind the scenes.

```xml
<dependency>
  <groupId>com.acme</groupId>
  <artifactId>hello</artifactId>
  <version>1.0.0</version>
</dependency>
```

Backed by:

```text
docker.io/acme/hello:mvn-1.0.0
```

The image contains a normal Maven repository tree at `/maven-repository`.
Maven still requests ordinary repository paths, and this extension pulls the
matching image and extracts the requested file.

## Status

Prototype.

Implemented:

- Pulling artifacts from `dockerhub://` Maven repositories.
- Docker-backed resolution through Maven core extensions.
- Default and collision-proof coordinate-to-image mappings.
- Private image support through the local Docker credential store.

Not implemented:

- `mvn deploy` directly to `dockerhub://...`.
- Rich metadata/version-range support beyond a `mvn-metadata` image tag.
- A production publisher plugin.

Publishing is currently a separate staging step: create a Maven repository
directory, copy it into an image, tag the image, and push it.

## Quick Start

Build and install the extension:

```sh
mvn install
```

Create a local test artifact image:

```sh
mkdir -p /tmp/mvn-docker-hub/maven-repository/com/acme/hello/1.0.0
printf 'hello\n' > /tmp/mvn-docker-hub/hello.txt
jar cf /tmp/mvn-docker-hub/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.jar \
  -C /tmp/mvn-docker-hub hello.txt
cat > /tmp/mvn-docker-hub/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.pom <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.acme</groupId>
  <artifactId>hello</artifactId>
  <version>1.0.0</version>
</project>
EOF
cat > /tmp/mvn-docker-hub/Dockerfile <<'EOF'
FROM alpine:3.20
COPY maven-repository/ /maven-repository/
EOF
docker build -t acme/hello:mvn-1.0.0 /tmp/mvn-docker-hub
```

Resolve it from the example project:

```sh
cd examples/simple
mvn dependency:copy-dependencies
jar tf target/dependency/hello-1.0.0.jar
```

You should see `hello.txt` in the copied dependency jar.

## Consumer Setup

Add `.mvn/extensions.xml` to the consuming project:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>io.mvndockerhub</groupId>
    <artifactId>mvn-docker-hub-resolver</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Add a repository:

```xml
<repositories>
  <repository>
    <id>docker-hub-acme</id>
    <url>dockerhub://docker.io/acme</url>
  </repository>
</repositories>
```

Then use normal dependencies:

```xml
<dependency>
  <groupId>com.acme</groupId>
  <artifactId>hello</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Coordinate Mapping

Repository URL:

```xml
<url>dockerhub://docker.io/acme</url>
```

Default mapping:

```text
groupId    -> stays in the Maven repository path inside the image
artifactId -> Docker repository
version    -> Docker tag with mvn- prefix
file path  -> /maven-repository/<normal Maven repository path>
```

For `com.acme:hello:1.0.0`, Maven requests:

```text
com/acme/hello/1.0.0/hello-1.0.0.pom
com/acme/hello/1.0.0/hello-1.0.0.jar
```

The resolver pulls:

```text
docker.io/acme/hello:mvn-1.0.0
```

Then extracts:

```text
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.pom
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.jar
```

This keeps the visible contract close to JBang dependency strings such as
`com.acme:hello:1.0.0`: users write Maven coordinates, not image names.

## Collision-Proof Layout

The default layout is compact, but it can collide if one Docker Hub namespace
publishes multiple Maven groups that reuse the same artifact ID.

Use `layout=group-artifact` when you want the Docker repository name to include
both group and artifact:

```xml
<url>dockerhub://docker.io/acme?layout=group-artifact</url>
```

Then `com.acme:hello:1.0.0` maps to:

```text
docker.io/acme/com-acme--hello:mvn-1.0.0
```

## Tags

Release versions map to tags with a reserved prefix:

```text
1.0.0     -> mvn-1.0.0
2.0.0-rc1 -> mvn-2.0.0-rc1
```

Versions that are not valid Docker tags are encoded:

```text
1.0.0+build.5 -> mvn-b64-MTAuMCtidWlsZC41
```

Artifact metadata maps to:

```text
mvn-metadata
```

## Publisher Workflow

Stage a local Maven repository:

```sh
mvn -DaltDeploymentRepository=docker-stage::default::file:target/docker-repository deploy
```

Create an image that contains the staged repository tree:

```dockerfile
FROM alpine:3.20
COPY target/docker-repository/ /maven-repository/
LABEL io.mvndockerhub.layout="/maven-repository"
```

Build and push one tag per Maven version:

```sh
docker build -t acme/hello:mvn-1.0.0 .
docker push acme/hello:mvn-1.0.0
```

For private Docker Hub repositories, run `docker login` first. docker-java
uses the local Docker configuration and credential store.

## Requirements

- Maven 3.8+ with core extensions enabled.
- Java 11+.
- A running Docker daemon reachable by docker-java.
- Docker credentials configured with `docker login` for private images.

## Design Notes

Maven deploy uploads one file at a time. Docker registries publish image
manifests. Those models do not line up cleanly, so this prototype keeps
resolution and publishing separate.

The Docker repository URL names the Docker Hub namespace explicitly instead of
deriving it from the Maven group ID. Many Maven group IDs are reverse-DNS names
such as `com.acme.tools`, while Docker Hub namespaces are account or
organization names that must already exist.

See [docs/mapping.md](docs/mapping.md) for more mapping details.
