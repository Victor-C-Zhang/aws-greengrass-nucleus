/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.packagemanager.plugins.ArtifactDownloader;
import com.aws.iot.evergreen.packagemanager.plugins.GreengrassRepositoryDownloader;
import com.aws.iot.evergreen.util.Coerce;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.inject.Inject;

@NoArgsConstructor // for dependency injection
public class PackageManager implements InjectionActions {
    private static final Logger logger = LogManager.getLogger(PackageManager.class);
    private static final String GREENGRASS_SCHEME = "GREENGRASS";
    private static final String VERSION_KEY = "version";
    private static final String PACKAGE_NAME_KEY = "packageName";

    private GreengrassRepositoryDownloader greengrassArtifactDownloader;

    private GreengrassPackageServiceHelper greengrassPackageServiceHelper;

    private ExecutorService executorService;

    private PackageStore packageStore;

    private Kernel kernel;

    /**
     * PackageManager constructor.
     *
     * @param greengrassArtifactDownloader   greengrassArtifactDownloader
     * @param greengrassPackageServiceHelper greengrassPackageServiceHelper
     * @param executorService                executorService
     * @param packageStore                   packageStore
     * @param kernel                         kernel
     */
    @Inject
    public PackageManager(GreengrassRepositoryDownloader greengrassArtifactDownloader,
                          GreengrassPackageServiceHelper greengrassPackageServiceHelper,
                          ExecutorService executorService, PackageStore packageStore, Kernel kernel) {
        this.greengrassArtifactDownloader = greengrassArtifactDownloader;
        this.greengrassPackageServiceHelper = greengrassPackageServiceHelper;
        this.executorService = executorService;
        this.packageStore = packageStore;
        this.kernel = kernel;
    }

    /**
     * List the package metadata for available package versions that satisfy the requirement.
     * It is ordered by the active version first if found, followed by available versions locally.
     *
     * @param packageName        the package name
     * @param versionRequirement the version requirement for this package
     * @return an iterator of PackageMetadata, with the active version first if found, followed by available versions
     *     locally.
     * @throws PackagingException if fails when trying to list available package metadata
     */
    Iterator<PackageMetadata> listAvailablePackageMetadata(String packageName, Requirement versionRequirement)
            throws PackagingException {
        // TODO Switch to customized Iterator to enable lazy iteration

        // 1. Find the version if this package is currently active with some version and it is satisfied by requirement
        Optional<PackageMetadata> optionalActivePackageMetadata =
                findActiveAndSatisfiedPackageMetadata(packageName, versionRequirement);

        // 2. list available packages locally
        List<PackageMetadata> packageMetadataList =
                packageStore.listAvailablePackageMetadata(packageName, versionRequirement);

        // 3. If the active satisfied version presents, set it as the head of list.
        if (optionalActivePackageMetadata.isPresent()) {
            PackageMetadata activePackageMetadata = optionalActivePackageMetadata.get();

            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, packageName)
                    .addKeyValue(VERSION_KEY, activePackageMetadata.getPackageIdentifier().getVersion())
                    .log("Found active version for dependency package and it is satisfied by the version requirement."
                            + " Setting it as the head of the available package list.");

            packageMetadataList.remove(activePackageMetadata);
            packageMetadataList.add(0, activePackageMetadata);

        }

        // TODO 4. list available packages from cloud when cloud SDK is ready.

        logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, packageName)
                .addKeyValue("packageMetadataList", packageMetadataList)
                .log("Found possible versions for dependency package");
        return packageMetadataList.iterator();
    }

    /**
     * Make sure all the specified packages exist in the package cache. Download them from remote repository if
     * they don't exist.
     *
     * @param pkgIds a list of packages.
     * @return a future to notify once this is finished.
     */
    public Future<Void> preparePackages(List<PackageIdentifier> pkgIds) {
        return executorService.submit(() -> {
            for (PackageIdentifier packageIdentifier : pkgIds) {
                preparePackage(packageIdentifier);
            }
            return null;
        });
    }

    private void preparePackage(PackageIdentifier packageIdentifier)
            throws PackageLoadingException, PackageDownloadException {
        logger.atInfo().setEventType("prepare-package-start").addKeyValue("packageIdentifier", packageIdentifier).log();
        try {
            PackageRecipe pkg = findRecipeDownloadIfNotExisted(packageIdentifier);
            List<URI> artifactURIList = pkg.getArtifacts().stream().map(artifactStr -> {
                try {
                    return new URI(artifactStr);
                } catch (URISyntaxException e) {
                    String message = String.format("artifact URI %s is invalid", artifactStr);
                    logger.atError().setCause(e).log(message);
                    throw new RuntimeException(message, e);
                }
            }).collect(Collectors.toList());
            downloadArtifactsIfNecessary(packageIdentifier, artifactURIList);
            logger.atInfo().setEventType("prepare-package-finished").addKeyValue("packageIdentifier", packageIdentifier)
                    .log();
        } catch (PackageLoadingException | PackageDownloadException e) {
            logger.atError().setCause(e).log(String.format("Failed to prepare package %s", packageIdentifier));
            throw e;
        }
    }

    private PackageRecipe findRecipeDownloadIfNotExisted(PackageIdentifier packageIdentifier)
            throws PackageDownloadException, PackageLoadingException {
        Optional<PackageRecipe> packageOptional = Optional.empty();
        try {
            packageOptional = packageStore.findPackageRecipe(packageIdentifier);
        } catch (PackageLoadingException e) {
            logger.atWarn().log(String.format("Failed to load package recipe for %s", packageIdentifier), e);
        }
        if (packageOptional.isPresent()) {
            return packageOptional.get();
        } else {
            PackageRecipe packageRecipe = greengrassPackageServiceHelper.downloadPackageRecipe(packageIdentifier);
            packageStore.savePackageRecipe(packageRecipe);
            return packageRecipe;
        }
    }

    void downloadArtifactsIfNecessary(PackageIdentifier packageIdentifier, List<URI> artifactList)
            throws PackageLoadingException, PackageDownloadException {
        Path packageArtifactDirectory = packageStore.resolveArtifactDirectoryPath(packageIdentifier);
        if (!Files.exists(packageArtifactDirectory) || !Files.isDirectory(packageArtifactDirectory)) {
            try {
                Files.createDirectories(packageArtifactDirectory);
            } catch (IOException e) {
                throw new PackageLoadingException(
                        String.format("Failed to create package artifact cache directory %s", packageArtifactDirectory),
                        e);
            }
        }

        List<URI> artifactsNeedToDownload = determineArtifactsNeedToDownload(packageArtifactDirectory, artifactList);
        logger.atDebug().setEventType("downloading-package-artifacts")
                .addKeyValue("packageIdentifier", packageIdentifier)
                .addKeyValue("artifactsNeedToDownload", artifactsNeedToDownload).log();

        for (URI artifact : artifactsNeedToDownload) {
            ArtifactDownloader downloader = selectArtifactDownloader(artifact);
            try {
                downloader.downloadToPath(packageIdentifier, artifact, packageArtifactDirectory);
            } catch (IOException e) {
                throw new PackageDownloadException(
                        String.format("Failed to download package %s artifact %s", packageIdentifier, artifact), e);
            }
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private List<URI> determineArtifactsNeedToDownload(Path packageArtifactDirectory, List<URI> artifacts) {
        //TODO implement proper idempotency logic to determine what artifacts need to download
        return artifacts;
    }

    private ArtifactDownloader selectArtifactDownloader(URI artifactUri) throws PackageLoadingException {
        String scheme = artifactUri.getScheme() == null ? null : artifactUri.getScheme().toUpperCase();
        if (GREENGRASS_SCHEME.equals(scheme)) {
            return greengrassArtifactDownloader;
        }

        throw new PackageLoadingException(String.format("artifact URI scheme %s is not supported yet", scheme));
    }

    /**
     * Find the active version for a package.
     *
     * @param packageName the package name
     * @return Optional of version; Empty if no active version for this package found.
     */
    private Optional<Semver> findActiveVersion(final String packageName) {
        EvergreenService service;
        try {
            service = kernel.locate(packageName);
        } catch (ServiceLoadException e) {
            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, packageName)
                    .log("Didn't find a active service for this package running in the kernel.");
            return Optional.empty();
        }
        return Optional.of(getPackageVersionFromService(service));
    }

    /**
     * Get the package version from the active Evergreen service.
     *
     * @param service the active evergreen service
     * @return the package version from the active Evergreen service
     */
    Semver getPackageVersionFromService(final EvergreenService service) {
        Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
        //TODO handle null case
        return new Semver(Coerce.toString(versionTopic));
    }

    /**
     * Find the package metadata for a package if it's active version satisfies the requirement.
     *
     * @param packageName the package name
     * @param requirement the version requirement
     * @return Optional of the package metadata for the package; empty if this package doesn't have active version or
     *     the active version doesn't satisfy the requirement.
     * @throws PackagingException if fails to find the target recipe or parse the recipe
     */
    private Optional<PackageMetadata> findActiveAndSatisfiedPackageMetadata(String packageName, Requirement requirement)
            throws PackagingException {
        Optional<Semver> activeVersionOptional = findActiveVersion(packageName);

        if (!activeVersionOptional.isPresent()) {
            return Optional.empty();
        }

        Semver activeVersion = activeVersionOptional.get();

        if (!requirement.isSatisfiedBy(activeVersion)) {
            return Optional.empty();
        }

        return Optional.of(packageStore.getPackageMetadata(new PackageIdentifier(packageName, activeVersion)));
    }
}
