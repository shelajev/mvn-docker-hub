# Design Ideas

These notes capture the design space behind Maven Docker Hub Resolver.

The core idea is not just "use Docker Hub instead of Maven Central". The more
interesting direction is:

```text
Maven coordinates and dependency semantics
inside OCI distribution and security primitives.
```

Maven remains the Java dependency model. OCI becomes the package-level
distribution, identity, provenance, and policy layer.

## Notes

- [OCI as the package layer](oci-package-layer.md)
- [Metadata mapping](metadata.md)
- [Security and provenance mapping](security.md)

## Short Pitch

Keep Maven coordinates:

```text
com.acme:hello:1.0.0
```

Resolve them through OCI:

```text
docker.io/acme/hello:mvn-1.0.0
docker.io/acme/hello@sha256:...
```

Store normal Maven repository files inside the artifact:

```text
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.jar
/maven-repository/com/acme/hello/1.0.0/hello-1.0.0.pom
```

Then use OCI-native capabilities around that package:

- digest-addressed content
- signatures
- SBOMs
- provenance attestations
- registry replication
- registry-side policy
- existing container security tooling

That gives Java builds a stronger supply-chain substrate without asking Java
developers to stop using Maven coordinates.
