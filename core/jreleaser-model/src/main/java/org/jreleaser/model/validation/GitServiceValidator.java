/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2020-2021 The JReleaser authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jreleaser.model.validation;

import org.jreleaser.model.Active;
import org.jreleaser.model.Changelog;
import org.jreleaser.model.GenericGit;
import org.jreleaser.model.GitService;
import org.jreleaser.model.JReleaserContext;
import org.jreleaser.model.JReleaserModel;
import org.jreleaser.model.Project;
import org.jreleaser.model.UpdateSection;
import org.jreleaser.util.Errors;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static java.lang.System.lineSeparator;
import static java.util.stream.Collectors.groupingBy;
import static org.jreleaser.model.GitService.BRANCH;
import static org.jreleaser.model.GitService.OVERWRITE;
import static org.jreleaser.model.GitService.RELEASE_NAME;
import static org.jreleaser.model.GitService.SKIP_RELEASE;
import static org.jreleaser.model.GitService.SKIP_TAG;
import static org.jreleaser.model.GitService.TAG_NAME;
import static org.jreleaser.model.GitService.UPDATE;
import static org.jreleaser.model.Milestone.MILESTONE_NAME;
import static org.jreleaser.util.StringUtils.isBlank;
import static org.jreleaser.util.StringUtils.isNotBlank;

/**
 * @author Andres Almiray
 * @since 0.1.0
 */
public abstract class GitServiceValidator extends Validator {
    private static final String DEFAULT_CHANGELOG_TPL = "src/jreleaser/templates/changelog.tpl";

    public static void validateGitService(JReleaserContext context, JReleaserContext.Mode mode, GitService service, Errors errors) {
        JReleaserModel model = context.getModel();
        Project project = model.getProject();

        if (!service.isEnabledSet()) {
            service.setEnabled(true);
        }
        if (isBlank(service.getOwner()) && !(service instanceof GenericGit)) {
            errors.configuration(service.getServiceName() + ".owner must not be blank");
        }
        if (isBlank(service.getName())) {
            service.setName(project.getName());
        }

        service.setUsername(
            checkProperty(context,
                service.getServiceName().toUpperCase() + "_USERNAME",
                service.getServiceName() + ".username",
                service.getUsername(),
                service.getOwner()));

        service.setToken(
            checkProperty(context,
                service.getServiceName().toUpperCase() + "_TOKEN",
                service.getServiceName() + ".token",
                service.getToken(),
                errors));

        service.setTagName(
            checkProperty(context,
                TAG_NAME,
                service.getServiceName() + ".tagName",
                service.getTagName(),
                "v{{projectVersion}}"));

        if (service.isReleaseSupported()) {
            service.setReleaseName(
                checkProperty(context,
                    RELEASE_NAME,
                    service.getServiceName() + ".releaseName",
                    service.getReleaseName(),
                    "Release {{tagName}}"));
        }

        service.setBranch(
            checkProperty(context,
                BRANCH,
                service.getServiceName() + ".branch",
                service.getBranch(),
                "main"));

        if (!service.isOverwriteSet()) {
            service.setOverwrite(
                checkProperty(context,
                    OVERWRITE,
                    service.getServiceName() + ".overwrite",
                    null,
                    false));
        }

        if (service.isReleaseSupported()) {
            if (!service.isUpdateSet()) {
                service.setUpdate(
                    checkProperty(context,
                        UPDATE,
                        service.getServiceName() + ".update",
                        null,
                        false));
            }

            if (service.isUpdate() && service.getUpdateSections().isEmpty()) {
                service.getUpdateSections().add(UpdateSection.ASSETS);
            }
        }

        if (!service.isSkipTagSet()) {
            service.setSkipTag(
                checkProperty(context,
                    SKIP_TAG,
                    service.getServiceName() + ".skipTag",
                    null,
                    false));
        }

        if (!service.isSkipReleaseSet()) {
            service.setSkipRelease(
                checkProperty(context,
                    SKIP_RELEASE,
                    service.getServiceName() + ".skipRelease",
                    null,
                    false));
        }

        if (isBlank(service.getTagName())) {
            service.setTagName("v" + project.getVersion());
        }
        if (service.isReleaseSupported()) {
            if (isBlank(service.getReleaseName())) {
                service.setReleaseName("Release {{ tagName }}");
            }
        }
        if (!service.getChangelog().isEnabledSet()) {
            service.getChangelog().setEnabled(true);
        }
        if (isBlank(service.getCommitAuthor().getName())) {
            service.getCommitAuthor().setName("jreleaserbot");
        }
        if (isBlank(service.getCommitAuthor().getEmail())) {
            service.getCommitAuthor().setEmail("jreleaser@kordamp.org");
        }

        validateTimeout(service);

        if (service.isReleaseSupported()) {
            // milestone
            service.getMilestone().setName(
                checkProperty(context,
                    MILESTONE_NAME,
                    service.getServiceName() + ".milestone.name",
                    service.getMilestone().getName(),
                    "{{tagName}}"));
        }

        // eager resolve
        service.getResolvedTagName(context.getModel());
        if (service.isReleaseSupported()) {
            service.getResolvedReleaseName(context.getModel());
            service.getMilestone().getResolvedName(service.props(context.getModel()));
        }

        if (project.isSnapshot()) {
            service.getChangelog().setEnabled(true);
            service.getChangelog().setExternal(null);
            service.getChangelog().setSort(Changelog.Sort.DESC);
            if (service.isReleaseSupported()) {
                service.setOverwrite(true);
            }
        }

        if (mode != JReleaserContext.Mode.ASSEMBLE) {
            if (service.isSign() && !model.getSigning().isEnabled()) {
                if (context.isDryrun()) {
                    service.setSign(false);
                } else {
                    errors.configuration(service.getServiceName() + ".sign is set to `true` but signing is not enabled");
                }
            }

            validateChangelog(context, service, errors);
        }
    }

