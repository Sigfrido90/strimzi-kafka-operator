/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.certs.OpenSslCertManager;
import io.strimzi.operator.cluster.operator.assembly.KafkaAssemblyOperator;
import io.strimzi.operator.cluster.operator.assembly.KafkaConnectAssemblyOperator;
import io.strimzi.operator.cluster.operator.assembly.KafkaConnectS2IAssemblyOperator;
import io.strimzi.operator.cluster.operator.resource.BuildConfigOperator;
import io.strimzi.operator.cluster.operator.resource.ConfigMapOperator;
import io.strimzi.operator.cluster.operator.resource.DeploymentConfigOperator;
import io.strimzi.operator.cluster.operator.resource.DeploymentOperator;
import io.strimzi.operator.cluster.operator.resource.ImageStreamOperator;
import io.strimzi.operator.cluster.operator.resource.KafkaSetOperator;
import io.strimzi.operator.cluster.operator.resource.PvcOperator;
import io.strimzi.operator.cluster.operator.resource.SecretOperator;
import io.strimzi.operator.cluster.operator.resource.ServiceOperator;
import io.strimzi.operator.cluster.operator.resource.ZookeeperSetOperator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
    private static final Logger log = LogManager.getLogger(Main.class.getName());

    public static void main(String[] args) {
        ClusterOperatorConfig config = ClusterOperatorConfig.fromMap(System.getenv());
        Vertx vertx = Vertx.vertx();
        KubernetesClient client = new DefaultKubernetesClient();

        isOnOpenShift(vertx, client).setHandler(os -> {
            if (os.succeeded()) {
                run(vertx, client, os.result().booleanValue(), config).setHandler(ar -> {
                    if (ar.failed()) {
                        log.error("Unable to start operator for 1 or more namespace", ar.cause());
                        System.exit(1);
                    }
                });
            } else {
                log.error("Failed to distinguish between Kubernetes and OpenShift", os.cause());
                System.exit(1);
            }
        });
    }

    static CompositeFuture run(Vertx vertx, KubernetesClient client, boolean isOpenShift, ClusterOperatorConfig config) {
        printEnvInfo();
        ServiceOperator serviceOperations = new ServiceOperator(vertx, client);
        ZookeeperSetOperator zookeeperSetOperations = new ZookeeperSetOperator(vertx, client, config.getOperationTimeoutMs());
        KafkaSetOperator kafkaSetOperations = new KafkaSetOperator(vertx, client, config.getOperationTimeoutMs());
        ConfigMapOperator configMapOperations = new ConfigMapOperator(vertx, client);
        PvcOperator pvcOperations = new PvcOperator(vertx, client);
        DeploymentOperator deploymentOperations = new DeploymentOperator(vertx, client);
        SecretOperator secretOperations = new SecretOperator(vertx, client);

        OpenSslCertManager certManager = new OpenSslCertManager();
        KafkaAssemblyOperator kafkaClusterOperations = new KafkaAssemblyOperator(vertx, isOpenShift, config.getOperationTimeoutMs(), certManager, configMapOperations, serviceOperations, zookeeperSetOperations, kafkaSetOperations, pvcOperations, deploymentOperations, secretOperations);
        KafkaConnectAssemblyOperator kafkaConnectClusterOperations = new KafkaConnectAssemblyOperator(vertx, isOpenShift, certManager, configMapOperations, deploymentOperations, serviceOperations, secretOperations);

        DeploymentConfigOperator deploymentConfigOperations = null;
        ImageStreamOperator imagesStreamOperations = null;
        BuildConfigOperator buildConfigOperations = null;
        KafkaConnectS2IAssemblyOperator kafkaConnectS2IClusterOperations = null;
        if (isOpenShift) {
            imagesStreamOperations = new ImageStreamOperator(vertx, client.adapt(OpenShiftClient.class));
            buildConfigOperations = new BuildConfigOperator(vertx, client.adapt(OpenShiftClient.class));
            deploymentConfigOperations = new DeploymentConfigOperator(vertx, client.adapt(OpenShiftClient.class));
            kafkaConnectS2IClusterOperations = new KafkaConnectS2IAssemblyOperator(vertx, isOpenShift,
                    certManager,
                    configMapOperations, deploymentConfigOperations,
                    serviceOperations, imagesStreamOperations, buildConfigOperations, secretOperations);
        }

        List<Future> futures = new ArrayList<>();
        for (String namespace : config.getNamespaces()) {
            Future<String> fut = Future.future();
            futures.add(fut);
            ClusterOperator operator = new ClusterOperator(namespace,
                    config.getReconciliationIntervalMs(),
                    client,
                    kafkaClusterOperations,
                    kafkaConnectClusterOperations,
                    kafkaConnectS2IClusterOperations);
            vertx.deployVerticle(operator,
                res -> {
                    if (res.succeeded()) {
                        log.info("Cluster Operator verticle started in namespace {}", namespace);
                    } else {
                        log.error("Cluster Operator verticle in namespace {} failed to start", namespace, res.cause());
                        System.exit(1);
                    }
                    fut.completer().handle(res);
                });
        }
        return CompositeFuture.join(futures);
    }

    static Future<Boolean> isOnOpenShift(Vertx vertx, KubernetesClient client)  {
        if (client.isAdaptable(OkHttpClient.class)) {
            OkHttpClient ok = client.adapt(OkHttpClient.class);
            Future<Boolean> fut = Future.future();

            vertx.executeBlocking(request -> {
                try (Response resp = ok.newCall(new Request.Builder().get().url(client.getMasterUrl().toString() + "oapi").build()).execute()) {
                    if (resp.code() == 200) {
                        log.debug("{} returned {}. We are on OpenShift.", resp.request().url(), resp.code());
                        // We should be on OpenShift based on the /oapi result. We can now safely try isAdaptable() to be 100% sure.
                        Boolean isOpenShift = Boolean.TRUE.equals(client.isAdaptable(OpenShiftClient.class));
                        request.complete(isOpenShift);
                    } else {
                        log.debug("{} returned {}. We are not on OpenShift.", resp.request().url(), resp.code());
                        request.complete(Boolean.FALSE);
                    }
                } catch (IOException e) {
                    log.error("OpenShift detection failed", e);
                    request.fail(e);
                }
            }, fut.completer());

            return fut;
        } else {
            log.error("Cannot adapt KubernetesClient to OkHttpClient");
            return Future.failedFuture("Cannot adapt KubernetesClient to OkHttpClient");
        }
    }

    static void printEnvInfo() {
        Map<String, String> m = new HashMap<>(System.getenv());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry: m.entrySet()) {
            sb.append("\t").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        log.info("Using config:\n" + sb.toString());
    }
}
