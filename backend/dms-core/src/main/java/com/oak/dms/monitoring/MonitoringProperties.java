package com.oak.dms.monitoring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Where Prometheus is, and how long to wait for it.
 * <p>
 * The default host name is the compose service, not localhost: dms-core
 * talks to Prometheus over the internal network, which is the point of this
 * endpoint - Prometheus itself never has to be reachable from a browser.
 */
@Component
@ConfigurationProperties(prefix = "dms.monitoring")
@Getter
@Setter
public class MonitoringProperties {

    /** Empty disables the monitoring API - the screen then says so rather than erroring. */
    private String prometheusUrl = "http://prometheus:9090";

    /** Where container logs are queried from. Blank switches the logs view off. */
    private String lokiUrl = "http://loki:3100";

    /**
     * Deliberately short. This serves a screen someone is watching; a
     * Prometheus that has not answered in a few seconds is a fault to
     * report, not something to keep a request thread waiting on.
     */
    private Duration timeout = Duration.ofSeconds(5);

    /**
     * Points per chart. The step is derived from this and the window, so a
     * 7-day chart costs Prometheus the same as a 1-hour one - without it, a
     * long window at a fine step is how a monitoring page takes down the
     * thing it is monitoring.
     */
    private int chartPoints = 120;
}
