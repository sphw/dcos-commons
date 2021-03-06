package com.mesosphere.sdk.scheduler;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.TextFormat;
import com.mesosphere.sdk.framework.TaskKiller;
import com.mesosphere.sdk.http.endpoints.*;
import com.mesosphere.sdk.http.queries.ArtifactQueries;
import com.mesosphere.sdk.http.types.EndpointProducer;
import com.mesosphere.sdk.http.types.StringPropertyDeserializer;
import com.mesosphere.sdk.offer.*;
import com.mesosphere.sdk.offer.evaluate.OfferEvaluator;
import com.mesosphere.sdk.offer.history.OfferOutcomeTracker;
import com.mesosphere.sdk.scheduler.decommission.DecommissionPlanFactory;
import com.mesosphere.sdk.scheduler.plan.*;
import com.mesosphere.sdk.scheduler.recovery.FailureUtils;
import com.mesosphere.sdk.scheduler.uninstall.UninstallRecorder;
import com.mesosphere.sdk.scheduler.uninstall.UninstallScheduler;
import com.mesosphere.sdk.specification.GoalState;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.state.*;
import com.mesosphere.sdk.storage.Persister;
import com.mesosphere.sdk.storage.PersisterException;

import org.apache.mesos.Protos;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This scheduler when provided with a ServiceSpec will deploy the service and recover from encountered faults
 * when possible.  Changes to the ServiceSpec will result in rolling configuration updates, or the creation of
 * new Tasks where applicable.
 */
public class DefaultScheduler extends AbstractScheduler {

    private final Logger logger;
    private final Optional<String> namespace;
    private final SchedulerConfig schedulerConfig;
    private final FrameworkStore frameworkStore;
    private final ConfigStore<ServiceSpec> configStore;
    private final Collection<PlanManager> plansToCheckFinished;
    private final PlanManager planToCheckDeployed;
    private final Collection<Object> customResources;
    private final Map<String, EndpointProducer> customEndpointProducers;
    private final PersistentLaunchRecorder launchRecorder;
    private final Optional<UninstallRecorder> decommissionRecorder;
    private final Optional<OfferOutcomeTracker> offerOutcomeTracker;
    private final PlanScheduler planScheduler;

    /**
     * Creates a new {@link SchedulerBuilder} based on the provided {@link ServiceSpec} describing the service,
     * including details such as the service name, the pods/tasks to be deployed, and the plans describing how the
     * deployment should be organized.
     */
    public static SchedulerBuilder newBuilder(
            ServiceSpec serviceSpec,
            SchedulerConfig schedulerConfig) throws PersisterException {
        return new SchedulerBuilder(serviceSpec, schedulerConfig);
    }

    /**
     * Creates a new {@link SchedulerBuilder} based on the provided {@link ServiceSpec} describing the service,
     * including details such as the service name, the pods/tasks to be deployed, and the plans describing how the
     * deployment should be organized.
     */
    public static SchedulerBuilder newBuilder(
            ServiceSpec serviceSpec,
            SchedulerConfig schedulerConfig,
            Persister persister) throws PersisterException {
        return new SchedulerBuilder(serviceSpec, schedulerConfig, persister);
    }

    /**
     * Creates a new DefaultScheduler. See information about parameters in {@link SchedulerBuilder}.
     */
    protected DefaultScheduler(
            ServiceSpec serviceSpec,
            SchedulerConfig schedulerConfig,
            Optional<String> namespace,
            Collection<Object> customResources,
            PlanCoordinator planCoordinator,
            Optional<PlanCustomizer> planCustomizer,
            FrameworkStore frameworkStore,
            StateStore stateStore,
            ConfigStore<ServiceSpec> configStore,
            ArtifactQueries.TemplateUrlFactory templateUrlFactory,
            Map<String, EndpointProducer> customEndpointProducers) throws ConfigStoreException {
        super(serviceSpec, stateStore, planCoordinator, planCustomizer, namespace);
        this.logger = LoggingUtils.getLogger(getClass(), namespace);
        this.namespace = namespace;
        this.schedulerConfig = schedulerConfig;
        this.frameworkStore = frameworkStore;
        this.configStore = configStore;
        if (serviceSpec.getGoal() == GoalState.FINISH) {
            // Get the recovery and deploy plans. If they are COMPLETED, then the service can be uninstalled. We store
            // the PlanManagers, not the underlying Plans, because PlanManagers can change their plans at any time.
            this.plansToCheckFinished = HealthResource.getDeploymentAndRecoveryManagers(planCoordinator);
        } else {
            // Disable this check
            this.plansToCheckFinished = Collections.emptyList();
        }
        this.planToCheckDeployed = planCoordinator.getPlanManagers().stream()
                .filter(pm -> pm.getPlan().isDeployPlan())
                .findAny()
                .get();
        this.customResources = customResources;
        this.customEndpointProducers = customEndpointProducers;

        this.launchRecorder = new PersistentLaunchRecorder(stateStore, serviceSpec, namespace);
        Optional<DecommissionPlanManager> decommissionManager = getDecommissionManager(planCoordinator);
        if (decommissionManager.isPresent()) {
            this.decommissionRecorder =
                    Optional.of(new UninstallRecorder(stateStore, decommissionManager.get().getResourceSteps()));
        } else {
            this.decommissionRecorder = Optional.empty();
        }

        // If the service is namespaced (i.e. part of a multi-service scheduler), disable the OfferOutcomeTracker to
        // reduce memory consumption.
        this.offerOutcomeTracker = namespace.isPresent() ? Optional.empty() : Optional.of(new OfferOutcomeTracker());

        this.planScheduler = new PlanScheduler(
                new OfferEvaluator(
                        frameworkStore,
                        stateStore,
                        offerOutcomeTracker,
                        serviceSpec.getName(),
                        configStore.getTargetConfig(),
                        templateUrlFactory,
                        schedulerConfig,
                        namespace),
                stateStore,
                namespace);

        customizePlans();
    }

