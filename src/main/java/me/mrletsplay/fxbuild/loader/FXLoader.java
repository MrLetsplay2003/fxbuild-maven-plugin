package me.mrletsplay.fxbuild.loader;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FXLoader {
	
	private static final String MAVEN_REPO_URL = "https://repo1.maven.org/maven2/org/openjfx/{artifact}/{version}/{artifact}-{version}{classifier}.jar";
	
	public static void main(String[] args) {
		URL selfURL = FXLoader.class.getProtectionDomain().getCodeSource().getLocation();
		String mainClass;
		List<String> dependencies;
		try {
			FileSystem plFS = FileSystems.newFileSystem(Paths.get(selfURL.toURI()), null);
			String[] meta = new String(Files.readAllBytes(plFS.getPath("/META-INF/fxbuild/meta.txt")), StandardCharsets.UTF_8).split("\n");
			mainClass = meta[0];
			dependencies = Arrays.asList(meta[1].split(";"));
		} catch (IOException | URISyntaxException e) {
			throw new FXLoaderException("Failed to load metadata file", e);
		}
		
		log("Main class: " + mainClass);
		log("Required dependencies: " + dependencies.stream().collect(Collectors.joining(", ")));
		
		String classifier = System.getProperty("os.name").toLowerCase().contains("windows") ? "win" : "linux";
		File downloadPath = new File("lib");
		downloadPath.mkdirs();
		
		log("Downloading dependencies...");
		
		List<URL> urls = new ArrayList<>();
		urls.add(selfURL);

		HttpClient httpClient = HttpClient.newBuilder().build();
		for(String dep : dependencies) {
			log("Downloading " + dep + "...");
			
			String[] spl = dep.split(":");
			String partialURL = MAVEN_REPO_URL
					.replace("{artifact}", spl[0])
					.replace("{version}", spl[1]);
			
			HttpRequest noClassifier = HttpRequest.newBuilder(URI.create(partialURL.replace("{classifier}", ""))).build();
			try {
				Path filePath = Paths.get(downloadPath.getPath(), spl[0] + ".jar");
				urls.add(filePath.toUri().toURL());
				if(!Files.exists(filePath)) {
					HttpResponse<byte[]> r = httpClient.send(noClassifier, BodyHandlers.ofByteArray());
					if(r.statusCode() != 200) {
						log("Failed to download dependency '" + dep + "'");
						System.exit(1);
						return;
					}
					
					Files.write(filePath, r.body());
				}
			} catch (IOException | InterruptedException e) {
				throw new FXLoaderException("Failed to download dependency '" + dep + "'", e);
			}
			
			HttpRequest withClassifier = HttpRequest.newBuilder(URI.create(partialURL.replace("{classifier}", "-" + classifier))).build();
			try {
				Path filePath = Paths.get(downloadPath.getPath(), spl[0] + "-" + classifier + ".jar");
				if(!Files.exists(filePath)) {
					HttpResponse<byte[]> r = httpClient.send(withClassifier, BodyHandlers.ofByteArray());
					if(r.statusCode() == 404) {
						log("Dependency " + dep + " doesn't seem to have platform-specific code");
						continue;
					}
					
					if(r.statusCode() != 200) {
						log("Failed to download dependency '" + dep + "'");
						System.exit(1);
						return;
					}
					
					Files.write(filePath, r.body());
					urls.add(filePath.toUri().toURL());
				}else {
					urls.add(filePath.toUri().toURL());
				}
			} catch (IOException | InterruptedException e) {
				throw new FXLoaderException("Failed to download dependency '" + dep + "'", e);
			}
		}
		
		try {
			ClassLoader cl = new FXClassLoader(urls.toArray(URL[]::new), FXLoader.class.getClassLoader());
			Thread.currentThread().setContextClassLoader(cl);
			Class<?> main = cl.loadClass(mainClass);
			main.getMethod("main", String[].class).invoke(null, (Object) args);
		}catch(IllegalAccessException | IllegalArgumentException | NoSuchMethodException | SecurityException | ClassNotFoundException e) {
			throw new FXLoaderException("Failed to run program", e);
		}catch(InvocationTargetException e) {
			e.getTargetException().printStackTrace();
		}
	}
	
	private static void log(String msg) {
		System.out.println("[FXLoader] " + msg);
	}

}
