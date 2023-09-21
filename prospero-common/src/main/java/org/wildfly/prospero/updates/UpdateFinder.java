/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.updates;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.VersionResult;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class UpdateFinder implements AutoCloseable {

    public static final int UPDATES_SEARCH_PARALLELISM = 10;

    private final ChannelSession channelSession;
    private final ExecutorService executorService;

    public UpdateFinder(ChannelSession channelSession) {
        this.channelSession = channelSession;
        this.executorService = Executors.newWorkStealingPool(UPDATES_SEARCH_PARALLELISM);
    }

    public UpdateSet findUpdates(List<Artifact> artifacts) throws ArtifactResolutionException {
        // use parallel executor to speed up the artifact resolution
        List<CompletableFuture<Optional<ArtifactChange>>> allPackages = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            final CompletableFuture<Optional<ArtifactChange>> cf = new CompletableFuture<>();
            executorService.submit(() -> {
                try {
                    final Optional<ArtifactChange> found = findUpdates(artifact);
                    cf.complete(found);
                } catch (Exception e) {
                    cf.completeExceptionally(e);
                }
            });
            allPackages.add(cf);
        }

        try {
            CompletableFuture.allOf(allPackages.toArray(new CompletableFuture[]{})).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ArtifactResolutionException) {
                throw (ArtifactResolutionException) e.getCause();
            } else {
                throw e;
            }
        }

        final List<ArtifactChange> updates = allPackages.stream()
                .map(cf ->cf.getNow(Optional.empty()))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());

        return new UpdateSet(updates);
    }

    private Optional<ArtifactChange> findUpdates(Artifact artifact) throws ArtifactResolutionException {

        final String latestVersion;
        final Optional<String> channelName;
        try {
            VersionResult versionResult = channelSession.findLatestMavenArtifactVersion(artifact.getGroupId(),
                    artifact.getArtifactId(), artifact.getExtension(), artifact.getClassifier(), null);
            latestVersion = versionResult.getVersion();
            channelName = versionResult.getChannelName();

        } catch (UnresolvedMavenArtifactException e) {
            return Optional.of(ArtifactChange.removed(artifact));
        }
        final Artifact latest = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getExtension(), latestVersion);

        if (latestVersion == null || latest.getVersion().equals(artifact.getVersion())) {
            return Optional.empty();
        } else if (channelName == null || channelName.isEmpty()) {
            return Optional.of(ArtifactChange.updated(artifact, latest));
        }else {
            return Optional.of(ArtifactChange.updated(artifact, latest, channelName.get()));
        }
    }

    @Override
    public void close() {
        this.executorService.shutdown();
    }

}
