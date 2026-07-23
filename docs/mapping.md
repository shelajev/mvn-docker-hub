# Mapping Notes

The resolver treats Maven coordinates as the source of truth and Docker Hub as
the transport/storage layer.

## Default

```text
dockerhub://docker.io/<namespace>
```

```text
<groupId>:<artifactId>:<version>
  -> docker.io/<namespace>/<artifactId>:mvn-<version>
```

The full Maven repository path is still present inside the image. That means
the resolver extracts exactly the file Maven requested and does not trust the
Docker repository name alone.

## Group-Artifact Layout

```text
dockerhub://docker.io/<namespace>?layout=group-artifact
```

```text
<groupId>:<artifactId>:<version>
  -> docker.io/<namespace>/<groupId-with-dashes>--<artifactId>:mvn-<version>
```

Use this when one namespace hosts several Maven group IDs or when artifact IDs
are likely to collide.

## Why Not Put The Group ID Directly In The Docker Hub Org?

Many Maven group IDs are reverse DNS names such as `com.acme.tools`, while
Docker Hub namespaces are account or organization names that must already
exist. A strict automatic mapping would either fail for common group IDs or
force unreadable generated namespace names. The repository URL names the Docker
Hub namespace explicitly, which is easier to operate and easier to explain.
