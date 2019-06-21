/*
 * Copyright 2014 the original author or authors.
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

import java.io.File;

class ProjectSpecs {

    static ProjectSpec forStartParameter(StartParameter startParameter, SettingsLocation settingsLocation) {
        File explicitProjectDir = startParameter.getProjectDir();
        File explicitBuildFile = startParameter.getBuildFile();
        if (explicitBuildFile != null) {
            return new BuildFileProjectSpec(explicitBuildFile);
        }
        if (explicitProjectDir != null) {
            return new ProjectDirectoryProjectSpec(explicitProjectDir);
        }
        return new CurrentDirectoryProjectSpec(startParameter.getCurrentDir(), settingsLocation);
    }
}
