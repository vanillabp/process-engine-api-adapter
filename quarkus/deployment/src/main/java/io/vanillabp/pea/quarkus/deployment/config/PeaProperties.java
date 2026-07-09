package io.vanillabp.pea.quarkus.deployment.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Build-time configuration root of the Process-Engine-API adapter. Empty for now
 * (skeleton); adapter-specific build-time settings are added by later feature stories.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface PeaProperties {

}
