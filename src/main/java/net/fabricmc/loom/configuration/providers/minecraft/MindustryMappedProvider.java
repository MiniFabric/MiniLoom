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

package net.fabricmc.loom.configuration.providers.mindustry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableMap;
import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MindustryProvider;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class MindustryMappedProvider extends DependencyProvider {
	private static final Map<String, String> JSR_TO_JETBRAINS = new ImmutableMap.Builder<String, String>()
			.put("javax/annotation/Nullable", "org/jetbrains/annotations/Nullable")
			.put("javax/annotation/Nonnull", "org/jetbrains/annotations/NotNull")
			.put("javax/annotation/concurrent/Immutable", "org/jetbrains/annotations/Unmodifiable")
			.build();

	private File mindustryMappedJar;
	private File mindustryIntermediaryJar;

	private MindustryProvider mindustryProvider;

	public MindustryMappedProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		if (!getExtension().getMappingsProvider().tinyMappings.exists()) {
			throw new RuntimeException("mappings file not found");
		}

		if (!getExtension().getMindustryProvider().getMergedJar().exists()) {
			throw new RuntimeException("input merged jar not found");
		}

		if (!mindustryMappedJar.exists() || !getIntermediaryJar().exists() || isRefreshDeps()) {
			if (mindustryMappedJar.exists()) {
				mindustryMappedJar.delete();
			}

			mindustryMappedJar.getParentFile().mkdirs();

			if (mindustryIntermediaryJar.exists()) {
				mindustryIntermediaryJar.delete();
			}

			try {
				mapMindustryJar();
			} catch (Throwable t) {
				// Cleanup some some things that may be in a bad state now
				mindustryMappedJar.delete();
				mindustryIntermediaryJar.delete();
				getExtension().getMappingsProvider().cleanFiles();
				throw new RuntimeException("Failed to remap mindustry", t);
			}
		}

		if (!mindustryMappedJar.exists()) {
			throw new RuntimeException("mapped jar not found");
		}

		addDependencies(dependency, postPopulationScheduler);
	}

	private void mapMindustryJar() throws IOException {
		String fromM = "official";

		MappingsProvider mappingsProvider = getExtension().getMappingsProvider();

		Path input = mindustryProvider.getMergedJar().toPath();
		Path outputMapped = mindustryMappedJar.toPath();
		Path outputIntermediary = mindustryIntermediaryJar.toPath();

		for (String toM : Arrays.asList("named", "intermediary")) {
			Path output = "named".equals(toM) ? outputMapped : outputIntermediary;

			getProject().getLogger().lifecycle(":remapping mindustry (TinyRemapper, " + fromM + " -> " + toM + ")");

			TinyRemapper remapper = getTinyRemapper(fromM, toM);

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
				outputConsumer.addNonClassFiles(input);
				remapper.readClassPath(getRemapClasspath());
				remapper.readInputs(input);
				remapper.apply(outputConsumer);
			} catch (Exception e) {
				throw new RuntimeException("Failed to remap JAR " + input + " with mappings from " + mappingsProvider.tinyMappings, e);
			} finally {
				remapper.finish();
			}
		}
	}

	public TinyRemapper getTinyRemapper(String fromM, String toM) throws IOException {
		return TinyRemapper.newRemapper()
				.withMappings(TinyRemapperMappingsHelper.create(getExtension().getMappingsProvider().getMappings(), fromM, toM, true))
				.withMappings(out -> JSR_TO_JETBRAINS.forEach(out::acceptClass))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.build();
	}

	public Path[] getRemapClasspath() {
		return getProject().getConfigurations().getByName(Constants.Configurations.MINDUSTRY_DEPENDENCIES).getFiles()
				.stream().map(File::toPath).toArray(Path[]::new);
	}

	protected void addDependencies(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) {
		getProject().getRepositories().flatDir(repository -> repository.dir(getJarDirectory(getExtension().getUserCache(), "mapped")));

		getProject().getDependencies().add(Constants.Configurations.MINDUSTRY_NAMED,
				getProject().getDependencies().module("mindustry:mindustry:" + getJarVersionString("mapped")));
	}

	public void initFiles(MindustryProvider mindustryProvider, MappingsProvider mappingsProvider) {
		this.mindustryProvider = mindustryProvider;
		mindustryIntermediaryJar = new File(getExtension().getUserCache(), "mindustry-" + getJarVersionString("intermediary") + ".jar");
		mindustryMappedJar = new File(getJarDirectory(getExtension().getUserCache(), "mapped"), "mindustry-" + getJarVersionString("mapped") + ".jar");
	}

	protected File getJarDirectory(File parentDirectory, String type) {
		return new File(parentDirectory, getJarVersionString(type));
	}

	protected String getJarVersionString(String type) {
		return String.format("%s-%s-%s-%s", mindustryProvider.getVersion(), type, getExtension().getMappingsProvider().mappingsName, getExtension().getMappingsProvider().mappingsVersion);
	}

	public File getIntermediaryJar() {
		return mindustryIntermediaryJar;
	}

	public File getMappedJar() {
		return mindustryMappedJar;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MINDUSTRY_NAMED;
	}
}
