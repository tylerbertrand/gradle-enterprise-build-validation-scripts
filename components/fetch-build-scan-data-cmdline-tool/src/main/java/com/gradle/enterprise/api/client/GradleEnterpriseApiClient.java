package com.gradle.enterprise.api.client;

import com.gradle.enterprise.api.model.*;
import com.gradle.enterprise.cli.ConsoleLogger;
import com.gradle.enterprise.loader.BuildScanDataLoader;
import com.gradle.enterprise.loader.OfflineBuildScanDataLoader;
import com.gradle.enterprise.loader.OnlineBuildScanDataLoader;
import com.gradle.enterprise.model.BuildScanData;
import com.gradle.enterprise.model.CustomValueNames;
import com.gradle.enterprise.model.NumberedBuildScan;
import com.gradle.enterprise.model.TaskExecutionSummary;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.tls.HandshakeCertificates;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import static com.gradle.enterprise.api.model.GradleBuildCachePerformanceTaskExecutionEntry.AvoidanceOutcomeEnum.EXECUTED_CACHEABLE;
import static com.gradle.enterprise.api.model.GradleBuildCachePerformanceTaskExecutionEntry.AvoidanceOutcomeEnum.EXECUTED_NOT_CACHEABLE;
import static com.gradle.enterprise.api.model.GradleBuildCachePerformanceTaskExecutionEntry.NonCacheabilityCategoryEnum.DISABLED_TO_ENSURE_CORRECTNESS;
import static com.gradle.enterprise.api.model.GradleBuildCachePerformanceTaskExecutionEntry.NonCacheabilityCategoryEnum.OVERLAPPING_OUTPUTS;
import static com.gradle.enterprise.loader.BuildScanDataLoader.BuildToolType;

public class GradleEnterpriseApiClient {

    private final URL baseUrl;
    private final CustomValueNames customValueNames;
    private final BuildScanDataLoader buildScanDataLoader;

    private final Logger logger;

    public GradleEnterpriseApiClient(URL baseUrl, CustomValueNames customValueNames, Logger logger) {
        this.baseUrl = baseUrl;
        this.customValueNames = customValueNames;
        this.logger = logger;

        // todo this all goes away
        ApiClient client = new ApiClient();
        client.setHttpClient(configureHttpClient(client.getHttpClient()));
        client.setBasePath(baseUrl.toString());
        AuthenticationConfigurator.configureAuth(baseUrl, client, logger);

        this.buildScanDataLoader = baseUrl.getProtocol().equals("file")
                ? OfflineBuildScanDataLoader.newInstance(null) // todo
                : new OnlineBuildScanDataLoader();
    }

    private OkHttpClient configureHttpClient(OkHttpClient httpClient) {
        OkHttpClient.Builder httpClientBuilder = httpClient.newBuilder();

        configureSsl(httpClientBuilder);
        configureProxyAuthentication(httpClientBuilder);
        configureTimeouts(httpClientBuilder);

        return httpClientBuilder.build();
    }

    private void configureSsl(OkHttpClient.Builder httpClientBuilder) {
        HandshakeCertificates.Builder trustedCertsBuilder = new HandshakeCertificates.Builder()
            .addPlatformTrustedCertificates();

        if (allowUntrustedServer()) {
            trustedCertsBuilder.addInsecureHost(baseUrl.getHost());
            httpClientBuilder.hostnameVerifier((hostname, session) -> baseUrl.getHost().equals(hostname));
        }

        HandshakeCertificates trustedCerts = trustedCertsBuilder.build();
        httpClientBuilder.sslSocketFactory(trustedCerts.sslSocketFactory(), trustedCerts.trustManager());
    }

