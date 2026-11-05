package com.hengce.aps.service.importexport.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import com.hengce.aps.service.masterdata.calendar.CalendarService;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarCreateRequest;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarResponse;
import com.hengce.aps.service.masterdata.calendar.dto.HolidayPayload;
import com.hengce.aps.service.masterdata.calendar.dto.ShiftPayload;
import com.hengce.aps.service.masterdata.resource.ResourceService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 日历 Excel 导入：班次/例外日 JSON 解析后交给 {@link CalendarService#create}，
 * 版本递增、资源绑定、日期区间等校验由服务层执行。
 */
@Component
public class CalendarImportHandler implements EntityImportHandler {

    private static final int PAGE_SIZE = 200;

    private static final int CODE_42200 = 42200;

    private static final Pattern START_TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private static final Pattern END_TIME = Pattern.compile("(24:00|([01]\\d|2[0-3]):[0-5]\\d)");

    private static final Pattern HOLIDAY_KIND = Pattern.compile("LEGAL|COMPANY|MAKEUP");

    private final CalendarService calendarService;

    private final ResourceService resourceService;

    private final ObjectMapper objectMapper;

    public CalendarImportHandler(CalendarService calendarService, ResourceService resourceService,
                                 ObjectMapper objectMapper) {
        this.calendarService = calendarService;
        this.resourceService = resourceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.CALENDARS;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        String name = ImportValues.requiredText(row, "name", "日历名称", 256);
        Integer version = ImportValues.integer(ImportValues.text(row, "calendarVersion"),
                "calendarVersion", "日历版本", 1);
        if (version == null) {
            throw new ImportRowException("calendarVersion", CODE_42200, "日历版本不能为空");
        }
        String type = ImportValues.enumValue(ImportValues.text(row, "type"), "type",
                entity().column("type").allowedValues(), "FACTORY");
        LocalDate effectiveFrom = requiredDate(row, "effectiveFrom", "生效日期");
        LocalDate effectiveTo = ImportValues.date(ImportValues.text(row, "effectiveTo"),
                "effectiveTo", "失效日期");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ImportRowException("effectiveTo", CODE_42200, "失效日期不能早于生效日期");
        }
        String resourceCode = ImportValues.text(row, "resourceCode");
        UUID resourceId = null;
        if (!"FACTORY".equals(type)) {
            if (resourceCode == null || resourceCode.isBlank()) {
                throw new ImportRowException("resourceCode", CODE_42200,
                        type + " 类型日历必须填写资源编码");
            }
            resourceId = resolveResource(resourceCode, "resourceCode");
        } else if (resourceCode != null && !resourceCode.isBlank()) {
            resourceId = resolveResource(resourceCode, "resourceCode");
        }
        List<ShiftPayload> shifts = parseShifts(ImportValues.text(row, "shiftsJson"));
        List<HolidayPayload> holidays = parseHolidays(ImportValues.text(row, "holidaysJson"));

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("name", name);
        normalized.put("calendarVersion", version);
        normalized.put("type", type);
        normalized.put("resourceCode", resourceCode);
        normalized.put("resourceId", resourceId);
        normalized.put("effectiveFrom", effectiveFrom);
        normalized.put("effectiveTo", effectiveTo);
        normalized.put("shiftsJson", ImportValues.text(row, "shiftsJson"));
        normalized.put("holidaysJson", ImportValues.text(row, "holidaysJson"));
        normalized.put("shifts", shifts);
        normalized.put("holidays", holidays);
        return normalized;
    }

    @Override
    @SuppressWarnings("unchecked")
    public WriteOutcome write(Map<String, Object> normalized) {
        String name = (String) normalized.get("name");
        Integer version = (Integer) normalized.get("calendarVersion");
        if (exists(name, version)) {
            return WriteOutcome.SKIPPED;
        }
        calendarService.create(new CalendarCreateRequest(name, version, (String) normalized.get("type"),
                (UUID) normalized.get("resourceId"), null,
                (LocalDate) normalized.get("effectiveFrom"), (LocalDate) normalized.get("effectiveTo"),
                (List<ShiftPayload>) normalized.get("shifts"),
                (List<HolidayPayload>) normalized.get("holidays")));
        return WriteOutcome.INSERTED;
    }

    private boolean exists(String name, Integer version) {
        PageQuery query = new PageQuery();
        query.setSize(PAGE_SIZE);
        for (CalendarResponse response : calendarService.list(name, null, null, null, query).items()) {
            if (response.name().equals(name) && response.calendarVersion().equals(version)) {
                return true;
            }
        }
        return false;
    }

    private List<ShiftPayload> parseShifts(String json) {
        JsonNode node = readArray(json, "shiftsJson", true);
        List<ShiftPayload> shifts = new ArrayList<>(node.size());
        for (JsonNode shiftNode : node) {
            String start = firstText(shiftNode, "startTime", "start");
            String end = firstText(shiftNode, "endTime", "end");
            if (start == null || !START_TIME.matcher(start).matches()) {
                throw new ImportRowException("shiftsJson", CODE_42200, "班次开始时间格式必须为 HH:mm");
            }
            if (end == null || !END_TIME.matcher(end).matches()) {
                throw new ImportRowException("shiftsJson", CODE_42200, "班次结束时间格式必须为 HH:mm 或 24:00");
            }
            shifts.add(new ShiftPayload(start, end, daysOfWeek(shiftNode)));
        }
        return shifts;
    }

    private List<Integer> daysOfWeek(JsonNode shiftNode) {
        JsonNode daysNode = shiftNode.path("daysOfWeek");
        if (!daysNode.isArray() || daysNode.isEmpty()) {
            return List.of(1, 2, 3, 4, 5, 6, 7);
        }
        List<Integer> days = new ArrayList<>(daysNode.size());
        for (JsonNode dayNode : daysNode) {
            int day = dayNode.asInt();
            if (day < 1 || day > 7) {
                throw new ImportRowException("shiftsJson", CODE_42200, "daysOfWeek 取值必须在 1-7 之间");
            }
            days.add(day);
        }
        return days;
    }

    private List<HolidayPayload> parseHolidays(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode node = readArray(json, "holidaysJson", false);
        List<HolidayPayload> holidays = new ArrayList<>(node.size());
        for (JsonNode holidayNode : node) {
            LocalDate date = ImportValues.date(holidayNode.path("date").asText(null),
                    "holidaysJson", "节假日日期");
            String name = holidayNode.path("name").asText(null);
            String kind = holidayNode.path("kind").asText("LEGAL");
            if (date == null) {
                throw new ImportRowException("holidaysJson", CODE_42200, "节假日日期缺少 date");
            }
            if (name == null || name.isBlank()) {
                throw new ImportRowException("holidaysJson", CODE_42200, "节假日名称不能为空");
            }
            if (!HOLIDAY_KIND.matcher(kind).matches()) {
                throw new ImportRowException("holidaysJson", CODE_42200,
                        "kind 只允许 LEGAL/COMPANY/MAKEUP");
            }
            holidays.add(new HolidayPayload(date, name, kind));
        }
        return holidays;
    }

    private JsonNode readArray(String json, String field, boolean required) {
        if (json == null || json.isBlank()) {
            if (required) {
                throw new ImportRowException(field, CODE_42200, "该字段不能为空");
            }
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                throw new ImportRowException(field, CODE_42200, "该字段必须是 JSON 数组");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ImportRowException(field, CODE_42200, "JSON 格式错误: " + exception.getOriginalMessage());
        }
    }

    private String firstText(JsonNode node, String primary, String fallback) {
        String value = node.path(primary).asText(null);
        return value == null ? node.path(fallback).asText(null) : value;
    }

    private UUID resolveResource(String code, String field) {
        try {
            return resourceService.get(code).id();
        } catch (BizException exception) {
            throw new ImportRowException(field, CODE_42200, "资源不存在: " + code);
        }
    }

    private LocalDate requiredDate(Map<String, String> row, String key, String label) {
        LocalDate value = ImportValues.date(ImportValues.text(row, key), key, label);
        if (value == null) {
            throw new ImportRowException(key, CODE_42200, label + "不能为空");
        }
        return value;
    }
}
