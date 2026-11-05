package com.hengce.aps.service.masterdata.calendar;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.masterdata.calendar.dto.HolidayPayload;
import com.hengce.aps.service.masterdata.calendar.dto.ShiftPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 班次与节假日的结构校验、JSONB 转换与按星期切分。
 *
 * <p>班次时间按工厂墙钟时间处理，{@code endTime} 早于 {@code startTime} 表示跨夜；
 * 跨夜班次会切分为「当日 start→24:00」与「次日 00:00→end」两段参与重叠校验。</p>
 */
final class CalendarShiftRules {

    private static final int MINUTES_PER_HOUR = 60;

    private static final int MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    private static final int MIN_DAY_OF_WEEK = 1;

    private static final int MAX_DAY_OF_WEEK = 7;

    private static final Pattern TIME_PATTERN = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d|24:00");

    private CalendarShiftRules() {
    }

    /**
     * 把班次入参转换为 JSONB 结构并做重叠校验。
     *
     * @param shifts 班次入参
     * @return JSONB 数组结构
     */
    static List<Map<String, Object>> shiftsToJson(List<ShiftPayload> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "班次不能为空");
        }
        List<Map<String, Object>> result = new ArrayList<>(shifts.size());
        for (ShiftPayload shift : shifts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("startTime", shift.startTime());
            item.put("endTime", shift.endTime());
            item.put("daysOfWeek", new ArrayList<>(shift.daysOfWeek()));
            result.add(item);
        }
        validateShifts(result);
        return result;
    }

    /**
     * 把节假日入参转换为 JSONB 结构。
     *
     * @param holidays 节假日入参，可空
     * @return JSONB 数组结构
     */
    static List<Map<String, Object>> holidaysToJson(List<HolidayPayload> holidays) {
        if (holidays == null || holidays.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(holidays.size());
        for (HolidayPayload holiday : holidays) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", holiday.date().toString());
            item.put("name", holiday.name());
            item.put("kind", holiday.kind());
            result.add(item);
        }
        return result;
    }

    /**
     * 校验班次结构并检查任意星期内的时段不重叠。
     *
     * @param shifts JSONB 班次数组
     */
    static void validateShifts(List<Map<String, Object>> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "班次不能为空");
        }
        Map<Integer, List<int[]>> byDay = segments(shifts);
        for (List<int[]> daySegments : byDay.values()) {
            daySegments.sort(Comparator.comparingInt(segment -> segment[0]));
            for (int index = 1; index < daySegments.size(); index++) {
                int[] current = daySegments.get(index);
                int[] previous = daySegments.get(index - 1);
                if (current[0] < previous[1]) {
                    throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "班次时段重叠：" + format(previous[0]) + "-" + format(previous[1])
                                    + " 与 " + format(current[0]) + "-" + format(current[1]));
                }
            }
        }
    }

    /**
     * 把 JSONB 班次数组切分为按星期的分钟区间，跨夜班次拆到次日。
     *
     * @param shifts JSONB 班次数组
     * @return 星期（1-7）到区间的映射，区间为 [start,end) 分钟
     */
    static Map<Integer, List<int[]>> segments(List<Map<String, Object>> shifts) {
        Map<Integer, List<int[]>> result = new HashMap<>();
        if (shifts == null) {
            return result;
        }
        for (Map<String, Object> shift : shifts) {
            int start = minutes(shift.get("startTime"));
            int end = minutes(shift.get("endTime"));
            if (start == end) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "班次起止时间不能相同");
            }
            List<Integer> days = daysOfWeek(shift.get("daysOfWeek"));
            for (Integer day : days) {
                if (end > start) {
                    result.computeIfAbsent(day, key -> new ArrayList<>()).add(new int[] {start, end});
                } else {
                    result.computeIfAbsent(day, key -> new ArrayList<>())
                            .add(new int[] {start, MINUTES_PER_DAY});
                    result.computeIfAbsent(nextDay(day), key -> new ArrayList<>())
                            .add(new int[] {0, end});
                }
            }
        }
        return result;
    }

    /**
     * 解析 HH:mm 为当日分钟数；24:00 记为 1440。
     *
     * @param value 时间文本
     * @return 分钟数
     */
    static int minutes(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!TIME_PATTERN.matcher(text).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "班次时间格式必须为 HH:mm: " + text);
        }
        String[] parts = text.split(":");
        return Integer.parseInt(parts[0]) * MINUTES_PER_HOUR + Integer.parseInt(parts[1]);
    }

    private static List<Integer> daysOfWeek(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "班次 daysOfWeek 不能为空");
        }
        List<Integer> result = new ArrayList<>(raw.size());
        Set<Integer> seen = new HashSet<>();
        for (Object item : raw) {
            int day = ((Number) item).intValue();
            if (day < MIN_DAY_OF_WEEK || day > MAX_DAY_OF_WEEK) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "daysOfWeek 取值必须为 1-7");
            }
            if (!seen.add(day)) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "daysOfWeek 不能重复");
            }
            result.add(day);
        }
        return result;
    }

    private static int nextDay(int day) {
        return day == MAX_DAY_OF_WEEK ? MIN_DAY_OF_WEEK : day + 1;
    }

    private static String format(int minutes) {
        int hour = minutes / MINUTES_PER_HOUR;
        int minute = minutes % MINUTES_PER_HOUR;
        return String.format("%02d:%02d", hour, minute);
    }
}
