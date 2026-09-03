package com.cozentus.enrichment;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/** Runs every scenario in {@code features/}, reporting to target/cucumber (SPEC 10). */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.cozentus.enrichment")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        // No "pretty" plugin: it writes every step to stdout, which makes the
        // mvn -q verify gate noisy. SPEC 10 asks for HTML and JSON only.
        value = "html:target/cucumber/city-enrichment.html,"
              + "json:target/cucumber/city-enrichment.json")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
public class RunCucumberTest {
}
