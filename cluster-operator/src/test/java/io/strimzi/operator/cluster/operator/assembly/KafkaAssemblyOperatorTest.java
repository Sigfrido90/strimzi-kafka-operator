/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.Reconciliation;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.AssemblyType;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.Labels;
import io.strimzi.operator.cluster.model.Storage;
import io.strimzi.operator.cluster.model.TopicOperator;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.cluster.operator.resource.ConfigMapOperator;
import io.strimzi.operator.cluster.operator.resource.DeploymentOperator;
import io.strimzi.operator.cluster.operator.resource.KafkaSetOperator;
import io.strimzi.operator.cluster.operator.resource.PvcOperator;
import io.strimzi.operator.cluster.operator.resource.ReconcileResult;
import io.strimzi.operator.cluster.operator.resource.SecretOperator;
import io.strimzi.operator.cluster.operator.resource.ServiceOperator;
import io.strimzi.operator.cluster.operator.resource.StatefulSetDiff;
import io.strimzi.operator.cluster.operator.resource.ZookeeperSetOperator;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunnerWithParametersFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(VertxUnitRunnerWithParametersFactory.class)
public class KafkaAssemblyOperatorTest {

    public static final String METRICS_CONFIG = "{\"foo\":\"bar\"}";
    public static final String LOG_KAFKA_CONFIG = "{\"kafka.root.logger.level\":\"INFO\"}";
    public static final String LOG_ZOOKEEPER_CONFIG = "{\"zookeeper.root.logger\":\"INFO\"}";
    public static final String LOG_CONNECT_CONFIG = "{\"connect.root.logger.level\":\"INFO\"}";
    private final boolean openShift;
    private final boolean metrics;
    private final String kafkaConfig;
    private final String zooConfig;
    private final String storage;
    private final String tcConfig;
    private final boolean deleteClaim;
    private MockCertManager certManager = new MockCertManager();

    public static class Params {
        private final boolean openShift;
        private final boolean metrics;
        private final String kafkaConfig;
        private final String zooConfig;
        private final String storage;
        private final String tcConfig;

        public Params(boolean openShift, boolean metrics, String kafkaConfig, String zooConfig, String storage, String tcConfig) {
            this.openShift = openShift;
            this.metrics = metrics;
            this.kafkaConfig = kafkaConfig;
            this.zooConfig = zooConfig;
            this.storage = storage;
            this.tcConfig = tcConfig;
        }

