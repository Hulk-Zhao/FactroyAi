package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.solver.api.AvailabilityWindow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 日历展开单测：覆盖 7×24 连续窗口、法定假日挖洞与空班次降级三种场景。
 */
class ScheduleProblemAssemblerCalendarTest {

    /**
     * 被测装配器：展开逻辑不依赖任何 Mapper。
     */
    private final ScheduleProblemAssembler assembler = new ScheduleProblemAssembler(
            null, null, null, null, null, null, null, null, null, "Asia/Shanghai");

    @Test
    void expandSevenDayShiftsShouldMergeIntoOneWindow() {
        Calendar calendar = calendar("00:00", "24:00", List.of(1, 2, 3, 4, 5, 6, 7));
        OffsetDateTime from = OffsetDateTime.parse("2026-11-04T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-11T00:00:00Z");

        List<AvailabilityWindow> windows = assembler.expand(calendar, from, to);

        assertEquals(1, windows.size());
        assertEquals(from, windows.get(0).start());
        assertEquals(to, windows.get(0).end());
    }

    @Test
    void expandShouldRemoveLegalHolidayFromWindows() {
        Calendar calendar = calendar("08:00", "17:00", List.of(1, 2, 3, 4, 5));
        calendar.setHolidays(List.of(Map.of("date", "2026-11-05", "name", "holiday", "kind", "LEGAL")));
        OffsetDateTime from = OffsetDateTime.parse("2026-11-04T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-07T00:00:00Z");

        List<AvailabilityWindow> windows = assembler.expand(calendar, from, to);

        assertEquals(2, windows.size());
        OffsetDateTime holidayMorning = OffsetDateTime.parse("2026-11-05T10:00:00Z");
        assertTrue(windows.stream().noneMatch(window ->
                window.start().isBefore(holidayMorning) && window.end().isAfter(holidayMorning)));
    }

    @Test
    void expandEmptyShiftsShouldReturnEmptyForCalendarFallback() {
        Calendar calendar = new Calendar();
        calendar.setName("empty");
        calendar.setType("FACTORY");
        calendar.setShifts(List.of());
        OffsetDateTime from = OffsetDateTime.parse("2026-11-04T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-05T00:00:00Z");

        assertTrue(assembler.expand(calendar, from, to).isEmpty());
    }

    private static Calendar calendar(String start, String end, List<Integer> days) {
        Calendar calendar = new Calendar();
        calendar.setName("test");
        calendar.setType("FACTORY");
        calendar.setCalendarVersion(1);
        calendar.setEffectiveFrom(LocalDate.parse("2026-01-01"));
        calendar.setShifts(List.of(Map.of(
                "startTime", start,
                "endTime", end,
                "daysOfWeek", days)));
        return calendar;
    }

    /**
     * 回归：东八区 08:00 早班必须落在本地 08:00，而不是被当成 UTC 后落到本地 16:00。
     */
    @Test
    void expandShiftShouldLandOnBusinessLocalTime() {
        Calendar calendar = calendar("08:00", "12:00", List.of(1, 2, 3, 4, 5));
        OffsetDateTime from = OffsetDateTime.parse("2026-11-04T00:00:00+08:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-05T00:00:00+08:00");
        OffsetDateTime expectedStart = OffsetDateTime.parse("2026-11-04T08:00:00+08:00");

        List<AvailabilityWindow> windows = assembler.expand(calendar, from, to);

        assertEquals(1, windows.size());
        assertEquals(expectedStart, windows.get(0).start().withOffsetSameInstant(expectedStart.getOffset()));
    }

    /**
     * 确保 UTC 偏移参与断言，避免依赖机器时区。
     */
    @Test
    void expandShouldUseUtcOffset() {
        Calendar calendar = calendar("00:00", "24:00", List.of(1, 2, 3, 4, 5, 6, 7));
        OffsetDateTime from = OffsetDateTime.parse("2026-11-04T08:00:00+08:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-05T08:00:00+08:00");

        List<AvailabilityWindow> windows = assembler.expand(calendar, from, to);

        assertEquals(1, windows.size());
        assertEquals(from.toInstant(), windows.get(0).start().toInstant());
        assertEquals(to.toInstant(), windows.get(0).end().toInstant());
    }
}
