/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.enmasse.controller.common;

import io.enmasse.address.model.AddressSpace;
import io.enmasse.config.AnnotationKeys;
import io.enmasse.config.LabelKeys;
import io.enmasse.address.model.Endpoint;
import io.enmasse.k8s.api.ConfigMapAddressSpaceApi;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.DoneableIngress;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.*;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.ParameterValue;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Wraps the Kubernetes client and adds some helper methods.
 */
public class KubernetesHelper implements Kubernetes {
    private static final Logger log = LoggerFactory.getLogger(KubernetesHelper.class.getName());
    private static final String TEMPLATE_SUFFIX = ".json";

    private final OpenShiftClient client;
    private final String namespace;
    private final String controllerToken;
    private final String environment;
    private final Optional<File> templateDir;

    public KubernetesHelper(String namespace, OpenShiftClient client, String token, String environment, Optional<File> templateDir) {
        this.client = client;
        this.namespace = namespace;
        this.controllerToken = token;
        this.environment = environment;
        this.templateDir = templateDir;
    }

    @Override
    public List<AddressCluster> listClusters() {
        Map<String, List<HasMetadata>> resourceMap = new HashMap<>();

        // Add other resources part of a destination cluster
        List<HasMetadata> objects = new ArrayList<>();
        objects.addAll(client.extensions().deployments().inNamespace(namespace).list().getItems());
        objects.addAll(client.persistentVolumeClaims().inNamespace(namespace).list().getItems());
        objects.addAll(client.configMaps().inNamespace(namespace).list().getItems());

        for (HasMetadata config : objects) {
            Map<String, String> annotations = config.getMetadata().getAnnotations();

            if (annotations != null && annotations.containsKey(AnnotationKeys.CLUSTER_ID)) {
                String groupId = annotations.get(AnnotationKeys.CLUSTER_ID);

                Map<String, String> labels = config.getMetadata().getLabels();

                if (labels != null && !"address-config".equals(labels.get(LabelKeys.TYPE))) {
                    if (!resourceMap.containsKey(groupId)) {
                        resourceMap.put(groupId, new ArrayList<>());
                    }
                    resourceMap.get(groupId).add(config);
                }
            }
        }

        return resourceMap.entrySet().stream()
                .map(entry -> {
                    KubernetesList list = new KubernetesList();
                    list.setItems(entry.getValue());
                    return new AddressCluster(entry.getKey(), list);
                }).collect(Collectors.toList());
    }

    @Override
    public void create(HasMetadata... resources) {
        create(new KubernetesListBuilder()
                .addToItems(resources)
                .build());
    }

    @Override
    public void create(KubernetesList resources) {
        client.lists().inNamespace(namespace).create(resources);
    }