    @Override
    public Collection<Object> getHTTPEndpoints() {
        Collection<Object> resources = new ArrayList<>();
        resources.addAll(customResources);
        resources.add(new ArtifactResource(configStore));
        resources.add(new ConfigResource<>(configStore));
        EndpointsResource endpointsResource = new EndpointsResource(stateStore, serviceSpec.getName(), schedulerConfig);
        for (Map.Entry<String, EndpointProducer> entry : customEndpointProducers.entrySet()) {
            endpointsResource.setCustomEndpoint(entry.getKey(), entry.getValue());
        }
        resources.add(endpointsResource);
        PlansResource plansResource = new PlansResource(planCoordinator);
        resources.add(plansResource);
        resources.add(new DeprecatedPlanResource(plansResource));
        resources.add(new HealthResource(planCoordinator));
        resources.add(new PodResource(stateStore, configStore, serviceSpec.getName()));
        resources.add(new StateResource(frameworkStore, stateStore, new StringPropertyDeserializer()));
        if (offerOutcomeTracker.isPresent()) {
            resources.add(new OfferOutcomeResource(offerOutcomeTracker.get()));
        }
        return resources;
    }

    @Override
    public Map<String, EndpointProducer> getCustomEndpoints() {
        return customEndpointProducers;
    }

    @Override
    public ConfigStore<ServiceSpec> getConfigStore() {
        return configStore;
    }

    @Override
    protected void registeredWithMesos() {
        Set<String> activeTasks = PlanUtils.getLaunchableTasks(getPlans());

        Optional<DecommissionPlanManager> decomissionManager = getDecommissionManager(getPlanCoordinator());
        if (decomissionManager.isPresent()) {
            Collection<String> decommissionedTasks = decomissionManager.get().getTasksToDecommission().stream()
                    .map(taskInfo -> taskInfo.getName())
                    .collect(Collectors.toList());
            activeTasks.addAll(decommissionedTasks);
        }

        killUnneededTasks(stateStore, activeTasks);
    }

    @Override
    public void unregistered() {
        throw new UnsupportedOperationException(
                "Should not have received unregistered call. This is only applicable to UninstallSchedulers");
    }

    @Override
    public ClientStatusResponse getClientStatus() {
        if (!plansToCheckFinished.isEmpty() &&
                plansToCheckFinished.stream().allMatch(pm -> pm.getPlan().isComplete())) {
            // We have finished our work. Tell upstream to uninstall us.
            return ClientStatusResponse.finished();
        } else if (!planToCheckDeployed.getPlan().isComplete()) {
            // The service is still deploying.
            // TODO(nickbp, INFINITY-3476): This should only be the case when we're getting a footprint.
            //     Once we're actually rolling out the tasks, this should instead be RUNNING.
            return ClientStatusResponse.reserving();
        } else {
            return ClientStatusResponse.running();
        }
    }

    private static Optional<DecommissionPlanManager> getDecommissionManager(PlanCoordinator planCoordinator) {
        return planCoordinator.getPlanManagers().stream()
                .filter(planManager -> planManager.getPlan().isDecommissionPlan())
                .map(planManager -> (DecommissionPlanManager) planManager)
                .findFirst();
    }