    private void configureProxyAuthentication(OkHttpClient.Builder httpClientBuilder) {
        httpClientBuilder
            .proxyAuthenticator((route, response) -> {
                if (response.code() == 407) {
                    String scheme = response.request().url().scheme().toLowerCase(Locale.ROOT);
                    String proxyUser = System.getProperty(scheme + ".proxyUser");
                    String proxyPassword = System.getProperty(scheme + ".proxyPassword");
                    if (proxyUser != null && proxyPassword != null) {
                        return response.request().newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword))
                            .build();
                    }
                }
                return null;
            });
    }

    private boolean allowUntrustedServer() {
        return Boolean.parseBoolean(System.getProperty("ssl.allowUntrustedServer"));
    }

    private void configureTimeouts(OkHttpClient.Builder httpClientBuilder) {
        Duration connectTimeout = parseTimeout("connect.timeout");
        if (connectTimeout != null) {
            httpClientBuilder.connectTimeout(connectTimeout);
        }
        Duration readTimeout = parseTimeout("read.timeout");
        if (readTimeout != null) {
            httpClientBuilder.readTimeout(readTimeout);
        }
    }

    private Duration parseTimeout(String key) {
        String value = System.getProperty(key);
        try {
            return value == null ? null : Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The value of " + key + " (\"" + value + "\") is not a valid duration.", e);
        }
    }

    public BuildScanData fetchBuildScanData(NumberedBuildScan buildScan) {
        int runNum = buildScan.runNum();
        String buildScanId = buildScan.buildScanId();
        try {
            BuildToolType buildToolType = buildScanDataLoader.determineBuildToolType(buildScan.uri());
            if (buildToolType.equals(BuildToolType.GRADLE)) {
                BuildScanDataLoader.Pair<GradleAttributes, GradleBuildCachePerformance> result =
                        buildScanDataLoader.loadDataForGradle(buildScan.uri());
                GradleAttributes attributes = result.first;
                GradleBuildCachePerformance performance = result.second;
                return new BuildScanData(
                        runNum,
                        attributes.getRootProjectName(),
                        buildScanId,
                        baseUrl,
                        findCustomValue(customValueNames.getGitRepositoryKey(), attributes.getValues()),
                        findCustomValue(customValueNames.getGitBranchKey(), attributes.getValues()),
                        findCustomValue(customValueNames.getGitCommitIdKey(), attributes.getValues()),
                        attributes.getRequestedTasks(),
                        buildOutcomeFrom(attributes),
                        remoteBuildCacheUrlFrom(performance),
                        summarizeTaskExecutions(performance),
                        buildTimeFrom(performance),
                        serializationFactorFrom(performance)
                );
            } else if (buildToolType.equals(BuildToolType.MAVEN)) {
                BuildScanDataLoader.Pair<MavenAttributes, MavenBuildCachePerformance> result =
                        buildScanDataLoader.loadDataForMaven(buildScan.uri());
                MavenAttributes attributes = result.first;
                MavenBuildCachePerformance performance = result.second;
                return new BuildScanData(
                        runNum,
                        attributes.getTopLevelProjectName(),
                        buildScanId,
                        baseUrl,
                        findCustomValue(customValueNames.getGitRepositoryKey(), attributes.getValues()),
                        findCustomValue(customValueNames.getGitBranchKey(), attributes.getValues()),
                        findCustomValue(customValueNames.getGitCommitIdKey(), attributes.getValues()),
                        attributes.getRequestedGoals(),
                        buildOutcomeFrom(attributes),
                        remoteBuildCacheUrlFrom(performance),
                        summarizeTaskExecutions(performance),
                        buildTimeFrom(performance),
                        serializationFactorFrom(performance)
                );
            } else {
                throw new RuntimeException(String.format("Build scan %s was generated by an unknown build agent: %s.", buildScan.url(), buildToolType));
            }
        } catch (RuntimeException e) { // todo fix nesting of exceptions
            throw e; // throw new FailedRequestException(buildScan.url(), e); // todo fix
        }
    }

    private static String findCustomValue(String key, List<BuildAttributesValue> values) {
        return values.stream()
            .filter(v -> v.getName().equals(key))
            .map(v -> {
                if (v.getValue() == null) {
                    return "";
                }
                return v.getValue();
            })
            .findFirst()
            .orElse("");
    }

    private static String buildOutcomeFrom(GradleAttributes attributes) {
        if(!attributes.getHasFailed()) {
            return "SUCCESS";
        }
        return "FAILED";
    }

    private static String buildOutcomeFrom(MavenAttributes attributes) {
        if(!attributes.getHasFailed()) {
            return "SUCCESS";
        }
        return "FAILED";
    }

    private static URL remoteBuildCacheUrlFrom(GradleBuildCachePerformance buildCachePerformance) {
        if (buildCachePerformance.getBuildCaches() == null ||
            buildCachePerformance.getBuildCaches().getRemote().getUrl() == null) {
            return null;
        }

        try {
            return new URL(buildCachePerformance.getBuildCaches().getRemote().getUrl());
        } catch (MalformedURLException e) {
            // Don't do anything on purpose.
            return null;
        }
    }

    private static URL remoteBuildCacheUrlFrom(MavenBuildCachePerformance buildCachePerformance) {
        if (buildCachePerformance.getBuildCaches() == null ||
            buildCachePerformance.getBuildCaches().getRemote() == null ||
            buildCachePerformance.getBuildCaches().getRemote().getUrl() == null) {
            return null;
        }

        try {
            return new URL(buildCachePerformance.getBuildCaches().getRemote().getUrl());
        } catch (MalformedURLException e) {
            // Don't do anything on purpose.
            return null;
        }
    }

    @NotNull
    private static Map<String, TaskExecutionSummary> summarizeTaskExecutions(GradleBuildCachePerformance buildCachePerformance) {
        Map<String, List<GradleBuildCachePerformanceTaskExecutionEntry>> tasksByOutcome = buildCachePerformance.getTaskExecution().stream()
            .collect(Collectors.groupingBy(GradleEnterpriseApiClient::avoidanceOutcome));

        Map<String, TaskExecutionSummary> summariesByOutcome = tasksByOutcome.entrySet()
            .stream()
            .map(GradleEnterpriseApiClient::summarizeForGradle)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Arrays.stream(GradleBuildCachePerformanceTaskExecutionEntry.AvoidanceOutcomeEnum.values())
            .forEach(outcome -> summariesByOutcome.putIfAbsent(outcome.toString(), TaskExecutionSummary.ZERO));

        return toTotalAvoidedFromCache(summariesByOutcome);
    }

    private static String avoidanceOutcome(GradleBuildCachePerformanceTaskExecutionEntry task) {
        GradleBuildCachePerformanceTaskExecutionEntry.AvoidanceOutcomeEnum avoidanceOutcome = task.getAvoidanceOutcome();
        GradleBuildCachePerformanceTaskExecutionEntry.NonCacheabilityCategoryEnum nonCacheabilityCategory = task.getNonCacheabilityCategory();
        if (avoidanceOutcome == EXECUTED_NOT_CACHEABLE && (nonCacheabilityCategory == OVERLAPPING_OUTPUTS || nonCacheabilityCategory == DISABLED_TO_ENSURE_CORRECTNESS)) {
            return EXECUTED_CACHEABLE.toString();
        }
        return avoidanceOutcome.toString();
    }

    @NotNull
    private static Map<String, TaskExecutionSummary> summarizeTaskExecutions(MavenBuildCachePerformance buildCachePerformance) {
        Map<String, List<MavenBuildCachePerformanceGoalExecutionEntry>> tasksByOutcome = buildCachePerformance.getGoalExecution().stream()
            .collect(Collectors.groupingBy(
                t -> t.getAvoidanceOutcome().toString()
            ));

        Map<String, TaskExecutionSummary> summariesByOutcome = tasksByOutcome.entrySet()
            .stream()
            .map(GradleEnterpriseApiClient::summarizeForMaven)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Arrays.stream(MavenBuildCachePerformanceGoalExecutionEntry.AvoidanceOutcomeEnum.values())
            .forEach(outcome -> summariesByOutcome.putIfAbsent(outcome.toString(), TaskExecutionSummary.ZERO));

        return toTotalAvoidedFromCache(summariesByOutcome);
    }

    private static Map.Entry<String, TaskExecutionSummary> summarizeForGradle(Map.Entry<String, List<GradleBuildCachePerformanceTaskExecutionEntry>> entry) {
        return new AbstractMap.SimpleEntry<>(entry.getKey(), summarizeForGradle(entry.getValue()));
    }

    private static TaskExecutionSummary summarizeForGradle(List<GradleBuildCachePerformanceTaskExecutionEntry> tasks) {
        return tasks.stream()
            .reduce(TaskExecutionSummary.ZERO, (summary, task) ->
               new TaskExecutionSummary(
                   summary.totalTasks() + 1,
                   summary.totalDuration().plus(toDuration(task.getDuration())),
                   summary.totalAvoidanceSavings().plus(toDuration(task.getAvoidanceSavings()))),
                TaskExecutionSummary::plus
            );
    }

    private static Map.Entry<String, TaskExecutionSummary> summarizeForMaven(Map.Entry<String, List<MavenBuildCachePerformanceGoalExecutionEntry>> entry) {
        return new AbstractMap.SimpleEntry<>(entry.getKey(), summarizeForMaven(entry.getValue()));
    }

    private static TaskExecutionSummary summarizeForMaven(List<MavenBuildCachePerformanceGoalExecutionEntry> tasks) {
        return tasks.stream()
            .reduce(TaskExecutionSummary.ZERO, (summary, task) ->
                    new TaskExecutionSummary(
                        summary.totalTasks() + 1,
                        summary.totalDuration().plus(toDuration(task.getDuration())),
                        summary.totalAvoidanceSavings().plus(toDuration(task.getAvoidanceSavings()))),
                TaskExecutionSummary::plus
            );
    }

    private static Duration toDuration(Long millis) {
        if (millis == null) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(millis);
    }

    private static Map<String, TaskExecutionSummary> toTotalAvoidedFromCache(Map<String, TaskExecutionSummary> summariesByOutcome) {
        TaskExecutionSummary fromLocalCache = summariesByOutcome.getOrDefault("avoided_from_local_cache", TaskExecutionSummary.ZERO);
        TaskExecutionSummary fromRemoteCache = summariesByOutcome.getOrDefault("avoided_from_remote_cache", TaskExecutionSummary.ZERO);

        summariesByOutcome.put("avoided_from_cache", fromLocalCache.plus(fromRemoteCache));
        return summariesByOutcome;
    }

    private static Duration buildTimeFrom(GradleBuildCachePerformance buildCachePerformance) {
        return toDuration(buildCachePerformance.getBuildTime());
    }

    private static Duration buildTimeFrom(MavenBuildCachePerformance buildCachePerformance) {
        return toDuration(buildCachePerformance.getBuildTime());
    }

    private static BigDecimal serializationFactorFrom(GradleBuildCachePerformance buildCachePerformance) {
        return BigDecimal.valueOf(buildCachePerformance.getSerializationFactor());
    }

    private static BigDecimal serializationFactorFrom(MavenBuildCachePerformance buildCachePerformance) {
        return BigDecimal.valueOf(buildCachePerformance.getSerializationFactor());
    }

}
