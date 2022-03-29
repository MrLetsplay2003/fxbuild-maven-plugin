package me.mrletsplay.fxbuild;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.jar.Manifest;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.mrletsplay.mrcore.misc.FriendlyException;

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
			mf.getMainAttributes().putValue("Main-Class", "me.mrletsplay.fxbuild.loader.FXLoader");

			getLog().info("Old Main-Class: " + oldMainClass);
			
			try(OutputStream out = Files.newOutputStream(p)) {
				mf.write(out);
			}
			
			Path copyFiles = plFS.getPath("/me/mrletsplay/fxbuild/loader/");
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
		} catch (IOException | URISyntaxException e) {
			throw new MojoExecutionException("Failed to patch JAR file", e);
		}catch(RuntimeException e) {
			throw new MojoExecutionException("Failed to copy loader classes to patched JAR", e);
		}
		
		getLog().info("Moving back");
	}
}