    private static void killUnneededTasks(StateStore stateStore, Set<String> taskToDeployNames) {
        Set<Protos.TaskInfo> unneededTaskInfos = stateStore.fetchTasks().stream()
                .filter(taskInfo -> !taskToDeployNames.contains(taskInfo.getName()))
                .collect(Collectors.toSet());

        Set<Protos.TaskID> taskIdsToKill = unneededTaskInfos.stream()
                .map(taskInfo -> taskInfo.getTaskId())
                .collect(Collectors.toSet());

        // Clear the TaskIDs from the TaskInfos so we drop all future TaskStatus Messages
        Set<Protos.TaskInfo> cleanedTaskInfos = unneededTaskInfos.stream()
                .map(taskInfo -> taskInfo.toBuilder())
                .map(builder -> builder.setTaskId(Protos.TaskID.newBuilder().setValue("")).build())
                .collect(Collectors.toSet());

        // Remove both TaskInfo and TaskStatus, then store the cleaned TaskInfo one at a time to limit damage in the
        // event of an untimely scheduler crash
        for (Protos.TaskInfo taskInfo : cleanedTaskInfos) {
            stateStore.clearTask(taskInfo.getName());
            stateStore.storeTasks(Arrays.asList(taskInfo));
        }

        taskIdsToKill.forEach(taskID -> TaskKiller.killTask(taskID));

        for (Protos.TaskInfo taskInfo : stateStore.fetchTasks()) {
            GoalStateOverride.Status overrideStatus = stateStore.fetchGoalOverrideStatus(taskInfo.getName());
            if (overrideStatus.progress == GoalStateOverride.Progress.PENDING) {
                // Enabling or disabling an override was triggered, but the task kill wasn't processed so that the
                // change in override could take effect. Kill the task so that it can enter (or exit) the override. The
                // override status will then be marked IN_PROGRESS once we have received the terminal TaskStatus.
                TaskKiller.killTask(taskInfo.getTaskId());
            }
        }
    }

    @Override
    protected OfferResponse processOffers(Collection<Protos.Offer> offers, Collection<Step> steps) {
        return processOffers(logger, planScheduler, launchRecorder, decommissionRecorder, offers, steps);
    }

    /**
     * Broken out into a separate function to facilitate direct testing.
     */
    @VisibleForTesting
    static OfferResponse processOffers(
            Logger logger,
            PlanScheduler planScheduler,
            PersistentLaunchRecorder launchRecorder,
            Optional<UninstallRecorder> decommissionRecorder,
            Collection<Protos.Offer> offers,
            Collection<Step> steps) {
        // See which offers are useful to the plans, then omit the ones that shouldn't be launched.
        List<OfferRecommendation> offerRecommendations = planScheduler.resourceOffers(offers, steps);

        if (!offers.isEmpty()) {
            logger.info("{} offer{} resulted in {} recommendation{}: {}",
                    offers.size(),
                    offers.size() == 1 ? "" : "s",
                    offerRecommendations.size(),
                    offerRecommendations.size() == 1 ? "" : "s",
                    offerRecommendations.stream()
                            .map(rec -> rec.getOffer().getId().getValue())
                            .collect(Collectors.toSet()));
        }

        try {
            launchRecorder.record(offerRecommendations);
            if (decommissionRecorder.isPresent()) {
                decommissionRecorder.get().recordRecommendations(offerRecommendations);
            }
        } catch (Exception ex) {
            // Note: If a subset of operations were recorded, things could still be left in a bad state. However, in
            // practice any storage failure should have occurred in launchRecorder before any of the recommendations
            // were recorded. So in practice this record operation should be 'roughly atomic'.
            logger.error("Failed to record offer operations, returning empty operations list", ex);
            offerRecommendations = Collections.emptyList();
        }

        // After recording the operations, filter out any launches that shouldn't actually be launched.
        // In other words, record the reservations for these tasks but do not actually launch them.
        List<OfferRecommendation> filteredOfferRecommendations = new ArrayList<>();
        for (OfferRecommendation offerRecommendation : offerRecommendations) {
            if (offerRecommendation instanceof LaunchOfferRecommendation &&
                    !((LaunchOfferRecommendation) offerRecommendation).shouldLaunch()) {
                logger.info("Skipping launch of transient Operation: {}",
                        TextFormat.shortDebugString(offerRecommendation.getOperation()));
            } else {
                filteredOfferRecommendations.add(offerRecommendation);
            }
        }

        return OfferResponse.processed(filteredOfferRecommendations);
    }

