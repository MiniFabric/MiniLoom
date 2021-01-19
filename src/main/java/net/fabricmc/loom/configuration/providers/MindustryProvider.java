/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.providers;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.zip.ZipError;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.stitch.merge.JarMerger;

public class MindustryProvider extends DependencyProvider {
	private String version;

	private File mindustryMergedJar;
	private File mindustryClientJar;
	private File mindustryServerJar;

	public MindustryProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		version = dependency.getDependency().getVersion();

		boolean offline = getProject().getGradle().getStartParameter().isOffline();

		initFiles();

		// Add Loom as an annotation processor
		addDependency(getProject().files(this.getClass().getProtectionDomain().getCodeSource().getLocation()), "compileOnly");

		if (offline) {
			if (mindustryClientJar.exists() && mindustryServerJar.exists()) {
				getProject().getLogger().debug("Found client and server jars, presuming up-to-date");
			} else if (mindustryMergedJar.exists()) {
				//Strictly we don't need the split jars if the merged one exists, let's try go on
				getProject().getLogger().warn("Missing game jar but merged jar present, things might end badly");
			} else {
				throw new GradleException("Missing jar(s); Client: " + mindustryClientJar.exists() + ", Server: " + mindustryServerJar.exists());
			}
		} else {
			downloadJars(getProject().getLogger());
		}

		if (!mindustryMergedJar.exists() || isRefreshDeps()) {
			try {
				mergeJars(getProject().getLogger());
			} catch (ZipError e) {
				DownloadUtil.delete(mindustryClientJar);
				DownloadUtil.delete(mindustryServerJar);

				getProject().getLogger().error("Could not merge JARs! Deleting source JARs - please re-run the command and move on.", e);
				throw new RuntimeException();
			}
		}
	}

	private void initFiles() {
		mindustryClientJar = new File(getExtension().getUserCache(), "mindustry-" + version + "-client.jar");
		mindustryServerJar = new File(getExtension().getUserCache(), "mindustry-" + version + "-server.jar");
		mindustryMergedJar = new File(getExtension().getUserCache(), "mindustry-" + version + "-merged.jar");
	}


	private void downloadJars(Logger logger) throws IOException {
		if (getExtension().isShareCaches() && !getExtension().isRootProject() && mindustryClientJar.exists() && mindustryServerJar.exists() && !isRefreshDeps()) {
			return;
		}
		getProject().getLogger().error("Download mindustry and put desktop.jar and server.jar here: " + getExtension().getUserCache().toPath().toString());
		getProject().getLogger().error("Rename to mindustry-" + version + "-client.jar and mindustry-" + version + "-server.jar, respectively.");
		throw new RuntimeException();
		//todo download mindustry
//		MindustryVersionInfo.Downloads client = versionInfo.downloads.get("client");
//		MindustryVersionInfo.Downloads server = versionInfo.downloads.get("server");
//
//		HashedDownloadUtil.downloadIfInvalid(new URL(client.url), mindustryClientJar, client.sha1, logger, false);
//		HashedDownloadUtil.downloadIfInvalid(new URL(server.url), mindustryServerJar, server.sha1, logger, false);
	}

	private void mergeJars(Logger logger) throws IOException {
		logger.lifecycle(":merging jars");

		try (JarMerger jarMerger = new JarMerger(mindustryClientJar, mindustryServerJar, mindustryMergedJar)) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}

	public File getMergedJar() {
		return mindustryMergedJar;
	}

	public String getVersion() {
		return version;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MINDUSTRY;
	}
}