        public String toString() {
            return "openShift=" + openShift +
                    ",metrics=" + metrics +
                    ",kafkaConfig=" + kafkaConfig +
                    ",zooConfig=" + zooConfig +
                    ",storage=" + storage +
                    ",tcConfig=" + tcConfig;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Params> data() {
        boolean[] shiftiness = {true, false};
        boolean[] metrics = {true, false};
        String[] storageConfigs = {
            "{\"type\": \"ephemeral\"}",
            "{\"type\": \"persistent-claim\", " +
                    "\"size\": \"123\", " +
                    "\"class\": \"foo\"," +
                    "\"delete-claim\": true}"
        };
        String[] kafkaConfigs = {
            null,
            "{ }",
            "{\"foo\": \"bar\"}"
        };
        String[] zooConfigs = {
            null,
            "{ }",
            "{\"foo\": \"bar\"}"
        };
        String[] tcConfigs = {
            null,
            "{ }",
            "{\"reconciliationInterval\": \"10 minutes\", " +
                    "\"zookeeperSessionTimeout\": \"10 seconds\"}"
        };
        List<Params> result = new ArrayList();
        for (boolean shift: shiftiness) {
            for (boolean metric: metrics) {
                for (String kafkaConfig: kafkaConfigs) {
                    for (String zooConfig: zooConfigs) {
                        for (String storage : storageConfigs) {
                            for (String tcConfig : tcConfigs) {
                                result.add(new Params(shift, metric, kafkaConfig, zooConfig, storage, tcConfig));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    public KafkaAssemblyOperatorTest(Params params) {
        this.openShift = params.openShift;
        this.metrics = params.metrics;
        this.kafkaConfig = params.kafkaConfig;
        this.zooConfig = params.zooConfig;
        this.storage = params.storage;
        this.tcConfig = params.tcConfig;
        this.deleteClaim = Storage.fromJson(new JsonObject(params.storage)).isDeleteClaim();
    }

    protected static Vertx vertx;

    @BeforeClass
    public static void before() {
        vertx = Vertx.vertx();
    }

    @AfterClass
    public static void after() {
        vertx.close();
    }

    @Test
    public void testCreateCluster(TestContext context) {
        createCluster(context, getConfigMap("foo", "kafka"), getInitialSecrets());
    }

    private void createCluster(TestContext context, ConfigMap clusterCm, List<Secret> secrets) {

        KafkaCluster kafkaCluster = KafkaCluster.fromConfigMap(certManager, clusterCm, secrets);
        ZookeeperCluster zookeeperCluster = ZookeeperCluster.fromConfigMap(certManager, clusterCm, secrets);
        TopicOperator topicOperator = TopicOperator.fromConfigMap(clusterCm);

        // create CM, Service, headless service, statefulset and so on
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        ZookeeperSetOperator mockZsOps = mock(ZookeeperSetOperator.class);
        KafkaSetOperator mockKsOps = mock(KafkaSetOperator.class);
        PvcOperator mockPvcOps = mock(PvcOperator.class);
        DeploymentOperator mockDepOps = mock(DeploymentOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        // Create a CM
        String clusterCmName = clusterCm.getMetadata().getName();
        String clusterCmNamespace = clusterCm.getMetadata().getNamespace();
        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(anyString(), anyString(), serviceCaptor.capture())).thenReturn(Future.succeededFuture(ReconcileResult.created(null)));
        when(mockServiceOps.endpointReadiness(anyString(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<StatefulSet> ssCaptor = ArgumentCaptor.forClass(StatefulSet.class);
        when(mockZsOps.reconcile(anyString(), anyString(), ssCaptor.capture())).thenReturn(Future.succeededFuture(ReconcileResult.created(null)));
        when(mockZsOps.scaleDown(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(null));
        when(mockZsOps.maybeRollingUpdate(any())).thenReturn(Future.succeededFuture());
        when(mockZsOps.scaleUp(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(42));
        when(mockKsOps.reconcile(anyString(), anyString(), ssCaptor.capture())).thenReturn(Future.succeededFuture(ReconcileResult.created(null)));
        when(mockKsOps.scaleDown(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(null));
        when(mockKsOps.maybeRollingUpdate(any())).thenReturn(Future.succeededFuture());
        when(mockKsOps.scaleUp(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(42));
        ArgumentCaptor<String> secretsCaptor = ArgumentCaptor.forClass(String.class);
        when(mockSecretOps.reconcile(anyString(), secretsCaptor.capture(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(null)));

        when(mockDepOps.reconcile(anyString(), anyString(), any())).thenAnswer(invocation -> {
            Deployment desired = invocation.getArgument(2);
            if (desired != null) {
                context.assertEquals(TopicOperator.topicOperatorName(clusterCmName), desired.getMetadata().getName());
            }
            return Future.succeededFuture(ReconcileResult.created(desired));
        }); //Return(Future.succeededFuture(ReconcileResult.created(depCaptor.getValue())));

        //when(mockSsOps.readiness(any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        //when(mockPodOps.readiness(any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        //when(mockEndpointOps.readiness(any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<ConfigMap> metricsCaptor = ArgumentCaptor.forClass(ConfigMap.class);
        ArgumentCaptor<String> metricsNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(anyString(), metricsNameCaptor.capture(), metricsCaptor.capture())).thenReturn(Future.succeededFuture(ReconcileResult.created(null)));

        ArgumentCaptor<ConfigMap> logCaptor = ArgumentCaptor.forClass(ConfigMap.class);
        ArgumentCaptor<String> logNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(anyString(), logNameCaptor.capture(), logCaptor.capture())).thenReturn(Future.succeededFuture(ReconcileResult.created(null)));

        KafkaAssemblyOperator ops = new KafkaAssemblyOperator(vertx, openShift,
                ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS,
                certManager,
                mockCmOps,
                mockServiceOps, mockZsOps, mockKsOps,
                mockPvcOps, mockDepOps, mockSecretOps);

        // Now try to create a KafkaCluster based on this CM
        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.KAFKA, clusterCmNamespace, clusterCmName), clusterCm, secrets, createResult -> {
            if (createResult.failed()) {
                createResult.cause().printStackTrace();
            }
            context.assertTrue(createResult.succeeded());

            // No metrics config  => no CMs created
            Set<String> logsAndMetricsNames = new HashSet<>();
            logsAndMetricsNames.add(KafkaCluster.metricAndLogConfigsName(clusterCmName));
            /*
            Map<String, ConfigMap> cmsByName = new HashMap<>();
            Iterator<ConfigMap> it2 = metricsCaptor.getAllValues().iterator();
            for (Iterator<String> it = metricsNameCaptor.getAllValues().iterator(); it.hasNext(); ) {
                cmsByName.put(it.next(), it2.next());
            }
            context.assertEquals(metricsNames, cmsByName.keySet(),
                    "Unexpected metrics ConfigMaps");
            if (kafkaCluster.isMetricsEnabled()) {
                ConfigMap kafkaMetricsCm = cmsByName.get(KafkaCluster.metricConfigsName(clusterCmName));
                context.assertEquals(ResourceUtils.labels(Labels.STRIMZI_TYPE_LABEL, "kafka",
                        Labels.STRIMZI_CLUSTER_LABEL, clusterCmName,
                        "my-user-label", "cromulent"), kafkaMetricsCm.getMetadata().getLabels());
            }
            if (zookeeperCluster.isMetricsEnabled()) {
                ConfigMap zookeeperMetricsCm = cmsByName.get(ZookeeperCluster.zookeeperMetricsName(clusterCmName));
                context.assertEquals(ResourceUtils.labels(Labels.STRIMZI_TYPE_LABEL, "zookeeper",
                        Labels.STRIMZI_CLUSTER_LABEL, clusterCmName,
                        "my-user-label", "cromulent"), zookeeperMetricsCm.getMetadata().getLabels());
            }*/


            // We expect a headless and headful service
            List<Service> capturedServices = serviceCaptor.getAllValues();
            context.assertEquals(4, capturedServices.size());
            context.assertEquals(set(KafkaCluster.kafkaClusterName(clusterCmName),
                    KafkaCluster.headlessName(clusterCmName),
                    ZookeeperCluster.zookeeperClusterName(clusterCmName),
                    ZookeeperCluster.zookeeperHeadlessName(clusterCmName)), capturedServices.stream().map(svc -> svc.getMetadata().getName()).collect(Collectors.toSet()));

            // Assertions on the statefulset
            List<StatefulSet> capturedSs = ssCaptor.getAllValues();
            // We expect a statefulSet for kafka and zookeeper...
            context.assertEquals(set(KafkaCluster.kafkaClusterName(clusterCmName), ZookeeperCluster.zookeeperClusterName(clusterCmName)),
                    capturedSs.stream().map(ss -> ss.getMetadata().getName()).collect(Collectors.toSet()));

            // expected Secrets with certificates
            context.assertEquals(set(
                    KafkaCluster.clientsCASecretName(clusterCmName),
                    KafkaCluster.clientsPublicKeyName(clusterCmName),
                    KafkaCluster.brokersClientsSecretName(clusterCmName),
                    KafkaCluster.brokersInternalSecretName(clusterCmName),
                    ZookeeperCluster.nodesSecretName(clusterCmName)),
                    captured(secretsCaptor));

            // Verify that we wait for readiness
            //verify(mockEndpointOps, times(4)).readiness(any(), any(), anyLong(), anyLong());
            //verify(mockSsOps, times(1)).readiness(any(), any(), anyLong(), anyLong());
            //verify(mockPodOps, times(zookeeperCluster.getReplicas() + kafkaCluster.getReplicas()))
            //        .readiness(any(), any(), anyLong(), anyLong());

            // PvcOperations only used for deletion
            verifyNoMoreInteractions(mockPvcOps);
            async.complete();
        });
    }

    private void deleteCluster(TestContext context, ConfigMap clusterCm, List<Secret> secrets) {

        ZookeeperCluster zookeeperCluster = ZookeeperCluster.fromConfigMap(certManager, clusterCm, secrets);
        KafkaCluster kafkaCluster = KafkaCluster.fromConfigMap(certManager, clusterCm, secrets);
        TopicOperator topicOperator = TopicOperator.fromConfigMap(clusterCm);
        // create CM, Service, headless service, statefulset
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        ZookeeperSetOperator mockZsOps = mock(ZookeeperSetOperator.class);
        KafkaSetOperator mockKsOps = mock(KafkaSetOperator.class);
        PvcOperator mockPvcOps = mock(PvcOperator.class);
        DeploymentOperator mockDepOps = mock(DeploymentOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = clusterCm.getMetadata().getName();
        String clusterCmNamespace = clusterCm.getMetadata().getNamespace();
        StatefulSet kafkaSs = kafkaCluster.generateStatefulSet(true);

        StatefulSet zkSs = zookeeperCluster.generateStatefulSet(true);
        when(mockKsOps.get(clusterCmNamespace, KafkaCluster.kafkaClusterName(clusterCmName))).thenReturn(kafkaSs);
        when(mockZsOps.get(clusterCmNamespace, ZookeeperCluster.zookeeperClusterName(clusterCmName))).thenReturn(zkSs);

        Secret clientsCASecret = kafkaCluster.generateClientsCASecret();
        Secret clientsPublicKeySecret = kafkaCluster.generateClientsPublicKeySecret();
        Secret brokersInternalSecret = kafkaCluster.generateBrokersInternalSecret();
        Secret brokersClientsSecret = kafkaCluster.generateBrokersClientsSecret();
        Secret nodesSecret = zookeeperCluster.generateNodesSecret();
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.clientsCASecretName(clusterCmName))).thenReturn(clientsCASecret);
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.clientsPublicKeyName(clusterCmName))).thenReturn(clientsPublicKeySecret);
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.brokersInternalSecretName(clusterCmName))).thenReturn(brokersInternalSecret);
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.brokersClientsSecretName(clusterCmName))).thenReturn(brokersClientsSecret);
        when(mockSecretOps.get(clusterCmNamespace, ZookeeperCluster.nodesSecretName(clusterCmName))).thenReturn(nodesSecret);

        ArgumentCaptor<String> secretsCaptor = ArgumentCaptor.forClass(String.class);
        when(mockSecretOps.reconcile(eq(clusterCmNamespace), secretsCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        ArgumentCaptor<String> serviceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ssCaptor = ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<String> metricsCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(eq(clusterCmNamespace), metricsCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> logCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(eq(clusterCmNamespace), logCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        when(mockServiceOps.reconcile(eq(clusterCmNamespace), serviceCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());
        when(mockKsOps.reconcile(anyString(), ssCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());
        when(mockZsOps.reconcile(anyString(), ssCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> pvcCaptor = ArgumentCaptor.forClass(String.class);
        when(mockPvcOps.reconcile(eq(clusterCmNamespace), pvcCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> depCaptor = ArgumentCaptor.forClass(String.class);
        when(mockDepOps.reconcile(eq(clusterCmNamespace), depCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());
        if (topicOperator != null) {
            Deployment tcDep = topicOperator.generateDeployment();
            when(mockDepOps.get(clusterCmNamespace, TopicOperator.topicOperatorName(clusterCmName))).thenReturn(tcDep);
        }

        KafkaAssemblyOperator ops = new KafkaAssemblyOperator(vertx, openShift,
                ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS,
                certManager,
                mockCmOps,
                mockServiceOps, mockZsOps, mockKsOps,
                mockPvcOps,
                mockDepOps, mockSecretOps);

        // Now try to delete a KafkaCluster based on this CM
        Async async = context.async();
        ops.delete(new Reconciliation("test-trigger", AssemblyType.KAFKA, clusterCmNamespace, clusterCmName), createResult -> {
            context.assertTrue(createResult.succeeded());

            /*Set<String> metricsNames = new HashSet<>();
            if (kafkaCluster.isMetricsEnabled()) {
                metricsNames.add(KafkaCluster.metricConfigsName(clusterCmName));
            }
            if (zookeeperCluster.isMetricsEnabled()) {
                metricsNames.add(ZookeeperCluster.zookeeperMetricsName(clusterCmName));
            }
            context.assertEquals(metricsNames, captured(metricsCaptor));*/
            verify(mockZsOps).reconcile(eq(clusterCmNamespace), eq(ZookeeperCluster.zookeeperClusterName(clusterCmName)), isNull());

            context.assertEquals(set(
                    ZookeeperCluster.zookeeperHeadlessName(clusterCmName),
                    ZookeeperCluster.zookeeperClusterName(clusterCmName),
                    KafkaCluster.kafkaClusterName(clusterCmName),
                    KafkaCluster.headlessName(clusterCmName)),
                    captured(serviceCaptor));

            // verify deleted Statefulsets
            context.assertEquals(set(zookeeperCluster.getName(), kafkaCluster.getName()), captured(ssCaptor));

            // verify deleted Secrets
            context.assertEquals(set(
                    KafkaCluster.clientsCASecretName(clusterCmName),
                    KafkaCluster.clientsPublicKeyName(clusterCmName),
                    KafkaCluster.brokersInternalSecretName(clusterCmName),
                    KafkaCluster.brokersClientsSecretName(clusterCmName),
                    ZookeeperCluster.nodesSecretName(clusterCmName)),
                    captured(secretsCaptor));

            // PvcOperations only used for deletion
            Set<String> expectedPvcDeletions = new HashSet<>();
            for (int i = 0; deleteClaim && i < kafkaCluster.getReplicas(); i++) {
                expectedPvcDeletions.add("data-" + clusterCmName + "-kafka-" + i);
            }
            for (int i = 0; deleteClaim && i < zookeeperCluster.getReplicas(); i++) {
                expectedPvcDeletions.add("data-" + clusterCmName + "-zookeeper-" + i);
            }
            context.assertEquals(expectedPvcDeletions, captured(pvcCaptor));

            // if topic operator configuration was defined in the CM
            if (topicOperator != null) {
                Set<String> expectedDepNames = new HashSet<>();
                expectedDepNames.add(TopicOperator.topicOperatorName(clusterCmName));
                context.assertEquals(expectedDepNames, captured(depCaptor));
            }

            async.complete();
        });
    }

    private ConfigMap getConfigMap(String clusterCmName, String model) {
        String clusterCmNamespace = "test";
        int replicas = 3;
        String image = "bar";
        int healthDelay = 120;
        String zooLogCmJson = LOG_ZOOKEEPER_CONFIG;
        String kafkaLogCmJson = LOG_KAFKA_CONFIG;
        String connectLogCmJson = LOG_CONNECT_CONFIG;
        int healthTimeout = 30;
        String metricsCmJson = metrics ? METRICS_CONFIG : null;


        return ResourceUtils.createKafkaClusterConfigMap(clusterCmNamespace, clusterCmName, replicas, image, healthDelay, healthTimeout, metricsCmJson, kafkaConfig, zooConfig, storage, tcConfig, null, kafkaLogCmJson, zooLogCmJson);
    }

    private List<Secret> getInitialSecrets() {
        String clusterCmNamespace = "test";
        return ResourceUtils.createKafkaClusterInitialSecrets(clusterCmNamespace);
    }

    private List<Secret> getClusterSecrets(String clusterCmName, int kafkaReplicas, int zkReplicas) {
        String clusterCmNamespace = "test";
        return ResourceUtils.createKafkaClusterSecretsWithReplicas(clusterCmNamespace, clusterCmName, kafkaReplicas, zkReplicas);
    }

    private static <T> Set<T> set(T... elements) {
        return new HashSet<>(asList(elements));
    }

    private static <T> Set<T> captured(ArgumentCaptor<T> captor) {
        return new HashSet<>(captor.getAllValues());
    }

    @Test
    public void testDeleteCluster(TestContext context) {
        ConfigMap clusterCm = getConfigMap("baz", "kafka");
        List<Secret> secrets = getClusterSecrets("baz",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        deleteCluster(context, clusterCm, secrets);

    }

    @Test
    public void testUpdateClusterNoop(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "kafka");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "kafka"), clusterCm, secrets);
    }

    @Test
    public void testUpdateKafkaClusterChangeImage(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "kafka");
        clusterCm.getData().put(KafkaCluster.KEY_IMAGE, "a-changed-image");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "kafka"), clusterCm, secrets);
    }

    @Test
    public void testUpdateZookeeperClusterChangeImage(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "zookeeper");
        clusterCm.getData().put(ZookeeperCluster.KEY_IMAGE, "a-changed-image");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "zookeeper"), clusterCm, secrets);
    }

    @Test
    public void testUpdateKafkaClusterScaleUp(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "kafka");
        clusterCm.getData().put(KafkaCluster.KEY_REPLICAS, "4");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "kafka"), clusterCm, secrets);
    }

    @Test
    public void testUpdateKafkaClusterScaleDown(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "kafka");
        clusterCm.getData().put(KafkaCluster.KEY_REPLICAS, "2");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "kafka"), clusterCm, secrets);
    }

    @Test
    public void testUpdateZookeeperClusterScaleUp(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "zookeeper");
        clusterCm.getData().put(ZookeeperCluster.KEY_REPLICAS, "4");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "zookeeper"), clusterCm, secrets);
    }

