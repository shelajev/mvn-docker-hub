package io.mvndockerhub.resolver;

import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.NoTransporterException;

@Named("dockerhub")
@Singleton
public final class DockerHubTransporterFactory implements TransporterFactory {
    static final String DOCKERHUB = "dockerhub";
    static final String DOCKER = "docker";

    @Override
    public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository)
            throws NoTransporterException {
        String protocol = repository.getProtocol();
        if (!DOCKERHUB.equals(protocol) && !DOCKER.equals(protocol)) {
            throw new NoTransporterException(repository);
        }
        return new DockerHubTransporter(repository);
    }

    @Override
    public float getPriority() {
        return 10.0f;
    }
}
