package com.smartgn.management.bdd;

import io.cucumber.core.cli.Main;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BddAcceptanceTest {
    @Test void executableGherkinAcceptanceScenarios() {
        byte result = Main.run(new String[] {"--glue", "com.smartgn.management.bdd", "--plugin", "pretty", "classpath:features"},
                getClass().getClassLoader());
        assertEquals(0, result, "All Gherkin acceptance scenarios must execute successfully");
    }
}
