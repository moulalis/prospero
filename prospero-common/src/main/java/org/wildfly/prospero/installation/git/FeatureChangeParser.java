/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.installation.git;

import org.apache.commons.lang3.StringUtils;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.xml.ProvisioningXmlParser;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.Diff;
import org.wildfly.prospero.api.FeatureChange;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.wildfly.prospero.api.FeatureChange.Type.FEATURE;
import static org.wildfly.prospero.api.FeatureChange.Type.LAYERS;
import static org.wildfly.prospero.api.FeatureChange.Type.CONFIG;

/**
 * Generates a {@code Diff} of recorded provisioning state changes.
 */
class FeatureChangeParser implements GitStorage.Parser<FeatureChange> {
    @Override
    public List<FeatureChange> parse(Path changed, Path base) throws IOException, MetadataException {
        final List<FeatureChange> featureChanges = new ArrayList<>();
        final ProvisioningConfig newConfig;
        final ProvisioningConfig oldConfig;
        try {
            newConfig = parseProvisioningConfig(changed);
            oldConfig = parseProvisioningConfig(base);
        } catch (ProvisioningException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToParseConfiguration(changed.resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML), e);
        }

        final Set<String> oldFeatureNames = oldConfig.getFeaturePackDeps().stream().map(c -> c.getLocation().toString()).collect(Collectors.toSet());
        final Set<String> newFeatureNames = newConfig.getFeaturePackDeps().stream().map(c -> c.getLocation().toString()).collect(Collectors.toSet());

        final Set<String> addedFeatureNames = new HashSet<>(newFeatureNames);
        addedFeatureNames.removeAll(oldFeatureNames);
        for (String addedFeatureName : addedFeatureNames) {
            featureChanges.add(new FeatureChange(FEATURE, addedFeatureName, Diff.Status.ADDED));
        }

        final Set<String> removedFeatureNames = new HashSet<>(oldFeatureNames);
        removedFeatureNames.removeAll(newFeatureNames);
        for (String removedFeatureName : removedFeatureNames) {
            featureChanges.add(new FeatureChange(FEATURE, removedFeatureName, Diff.Status.REMOVED));
        }

        final Set<ConfigId> oldConfigs = oldConfig.getDefinedConfigs().stream().map(ConfigModel::getId).collect(Collectors.toSet());
        final Set<ConfigId> newConfigs = newConfig.getDefinedConfigs().stream().map(ConfigModel::getId).collect(Collectors.toSet());

        final Set<ConfigId> modifiedConfigs = new HashSet<>(newConfigs);
        modifiedConfigs.retainAll(oldConfigs);
        for (ConfigId cfg : modifiedConfigs) {
            createConfigModelDiff(newConfig, oldConfig, cfg).map(featureChanges::add);
        }

        final Set<ConfigId> addedConfigs = new HashSet<>(newConfigs);
        addedConfigs.removeAll(oldConfigs);

        for (ConfigId cfg : addedConfigs) {
            createConfigModelDiff(newConfig, null, cfg).map(featureChanges::add);
        }

        final Set<ConfigId> removedConfigs = new HashSet<>(oldConfigs);
        removedConfigs.removeAll(newConfigs);

        for (ConfigId cfg : removedConfigs) {
            createConfigModelDiff(null, oldConfig, cfg).map(featureChanges::add);
        }


        return featureChanges;
    }

    private static ProvisioningConfig parseProvisioningConfig(Path changed) throws ProvisioningException {
        if (changed == null || !Files.exists(changed.resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML))) {
            return ProvisioningConfig.builder().build();
        } else {
            return ProvisioningXmlParser.parse(changed.resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML));
        }
    }

    private static Optional<FeatureChange> createConfigModelDiff(ProvisioningConfig newConfig, ProvisioningConfig oldConfig, ConfigId cfg) {
        final List<FeatureChange> configChanges = new ArrayList<>();
        final ConfigModel oldCfgModel = oldConfig == null?null:oldConfig.getDefinedConfig(cfg);
        final ConfigModel newCfgModel = newConfig == null?null:newConfig.getDefinedConfig(cfg);
        final Set<String> oldLayers = oldCfgModel == null? Collections.emptySet():oldCfgModel.getIncludedLayers();
        final Set<String> newLayers = newCfgModel == null? Collections.emptySet():newCfgModel.getIncludedLayers();

        if (oldLayers.equals(newLayers)) {
            return Optional.empty();
        }

        final String oldConfigId = oldCfgModel==null ? null : oldCfgModel.getModel()+":"+oldCfgModel.getName();
        final String newConfigId = newCfgModel==null ? null : newCfgModel.getModel()+":"+newCfgModel.getName();

        configChanges.add(new FeatureChange(LAYERS, serialize(oldLayers), serialize(newLayers)));

        final Diff.Status status;
        if (newConfig == null) {
            status = Diff.Status.REMOVED;
        } else if (oldConfig == null) {
            status = Diff.Status.ADDED;
        } else {
            status=Diff.Status.MODIFIED;
        }
        return Optional.of(new FeatureChange(CONFIG, oldConfigId==null?newConfigId:oldConfigId, status, configChanges.toArray(new FeatureChange[]{})));
    }

    private static String serialize(Set<String> oldLayers) {
        if (oldLayers.isEmpty()) {
            return null;
        } else {
            return StringUtils.join(oldLayers, ", ");
        }
    }
}
