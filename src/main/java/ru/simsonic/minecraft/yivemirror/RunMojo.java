package ru.simsonic.minecraft.yivemirror;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@SuppressWarnings("AccessOfSystemProperties")
@Mojo(name = "run")
public class RunMojo extends AbstractMojo {

    public static final String DEFAULT_SERVER_TYPE = "spigot";

    public static final String DEFAULT_SERVER_VERSION = "latest";

    public static final String DEFAULT_SUBDIRECTORY = "server";

    public static final String DEFAULT_RESOURCES = "server-resources";

    /**
     * Spigot, PaperSpigot, Thermos, etc.
     */
    @Parameter(property = "serverType", defaultValue = DEFAULT_SERVER_TYPE)
    public String serverType;

    /**
     * Like 1.12.2-R0.1-SNAPSHOT-b1612
     */
    @Parameter(property = "serverVersion", defaultValue = DEFAULT_SERVER_VERSION)
    public String serverVersion;

    @Parameter(property = "directory", defaultValue = DEFAULT_SUBDIRECTORY)
    public String directory;

    @Parameter(property = "resources", defaultValue = DEFAULT_RESOURCES)
    public String resources;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    public File projectDirectory;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    public File buildDirectory;

    @Parameter(defaultValue = "${project.build.finalName}", readonly = true, required = true)
    public String finalName;

    private final LogWrapper logger = new LogWrapper(getLog());

    private final LocalCache localCache = new LocalCache();

    private final ServerStarter serverStarter = new ServerStarter(logger);

    private final YivesMirrorUrlSource urlSource = new YivesMirrorUrlSource();

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ServerDescription serverDescription = getServerVersion();
            String filename = urlSource.getFilenameForServer(serverDescription);
            File locationInCache = localCache.getServerFile(filename);
            if (locationInCache.isFile()) {
                logger.debug("Location of cached version: %s", locationInCache);
            } else {
                String url = urlSource.getUrlForServer(serverDescription);
                logger.info("Downloading from %s into %s", url, locationInCache);
                downloadFile(url, locationInCache);
            }

            logger.info("Preparing directory for running server ...");
            File serverDirectory = new File(buildDirectory, directory);
            ServerEnvironment environment = new ServerEnvironment(serverDirectory, locationInCache);
            install(environment);

            logger.info("Starting minecraft server ...");
            serverStarter.run(environment);
        } catch (Exception ex) {
            throw new MojoExecutionException("Internal plugin error.", ex);
        }
    }

    private ServerDescription getServerVersion() {
        return new ServerDescription(
                ServerType.valueOf(serverType.toUpperCase()),
                serverVersion);
    }

    @SuppressWarnings({"resource", "IOResourceOpenedButNotSafelyClosed"})
    private static void downloadFile(String sourceUrl, File target) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpUriRequest request = new HttpGet(sourceUrl);
            CloseableHttpResponse response = httpClient.execute(request);
            ReadableByteChannel sourceChannel = Channels.newChannel(response.getEntity().getContent());
            FileChannel destinationChannel = new FileOutputStream(target).getChannel();
            destinationChannel.transferFrom(sourceChannel, 0, Integer.MAX_VALUE);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void install(ServerEnvironment environment) throws IOException, MojoExecutionException {
        File serverDirectory = environment.getServerDirectory();
        serverDirectory.mkdirs();

        File resourcesDir = new File(projectDirectory, resources);
        if (resourcesDir.isDirectory()) {
            copyResources(resourcesDir, serverDirectory);
        }

        File pluginsDirectory = environment.getPluginsDirectory();
        pluginsDirectory.mkdirs();

        String filename = finalName + ".jar";
        File compiledPlugin = getCompiledPluginJar(filename);
        File installedPlugin = new File(pluginsDirectory, filename);
        Files.copy(compiledPlugin.toPath(), installedPlugin.toPath());
    }

    private File getCompiledPluginJar(String filename) throws MojoExecutionException {
        File compiledPlugin = new File(buildDirectory, filename);
        if (compiledPlugin.isFile()) {
            return compiledPlugin;
        }
        throw new MojoExecutionException("Compiled plugin .jar is absent.");
    }

    private void copyResources(File resourcesDir, File serverDirectory) throws IOException {
        Path resourcesPath = resourcesDir.toPath();
        Path destinationPath = serverDirectory.toPath();
        try (Stream<Path> stream = Files.walk(resourcesPath)) {
            stream.filter(path -> path.toFile().isFile())
                    .forEach(path -> copyResource(resourcesPath, path, destinationPath));
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void copyResource(Path resourcesPath, Path sourcePath, Path destPath) {
        try {
            logger.debug("Copy file: " + destPath);
            Path relative = resourcesPath.relativize(sourcePath);
            Path resolved = destPath.resolve(relative);
            resolved.toFile().getParentFile().mkdirs();
            Files.copy(sourcePath, resolved);
        } catch (Exception ex) {
            logger.error(ex.getMessage());
        }
    }
}
