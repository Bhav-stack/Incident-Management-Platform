package io.aegis.simulator.scenarios;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceSimulatorTest {

    private final ScenarioState scenarios = new ScenarioState();
    private final ServiceSimulator simulator = new ServiceSimulator(scenarios);

    @Test
    void restartHealsAnErrorSpike() {
        scenarios.startErrorSpike("checkout-service", 8.5, Duration.ofMinutes(5));

        assertTrue(simulator.status("checkout-service").errorRate() > 5.0);
        simulator.execute("restart_instance", "checkout-service", Map.of());

        assertEquals(1.0, simulator.status("checkout-service").errorRate(),
                "restart must clear the error spike back to baseline");
    }

    @Test
    void stubbornFailureIgnoresRecovery() {
        scenarios.startStubborn("checkout-service", Duration.ofMinutes(5));

        simulator.execute("restart_instance", "checkout-service", Map.of());
        simulator.execute("scale_replicas", "checkout-service", Map.of("to", 8));

        assertEquals(8.0, simulator.status("checkout-service").errorRate(),
                "stubborn failures must persist despite recovery actions");
    }

    @Test
    void scaleTracksReplicasForUndo() {
        simulator.execute("scale_replicas", "checkout-service", Map.of("to", 8));
        assertEquals(8, simulator.status("checkout-service").replicas());

        simulator.execute("scale_replicas", "checkout-service", Map.of("to", 2));
        assertEquals(2, simulator.status("checkout-service").replicas(),
                "undo restores the pre-action replica count");
    }

    @Test
    void unknownActionFails() {
        assertFalse(simulator.execute("nuke_cluster", "checkout-service", Map.of()).success());
    }
}