    private static void validateChangelog(JReleaserContext context, GitService service, Errors errors) {
        Changelog changelog = service.getChangelog();

        if (isNotBlank(changelog.getExternal())) {
            changelog.setFormatted(Active.NEVER);
        }

        if (!changelog.resolveFormatted(context.getModel().getProject())) return;

        if (isBlank(changelog.getFormat())) {
            changelog.setFormat("- {{commitShortHash}} {{commitTitle}} ({{commitAuthor}})");
        }

        if (isBlank(changelog.getContent()) && isBlank(changelog.getContentTemplate())) {
            if (Files.exists(context.getBasedir().resolve(DEFAULT_CHANGELOG_TPL))) {
                changelog.setContentTemplate(DEFAULT_CHANGELOG_TPL);
            } else {
                changelog.setContent(lineSeparator() + "## Changelog" +
                    lineSeparator() + lineSeparator() + "{{changelogChanges}}" +
                    lineSeparator() + "    {{changelogContributors}}");
            }
        }

        if (isNotBlank(changelog.getContentTemplate()) &&
            !Files.exists(context.getBasedir().resolve(changelog.getContentTemplate().trim()))) {
            errors.configuration("changelog.contentTemplate does not exist. " + changelog.getContentTemplate());
        }

        if (changelog.getCategories().isEmpty()) {
            changelog.getCategories().add(Changelog.Category.of("\uD83D\uDE80 Features", "feature", "enhancement"));
            changelog.getCategories().add(Changelog.Category.of("\uD83D\uDC1B Bug Fixes", "bug", "fix"));
        } else {
            int i = 0;
            for (Changelog.Category category : changelog.getCategories()) {
                if (isBlank(category.getTitle())) {
                    errors.configuration(service.getServiceName() + ".changelog.categories[" + i + "].title is missing");
                }
                if (category.getLabels().isEmpty()) {
                    errors.configuration(service.getServiceName() + ".changelog.categories[" + i + "].labels are missing");
                }

                i++;
            }

            // validate category.title is unique
            Map<String, List<Changelog.Category>> byTitle = changelog.getCategories().stream()
                .collect(groupingBy(Changelog.Category::getTitle));
            byTitle.forEach((title, categories) -> {
                if (categories.size() > 1) {
                    errors.configuration(service.getServiceName() + ".changelog has more than one category with title: " + title);
                }
            });
        }

        if (!changelog.getLabelers().isEmpty()) {
            int i = 0;
            for (Changelog.Labeler labeler : changelog.getLabelers()) {
                if (isBlank(labeler.getLabel())) {
                    errors.configuration(service.getServiceName() + ".changelog.labelers[" + i + "].label is missing");
                }
                if (isBlank(labeler.getTitle()) && isBlank(labeler.getBody())) {
                    errors.configuration(service.getServiceName() + ".changelog.labelers[" + i + "] title or body is required");
                }

                i++;
            }
        }

        if (!changelog.getReplacers().isEmpty()) {
            int i = 0;
            for (Changelog.Replacer replacer : changelog.getReplacers()) {
                if (isBlank(replacer.getSearch())) {
                    errors.configuration(service.getServiceName() + ".changelog.replacers[" + i + "].search is missing");
                }
                if (null == replacer.getReplace()) {
                    errors.configuration(service.getServiceName() + ".changelog.replacers[" + i + "].replace is missing");
                }

                i++;
            }
        }

        if (!changelog.getContributors().isEnabledSet()) {
            changelog.getContributors().setEnabled(true);
        }
    }
}
