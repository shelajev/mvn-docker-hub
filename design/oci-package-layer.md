# OCI as the Package Layer

The strongest version of this project is not "Maven artifacts happen to be
stored in Docker images". It is a package model:

```text
Maven coordinate -> OCI artifact -> Maven repository layout inside
```

Maven gives Java-native semantics. OCI gives modern package distribution and
supply-chain controls.

## Why This Is Interesting

Maven repositories are excellent at Java dependency resolution:

- POMs
- transitive dependencies
- classifiers
- snapshots
- plugin metadata
- repository mirrors and caches

OCI registries are excellent at package distribution and artifact identity:

- content-addressed digests
- immutable references
- signatures
- attestations
- SBOM attachment
- vulnerability scanning
- replication and caching
- registry-side policy engines

The design goal is to combine these instead of replacing one with the other.

## Model

For a Maven coordinate:

```text
com.acme:hello:1.0.0
```

The resolver maps to an OCI reference:

```text
docker.io/acme/hello:mvn-1.0.0
```

The tag is then resolved to a digest:

```text
docker.io/acme/hello@sha256:...
```

The OCI artifact contains a normal Maven repository subtree:

```text
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.pom
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.jar
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0-sources.jar
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0-javadoc.jar
```

The resolver extracts only the file Maven requested.

## What Users See

Consumers still write:

```xml
<dependency>
  <groupId>com.acme</groupId>
  <artifactId>hello</artifactId>
  <version>1.0.0</version>
</dependency>
```

The Maven repository URL selects the OCI namespace:

```xml
<repository>
  <id>acme-oci</id>
  <url>docker://docker.io/acme</url>
</repository>
```

This keeps the user-facing model close to JBang-style dependency strings:

```text
com.acme:hello:1.0.0
```

The image name is an implementation detail unless the user wants stronger
security policy.

## Why Not Encode Everything In Docker Names?

Docker repository names are not Maven coordinates. Maven group IDs are often
reverse-DNS names such as:

```text
com.acme.tools
```

Docker namespaces are accounts or organizations that must already exist:

```text
acme
```

So the repository URL should name the OCI namespace explicitly:

```text
docker://docker.io/acme
```

Then the resolver maps Maven paths under that namespace.

## Layouts

Compact default:

```text
com.acme:hello:1.0.0
  -> docker.io/acme/hello:mvn-1.0.0
```

Collision-proof layout:

```text
docker://docker.io/acme?layout=group-artifact
```

```text
com.acme:hello:1.0.0
  -> docker.io/acme/com-acme--hello:mvn-1.0.0
```

The second form is better for organizations that publish multiple Maven groups
or have overlapping artifact IDs.

## Product Shapes

There are three viable user experiences.

### Maven Core Extension

Users add `.mvn/extensions.xml`, then Maven understands `docker://`.

Pros:

- no hosted service
- normal Maven dependency resolution
- works with private registries through local Docker credentials

Cons:

- project-level setup is required
- Maven-specific

### HTTP Gateway

Users add a normal Maven repository:

```xml
<url>https://maven-oci.example/acme</url>
```

The gateway speaks Maven HTTP layout to clients and OCI to registries.

Pros:

- no Maven extension
- works with Maven, Gradle, SBT, Ivy, Bazel, and IDEs
- easier to centralize policy

Cons:

- someone operates a service
- availability and trust shift to the gateway

### Bootstrap CLI

Users run:

```sh
mvndh init docker.io/acme
```

The CLI writes `.mvn/extensions.xml`, checks Docker access, and configures
repositories.

Pros:

- simple onboarding
- keeps decentralized extension model

Cons:

- still an extension underneath
- another tool to install

## Positioning

This is not a Maven Central replacement. It is a pressure valve and an
experiment in using OCI as a stronger package substrate for Java artifacts.

Good use cases:

- high-volume publishing
- commercial-scale distribution
- internal platform teams already using OCI registries
- projects that want stronger provenance and SBOM workflows
- experiments with registry-native policy

Bad use cases:

- projects needing universal Maven compatibility without any setup
- projects relying heavily on dynamic version ranges
- publishers expecting `mvn deploy` to map directly to registry push