    @Test
    public void testUpdateZookeeperClusterScaleDown(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "zookeeper");
        clusterCm.getData().put(ZookeeperCluster.KEY_REPLICAS, "2");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "zookeeper"), clusterCm, secrets);
    }

    @Test
    public void testUpdateClusterMetricsConfig(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "kafka");
        clusterCm.getData().put(KafkaCluster.KEY_METRICS_CONFIG, "{\"something\":\"changed\"}");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "kafka"), clusterCm, secrets);
    }

    @Test
    public void testUpdateClusterLogConfig(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "kafka");
        clusterCm.getData().put(KafkaCluster.KEY_KAFKA_LOG_CONFIG, "{\"kafka.root.logger.level\":\"DEBUG\"}");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "kafka"), clusterCm, secrets);
    }

    @Test
    public void testUpdateZkClusterMetricsConfig(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "zookeeper");
        clusterCm.getData().put(ZookeeperCluster.KEY_METRICS_CONFIG, "{\"something\":\"changed\"}");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "zookeeper"), clusterCm, secrets);
    }

    @Test
    public void testUpdateZkClusterLogConfig(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "zookeeper");
        clusterCm.getData().put(ZookeeperCluster.KEY_ZOOKEEPER_LOG_CONFIG, "{\"zookeeper.root.logger\":\"OFF\"}");
        List<Secret> secrets = getClusterSecrets("bar",
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)),
                Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        updateCluster(context, getConfigMap("bar", "zookeeper"), clusterCm, secrets);
    }

    @Test
    public void testUpdateTopicOperatorConfig(TestContext context) {
        ConfigMap clusterCm = getConfigMap("bar", "kafka");
        if (tcConfig != null) {
            clusterCm.getData().put(TopicOperator.KEY_CONFIG, "{\"something\":\"changed\"}");
            List<Secret> secrets = getClusterSecrets("bar",
                    Integer.valueOf(clusterCm.getData().get(KafkaCluster.KEY_REPLICAS)),
                    Integer.valueOf(clusterCm.getData().get(ZookeeperCluster.KEY_REPLICAS)));
            updateCluster(context, getConfigMap("bar", "kafka"), clusterCm, secrets);

        }
    }


    private void updateCluster(TestContext context, ConfigMap originalCm, ConfigMap clusterCm, List<Secret> secrets) {

        KafkaCluster originalKafkaCluster = KafkaCluster.fromConfigMap(certManager, originalCm, secrets);
        KafkaCluster updatedKafkaCluster = KafkaCluster.fromConfigMap(certManager, clusterCm, secrets);
        ZookeeperCluster originalZookeeperCluster = ZookeeperCluster.fromConfigMap(certManager, originalCm, secrets);
        ZookeeperCluster updatedZookeeperCluster = ZookeeperCluster.fromConfigMap(certManager, clusterCm, secrets);
        TopicOperator originalTopicOperator = TopicOperator.fromConfigMap(originalCm);

        // create CM, Service, headless service, statefulset and so on
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        ZookeeperSetOperator mockZsOps = mock(ZookeeperSetOperator.class);
        KafkaSetOperator mockKsOps = mock(KafkaSetOperator.class);
        PvcOperator mockPvcOps = mock(PvcOperator.class);
        DeploymentOperator mockDepOps = mock(DeploymentOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = clusterCm.getMetadata().getName();
        String clusterCmNamespace = clusterCm.getMetadata().getNamespace();

        // Mock CM get
        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        ConfigMap metricsCm = new ConfigMapBuilder().withNewMetadata()
                    .withName(KafkaCluster.metricAndLogConfigsName(clusterCmName))
                    .withNamespace(clusterCmNamespace)
                .endMetadata()
                .withData(Collections.singletonMap(AbstractModel.ANCILLARY_CM_KEY_METRICS, METRICS_CONFIG))
                .build();
        when(mockCmOps.get(clusterCmNamespace, KafkaCluster.metricAndLogConfigsName(clusterCmName))).thenReturn(metricsCm);
        ConfigMap zkMetricsCm = new ConfigMapBuilder().withNewMetadata()
                .withName(ZookeeperCluster.zookeeperMetricAndLogConfigsName(clusterCmName))
                .withNamespace(clusterCmNamespace)
                .endMetadata()
                .withData(Collections.singletonMap(AbstractModel.ANCILLARY_CM_KEY_METRICS, METRICS_CONFIG))
                .build();
        when(mockCmOps.get(clusterCmNamespace, ZookeeperCluster.zookeeperMetricAndLogConfigsName(clusterCmName))).thenReturn(zkMetricsCm);


        ConfigMap logCm = new ConfigMapBuilder().withNewMetadata()
                .withName(KafkaCluster.metricAndLogConfigsName(clusterCmName))
                .withNamespace(clusterCmNamespace)
                .endMetadata()
                .withData(Collections.singletonMap(AbstractModel.ANCILLARY_CM_KEY_LOG_CONFIG, LOG_KAFKA_CONFIG))
                .build();
        when(mockCmOps.get(clusterCmNamespace, KafkaCluster.metricAndLogConfigsName(clusterCmName))).thenReturn(logCm);
        ConfigMap zklogsCm = new ConfigMapBuilder().withNewMetadata()
                .withName(ZookeeperCluster.zookeeperMetricAndLogConfigsName(clusterCmName))
                .withNamespace(clusterCmNamespace)
                .endMetadata()
                .withData(Collections.singletonMap(AbstractModel.ANCILLARY_CM_KEY_LOG_CONFIG, LOG_KAFKA_CONFIG))
                .build();
        when(mockCmOps.get(clusterCmNamespace, ZookeeperCluster.zookeeperMetricAndLogConfigsName(clusterCmName))).thenReturn(zklogsCm);


        // Mock Service gets
        when(mockServiceOps.get(clusterCmNamespace, KafkaCluster.kafkaClusterName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateService()
        );
        when(mockServiceOps.get(clusterCmNamespace, KafkaCluster.headlessName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateHeadlessService()
        );
        when(mockServiceOps.get(clusterCmNamespace, ZookeeperCluster.zookeeperClusterName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateService()
        );
        when(mockServiceOps.get(clusterCmNamespace, ZookeeperCluster.zookeeperHeadlessName(clusterCmName))).thenReturn(
                originalZookeeperCluster.generateHeadlessService()
        );
        when(mockServiceOps.endpointReadiness(eq(clusterCmNamespace), any(), anyLong(), anyLong())).thenReturn(
                Future.succeededFuture()
        );

        // Mock Secret gets
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.clientsCASecretName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateClientsCASecret()
        );
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.clientsPublicKeyName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateClientsPublicKeySecret()
        );
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.brokersClientsSecretName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateBrokersClientsSecret()
        );
        when(mockSecretOps.get(clusterCmNamespace, KafkaCluster.brokersInternalSecretName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateBrokersInternalSecret()
        );

        // Mock StatefulSet get
        when(mockKsOps.get(clusterCmNamespace, KafkaCluster.kafkaClusterName(clusterCmName))).thenReturn(
                originalKafkaCluster.generateStatefulSet(openShift)
        );
        when(mockZsOps.get(clusterCmNamespace, ZookeeperCluster.zookeeperClusterName(clusterCmName))).thenReturn(
                originalZookeeperCluster.generateStatefulSet(openShift)
        );
        // Mock Deployment get
        if (originalTopicOperator != null) {
            when(mockDepOps.get(clusterCmNamespace, TopicOperator.topicOperatorName(clusterCmName))).thenReturn(
                    originalTopicOperator.generateDeployment()
            );
        }

        // Mock CM patch
        Set<String> metricsCms = set();
        doAnswer(invocation -> {
            metricsCms.add(invocation.getArgument(1));
            return Future.succeededFuture();
        }).when(mockCmOps).reconcile(eq(clusterCmNamespace), anyString(), any());

        Set<String> logCms = set();
        doAnswer(invocation -> {
            logCms.add(invocation.getArgument(1));
            return Future.succeededFuture();
        }).when(mockCmOps).reconcile(eq(clusterCmNamespace), anyString(), any());

        // Mock Service patch (both service and headless service
        ArgumentCaptor<String> patchedServicesCaptor = ArgumentCaptor.forClass(String.class);
        when(mockServiceOps.reconcile(eq(clusterCmNamespace), patchedServicesCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        // Mock Secrets patch
        when(mockSecretOps.reconcile(eq(clusterCmNamespace), any(), any())).thenReturn(Future.succeededFuture());
        // Mock StatefulSet patch
        when(mockZsOps.reconcile(anyString(), anyString(), any())).thenAnswer(invocation -> {
            StatefulSet ss = invocation.getArgument(2);
            return Future.succeededFuture(ReconcileResult.patched(ss));
        });
        when(mockKsOps.reconcile(anyString(), anyString(), any())).thenAnswer(invocation -> {
            StatefulSet ss = invocation.getArgument(2);
            return Future.succeededFuture(ReconcileResult.patched(ss));
        });
        when(mockZsOps.maybeRollingUpdate(any())).thenReturn(Future.succeededFuture());
        when(mockKsOps.maybeRollingUpdate(any())).thenReturn(Future.succeededFuture());

        // Mock StatefulSet scaleUp
        ArgumentCaptor<String> scaledUpCaptor = ArgumentCaptor.forClass(String.class);
        when(mockZsOps.scaleUp(anyString(), scaledUpCaptor.capture(), anyInt())).thenReturn(
                Future.succeededFuture(42)
        );
        // Mock StatefulSet scaleDown
        ArgumentCaptor<String> scaledDownCaptor = ArgumentCaptor.forClass(String.class);
        when(mockZsOps.scaleDown(anyString(), scaledDownCaptor.capture(), anyInt())).thenReturn(
                Future.succeededFuture(42)
        );
        //ArgumentCaptor<String> scaledUpCaptor = ArgumentCaptor.forClass(String.class);
        when(mockKsOps.scaleUp(anyString(), scaledUpCaptor.capture(), anyInt())).thenReturn(
                Future.succeededFuture(42)
        );
        // Mock StatefulSet scaleDown
        //ArgumentCaptor<String> scaledDownCaptor = ArgumentCaptor.forClass(String.class);
        when(mockKsOps.scaleDown(anyString(), scaledDownCaptor.capture(), anyInt())).thenReturn(
                Future.succeededFuture(42)
        );

        // Mock Deployment patch
        ArgumentCaptor<String> depCaptor = ArgumentCaptor.forClass(String.class);
        when(mockDepOps.reconcile(anyString(), depCaptor.capture(), any())).thenReturn(Future.succeededFuture());

        KafkaAssemblyOperator ops = new KafkaAssemblyOperator(vertx, openShift,
                ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS,
                certManager,
                mockCmOps,
                mockServiceOps, mockZsOps, mockKsOps,
                mockPvcOps, mockDepOps, mockSecretOps);

        // Now try to update a KafkaCluster based on this CM
        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.KAFKA, clusterCmNamespace, clusterCmName), clusterCm, secrets, createResult -> {
            if (createResult.failed()) createResult.cause().printStackTrace();
            context.assertTrue(createResult.succeeded());

            // rolling restart
            Set<String> expectedRollingRestarts = set();
            if (KafkaSetOperator.needsRollingUpdate(
                    new StatefulSetDiff(originalKafkaCluster.generateStatefulSet(openShift),
                    updatedKafkaCluster.generateStatefulSet(openShift)))) {
                expectedRollingRestarts.add(originalKafkaCluster.getName());
            }
            if (ZookeeperSetOperator.needsRollingUpdate(
                    new StatefulSetDiff(originalZookeeperCluster.generateStatefulSet(openShift),
                            updatedZookeeperCluster.generateStatefulSet(openShift)))) {
                expectedRollingRestarts.add(originalZookeeperCluster.getName());
            }

            // No metrics config  => no CMs created
            verify(mockCmOps, never()).createOrUpdate(any());
            verifyNoMoreInteractions(mockPvcOps);
            async.complete();
        });
    }

    @Test
    public void testReconcile(TestContext context) throws InterruptedException {
        Async async = context.async(3);

        // create CM, Service, headless service, statefulset
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        ZookeeperSetOperator mockZsOps = mock(ZookeeperSetOperator.class);
        KafkaSetOperator mockKsOps = mock(KafkaSetOperator.class);
        PvcOperator mockPvcOps = mock(PvcOperator.class);
        DeploymentOperator mockDepOps = mock(DeploymentOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmNamespace = "myNamespace";

        ConfigMap foo = getConfigMap("foo", "kafka");
        ConfigMap bar = getConfigMap("bar", "kafka");
        ConfigMap baz = getConfigMap("baz", "kafka");
        when(mockCmOps.list(eq(clusterCmNamespace), any())).thenReturn(
            asList(foo, bar)
        );
        // when requested ConfigMap for a specific Kafka cluster
        when(mockCmOps.get(eq(clusterCmNamespace), eq("foo"))).thenReturn(foo);
        when(mockCmOps.get(eq(clusterCmNamespace), eq("bar"))).thenReturn(bar);

        // providing certificates Secrets for existing clusters
        List<Secret> barSecrets = ResourceUtils.createKafkaClusterSecretsWithReplicas(clusterCmNamespace, "bar",
                Integer.valueOf(bar.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(bar.getData().get(ZookeeperCluster.KEY_REPLICAS)));
        List<Secret> bazSecrets = ResourceUtils.createKafkaClusterSecretsWithReplicas(clusterCmNamespace, "baz",
                Integer.valueOf(baz.getData().get(KafkaCluster.KEY_REPLICAS)),
                Integer.valueOf(baz.getData().get(ZookeeperCluster.KEY_REPLICAS)));

        // providing the list of ALL StatefulSets for all the Kafka clusters
        Labels newLabels = Labels.forType(AssemblyType.KAFKA);
        when(mockKsOps.list(eq(clusterCmNamespace), eq(newLabels))).thenReturn(
                asList(KafkaCluster.fromConfigMap(certManager, bar, barSecrets).generateStatefulSet(openShift),
                        KafkaCluster.fromConfigMap(certManager, baz, bazSecrets).generateStatefulSet(openShift))
        );

        // providing the list StatefulSets for already "existing" Kafka clusters
        Labels barLabels = Labels.forCluster("bar");
        KafkaCluster barCluster = KafkaCluster.fromConfigMap(certManager, bar, barSecrets);
        when(mockKsOps.list(eq(clusterCmNamespace), eq(barLabels))).thenReturn(
                asList(barCluster.generateStatefulSet(openShift))
        );
        when(mockSecretOps.list(eq(clusterCmNamespace), eq(barLabels))).thenReturn(
                new ArrayList<>(asList(barCluster.generateClientsCASecret(), barCluster.generateClientsPublicKeySecret(),
                        barCluster.generateBrokersInternalSecret(), barCluster.generateBrokersClientsSecret()))
        );
        when(mockSecretOps.get(eq(clusterCmNamespace), eq(AbstractAssemblyOperator.INTERNAL_CA_NAME))).thenReturn(barSecrets.get(0));

        Labels bazLabels = Labels.forCluster("baz");
        KafkaCluster bazCluster = KafkaCluster.fromConfigMap(certManager, baz, bazSecrets);
        when(mockKsOps.list(eq(clusterCmNamespace), eq(bazLabels))).thenReturn(
                asList(bazCluster.generateStatefulSet(openShift))
        );
        when(mockSecretOps.list(eq(clusterCmNamespace), eq(bazLabels))).thenReturn(
                new ArrayList<>(asList(bazCluster.generateClientsCASecret(), bazCluster.generateClientsPublicKeySecret(),
                        bazCluster.generateBrokersInternalSecret(), bazCluster.generateBrokersClientsSecret()))
        );
        when(mockSecretOps.get(eq(clusterCmNamespace), eq(AbstractAssemblyOperator.INTERNAL_CA_NAME))).thenReturn(bazSecrets.get(0));

        Set<String> createdOrUpdated = new CopyOnWriteArraySet<>();
        Set<String> deleted = new CopyOnWriteArraySet<>();

        KafkaAssemblyOperator ops = new KafkaAssemblyOperator(vertx, openShift,
                ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS,
                certManager,
                mockCmOps,
                mockServiceOps, mockZsOps, mockKsOps,
                mockPvcOps, mockDepOps, mockSecretOps) {
            @Override
            public void createOrUpdate(Reconciliation reconciliation, ConfigMap assemblyCm, List<Secret> assemblySecrets, Handler<AsyncResult<Void>> h) {
                createdOrUpdated.add(assemblyCm.getMetadata().getName());
                async.countDown();
                h.handle(Future.succeededFuture());
            }
            @Override
            public void delete(Reconciliation reconciliation, Handler h) {
                deleted.add(reconciliation.assemblyName());
                async.countDown();
                h.handle(Future.succeededFuture());
            }
        };

        // Now try to reconcile all the Kafka clusters
        ops.reconcileAll("test", clusterCmNamespace, Labels.EMPTY).await();

        async.await();

        context.assertEquals(new HashSet(asList("foo", "bar")), createdOrUpdated);
        context.assertEquals(singleton("baz"), deleted);
    }
}
