package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkAvailability;
import com.oak.dms.watermark.job.WatermarkJobQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code dms.watermark.enabled=false} the feature is not merely
 * refusing requests - it is doing nothing at all.
 *
 * <h2>Why this needs a test rather than a reading of the annotations</h2>
 * "Off" is made of several independent decisions - a conditional on each
 * controller, on the worker, on the executors, on the sweeper - and the
 * failure mode of getting one wrong is silent. A {@code @Scheduled} method
 * on a bean that was supposed to be conditional runs on every instance of
 * every deployment that has watermarking switched off, for ever, and nobody
 * notices until it touches something.
 * <p>
 * So rather than asserting that particular beans are absent, the scheduling
 * check <b>scans every bean in the watermark package for any
 * {@code @Scheduled} method</b>. That makes it a standing guard: a timer
 * added in a later step without a conditional fails this test the day it is
 * written, rather than shipping quietly.
 */
@SpringBootTest(properties = "dms.watermark.enabled=false")
class WatermarkDisabledIT {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private WebApplicationContext webContext;

    @Test
    void nothingInTheWatermarkPackageSchedulesAnything() {
        List<String> scheduled = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null || !type.getName().startsWith("com.oak.dms.watermark")) {
                continue;
            }
            for (Method method : org.springframework.util.ReflectionUtils.getAllDeclaredMethods(type)) {
                if (AnnotationUtils.findAnnotation(method, Scheduled.class) != null
                        || AnnotationUtils.findAnnotation(method, Schedules.class) != null) {
                    scheduled.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(scheduled)
                .describedAs("a @Scheduled method on a bean that exists while the feature is off runs for ever "
                        + "on every deployment that never switched it on")
                .isEmpty();
    }

    @Test
    void thereIsNothingToQueueJobsOnto() {
        // The worker and its executors are what actually consume resources.
        assertThat(context.getBeanNamesForType(WatermarkJobQueue.class))
                .describedAs("no job queue implementation should exist while the feature is off")
                .isEmpty();
    }

    @Test
    void noWatermarkExecutorThreadPoolsAreCreated() {
        List<String> pools = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null && type.getName().startsWith("com.oak.dms.watermark")
                    && java.util.concurrent.Executor.class.isAssignableFrom(type)) {
                pools.add(name);
            }
        }
        assertThat(pools).isEmpty();
    }

    @Test
    void theEndpointsAreNotMappedAtAllRatherThanRefusing() {
        // A genuine 404: the feature does not exist on this deployment. Not
        // a 403 or a 503, which would both suggest it does and something
        // else is wrong.
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        UUID keycloakId = UUID.randomUUID();

        for (String path : new String[]{"/api/v1/watermark-templates",
                "/api/v1/watermark-templates/" + UUID.randomUUID(),
                "/api/v1/watermark-jobs",
                "/api/v1/watermark-jobs/" + UUID.randomUUID()}) {
            try {
                mockMvc.perform(get(path).with(jwt()
                                .jwt(builder -> builder.subject(keycloakId.toString())
                                        .claim("tenant_id", UUID.randomUUID().toString()))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))))
                        .andExpect(status().isNotFound());
            } catch (Exception e) {
                throw new AssertionError("GET " + path + " should 404 while the feature is off", e);
            }
        }
    }

    @Test
    void theAvailabilityBeanExistsButDoesNotProbeFonts() {
        // It has to exist - WatermarkService depends on it, and making that
        // conditional too would mean annotating every bean in the module,
        // where getting one wrong breaks the whole context for everybody.
        // What it must NOT do while the feature is off is load fonts.
        WatermarkAvailability availability = context.getBean(WatermarkAvailability.class);
        availability.probeFonts();

        assertThat(availability.isAvailable())
                .describedAs("the probe is skipped, so the flag stays at its optimistic default")
                .isTrue();
    }
}
