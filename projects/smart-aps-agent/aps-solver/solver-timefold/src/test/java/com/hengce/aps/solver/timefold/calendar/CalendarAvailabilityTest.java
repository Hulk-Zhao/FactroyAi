package com.hengce.aps.solver.timefold.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可用时段判定：半开区间、整段占用必须落在单一窗口内、空窗口等价 7×24。
 */
class CalendarAvailabilityTest {

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 1, 5, 8, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 1, 5, 18, 0);

    @Test
    void windowContainsOnlyWholeBlock() {
        TimeWindow window = new TimeWindow(WINDOW_START, WINDOW_END);

        assertTrue(window.contains(WINDOW_START, WINDOW_END));
        assertTrue(window.contains(WINDOW_START.plusHours(1), WINDOW_END.minusHours(1)));
        assertFalse(window.contains(WINDOW_START.minusMinutes(1), WINDOW_END));
        assertFalse(window.contains(WINDOW_START, WINDOW_END.plusMinutes(1)));
    }

    @Test
    void emptyWindowsMeanAlwaysAllowed() {
        ResourceAvailability availability = new ResourceAvailability(
                UUID.randomUUID(), "R-CNC", List.of());

        assertTrue(availability.allows(WINDOW_START, WINDOW_END.plusDays(1)));
    }

    @Test
    void blockMustFitSingleWindow() {
        ResourceAvailability availability = new ResourceAvailability(
                UUID.randomUUID(), "R-CNC",
                List.of(new TimeWindow(WINDOW_START, WINDOW_END)));

        assertTrue(availability.allows(WINDOW_START.plusHours(2), WINDOW_START.plusHours(4)));
        assertFalse(availability.allows(WINDOW_START.minusHours(1), WINDOW_START.plusHours(1)));
    }

    @Test
    void blockCrossingTwoWindowsIsRejected() {
        ResourceAvailability availability = new ResourceAvailability(
                UUID.randomUUID(), "R-CNC",
                List.of(
                        new TimeWindow(WINDOW_START, WINDOW_END),
                        new TimeWindow(WINDOW_END.plusHours(1), WINDOW_END.plusHours(5))));

        assertFalse(availability.allows(WINDOW_END.minusHours(1), WINDOW_END.plusHours(2)));
    }
}
