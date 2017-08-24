/*
 * Copyright 2017 the original author or authors.
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
package org.gradle.util

import org.gradle.api.DefaultTask
import org.gradle.api.internal.ClassGenerator
import org.gradle.api.internal.project.taskfactory.TaskFactory
import org.gradle.initialization.DefaultProjectDescriptor
import org.gradle.initialization.ProjectDescriptorRegistry
import org.gradle.internal.featurelifecycle.DeprecatedFeatureUsage
import org.gradle.internal.featurelifecycle.LoggingDeprecatedFeatureHandler
import org.gradle.internal.reflect.Instantiator
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

@Subject(NameValidator)
class NameValidatorTest extends Specification {
    static forbiddenCharacters = NameValidator.FORBIDDEN_CHARACTERS
    static forbiddenLeadingAndTrailingCharacter = NameValidator.FORBIDDEN_LEADING_AND_TRAILING_CHARACTER
    static invalidNames = forbiddenCharacters.collect { "a${it}b"} + ["${forbiddenLeadingAndTrailingCharacter}ab", "ab${forbiddenLeadingAndTrailingCharacter}"]

    def loggingDeprecatedFeatureHandler = Mock(LoggingDeprecatedFeatureHandler)

    def setup() {
        SingleMessageLogger.handler = loggingDeprecatedFeatureHandler
    }

    def cleanup() {
        SingleMessageLogger.reset()
    }

    @Unroll
    def "projects are not allowed to be named '#name'"() {
        when:
        new DefaultProjectDescriptor(null, name, null, Mock(ProjectDescriptorRegistry), null)

        then:
        1 * loggingDeprecatedFeatureHandler.deprecatedFeatureUsed(_  as DeprecatedFeatureUsage) >> { DeprecatedFeatureUsage usage ->
            assertForbidden(name, usage.message)
        }

        where:
        name << invalidNames
    }

    @Unroll
    def "tasks are not allowed to be named '#name'"() {
        when:
        new TaskFactory(Mock(ClassGenerator), null, Mock(Instantiator)).create(name, DefaultTask)

        then:
        1 * loggingDeprecatedFeatureHandler.deprecatedFeatureUsed(_  as DeprecatedFeatureUsage) >> { DeprecatedFeatureUsage usage ->
            assertForbidden(name, usage.message)
        }

        where:
        name << invalidNames
    }

    private assertForbidden(name, message) {
        assert message == """The name '${name}' contains at least one of the following characters: [ , /, \\, :, <, >, ", ?, *]. This has been deprecated and is scheduled to be removed in Gradle 5.0""" ||
            message == """The name '${name}' starts or ends with a '.'. This has been deprecated and is scheduled to be removed in Gradle 5.0"""
    }
}
