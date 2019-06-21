/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.initialization;

import org.gradle.StartParameter;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.initialization.buildsrc.BuildSourceBuilder;

/**
 * Handles locating and processing setting.gradle files.  Also deals with the buildSrc module, since that modules is
 * found after settings is located, but needs to be built before settings is processed.
 */
public class DefaultSettingsLoader implements SettingsLoader {
    private final ISettingsFinder settingsFinder;
    private final SettingsProcessor settingsProcessor;
    private final BuildSourceBuilder buildSourceBuilder;
    private final IGradlePropertiesLoader propertiesLoader;
    private final InitScriptHandler initScriptHandler;

    public DefaultSettingsLoader(ISettingsFinder settingsFinder,
                                 SettingsProcessor settingsProcessor,
                                 BuildSourceBuilder buildSourceBuilder,
                                 IGradlePropertiesLoader propertiesLoader, InitScriptHandler initScriptHandler) {
        this.settingsFinder = settingsFinder;
        this.settingsProcessor = settingsProcessor;
        this.buildSourceBuilder = buildSourceBuilder;
        this.propertiesLoader = propertiesLoader;
        this.initScriptHandler = initScriptHandler;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        StartParameter startParameter = gradle.getStartParameter();
        SettingsLocation settingsLocation = findSettings(startParameter);

        ProjectSpec spec = ProjectSpecs.forStartParameter(startParameter, settingsLocation);
        final SettingsInternal settings;
        if (useEmptySettings(spec, settingsLocation, startParameter)) {
            settings = createEmptySettings(gradle, settingsLocation, startParameter);
        } else {
            settings = findSettingsAndLoadIfAppropriate(gradle, settingsLocation, startParameter);
        }

        setDefaultProject(spec, settings);
        return settings;
    }

    private boolean useEmptySettings(ProjectSpec spec, SettingsLocation settingsLocation, StartParameter startParameter) {
        // Never use empty settings when the settings were explicitly set
        if (startParameter.getSettingsFile() != null) {
            return false;
        }

        // Use an empty settings for a target build file located in the same directory as the settings file.
        if (startParameter.getProjectDir() != null && settingsLocation.getSettingsDir().equals(startParameter.getProjectDir())) {
            return true;
        }

        return false;
    }

    private SettingsInternal createEmptySettings(GradleInternal gradle, SettingsLocation settingsLocation, StartParameter startParameter) {
        StartParameter noSearchParameter = startParameter.newInstance();
        noSearchParameter.useEmptySettings();
        SettingsInternal settings = findSettingsAndLoadIfAppropriate(gradle, settingsLocation, noSearchParameter);

        // Set explicit build file, if required
        if (noSearchParameter.getBuildFile() != null) {
            ProjectDescriptor rootProject = settings.getRootProject();
            rootProject.setBuildFileName(noSearchParameter.getBuildFile().getName());
        }
        return settings;
    }

    private void setDefaultProject(ProjectSpec spec, SettingsInternal settings) {
        settings.setDefaultProject(spec.selectProject(settings.getSettingsScript().getDisplayName(), settings.getProjectRegistry()));
    }

    /**
     * Finds the settings.gradle for the given startParameter, and loads it if contains the project selected by the
     * startParameter, or if the startParameter explicitly specifies a settings script.  If the settings file is not
     * loaded (executed), then a null is returned.
     */
    private SettingsInternal findSettingsAndLoadIfAppropriate(GradleInternal gradle,
                                                              SettingsLocation settingsLocation,
                                                              StartParameter startParameter) {
        // We found the desired settings file, now build the associated buildSrc before loading settings.  This allows
        // the settings script to reference classes in the buildSrc.
        ClassLoaderScope buildSourceClassLoaderScope = buildSourceBuilder.buildAndCreateClassLoader(settingsLocation.getSettingsDir(), startParameter);

        propertiesLoader.loadProperties(settingsLocation.getSettingsDir());
        initScriptHandler.executeScripts(gradle);

        return settingsProcessor.process(gradle, settingsLocation, buildSourceClassLoaderScope, startParameter);
    }

    private SettingsLocation findSettings(StartParameter startParameter) {
        return settingsFinder.find(startParameter);
    }
}