    /**
     * Returns the resources which are not expected by this service.
     *
     * <p>Resources can be unexpected for one of the following reasons:
     * <ul>
     * <li>The resource is from an old task and the service has since moved on (e.g. agent recently revived)</li>
     * <li>The resource is from a prior version of a replaced task (marked as permanently failed)</li>
     * <li>The resource is part of a task that's being decommissioned. In this case we also notify the decommission plan
     * that the resource is (about to be) cleaned.</li></ul>
     */
    @Override
    public UnexpectedResourcesResponse getUnexpectedResources(Collection<Protos.Offer> unusedOffers) {
        // First, determine which resource IDs we want to keep. Anything not listed here will be destroyed.
        final Set<String> resourceIdsToKeep;
        try {
            resourceIdsToKeep = stateStore.fetchTasks().stream()
                    // A known task's resources should be kept if:
                    // - the task is not marked as permanently failed, and
                    // - the task is not in the process of being decommissioned
                    .filter(taskInfo ->
                            !FailureUtils.isPermanentlyFailed(taskInfo) &&
                            !stateStore.fetchGoalOverrideStatus(taskInfo.getName())
                                    .equals(DecommissionPlanFactory.DECOMMISSIONING_STATUS))
                    .map(taskInfo -> ResourceUtils.getResourceIds(ResourceUtils.getAllResources(taskInfo)))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            logger.error("Failed to fetch expected tasks to determine unexpected resources", e);
            return UnexpectedResourcesResponse.failed(Collections.emptyList());
        }

        // Then, select any resources (and their parent offers) which are not in the above whitelist.
        Collection<OfferResources> unexpectedResources = new ArrayList<>();
        for (Protos.Offer offer : unusedOffers) {
            OfferResources unexpectedResourcesForOffer = new OfferResources(offer);
            for (Protos.Resource resource : offer.getResourcesList()) {
                if (!ResourceUtils.getReservation(resource).isPresent()) {
                    continue; // Not a reserved resource, disregard
                }
                Optional<String> resourceId = ResourceUtils.getResourceId(resource);
                if (!resourceId.isPresent() || !resourceIdsToKeep.contains(resourceId.get())) {
                    unexpectedResourcesForOffer.add(resource);
                }
            }
            if (!unexpectedResourcesForOffer.getResources().isEmpty()) {
                unexpectedResources.add(unexpectedResourcesForOffer);
            }
        }

        // Finally, notify any decommissionRecorder with the resources that are about to be unreserved/destroyed. In
        // practice this should be handled via the offer evaluation call above, but it can't hurt to also check here.
        if (decommissionRecorder.isPresent()) {
            try {
                decommissionRecorder.get().recordResources(unexpectedResources);
            } catch (Exception e) {
                // Failed to record the decommission. Refrain from returning these resources as unexpected for now, try
                // again later.
                logger.error("Failed to record unexpected resources in decommission recorder", e);
                return UnexpectedResourcesResponse.failed(Collections.emptyList());
            }
        }
        return UnexpectedResourcesResponse.processed(unexpectedResources);
    }

    @Override
    protected void processStatusUpdate(Protos.TaskStatus status) throws Exception {
        // Store status, then pass status to PlanManager => Plan => Steps
        String taskName = StateStoreUtils.getTaskName(stateStore, status);

        // StateStore updates:
        // - TaskStatus
        // - Override status (if applicable)
        stateStore.storeStatus(taskName, status);

        // Notify plans of status update:
        planCoordinator.getPlanManagers().forEach(planManager -> planManager.update(status));

        // If the TaskStatus contains an IP Address, store it as a property in the StateStore.
        // We expect the TaskStatus to contain an IP address in both Host or CNI networking.
        // Currently, we are always _missing_ the IP Address on TASK_LOST. We always expect it on TASK_RUNNINGs
        if (status.hasContainerStatus() &&
                status.getContainerStatus().getNetworkInfosCount() > 0 &&
                status.getContainerStatus().getNetworkInfosList().stream()
                        .anyMatch(networkInfo -> networkInfo.getIpAddressesCount() > 0)) {
            // Map the TaskStatus to a TaskInfo. The map will throw a StateStoreException if no such TaskInfo exists.
            try {
                StateStoreUtils.storeTaskStatusAsProperty(stateStore, taskName, status);
            } catch (StateStoreException e) {
                logger.warn("Unable to store network info for status update: " + status, e);
            }
        }
    }

    /**
     * Creates a new {@link UninstallScheduler} based on the local components.  This is used to trigger uninstall of a
     * service without restarting the framework scheduler process. The created {@link UninstallScheduler} will
     * automatically flag the service's StateStore with an uninstall bit.
     */
    public UninstallScheduler toUninstallScheduler() {
        return new UninstallScheduler(
                this.serviceSpec,
                this.stateStore,
                this.configStore,
                this.schedulerConfig,
                this.planCustomizer,
                this.namespace);
    }
}
