package com.abbas.hiromacro;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class MacroInterpreterTest {
    private static final class FakeHost implements MacroInterpreter.Host {
        final List<String> messages = new ArrayList<>();
        boolean running = true;
        boolean stopOnSleep;
        int taps;
        @Override public boolean isRunning() { return running; }
        @Override public void sleep(long millis) { if (stopOnSleep) running = false; }
        @Override public void log(String message) { messages.add(message); }
        @Override public void touchPath(List<int[]> points, long durationMs) { }
        @Override public void tap(int x, int y) { taps++; }
        @Override public int getColor(int x, int y) { return 0; }
    }

    @Test public void gotoWithColonResolvesLabel() throws Exception {
        FakeHost host = new FakeHost();
        String script = ":start\n"
                + "#count = 0\n"
                + "goto :done\n"
                + "#count = 99\n"
                + ":done\n"
                + "toast #count\n";
        new MacroInterpreter(host).run(script);
        assertTrue(host.messages.contains("TOAST: 0"));
    }

    @Test public void structureCheckRejectsUnknownGotoTarget() {
        String result = MacroInterpreter.validateStructure("goto :missing\n");
        assertTrue(result, result.startsWith("Structure check failed: Unknown label"));
    }

    @Test public void structureCheckRejectsDuplicateLabels() {
        String result = MacroInterpreter.validateStructure(":same\n:same\n");
        assertTrue(result, result.startsWith("Structure check failed: Duplicate label"));
    }

    @Test public void stopDiscardsBufferedTouch() throws Exception {
        FakeHost host = new FakeHost();
        host.stopOnSleep = true;
        new MacroInterpreter(host).run("touchDown 0 50 50\nsleep 10\n");
        assertEquals("No touch should be sent after STOP.", 0, host.taps);
    }

    @Test public void commandCheckRejectsTouchDownWithoutBothCoordinates() {
        String result = MacroInterpreter.validateCommands("touchDown 0 #xC\n");
        assertTrue(result, result.startsWith("Command check failed: touchDown needs id x y"));
    }

    @Test public void commandCheckAcceptsTouchUpWithOrWithoutPointerId() {
        String result = MacroInterpreter.validateCommands("touchDown 0 10 20\ntouchUp\ntouchDown 0 10 20\ntouchUp 0\n");
        assertTrue(result, result.startsWith("Command check passed."));
    }

    @Test public void commandCheckRejectsUnknownLegacyCommands() {
        String result = MacroInterpreter.validateCommands("someLegacyCommand 1 2\n");
        assertTrue(result, result.startsWith("Command check failed: unsupported or malformed command"));
    }

    @Test public void expressionRejectsUnexpectedCharacters() throws Exception {
        FakeHost host = new FakeHost();
        try {
            new MacroInterpreter(host).run("#value = 3 @ 4\n");
            fail("Invalid expression character should be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Invalid character"));
        }
    }
}
