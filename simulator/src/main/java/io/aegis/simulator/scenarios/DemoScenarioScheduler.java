package io.aegis.simulator.scenarios;

import io.aegis.simulator.config.DemoProperties;
import io.aegis.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Injects a fault on a schedule so a hosted demo always has something to show.
 *
 * <p>Deliberately thin: it does not create incidents, propose actions or touch
 * any other service. It only raises an error rate on one simulated service and
 * lets the real pipeline do the rest — ingest confirms the anomaly, the
 * incident is opened, the proposer drafts a recovery, and a human has to
 * approve it. Nothing about the demo path is a shortcut through the system,
 * which is the point of having the demo at all.
 *
 * <p>Two rules keep it from becoming noise:
 *
 * <ul>
 *   <li>it never injects while another fault is active, so faults arrive one at
 *       a time rather than stacking into an unexplained pile;</li>
 *   <li>it rotates through the service list, so consecutive incidents are about
 *       different services instead of the same one failing forever.</li>
 * </ul>
 *
 * <p>Off unless {@code simulator.demo.enabled} is set.
 */
@Component
public class DemoScenarioScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemoScenarioScheduler.class);

    private final DemoProperties demo;
    private final SimulatorProperties simulator;
    private final ScenarioState scenarios;
    private final AtomicInteger next = new AtomicInteger();

    public DemoScenarioScheduler(DemoProperties demo, SimulatorProperties simulator,
                                 ScenarioState scenarios) {
        this.demo = demo;
        this.simulator = simulator;
        this.scenarios = scenarios;
        if (demo.enabled()) {
            log.info("Demo fault injection is ON: one service every {}s for {}s at {}% error rate",
                    demo.intervalSeconds(), demo.durationSeconds(), demo.errorRate());
        }
    }

    @Scheduled(fixedDelayString = "${simulator.demo.interval-seconds:180}s")
    public void injectFault() {
        if (!demo.enabled()) {
            return;
        }
        if (!scenarios.snapshot().isEmpty()) {
            // A fault is still running. Waiting for it to heal keeps the board
            // readable and lets verification succeed on the recovery action.
            return;
        }
        List<String> services = simulator.services();
        String service = services.get(Math.floorMod(next.getAndIncrement(), services.size()));
        scenarios.startErrorSpike(service, demo.errorRate(),
                Duration.ofSeconds(demo.durationSeconds()));
        log.info("Demo fault injected: {} error rate {}% for {}s",
                service, demo.errorRate(), demo.durationSeconds());
    }
}
