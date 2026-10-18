package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolchainTest {

    @Test
    void testsRunOnJava17() {
        assertEquals(17, Runtime.version().feature());
    }
}
