# Metadata Mapping

Fixed Maven versions are easy. Metadata is where the design needs care.

Maven asks repositories for ordinary paths such as:

```text
com/acme/hello/1.0.0/hello-1.0.0.pom
com/acme/hello/1.0.0/hello-1.0.0.jar
```

For fixed versions, the resolver can infer:

```text
com.acme:hello:1.0.0
  -> docker.io/acme/hello:mvn-1.0.0
```

But Maven also asks for metadata:

```text
com/acme/hello/maven-metadata.xml
com/acme/hello/maven-metadata.xml.sha1
com/acme/hello/maven-metadata.xml.sha256
com/acme/hello/maven-metadata.xml.sha512
```

That metadata drives release/latest discovery, version ranges, snapshots, and
some plugin behavior.

## Recommended Metadata Image

Use a reserved metadata tag:

```text
docker.io/acme/hello:mvn-metadata
```

The image contains:

```text
/maven-repository/com/acme/hello/maven-metadata.xml
/maven-repository/com/acme/hello/maven-metadata.xml.sha1
/maven-repository/com/acme/hello/maven-metadata.xml.sha256
/maven-repository/com/acme/hello/maven-metadata.xml.sha512
```

This keeps metadata in Maven's normal format while making it an OCI artifact.

## Fixed Releases

Release artifacts should use one image tag per Maven version:

```text
com.acme:hello:1.0.0
  -> docker.io/acme/hello:mvn-1.0.0
```

Inside:

```text
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.pom
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.jar
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0-sources.jar
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0-javadoc.jar
```

Include checksum and signature sidecars:

```text
hello-1.0.0.jar.sha256
hello-1.0.0.jar.sha512
hello-1.0.0.jar.asc
hello-1.0.0.pom.sha256
hello-1.0.0.pom.sha512
hello-1.0.0.pom.asc
```

## Snapshots

Do not collapse Maven snapshots into a single mutable OCI tag without keeping
Maven's timestamped snapshot metadata. That would make Maven behavior
surprising.

Use:

```text
logical snapshot:
  docker.io/acme/hello:mvn-1.0.0-SNAPSHOT

timestamped build:
  docker.io/acme/hello:mvn-1.0.0-20260724.123456-1
```

The logical snapshot image contains:

```text
/maven-repository/com/acme/hello/1.0.0-SNAPSHOT/maven-metadata.xml
```

That metadata points to timestamped artifact files, as Maven expects.

Timestamped build images contain:

```text
/maven-repository/com/acme/hello/1.0.0-SNAPSHOT/hello-1.0.0-20260724.123456-1.jar
/maven-repository/com/acme/hello/1.0.0-SNAPSHOT/hello-1.0.0-20260724.123456-1.pom
```

This preserves Maven snapshot semantics while letting OCI carry the actual
published build.

## Version Ranges

Version ranges require `maven-metadata.xml`.

The resolver can support them if:

1. Maven requests metadata.
2. The resolver maps that request to `mvn-metadata`.
3. Maven uses the metadata to choose a concrete version.
4. The resolver maps the concrete version to `mvn-<version>`.

This is viable, but metadata freshness becomes important. OCI tags can move,
so strict users should lock metadata by digest too.

## Plugin Metadata

Maven plugins use group-level metadata for prefix resolution. Example:

```text
org/example/maven-metadata.xml
```

This is harder to infer from `artifactId` because the request is at group
level, not artifact level.

Possible mappings:

```text
docker.io/acme/maven-plugin-metadata:mvn-org.example
docker.io/acme/_metadata:mvn-org.example
```

Recommendation: treat plugin prefix metadata as a later design problem. Fixed
dependency resolution matters first.

## Open Questions

- Should metadata live in one image per artifact, or one namespace-wide
  metadata image?
- Should metadata tags be mutable by design, or should consumers always lock
  them to digests?
- Should the resolver synthesize `maven-metadata.xml` by listing OCI tags, or
  only read publisher-provided metadata?

Current preference: publishers provide Maven metadata as files. The resolver
should not synthesize Maven metadata from registry tags until there is a very
clear compatibility story.
