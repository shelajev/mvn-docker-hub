# Security and Provenance Mapping

OCI can be a stronger security substrate than a traditional Maven repository,
but only if the resolver actually uses OCI security properties.

The design should preserve Maven's native checks while adding OCI-native
identity, signatures, SBOMs, and provenance.

```text
Maven checksums and signatures inside the package
+ OCI digest, signature, SBOM, and provenance around the package
```

## Security Layers

There are three distinct layers.

### 1. Maven File Integrity

Each Maven file should keep normal checksum sidecars:

```text
hello-1.0.0.jar
hello-1.0.0.jar.sha1
hello-1.0.0.jar.sha256
hello-1.0.0.jar.sha512
hello-1.0.0.pom
hello-1.0.0.pom.sha1
hello-1.0.0.pom.sha256
hello-1.0.0.pom.sha512
```

The resolver should:

1. Extract the requested file.
2. Extract known sibling checksum files if present.
3. Verify the payload itself.
4. Pass checksum values to Maven Resolver when serving the main file.

This preserves Maven's existing checksum policy behavior.

### 2. Maven Publisher Signatures

Keep `.asc` detached signatures:

```text
hello-1.0.0.jar.asc
hello-1.0.0.pom.asc
hello-1.0.0-sources.jar.asc
hello-1.0.0-javadoc.jar.asc
```

The resolver can either expose these as normal files when Maven asks for them
or enforce them in a stricter policy mode.

Maven `.asc` signatures answer:

```text
Did a known Maven publisher sign this Maven file?
```

They are useful, but they do not answer the full build provenance question.

### 3. OCI Package Trust

OCI adds package-level identity:

```text
docker.io/acme/hello:mvn-1.0.0
  -> docker.io/acme/hello@sha256:...
```

The digest identifies the whole package, including POM, jar, sidecars, and
metadata included in that image.

OCI signatures and attestations can answer stronger questions:

```text
Who signed this package?
Which CI workflow built it?
Which source revision was used?
What SBOM describes it?
What policy did the registry enforce?
```

This is the strongest argument for OCI as the package layer.

## Policy Modes

The resolver should expose explicit policy modes instead of silently assuming
that pulling an image is enough.

### Baseline

```text
resolve tag
pull image
serve Maven files
verify Maven checksums if present
```

Good for experiments and local registries.

### Digest-Aware

```text
resolve tag to digest
pull by digest
record digest in logs
verify Maven checksums
```

Good default for serious use. Tags remain user-friendly, but the resolver
knows the immutable package identity it actually consumed.

### Locked

Use a lockfile:

```text
.mvn-docker-hub.lock
```

Example:

```text
com.acme:hello:1.0.0 = docker.io/acme/hello@sha256:abc...
com.acme:hello:maven-metadata = docker.io/acme/hello@sha256:def...
```

The resolver rejects any tag that resolves to a different digest.

This gives reproducible builds.

### Signed

Require OCI package signatures for the resolved digest.

Policy examples:

```text
trusted signer identity:
  https://github.com/acme/hello/.github/workflows/release.yml

trusted issuer:
  https://token.actions.githubusercontent.com
```

This lets users express "only artifacts built by this release workflow are
accepted".

### Provenance Required

Require build provenance attestations.

The resolver or a companion verifier checks:

- source repository
- commit SHA
- build workflow
- builder identity
- build trigger
- artifact digest

This is where OCI becomes meaningfully stronger than typical Maven repository
consumption.

### SBOM Required

Require an SBOM attached to the OCI artifact digest.

The resolver may not need to parse the full SBOM during dependency resolution,
but it can enforce that an SBOM exists and optionally hand it to scanners or
policy engines.

## Example Configuration

Possible Maven properties:

```xml
<properties>
  <mvndockerhub.policy>locked-signed</mvndockerhub.policy>
  <mvndockerhub.requireMavenChecksums>true</mvndockerhub.requireMavenChecksums>
  <mvndockerhub.requireMavenSignatures>false</mvndockerhub.requireMavenSignatures>
  <mvndockerhub.requireOciSignature>true</mvndockerhub.requireOciSignature>
  <mvndockerhub.requireSbom>true</mvndockerhub.requireSbom>
  <mvndockerhub.trustedIdentity>https://github.com/acme/hello/.github/workflows/release.yml</mvndockerhub.trustedIdentity>
</properties>
```

Possible repository URL:

```xml
<url>docker://docker.io/acme?policy=locked-signed</url>
```

Possible command-line override:

```sh
mvn test -Dmvndockerhub.policy=baseline
```

## Image Labels and Annotations

Each package should identify the Maven coordinate it claims to contain:

```text
io.mvndockerhub.groupId=com.acme
io.mvndockerhub.artifactId=hello
io.mvndockerhub.version=1.0.0
io.mvndockerhub.mavenRoot=/maven-repository
```

The resolver should reject an image when labels do not match the requested
coordinate.

Potential checksum labels:

```text
io.mvndockerhub.sha256.hello-1.0.0.jar=<hex>
io.mvndockerhub.sha256.hello-1.0.0.pom=<hex>
```

Checksums in labels are convenient, but file sidecars should remain the Maven
source of truth.

## Threat Model

### Tag Replacement

Risk:

```text
docker.io/acme/hello:mvn-1.0.0
```

could be moved to a different digest.

Mitigation:

- resolve and log digest
- use lockfile in strict mode
- pull by digest after resolution
- require OCI signature

### Wrong Image Contents

Risk: the tag exists but contains a different Maven coordinate.

Mitigation:

- verify labels
- verify requested path is inside `/maven-repository`
- verify POM coordinates match requested coordinates

### Tampered Maven File

Risk: jar or POM content does not match published checksums.

Mitigation:

- verify `.sha256` or `.sha512`
- expose checksums to Maven Resolver
- optionally require `.asc`

### Compromised Publisher Token

Risk: attacker pushes a valid tag to the registry.

Mitigation:

- require digest lockfile
- require keyless signature identity from a trusted CI workflow
- require provenance tied to source repo and commit
- use registry immutability policies where available

### Missing SBOM

Risk: package resolves but cannot be audited.

Mitigation:

- require SBOM referrer or attached artifact in policy mode
- fail closed for production builds

## Resolver Responsibilities

The resolver should eventually:

1. Resolve tag to digest.
2. Pull by digest.
3. Verify coordinate labels.
4. Extract the requested Maven file.
5. Verify Maven checksum sidecars.
6. Expose checksum values to Maven Resolver.
7. Optionally verify `.asc` signatures.
8. Optionally verify OCI signatures.
9. Optionally require SBOM/provenance attestations.
10. Optionally enforce lockfile digests.

The current prototype only covers the transport and extraction part. The next
security milestone should be digest awareness plus Maven checksum sidecar
handling.

## Why This Can Be Stronger Than Normal Maven

Traditional Maven consumption normally centers on URLs, checksums, and optional
PGP signatures. OCI adds a package-level envelope around the entire Maven
artifact set.

That envelope can carry:

- immutable digest identity
- signer identity
- CI/build provenance
- SBOMs
- registry policy
- transparency-log-backed signatures, depending on signing mode

This is the discussion-worthy claim:

```text
OCI can make the Maven package itself a signed, attestable, policy-checkable
supply-chain object.
```

The resolver should not hide that. It should make policy visible and
configurable.
