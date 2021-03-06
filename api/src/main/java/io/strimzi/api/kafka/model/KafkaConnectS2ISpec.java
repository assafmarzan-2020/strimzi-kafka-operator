/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

@Buildable(
        editableEnabled = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "replicas", "image", "buildResources",
        "livenessProbe", "readinessProbe", "jvmOptions",
        "affinity", "logging", "metrics", "template"})
@EqualsAndHashCode(callSuper = true)
public class KafkaConnectS2ISpec extends KafkaConnectSpec {

    private static final long serialVersionUID = 1L;

    private ResourceRequirements buildResources;

    private boolean insecureSourceRepository = false;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Description("CPU and memory resources to reserve.")
    public ResourceRequirements getBuildResources() {
        return buildResources;
    }

    public void setBuildResources(ResourceRequirements buildResources) {
        this.buildResources = buildResources;
    }

    @Description("When true this configures the source repository with the 'Local' reference policy " +
            "and an import policy that accepts insecure source tags.")
    public boolean isInsecureSourceRepository() {
        return insecureSourceRepository;
    }

    public void setInsecureSourceRepository(boolean insecureSourceRepository) {
        this.insecureSourceRepository = insecureSourceRepository;
    }
}
