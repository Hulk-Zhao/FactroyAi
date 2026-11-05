package com.hengce.aps.service.schedule.ctp;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.solver.api.ctp.CtpRequest;
import com.hengce.aps.solver.api.ctp.CtpResult;
import com.hengce.aps.solver.api.ctp.CtpVerdict;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * C53 CTP 询单：物料侧判定 + 容量验证编排。
 *
 * <p>结论边界：</p>
 * <ul>
 *   <li>{@code OUT_OF_HORIZON}：请求交期超出 168h 视野，直接返回并带 {@code horizonEnd}；</li>
 *   <li>{@code NO_MATERIAL}：主数据/库存/在途缺失到无法判定，或触及 BOM 深度/成环限制；</li>
 *   <li>{@code UNPROMISABLE}：存在库存+在途+替代都无法补齐的缺口，或最早可齐日晚于请求交期，
 *       或容量验证三条接受条件任一不满足；</li>
 *   <li>{@code PROMISED}：物料可齐且容量验证严格通过 {@code hard==0}、全部工序已分配、
 *       完成日 ≤ 请求交期；</li>
 *   <li>{@code SOLVER_TIMEOUT}/{@code NO_ROUTE}：容量验证未完成或无路由，绝不承诺；</li>
 *   <li>{@code UNVERIFIED}：未装配容量验证器时的兼容中间态（仅物料侧单元测试使用）。</li>
 * </ul>
 *
 * <p>只读：{@code mainPlanWritten} 恒 false，不写 {@code plan_version}/{@code task}/{@code orders}/
 * {@code solve_task}。</p>
 */
@Service
public class CtpInquiryService {

    /** 证据条数上限（C53）。 */
    private static final int MAX_EVIDENCE = 8;

    /** 默认视野小时数（C53：168h）。 */
    private static final int DEFAULT_HORIZON_HOURS = 168;

    private final CtpDataReader dataReader;

    private final CtpMaterialGate materialGate;

    private final CtpCapacityVerifier capacityVerifier;

    private final ZoneId businessZone;

    private final int horizonHours;

    private final int defaultTimeoutSeconds;

    /**
     * 兼容不含容量验证的构造（物料侧单元测试可继续使用，结果停在 UNVERIFIED）。
     *
     * @param dataReader CTP 只读数据读取器
     * @param materialGate 物料门
     * @param businessTimeZone 业务时区
     * @param horizonHours 视野小时数
     * @param defaultTimeoutSeconds 缺省限时秒数
     */
    public CtpInquiryService(CtpDataReader dataReader, CtpMaterialGate materialGate,
                             String businessTimeZone, int horizonHours,
                             int defaultTimeoutSeconds) {
        this(dataReader, materialGate, null, businessTimeZone, horizonHours, defaultTimeoutSeconds);
    }

