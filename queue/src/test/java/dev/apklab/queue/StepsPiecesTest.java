package dev.apklab.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apklab.api.ApiException;
import dev.apklab.api.QueueRequests.Step;
import dev.apklab.api.Selector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pieces of the screen in job steps: screenshot with rect and padding, and screenshotPieces, validated before a job is accepted. */
class StepsPiecesTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rectIsABoxOfTheWindowInDpGivenAsTextAListOrAnObject() {
        assertEquals("0,0,200,100", Steps.rectQuery("0, 0, 200, 100", "rect"));
        assertEquals("16,172.5,395,236", Steps.rectQuery(List.of(16, 172.5, 395, 236), "rect"));
        assertEquals("1,2,3,4", Steps.rectQuery(Map.of("left", 1, "top", 2, "right", 3, "bottom", 4), "rect"));
        for (Object bad : List.of("1,2,3", "0,0,0,10", "10,0,5,5", "a,b,c,d", List.of(0, 0, 1e6, 10)))
            assertEquals(400, assertThrows(ApiException.class, () -> Steps.rectQuery(bad, "rect")).status(), String.valueOf(bad));
    }

    @Test
    void screenshotAndScreenshotPiecesStepsAreValidated() {
        Steps.validate(List.of(Step.builder("screenshot").rect("0,0,200,100").padding(4).build(),
                Step.builder("screenshotPieces").pieces(List.of(Map.of("selector", Map.of("testTag", "search")), Map.of("rect", List.of(0, 0, 10, 10)))).padding(8).build()), true, mapper);
        ApiException both = assertThrows(ApiException.class, () -> Steps.validate(List.of(Step.builder("screenshot").rect("0,0,1,1").selector(Selector.byTestTag("x")).build()), true, mapper));
        assertTrue(both.getMessage().contains("rect or selector"), both.getMessage());
        assertThrows(ApiException.class, () -> Steps.validate(List.of(Step.builder("screenshot").rect("5,5,1,1").build()), true, mapper));
        assertThrows(ApiException.class, () -> Steps.validate(List.of(Step.builder("screenshotPieces").pieces(List.of()).build()), true, mapper));
        assertThrows(ApiException.class, () -> Steps.validate(List.of(Step.builder("screenshotPieces").pieces(List.of(Map.of("key", "k", "rect", "0,0,1,1"))).build()), true, mapper));
    }
}