    @Override
    public void create(KubernetesList resources, String namespace) {
        client.lists().inNamespace(namespace).create(resources);
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public void delete(KubernetesList resources) {
        client.lists().inNamespace(namespace).delete(resources);
    }

    @Override
    public void delete(HasMetadata... resources) {
        delete(new KubernetesListBuilder()
                .addToItems(resources)
                .build());
    }

    @Override
    public void createNamespace(AddressSpace addressSpace) {
        client.namespaces().createNew()
                .editOrNewMetadata()
                .withName(addressSpace.getNamespace())
                .addToLabels("app", "enmasse")
                .addToLabels(LabelKeys.TYPE, "address-space")
                .addToLabels(LabelKeys.ENVIRONMENT, environment)
                .addToAnnotations(AnnotationKeys.ADDRESS_SPACE, addressSpace.getName())
                // We're leaking the underlying serialized form of the AddressSpace which is not ideal.
                .addNewOwnerReference()
                    .withKind("ConfigMap")
                    .withApiVersion("v1")
                    .withName(ConfigMapAddressSpaceApi.getConfigMapName(addressSpace.getName()))
                    .withUid(addressSpace.getUid())
                .endOwnerReference()
                .endMetadata()
                .done();
    }

    @Override
    public Kubernetes withNamespace(String namespace) {
        return new KubernetesHelper(namespace, client, controllerToken, environment, templateDir);
    }

    @Override
    public KubernetesList processTemplate(String templateName, ParameterValue... parameterValues) {
        if (templateDir.isPresent()) {
            File templateFile = new File(templateDir.get(), templateName + TEMPLATE_SUFFIX);
            return client.templates().load(templateFile).processLocally(parameterValues);
        } else {
            return client.templates().withName(templateName).process(parameterValues);
        }
    }

    @Override
    public void addSystemImagePullerPolicy(String namespace, String tenantNamespace) {
        if (client.isAdaptable(OpenShiftClient.class)) {
            String groupName = "system:serviceaccounts:" + tenantNamespace;
            log.info("Adding system:image-pullers policy for {}", groupName);
            client.roleBindings()
                    .inNamespace(namespace)
                    .withName("system:image-pullers")
                    .edit()
                    .addToGroupNames(groupName)
                    .addNewSubject()
                    .withKind("SystemGroup")
                    .withName(groupName)
                    .endSubject()
                    .done();
        }
    }

    @Override
    public boolean existsNamespace(String namespace) {
        return client.namespaces().withName(namespace).get() != null;
    }

    @Override
    public boolean hasService(String service) {
        return client.services().inNamespace(namespace).withName(service).get() != null;
    }

    @Override
    public void createEndpoint(Endpoint endpoint, Service service, String addressSpaceName, String namespace) {
        if (service == null || service.getMetadata().getAnnotations() == null) {
            log.info("Skipping creating endpoint for unknown service {}", endpoint.getService());
            return;
        }

        String defaultPort = service.getMetadata().getAnnotations().get(AnnotationKeys.ENDPOINT_PORT);

        // TODO: Add labels
        if (client.isAdaptable(OpenShiftClient.class)) {
            DoneableRoute route = client.routes().inNamespace(namespace).createNew()
                    .editOrNewMetadata()
                    .withName(endpoint.getName())
                    .addToAnnotations(AnnotationKeys.ADDRESS_SPACE, addressSpaceName)
                    .endMetadata()
                    .editOrNewSpec()
                    .withHost(endpoint.getHost().orElse(""))
                    .withNewTo()
                    .withName(endpoint.getService())
                    .withKind("Service")
                    .endTo()
                    .withNewPort()
                    .editOrNewTargetPort()
                    .withStrVal(defaultPort)
                    .endTargetPort()
                    .endPort()
                    .endSpec();

            if (endpoint.getCertProvider().isPresent()) {
                route.editOrNewMetadata()
                        .addToAnnotations(AnnotationKeys.CERT_SECRET_NAME, endpoint.getCertProvider().get().getSecretName())
                        .endMetadata()
                        .editOrNewSpec()
                        .withNewTls()
                        .withTermination("passthrough")
                        .endTls()
                        .endSpec();
            }
            route.done();
        } else {
            DoneableIngress ingress = client.extensions().ingresses().inNamespace(namespace).createNew()
                    .editOrNewMetadata()
                    .withName(endpoint.getName())
                    .addToAnnotations(AnnotationKeys.ADDRESS_SPACE, addressSpaceName)
                    .endMetadata()
                    .editOrNewSpec()
                    .editOrNewBackend()
                    .withServiceName(endpoint.getService())
                    .withServicePort(new IntOrString(defaultPort))
                    .endBackend()
                    .endSpec();

            if (endpoint.getCertProvider().isPresent()) {
                ingress.editOrNewMetadata()
                        .addToAnnotations(AnnotationKeys.CERT_SECRET_NAME, endpoint.getCertProvider().get().getSecretName())
                        .endMetadata()
                        .editOrNewSpec();

                if (endpoint.getHost().isPresent()) {
                    ingress.editOrNewSpec()
                            .addNewTl()
                            .addToHosts(endpoint.getHost().get())
                            .withSecretName(endpoint.getCertProvider().get().getSecretName())
                            .endTl();
                }
            }
            ingress.done();
        }
    }

    public Set<Deployment> getReadyDeployments() {
        return client.extensions().deployments().inNamespace(namespace).list().getItems().stream()
                .filter(KubernetesHelper::isReady)
                .collect(Collectors.toSet());
    }

    @Override
    public boolean isDestinationClusterReady(String clusterId) {
        return listClusters().stream()
                .filter(dc -> clusterId.equals(dc.getClusterId()))
                .anyMatch(KubernetesHelper::areAllDeploymentsReady);
    }

    @Override
    public List<Pod> listRouters() {
        return client.pods().withLabel(LabelKeys.CAPABILITY, "router").list().getItems();
    }

    @Override
    public Optional<Secret> getSecret(String secretName) {
        return Optional.ofNullable(client.secrets().inNamespace(namespace).withName(secretName).get());
    }

    private JsonObject doRawHttpRequest(String path, String method, JsonObject body, boolean errorOk) {
        OkHttpClient httpClient = client.adapt(OkHttpClient.class);

        HttpUrl url = HttpUrl.get(client.getOpenshiftUrl()).resolve(path);
        //log.info("Performing {} on {} with body '{}'", method, path, body.encodePrettily());
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + controllerToken)
                .method(method, body != null ? RequestBody.create(MediaType.parse("application/json"), body.encode()) : null)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return new JsonObject(response.body().string());
            } else {
                if (errorOk) {
                    return null;
                } else {
                    String errorMessage = String.format("Error performing %s on %s: %d, %s", method, path, response.code(), response.body().string());
                    log.warn(errorMessage);
                    throw new RuntimeException(errorMessage);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    @Override
    public TokenReview performTokenReview(String token) {
        if (client.isAdaptable(OkHttpClient.class)) {
            JsonObject body = new JsonObject();

            body.put("kind", "TokenReview");
            body.put("apiVersion", "authentication.k8s.io/v1beta1");

            JsonObject spec = new JsonObject();
            spec.put("token", token);
            body.put("spec", spec);

            JsonObject responseBody= doRawHttpRequest("/apis/authentication.k8s.io/v1beta1/tokenreviews", "POST", body, false);
            JsonObject status = responseBody.getJsonObject("status");
            boolean authenticated = false;
            String userName = null;
            if (status != null) {
                Boolean auth = status.getBoolean("authenticated");
                authenticated = auth == null ? false : auth;
                JsonObject user = status.getJsonObject("user");
                if (user != null) {
                    userName = user.getString("username");
                }
            }
            return new TokenReview(userName, authenticated);
        } else {
            return new TokenReview(null, false);
        }
    }

    @Override
    public SubjectAccessReview performSubjectAccessReview(String user, String namespace, String verb) {
        if (client.isAdaptable(OkHttpClient.class)) {
            JsonObject body = new JsonObject();

            body.put("kind", "LocalSubjectAccessReview");
            body.put("apiVersion", "authorization.k8s.io/v1beta1");

            JsonObject metadata = new JsonObject();
            metadata.put("namespace", namespace);
            body.put("metadata", metadata);

            JsonObject spec = new JsonObject();

            JsonObject resourceAttributes = new JsonObject();
            resourceAttributes.put("namespace", namespace);
            resourceAttributes.put("resource", "configmaps");
            resourceAttributes.put("verb", verb);

            spec.put("resourceAttributes", resourceAttributes);
            spec.put("user", user);

            body.put("spec", spec);
            JsonObject responseBody = doRawHttpRequest("/apis/authorization.k8s.io/v1beta1/namespaces/" + namespace + "/localsubjectaccessreviews", "POST", body, false);

            JsonObject status = responseBody.getJsonObject("status");
            boolean allowed = false;
            if (status != null) {
                Boolean allowedMaybe = status.getBoolean("allowed");
                allowed = allowedMaybe == null ? false : allowedMaybe;
            }
            return new SubjectAccessReview(user, allowed);
        } else {
            return new SubjectAccessReview(user, false);
        }
    }

    @Override
    public boolean isRBACSupported() {
        return client.supportsApiPath("/apis/authentication.k8s.io") &&
                client.supportsApiPath("/apis/authorization.k8s.io") &&
                client.supportsApiPath("/apis/rbac.authorization.k8s.io");

    }

    private void createRoleBinding(String name, String namespace, String refKind, String refName, List<Subject> subjectList) {

        String apiVersion = client.isAdaptable(OpenShiftClient.class) ? "v1" : "rbac.authorization.k8s.io/v1beta1";
        String apiPath = client.isAdaptable(OpenShiftClient.class) ? "/oapi/v1" : "/apis/rbac.authorization.k8s.io/v1beta1";

        JsonObject body = new JsonObject();

        body.put("kind", "RoleBinding");
        body.put("apiVersion", apiVersion);

        JsonObject metadata = new JsonObject();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        body.put("metadata", metadata);

        JsonObject roleRef = new JsonObject();
        roleRef.put("apiGroup", "rbac.authorization.k8s.io");
        roleRef.put("kind", refKind);
        roleRef.put("name", refName);
        body.put("roleRef", roleRef);

        JsonArray subjects = new JsonArray();

        for (Subject subjectEntry : subjectList) {
            JsonObject subject = new JsonObject();
            subject.put("apiGroup", "rbac.authorization.k8s.io");
            subject.put("kind", subjectEntry.getKind());
            subject.put("name", subjectEntry.getName());
            if (subjectEntry.getNamespace() != null) {
                subject.put("namespace", subjectEntry.getNamespace());
            }
            subjects.add(subject);
        }

        body.put("subjects", subjects);


        doRawHttpRequest(apiPath + "/namespaces/" + namespace + "/rolebindings", "POST", body, false);
    }

    @Override
    public void addAddressAdminRole(String namespace) {
        String roleName = "enmasse-address-admin";

        if (isRBACSupported()) {
            createRoleBinding("address-admins", namespace, "ClusterRole", roleName, Arrays.asList(
                    new Subject("ServiceAccount", "default", namespace)));
        } else if (client.isAdaptable(OpenShiftClient.class)) {
            String groupName = "system:serviceaccounts:" + namespace;
            RoleBinding roleBinding = new RoleBindingBuilder()
                    .editOrNewMetadata()
                    .withName("enmasse-address-admin")
                    .withNamespace(namespace)
                    .endMetadata()
                    .addToGroupNames(groupName)
                    .addNewSubject()
                    .withKind("SystemGroup")
                    .withName(groupName)
                    .endSubject()
                    .withNewRoleRef()
                    .withName(roleName)
                    .withNamespace(namespace)
                    .endRoleRef()
                    .build();


            String policyBindingName = ":default";

            Resource<PolicyBinding, DoneablePolicyBinding> bindingResource = client.policyBindings()
                    .inNamespace(namespace)
                    .withName(policyBindingName);


            PolicyBinding policyBinding = new PolicyBindingBuilder(bindingResource.get())
                    .addNewRoleBinding()
                    .withName(roleName)
                    .withNewRoleBindingLike(roleBinding)
                    .endRoleBinding()
                    .endRoleBinding()
                    .build();

            bindingResource.replace(policyBinding);

        } else {
            log.info("No support for RBAC, won't add to address-admin role");
        }
    }

    @Override
    public void addInfraViewRole(String controllerNamespace, String namespace) {
        if (isRBACSupported()) {
            createRoleBinding("infra-viewers", namespace, "ClusterRole", "enmasse-infra-view", Arrays.asList(
                    new Subject("ServiceAccount", "default", namespace)));
        } else if (client.isAdaptable(OpenShiftClient.class)) {
            Resource<PolicyBinding, DoneablePolicyBinding> bindingResource = client.policyBindings()
                    .inNamespace(namespace)
                    .withName(":default");

            DoneablePolicyBinding binding;
            if (bindingResource.get() == null) {
                binding = bindingResource.createNew();
            } else {
                binding = bindingResource.edit();
            }
            binding.editOrNewMetadata()
                    .withName(":default")
                    .endMetadata()
                    .editOrNewPolicyRef()
                    .withName("default")
                    .endPolicyRef()
                    .addNewRoleBinding()
                    .withName("infra-viewers")
                    .editOrNewRoleBinding()
                    .editOrNewMetadata()
                    .withName("infra-viewers")
                    .withNamespace(namespace)
                    .endMetadata()
                    .addToUserNames("system:serviceaccount:" + namespace + ":default")
                    .addNewSubject()
                    .withName("default")
                    .withNamespace(namespace)
                    .withKind("ServiceAccount")
                    .endSubject()
                    .withNewRoleRef()
                    .withName("view")
                    .withKind("ClusterRole")
                    .endRoleRef()
                    .endRoleBinding()
                    .endRoleBinding()
                    .done();
        } else {
            log.info("No support for RBAC, won't add to infra-admin role");
        }
    }

    public static boolean isDeployment(HasMetadata res) {
        return res.getKind().equals("Deployment");  // TODO: is there an existing constant for this somewhere?
    }

    private static boolean areAllDeploymentsReady(AddressCluster dc) {
        return dc.getResources().getItems().stream().filter(KubernetesHelper::isDeployment).allMatch(d -> isReady((Deployment) d));
    }

    private static boolean isReady(Deployment deployment) {
        Integer unavailableReplicas = deployment.getStatus().getUnavailableReplicas();
        return unavailableReplicas == null || unavailableReplicas == 0;
    }

    private static class Subject {
        private final String kind;
        private final String name;
        private final String namespace;

        private Subject(String kind, String name, String namespace) {
            this.kind = kind;
            this.name = name;
            this.namespace = namespace;
        }

        public String getName() {
            return name;
        }

        public String getKind() {
            return kind;
        }

        public String getNamespace() {
            return namespace;
        }
    }
}