    /**
     * 构造询单服务。
     *
     * @param dataReader CTP 只读数据读取器
     * @param materialGate 物料门
     * @param capacityVerifier 容量验证器（C53 后半段）
     * @param businessTimeZone 业务时区
     * @param horizonHours 视野小时数
     * @param defaultTimeoutSeconds 缺省限时秒数
     */
    @Autowired
    public CtpInquiryService(CtpDataReader dataReader, CtpMaterialGate materialGate,
                             CtpCapacityVerifier capacityVerifier,
                             @Value("${aps.business.time-zone:Asia/Shanghai}") String businessTimeZone,
                             @Value("${aps.ctp.horizon-hours:168}") int horizonHours,
                             @Value("${aps.ctp.default-timeout-seconds:15}") int defaultTimeoutSeconds) {
        this.dataReader = dataReader;
        this.materialGate = materialGate;
        this.capacityVerifier = capacityVerifier;
        this.businessZone = ZoneId.of(businessTimeZone.trim());
        this.horizonHours = horizonHours <= 0 ? DEFAULT_HORIZON_HOURS : horizonHours;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    /**
     * 执行物料侧 CTP 询单。
     *
     * @param request 询单入参
     * @return C53 询单响应；不包含承诺日期
     * @throws BizException 参数非法（422）或物料不存在（404）
     */
    public CtpResult inquire(CtpRequest request) {
        CtpRequestValidator.NormalizedCtpRequest normalized =
                CtpRequestValidator.validate(request, defaultTimeoutSeconds);
        Material material = dataReader.findMaterialsByCodes(List.of(normalized.materialCode()))
                .get(normalized.materialCode());
        if (material == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "物料不存在：" + normalized.materialCode());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        LocalDate baseDate = now.atZoneSameInstant(businessZone).toLocalDate();
        OffsetDateTime horizonEnd = now.plusHours(horizonHours);
        if (normalized.requestedDate().atStartOfDay().atOffset(ZoneOffset.UTC).isAfter(horizonEnd)) {
            return outOfHorizon(normalized, material, horizonEnd, now);
        }
        CtpMaterialGate.Result gate = materialGate.evaluate(material, normalized.qty(),
                normalized.requestedDate(), baseDate, normalized.allowSubstitute());
        CtpVerdict verdict = verdictOf(gate, normalized.requestedDate());
        if (verdict == CtpVerdict.UNVERIFIED && capacityVerifier != null) {
            return capacityVerified(request, normalized, material, gate, now);
        }
        List<Map<String, Object>> evidence = new ArrayList<>(gate.evidence());
        if (evidence.size() < MAX_EVIDENCE) {
            evidence.add(verdictEvidence(verdict, gate, normalized.requestedDate()));
        }
        evidence = capEvidence(evidence);
        List<String> assumptions = new ArrayList<>(gate.assumptions());
        assumptions.add("PROMISED_DATE_REQUIRES_SOLVER：承诺日期只由容量验证车道（C2）返回，物料侧不给出。");
        return new CtpResult(UUID.randomUUID(), verdict, null, null, gate.materialReadyAt(), null,
                null, null, materialMap(material, gate), evidence, assumptions,
                messageOf(verdict, gate, normalized.requestedDate()), now, false);
    }

    private CtpResult capacityVerified(CtpRequest request,
                                       CtpRequestValidator.NormalizedCtpRequest normalized,
                                       Material material, CtpMaterialGate.Result gate,
                                       OffsetDateTime now) {
        String priority = request.priority() == null ? "NORMAL" : request.priority().name();
        CtpCapacityVerifier.CapacityOutcome outcome = capacityVerifier.verify(material, normalized,
                gate.materialReadyAt(), priority, now);
        List<Map<String, Object>> evidence = mergeEvidence(gate.evidence(), outcome.evidence());
        List<String> assumptions = new ArrayList<>(gate.assumptions());
        assumptions.add("PROMISED_DATE_REQUIRES_SOLVER：承诺日期由容量验证返回，物料侧不单独给出。");
        assumptions.addAll(outcome.assumptions());
        return new CtpResult(UUID.randomUUID(), outcome.verdict(), outcome.promisedDate(),
                outcome.promisedQty(), gate.materialReadyAt(), outcome.capacityVerifiedAt(), null,
                outcome.solver(), materialMap(material, gate), evidence, assumptions,
                outcome.message(), now, false);
    }

    /**
     * 物料证据在前、容量证据追加在后，共用 8 条上限；容量证据必须保留。
     */
    private List<Map<String, Object>> mergeEvidence(List<Map<String, Object>> materialEvidence,
                                                    Map<String, Object> capacityEvidence) {
        List<Map<String, Object>> merged = new ArrayList<>(materialEvidence);
        if (capacityEvidence != null) {
            if (merged.size() + 1 > MAX_EVIDENCE) {
                merged = new ArrayList<>(merged.subList(0, MAX_EVIDENCE - 1));
            }
            merged.add(capacityEvidence);
        }
        return List.copyOf(merged.size() > MAX_EVIDENCE
                ? merged.subList(0, MAX_EVIDENCE) : merged);
    }

    private CtpVerdict verdictOf(CtpMaterialGate.Result gate, LocalDate requestedDate) {
        if (gate.missingMasterData() || gate.depthLimited() || gate.cycleDetected()) {
            return CtpVerdict.NO_MATERIAL;
        }
        if (gate.materialReadyAt() == null
                || gate.materialReadyAt().isAfter(requestedDate)) {
            return CtpVerdict.UNPROMISABLE;
        }
        return CtpVerdict.UNVERIFIED;
    }

    private CtpResult outOfHorizon(CtpRequestValidator.NormalizedCtpRequest normalized,
                                   Material material, OffsetDateTime horizonEnd,
                                   OffsetDateTime now) {
        Map<String, Object> evidenceItem = new LinkedHashMap<>();
        evidenceItem.put("kind", "HORIZON_EXCEEDED");
        evidenceItem.put("taskIds", List.of());
        evidenceItem.put("resourceCode", normalized.materialCode());
        evidenceItem.put("start", null);
        evidenceItem.put("end", null);
        evidenceItem.put("detail", "请求交期 " + normalized.requestedDate()
                + " 超出 " + horizonHours + "h 视野（视野结束 " + horizonEnd.toLocalDate()
                + "），需在视野内重新询单");
        evidenceItem.put("requestedDate", normalized.requestedDate());
        evidenceItem.put("horizonEnd", horizonEnd);
        Map<String, Object> materialSummary = new LinkedHashMap<>();
        materialSummary.put("materialCode", material.getCode());
        materialSummary.put("materialName", material.getName());
        materialSummary.put("bomDepth", 0);
        materialSummary.put("shortages", List.of());
        materialSummary.put("substitutes", List.of());
        materialSummary.put("limitFlags", List.of("OUT_OF_HORIZON"));
        materialSummary.put("assumptions", List.of());
        List<String> assumptions = List.of(
                "HORIZON_168H：CTP 视野为基准时刻 + " + horizonHours + " 小时，超出视野不做物料展开。",
                "OUT_OF_HORIZON 不代表不可行，仅表示该交期无法在当前视野内验证。");
        return new CtpResult(UUID.randomUUID(), CtpVerdict.OUT_OF_HORIZON, null, null, null, null,
                horizonEnd, null, materialSummary, List.of(evidenceItem), assumptions,
                "请求交期 " + normalized.requestedDate() + " 超出 " + horizonHours
                        + "h 视野（至 " + horizonEnd.toLocalDate() + "），未做物料判定。",
                now, false);
    }

    private Map<String, Object> materialMap(Material material, CtpMaterialGate.Result gate) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("materialCode", material.getCode());
        summary.put("materialName", material.getName());
        summary.put("bomDepth", gate.bomDepth());
        summary.put("shortages", gate.shortages().stream().map(this::shortageMap).toList());
        summary.put("substitutes", gate.substitutes().stream().map(this::substituteMap).toList());
        summary.put("limitFlags", gate.limitFlags());
        summary.put("assumptions", gate.assumptions());
        return summary;
    }

