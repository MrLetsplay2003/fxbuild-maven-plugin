package me.mrletsplay.fxbuild;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import me.mrletsplay.fxloader.launcher.FXLoaderLauncher;

/**
 * Goal which touches a timestamp file.
 */
@Mojo(name = "packagefx", defaultPhase = LifecyclePhase.PACKAGE)
public class FXBuild extends AbstractMojo {

	/**
	 * Location of the build JAR
	 */
	@Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.jar", property = "buildFile", required = true)
	private File buildFile;

	/**
	 * Location for the temporary JAR file while patching
	 */
	@Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}-patched.jar", property = "tempFile", required = true)
	private File tempFile;

	/**
	 * The project to get the JavaFX dependencies from
	 */
	@Parameter(defaultValue = "${project}")
	private MavenProject mavenProject;

	@Parameter(defaultValue = "lib")
	private String libDirectory;

	@Override
	public void execute() throws MojoExecutionException {
		getLog().info("Patching JAR file: " + buildFile.getAbsolutePath());
		getLog().info("Temporary file: " + tempFile.getAbsolutePath());

		URL selfURL = getClass().getProtectionDomain().getCodeSource().getLocation();

		if(!buildFile.exists()) {
			throw new MojoExecutionException(String.format("No JAR file found (Path: %s)", buildFile.getPath()));
		}

		getLog().info("Copying file");

		try {
			Files.copy(buildFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to copy file", e);
		}

		getLog().info("Patching file");

		try(FileSystem fs = FileSystems.newFileSystem(tempFile.toPath(), null);
				FileSystem plFS = FileSystems.newFileSystem(Paths.get(selfURL.toURI()), null)) {
			Path p = fs.getPath("/META-INF/MANIFEST.MF");

			Manifest mf;
			try(InputStream in = Files.newInputStream(p)) {
				mf = new Manifest(in);
			}

			String oldMainClass = mf.getMainAttributes().getValue("Main-Class");

			getLog().info("Old Main-Class: " + oldMainClass);

			List<Dependency> deps = mavenProject.getDependencies().stream()
					.filter(d -> d.getGroupId().equalsIgnoreCase("org.openjfx"))
					.filter(d -> {
						if(!d.getScope().equalsIgnoreCase("provided")) {
							getLog().warn("Every OpenJFX dependency's scope should be set to 'provided'. Ignoring artifact '" + d.getArtifactId() + "'");
							return false;
						}

						if(d.getClassifier() != null && !d.getClassifier().isEmpty()) {
							getLog().warn("Ignoring OpenJFX artifact '" + d.getArtifactId() + "' with non-empty classifier '" + d.getClassifier() + "'");
							return false;
						}

						if(d.getVersion().equalsIgnoreCase("latest")) {
							getLog().warn("Ignoring OpenJFX artifact '" + d.getArtifactId() + "'. Version 'latest' is not supported");
							return false;
						}

						return true;
					})
					.collect(Collectors.toList());

			if(deps.isEmpty()) {
				getLog().warn("No OpenJFX dependencies with scope 'provided' found. Not doing anything");
				return;
			}

			getLog().info("OpenJFX dependencies: " + deps.stream().map(d -> d.getArtifactId()).collect(Collectors.joining(", ")));
			getLog().info("Lib directory: " + libDirectory);

			getLog().info("Writing manifest");

			mf.getMainAttributes().putValue("Main-Class", FXLoaderLauncher.class.getName());
			try(OutputStream out = Files.newOutputStream(p)) {
				mf.write(out);
			}

			String meta = String.format("%s\n%s\n%s",
				oldMainClass,
				deps.stream()
					.map(d -> d.getArtifactId() + ":" + d.getVersion())
					.collect(Collectors.joining(";")),
				libDirectory);

			Path metaPath = fs.getPath("/META-INF/fxbuild/meta.txt");
			if(Files.exists(metaPath)) {
				getLog().info("File appears to be patched already, not writing meta file");
			}else {
				Files.createDirectories(metaPath.getParent());
				Files.write(metaPath, meta.getBytes(StandardCharsets.UTF_8));

				Path copyFiles = plFS.getPath("/me/mrletsplay/fxloader/");
				Files.walk(copyFiles).forEach(fl -> {
					if(Files.isDirectory(fl)) return;
					getLog().info("Copying " + fl);
					Path dest = fs.getPath(fl.toString());
					try {
						Files.createDirectories(dest.getParent());
						Files.copy(fl, dest, StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException e) {
						throw new RuntimeException("Failed to copy file", e);
					}
				});
			}
		} catch (IOException | URISyntaxException e) {
			throw new MojoExecutionException("Failed to patch JAR file", e);
		}catch(RuntimeException e) {
			throw new MojoExecutionException("Failed to copy loader classes to patched JAR", e);
		}

		getLog().info("Moving back");
		try {
			Files.move(tempFile.toPath(), buildFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to move file", e);
		}

		getLog().info("Done!");
	}
}
