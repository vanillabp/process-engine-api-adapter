package io.vanillabp.pea.quarkus.deployment.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * The root of the build-time configuration of this adapter. It is empty for now. Later
 * feature stories add build-time settings of the adapter here.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface PeaProperties {

}