    private Map<String, Object> shortageMap(CtpMaterialGate.ShortageLine line) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialCode", line.materialCode());
        item.put("materialName", line.materialName());
        item.put("level", line.level());
        item.put("requiredQty", line.requiredQty());
        item.put("availableQty", line.availableQty());
        item.put("shortageQty", line.shortageQty());
        item.put("onHandQty", line.onHandQty());
        item.put("transitQty", line.transitQty());
        item.put("safetyStockQty", line.safetyStockQty());
        item.put("expectedArrival", line.expectedArrival());
        item.put("substituteCoveredQty", line.substituteCoveredQty());
        item.put("remainingShortageQty", line.remainingQty());
        item.put("substituteCode", line.substituteCode());
        item.put("substituteReadyAt", line.substituteReadyAt());
        item.put("unresolved", line.unresolved());
        return item;
    }

    private Map<String, Object> substituteMap(CtpMaterialGate.SubstituteLine line) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialCode", line.materialCode());
        item.put("substituteCode", line.substituteCode());
        item.put("priority", line.priority());
        item.put("ratio", line.ratio());
        item.put("availableQty", line.availableQty());
        item.put("coveredQty", line.coveredQty());
        item.put("applied", line.applied());
        item.put("readyAt", line.readyAt());
        return item;
    }

    private Map<String, Object> verdictEvidence(CtpVerdict verdict, CtpMaterialGate.Result gate,
                                                LocalDate requestedDate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", verdict.name());
        item.put("taskIds", List.of());
        item.put("resourceCode", null);
        item.put("start", null);
        item.put("end", null);
        item.put("detail", messageOf(verdict, gate, requestedDate));
        item.put("materialReadyAt", gate.materialReadyAt());
        return item;
    }

    private List<Map<String, Object>> capEvidence(List<Map<String, Object>> evidence) {
        if (evidence.size() <= MAX_EVIDENCE) {
            return List.copyOf(evidence);
        }
        List<Map<String, Object>> capped = new ArrayList<>(evidence.subList(0, MAX_EVIDENCE - 1));
        Map<String, Object> truncated = new LinkedHashMap<>();
        truncated.put("kind", "EVIDENCE_TRUNCATED");
        truncated.put("taskIds", List.of());
        truncated.put("resourceCode", null);
        truncated.put("start", null);
        truncated.put("end", null);
        truncated.put("detail", "另有 " + (evidence.size() - MAX_EVIDENCE + 1)
                + " 条证据未展示（已按严重度截断）");
        capped.add(truncated);
        return List.copyOf(capped);
    }

    private String messageOf(CtpVerdict verdict, CtpMaterialGate.Result gate,
                             LocalDate requestedDate) {
        if (verdict == CtpVerdict.NO_MATERIAL) {
            return "物料主数据或 BOM 结构不足以判定可齐日（限制标记："
                    + String.join("、", gate.limitFlags()) + "），需补全数据后重新询单。";
        }
        if (verdict == CtpVerdict.UNPROMISABLE) {
            if (gate.materialReadyAt() == null) {
                return "存在无法由库存/在途/替代料补齐的物料缺口，物料可齐日不可确定，不能承诺 "
                        + requestedDate + "。";
            }
            return "物料最早可齐日 " + gate.materialReadyAt() + " 晚于请求交期 " + requestedDate
                    + "，不能承诺该交期。";
        }
        return "物料可齐（最早 " + gate.materialReadyAt() + "），产能尚未由求解器验证；"
                + "本车道不返回承诺日期，需容量验证后才能给出承诺。";
    }
}
