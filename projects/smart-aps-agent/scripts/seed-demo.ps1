<#
.SYNOPSIS
    统一演示种子：储能 PACK + SMT 电子制造的端到端 APS 演示数据。

.DESCRIPTION
    只写指定目标库，拒绝 aps_local* / postgres / template*。
    幂等：先按固定 UUID 与业务键清理本脚本数据，再在单事务内插入；重复执行行数一致。
    -Reset 只清理本脚本演示数据，不 DROP 数据库、不碰其它车道的数据。

.PARAMETER Database
    必填。目标数据库名，例如 aps_demo_verify。必须已存在且已执行 V1–V14 迁移。

.PARAMETER Scene
    all | master | orders | sandbox | solve | plans | audit
      master  = 物料/BOM/资源/日历/工序模版/工艺路线/库存/换型矩阵
      orders  = master + 客户/供应商/订单/策略（含 8 条 strategy_rule）
      sandbox = orders + BASELINE / WHAT_IF / ARCHIVED 任务
      solve   = sandbox + 求解任务与约束归因 + 人工调整
      plans   = solve + 已发布计划版本 + 下发 outbox
      audit   = 仅审计日志 / 导入任务 / 演示用户（不碰业务数据）
      all     = 全部

.PARAMETER Reset
    只清理演示数据后退出。

.PARAMETER AnchorDate
    可选。业务基准日（yyyy-MM-dd，Asia/Shanghai），缺省为运行当天。
    所有业务日期（订单交期、冻结任务起止、锁窗、节假日、在途、快照、求解/计划时间）
    都由它按固定偏移派生，脚本内不再写绝对日期。固定该值可复现同一份日期。

.EXAMPLE
    .\scripts\seed-demo.ps1 -Database aps_demo_verify -Scene all
.EXAMPLE
    .\scripts\seed-demo.ps1 -Database aps_demo_verify -Scene all -AnchorDate 2026-11-05
.EXAMPLE
    .\scripts\seed-demo.ps1 -Database aps_demo_verify -Reset
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Database,

    [ValidateSet('all', 'master', 'orders', 'sandbox', 'solve', 'plans', 'audit')]
    [string]$Scene = 'all',

    [switch]$Reset,

    [string]$AnchorDate = '',

    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 55432,
    [string]$DbUser = 'aps',
    [string]$DbPassword = 'aps_dev_pwd',
    [string]$TenantId = '00000000-0000-0000-0000-000000000001',
    [string]$AdminUserId = '00000000-0000-0000-0000-000000000301',
    [string]$BaselineSandboxId = '00000000-0000-0000-0000-000000000401',
    [string]$PsqlPath = 'D:\tools\_aps-toolchain\pgsql\bin\psql.exe'
)

$ErrorActionPreference = 'Stop'

# ----------------------------------------------------------------------------- 安全校验
if ($Database -match '^aps_local') { throw "拒绝操作基线库：$Database（aps_local / aps_local_2 受保护）" }
if ($Database -in @('postgres', 'template0', 'template1')) { throw "拒绝操作系统库：$Database" }
if (-not (Test-Path $PsqlPath)) { throw "psql 不存在：$PsqlPath" }

$env:PGPASSWORD = $DbPassword
$psqlArgs = @('-h', $DbHost, '-p', "$DbPort", '-U', $DbUser, '-v', 'ON_ERROR_STOP=1')

$exists = (& $PsqlPath @psqlArgs -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$Database';")
if (-not $exists) { throw "目标库不存在：$Database（请先创建并执行 V1–V14 迁移）" }

# ----------------------------------------------------------------------------- 基准日（所有业务日期相对它派生）
# 缺省 = 运行当天（Asia/Shanghai，UTC+8，无夏令时）；固定 -AnchorDate 可复现同一份日期。
# 偏移约定：FROZEN 任务与锁窗 +1~+5 天（求解窗口约 now-2h ~ now+7d，留头留尾）；
#           订单交期 +20~+53 天（窗口外，不做窗口边沿）；历史上/元数据用负偏移。
function Resolve-AnchorDay([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) {
        return [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8)).Date
    }
    $parsed = [datetime]::MinValue
    $ok = [datetime]::TryParseExact($value.Trim(), 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$parsed)
    if (-not $ok) { throw "AnchorDate 必须为 yyyy-MM-dd：$value" }
    return $parsed
}
$AnchorDayBase = Resolve-AnchorDay $AnchorDate
function AnchorDay([int]$days) { return $AnchorDayBase.AddDays($days).ToString('yyyy-MM-dd') }
function AnchorTime([int]$days, [string]$time) { return $AnchorDayBase.AddDays($days).ToString('yyyy-MM-dd') + 'T' + $time + '+08:00' }
function AnchorMd([int]$days) { return $AnchorDayBase.AddDays($days).ToString('MM/dd') }

# ----------------------------------------------------------------------------- 固定标识
$tenant = $TenantId
function DemoUuid([string]$group, [int]$n) { return "${group}-0000-0000-0000-$('{0:D12}' -f $n)" }
function Q([string]$v) { return "'" + $v.Replace("'", "''") + "'" }
function J($obj) { return (ConvertTo-Json -InputObject $obj -Compress -Depth 12) }
function NullOr([string]$v) { return $(if ($v) { $v } else { 'NULL' }) }

# 复用 seed-m3-customers.ps1 的固定客户（CUS-001..CUS-008），不另造客户实体
$custIds    = 1..8  | ForEach-Object { 'd0000000-0000-0000-0000-00000000090' + $_ }
$supIds     = 1..4  | ForEach-Object { DemoUuid 'e1110000' $_ }
$catIds     = 1..5  | ForEach-Object { DemoUuid 'e1120000' $_ }
$matIds     = 1..26 | ForEach-Object { DemoUuid 'e1130000' $_ }
$bomIds     = 1..8  | ForEach-Object { DemoUuid 'e1140000' $_ }
$bomItemIds = 1..40 | ForEach-Object { DemoUuid 'e1150000' $_ }
$calIds     = 1..3  | ForEach-Object { DemoUuid 'e1160000' $_ }
$grpIds     = 1..5  | ForEach-Object { DemoUuid 'e1170000' $_ }
$resIds     = 1..12 | ForEach-Object { DemoUuid 'e1180000' $_ }
$opIds      = 1..8  | ForEach-Object { DemoUuid 'e1190000' $_ }
$routingIds = 1..3  | ForEach-Object { DemoUuid 'e11a0000' $_ }
$nodeIds    = 1..18 | ForEach-Object { DemoUuid 'e11b0000' $_ }
$edgeIds    = 1..15 | ForEach-Object { DemoUuid 'e11c0000' $_ }
$invIds     = 1..22 | ForEach-Object { DemoUuid 'e11d0000' $_ }
$transitIds = 1..5  | ForEach-Object { DemoUuid 'e11e0000' $_ }
$orderIds   = 1..10 | ForEach-Object { DemoUuid 'e2200000' $_ }
$stratIds   = 1..2  | ForEach-Object { DemoUuid 'e2400000' $_ }
$ruleIds    = 1..16 | ForEach-Object { DemoUuid 'e2410000' $_ }
$justIds    = 1..4  | ForEach-Object { DemoUuid 'e2600000' $_ }
$whatIfId   = DemoUuid 'e2700000' 2
$archivedId = DemoUuid 'e2700000' 3
$planIds    = 1..4  | ForEach-Object { DemoUuid 'e2800000' $_ }
$setupIds   = 1..6  | ForEach-Object { DemoUuid 'e2c00000' $_ }
$dispatchId = DemoUuid 'e2d00000' 1
$importIds  = 1..2  | ForEach-Object { DemoUuid 'e2e00000' $_ }
$demoUserId1 = 'e2100000-0000-0000-0000-000000000301'
$demoUserId2 = 'e2100000-0000-0000-0000-000000000302'

$matIdsList   = ($matIds   | ForEach-Object { Q $_ }) -join ','
$orderIdsList = ($orderIds | ForEach-Object { Q $_ }) -join ','
$planIdsList  = ($planIds  | ForEach-Object { Q $_ }) -join ','
$routingIdsList = ($routingIds | ForEach-Object { Q $_ }) -join ','
$grpIdsList   = ($grpIds | ForEach-Object { Q $_ }) -join ','
$resIdsList   = ($resIds | ForEach-Object { Q $_ }) -join ','
$calIdsList   = ($calIds | ForEach-Object { Q $_ }) -join ','
$opIdsList    = ($opIds  | ForEach-Object { Q $_ }) -join ','
$bomIdsList   = ($bomIds | ForEach-Object { Q $_ }) -join ','
$stratIdsList = ($stratIds | ForEach-Object { Q $_ }) -join ','
$setupIdsList = ($setupIds | ForEach-Object { Q $_ }) -join ','
$catIdsList   = ($catIds | ForEach-Object { Q $_ }) -join ','
$custIdsList  = ($custIds | ForEach-Object { Q $_ }) -join ','
$supIdsList   = ($supIds | ForEach-Object { Q $_ }) -join ','
$importIdsList= ($importIds | ForEach-Object { Q $_ }) -join ','
$justIdsList  = ($justIds | ForEach-Object { Q $_ }) -join ','

$hasSetupMatrix = (& $PsqlPath @psqlArgs -d $Database -Atc "SELECT (to_regclass('public.setup_matrix_entry') IS NOT NULL);") -eq 't'
$hasAtp = (& $PsqlPath @psqlArgs -d $Database -Atc "SELECT (to_regclass('public.atp_result') IS NOT NULL OR to_regclass('public.atp_query') IS NOT NULL);") -eq 't'

# ----------------------------------------------------------------------------- 数据定义
$categories = @(
    @{ id = $catIds[0]; code = 'DM-CAT-RAW';  name = '原材料' },
    @{ id = $catIds[1]; code = 'DM-CAT-SEMI'; name = '半成品' },
    @{ id = $catIds[2]; code = 'DM-CAT-FIN';  name = '成品' },
    @{ id = $catIds[3]; code = 'DM-CAT-AUX';  name = '辅料' },
    @{ id = $catIds[4]; code = 'DM-CAT-PACK'; name = '包装材料' }
)

# 与 scripts/seed-m3-customers.ps1 完全一致的 8 家客户（同 UUID/编码/名称/优先级），
# 因此无论先跑哪条车道都只有一套客户实体；本脚本按 (tenant_id, code) 幂等 upsert。
$customers = @(
    @{ id = $custIds[0]; code = 'CUS-001'; name = '华北储能客户 A';       priority = 'VIP';    lead = 3;  payment = '月结30天';     black = 'false'; ext = '{"contact":"张伟","phone":"010-88991234","shipTo":"河北省张家口市桥东区工业园 1 号","creditLimit":5000000,"currency":"CNY"}' },
    @{ id = $custIds[1]; code = 'CUS-002'; name = '华东渠道客户 B';       priority = 'NORMAL'; lead = 5;  payment = '月结45天';     black = 'false'; ext = '{"contact":"李娜","phone":"021-55667788","shipTo":"江苏省苏州市工业园区星湖街 88 号","creditLimit":3000000,"currency":"CNY"}' },
    @{ id = $custIds[2]; code = 'CUS-003'; name = '华南系统集成客户 C';   priority = 'NORMAL'; lead = 7;  payment = '货到付款';     black = 'false'; ext = '{"contact":"陈志强","phone":"020-38381111","shipTo":"广东省深圳市宝安区福海街道","creditLimit":2000000,"currency":"CNY"}' },
    @{ id = $custIds[3]; code = 'CUS-004'; name = '西南分布式客户 D';     priority = 'LOW';    lead = 10; payment = '月结60天';     black = 'false'; ext = '{"contact":"赵敏","phone":"028-86665555","shipTo":"四川省成都市青白江区物流园","creditLimit":1200000,"currency":"CNY"}' },
    @{ id = $custIds[4]; code = 'CUS-005'; name = '海外出口客户 E';       priority = 'VIP';    lead = 14; payment = 'T/T 30% 预付'; black = 'false'; ext = '{"contact":"Anna Schmidt","phone":"+49-30-1234567","shipTo":"Hamburg Port, Germany","creditLimit":8000000,"currency":"EUR"}' },
    @{ id = $custIds[5]; code = 'CUS-006'; name = '西北电网侧客户 F';     priority = 'NORMAL'; lead = 21; payment = '月结90天';     black = 'false'; ext = '{"contact":"马建国","phone":"0931-4666888","shipTo":"甘肃省兰州市西固区电网产业园","creditLimit":4000000,"currency":"CNY"}' },
    @{ id = $custIds[6]; code = 'CUS-007'; name = '华中工商业储能客户 G'; priority = 'LOW';    lead = 15; payment = '月结60天';     black = 'false'; ext = '{"contact":"周晓峰","phone":"027-87775555","shipTo":"湖北省武汉市东湖高新区","creditLimit":1500000,"currency":"CNY"}' },
    @{ id = $custIds[7]; code = 'CUS-008'; name = '高风险账期客户 H';     priority = 'LOW';    lead = 30; payment = '月结120天';    black = 'true'; ext = '{"contact":"吴海涛","phone":"029-85556666","shipTo":"陕西省西安市泾渭工业园","creditLimit":500000,"currency":"CNY"}' }
)


$suppliers = @(
    @{ id = $supIds[0]; code = 'SUP-D01'; name = '宁德时代新能源科技'; lead = 14; moq = 1000; grade = 'A'; ext = '{"contact":"周启明","phone":"0593-2888000","email":"sales@catl-demo.cn"}' },
    @{ id = $supIds[1]; code = 'SUP-D02'; name = '鹏鼎控股电子';       lead = 10; moq = 500;  grade = 'A'; ext = '{"contact":"林嘉怡","phone":"0755-33886666","email":"pcb@zdt-demo.cn"}' },
    @{ id = $supIds[2]; code = 'SUP-D03'; name = '瑞可达连接系统';     lead = 7;  moq = 1000; grade = 'B'; ext = '{"contact":"王振","phone":"0512-66118888","email":"conn@recode-demo.cn"}' },
    @{ id = $supIds[3]; code = 'SUP-D04'; name = '珠海冠宇结构件';     lead = 12; moq = 300;  grade = 'B'; ext = '{"contact":"陈思远","phone":"0756-3831999","email":"case@gy-demo.cn"}' }
)

$materials = @(
    @{ id = $matIds[0];  code = 'RAW-CELL-001';    name = '磷酸铁锂方形电芯 3.2V/280Ah'; type = 'RAW';      spec = 'LF280K 方形铝壳，≥8000 次循环'; unit = '只'; cat = $catIds[0]; safety = 200; moq = 1000; lead = 14; sup = $supIds[0]; ind = '{"material":"磷酸铁锂","cell_model":"LF280K","nominal_capacity_ah":280,"nominal_voltage_v":3.2,"dimensions_mm":"173.9×71.7×207.2","grading_rule":"容量分档 A/B/C"}' },
    @{ id = $matIds[1];  code = 'RAW-SMT-001';     name = '六层 FR-4 控制主板 PCB';    type = 'RAW';      spec = 'FR-4 1.6mm 沉金，280×180mm'; unit = '片'; cat = $catIds[0]; safety = 50;  moq = 500;  lead = 10; sup = $supIds[1]; ind = '{"material":"FR-4","layers":6,"thickness_mm":1.6,"dimensions_mm":"280×180","surface":"沉金","panel_size":8,"msd_level":3,"exposure_hours":168,"bake_hours":12}' },
    @{ id = $matIds[2];  code = 'RAW-SMT-002';    name = '车规 MCU 与功率器件套片';  type = 'RAW';      spec = 'AEC-Q100 Grade 2，MCU+IGBT+驱动'; unit = '套'; cat = $catIds[0]; safety = 500; moq = 2000; lead = 21; sup = $supIds[1]; ind = '{"voltage_v":3.3,"package":"LQFP-144/BGA","grade":"AEC-Q100 Grade 2","msd_level":3,"exposure_hours":72,"bake_hours":24}' },
    @{ id = $matIds[3];  code = 'RAW-STR-001';    name = 'PACK 液冷铝箱体 100kWh';    type = 'RAW';      spec = '6061-T6 铝型材，IP67，1000V 绝缘'; unit = '个'; cat = $catIds[0]; safety = 20;  moq = 300;  lead = 12; sup = $supIds[3]; ind = '{"material":"6061-T6 铝合金","protection":"IP67","voltage_v":1000,"dimensions_mm":"1200×800×2200","process":"型材焊接+CNC"}' },
    @{ id = $matIds[4];  code = 'RAW-ELEC-001';    name = '高压铜排连接组件';           type = 'RAW';      spec = 'T2 紫铜 30×8，镀镍 25mm²'; unit = '套'; cat = $catIds[0]; safety = 100; moq = 1000; lead = 7;  sup = $supIds[2]; ind = '{"material":"T2 紫铜","cross_section_mm2":25,"voltage_v":1500,"plating":"镀镍","dimensions_mm":"30×8"}' },
    @{ id = $matIds[5];  code = 'RAW-ELEC-002';    name = '高压直流熔断器 FWH-500A';  type = 'RAW';      spec = 'DC 1000V / 500A，IEC 60269-7'; unit = '只'; cat = $catIds[0]; safety = 20;  moq = 500;  lead = 10; sup = $supIds[2]; ind = '{"rated_voltage_v":1000,"rated_current_a":500,"standard":"IEC 60269-7","breaking_capacity_ka":50,"mounting":"螺栓式"}' },
    @{ id = $matIds[6];  code = 'RAW-ELEC-003';    name = '高压接线端子组件 M8-25mm²'; type = 'RAW';     spec = 'M8 螺栓压接，25mm²，镀锡铜'; unit = '套'; cat = $catIds[0]; safety = 100; moq = 1000; lead = 7;  sup = $supIds[2]; ind = '{"material":"T2 紫铜","bolt":"M8","cross_section_mm2":25,"plating":"镀锡","rated_current_a":200}' },
    @{ id = $matIds[7];  code = 'SEMI-SMT-001';   name = 'SMT 控制主板 PCBA（双面混装）'; type = 'SEMI'; spec = '双面混装 680 点，六层沉金板'; unit = '块'; cat = $catIds[1]; safety = 30;  moq = 100;  lead = 3;  sup = $null;      ind = '{"board_layers":6,"placement_points":680,"side":"双面","process":"SMT 贴片+回流焊","test":"AOI+ICT"}' },
    @{ id = $matIds[8];  code = 'SEMI-MOD-001'; name = '1P16S 磷酸铁锂电池模组 51.2V/280Ah'; type = 'SEMI'; spec = '16 串 51.2V / 14.3kWh，激光焊接'; unit = '组'; cat = $catIds[1]; safety = 30;  moq = 50;   lead = 5;  sup = $null;      ind = '{"configuration":"1P16S","nominal_voltage_v":51.2,"capacity_ah":280,"energy_kwh":14.3,"welding":"激光焊接","busbar":"铝排"}' },
    @{ id = $matIds[9];  code = 'FG-ESS-001';      name = '工商业储能电池柜 ESS-100（液冷）'; type = 'FINISHED'; spec = '磷酸铁锂 1P52S，100kWh / 1000V，液冷'; unit = '台'; cat = $catIds[2]; safety = 5; moq = 10; lead = 20; sup = $null; ind = '{"configuration":"1P52S","energy_kwh":100,"cooling":"液冷","voltage_v":1000,"protection":"IP55","standard":"GB/T 36276"}' },
    @{ id = $matIds[10]; code = 'FG-ESS-002';      name = '电网侧储能电池柜 ESS-215（风冷）'; type = 'FINISHED'; spec = '磷酸铁锂 1P104S，215kWh，20 尺集装箱单元'; unit = '台'; cat = $catIds[2]; safety = 5; moq = 10; lead = 25; sup = $null; ind = '{"configuration":"1P104S","energy_kwh":215,"cooling":"风冷","voltage_v":1331,"protection":"IP54","container":"20 尺"}' },
    @{ id = $matIds[11]; code = 'AUX-CHEM-001'; name = '乙二醇冷却液（50% 水溶液）'; type = 'AUX';     spec = '50% 乙二醇水溶液，-40℃~+120℃，20L/桶'; unit = '桶'; cat = $catIds[3]; safety = 20;  moq = 200;  lead = 5;  sup = $supIds[3]; ind = '{"concentration_pct":50,"freezing_point_c":-40,"boiling_point_c":120,"volume_l":20,"standard":"GB 29743"}' },
    # 缺料演示专用物料：仅在 DM-SO-1010 的 BOM 中使用，六单（1002-1007）不引用，库存 0、无在途。
    @{ id = $matIds[12]; code = 'RAW-BMS-001';     name = 'BMS 主控采集模块 16S（BCU-16S）'; type = 'RAW'; spec = '16 串，主动均衡 5A，CAN 2.0B'; unit = '套'; cat = $catIds[0]; safety = 0;   moq = 50;   lead = 21; sup = $supIds[1]; ind = '{"cell_count":16,"balancing":"主动均衡 5A","communication":"CAN 2.0B","sampling_precision_mv":2,"functional_safety":"ISO 26262 ASIL-B"}' },
    @{ id = $matIds[13]; code = 'FG-ESS-003';      name = '户用储能一体机 ESS-50（壁挂式）'; type = 'FINISHED'; spec = '单相 10kW / 50kWh，IP65 壁挂式'; unit = '台'; cat = $catIds[2]; safety = 0;   moq = 5;    lead = 15; sup = $null;      ind = '{"energy_kwh":50,"power_kw":10,"phase":"单相","protection":"IP65","installation":"壁挂式"}' },
    # 真实物料族扩展：150kWh 柜体专用件 + 替代料 + 配电半成品
    @{ id = $matIds[14]; code = 'RAW-THM-001';     name = 'EC 离心散热风机模组 48V';   type = 'RAW';      spec = '48V / 8500rpm，IP68，Φ250'; unit = '套'; cat = $catIds[0]; safety = 10;  moq = 100;  lead = 15; sup = $supIds[3]; ind = '{"voltage_v":48,"speed_rpm":8500,"protection":"IP68","diameter_mm":250,"airflow_m3h":1200,"noise_db":58}' },
    @{ id = $matIds[15]; code = 'RAW-THM-002';     name = 'EC 轴流散热风机模组 48V（替代）'; type = 'RAW'; spec = '48V / 7200rpm，IP68，Φ250'; unit = '套'; cat = $catIds[0]; safety = 20;  moq = 100;  lead = 10; sup = $supIds[3]; ind = '{"alternate_of":"RAW-THM-001","voltage_v":48,"speed_rpm":7200,"protection":"IP68","diameter_mm":250,"airflow_m3h":1050,"noise_db":56}' },
    @{ id = $matIds[16]; code = 'RAW-BMS-002';     name = 'BMS 主控采集模块 32S（BCU-32S）'; type = 'RAW'; spec = '32 串，主动均衡 5A，CAN FD'; unit = '套'; cat = $catIds[0]; safety = 0;   moq = 50;   lead = 30; sup = $supIds[1]; ind = '{"cell_count":32,"balancing":"主动均衡 5A","communication":"CAN FD","sampling_precision_mv":2,"functional_safety":"ISO 26262 ASIL-B"}' },
    @{ id = $matIds[17]; code = 'RAW-BMS-003';     name = 'BMS 主控采集模块 32S（BCU-32S 兼容替代）'; type = 'RAW'; spec = '32 串，CAN FD 兼容，替代 BCU-32S'; unit = '套'; cat = $catIds[0]; safety = 10;  moq = 50;   lead = 25; sup = $supIds[2]; ind = '{"alternate_of":"RAW-BMS-002","cell_count":32,"communication":"CAN FD","sampling_precision_mv":3,"functional_safety":"ISO 26262 ASIL-B"}' },
    @{ id = $matIds[18]; code = 'SEMI-PDU-001';    name = '高压配电单元 PDU-1000V';    type = 'SEMI';     spec = '1000V / 250A，含预充回路'; unit = '套'; cat = $catIds[1]; safety = 10;  moq = 50;   lead = 5;  sup = $null;      ind = '{"voltage_v":1000,"current_a":250,"precharge":true,"protection":"IP67","components":"断路器+母排+预充电阻"}' },
    @{ id = $matIds[19]; code = 'RAW-ELEC-004'; name = '直流塑壳断路器 DC-250A';   type = 'RAW';      spec = 'DC 1000V / 250A，2P，IEC 60947-2'; unit = '只'; cat = $catIds[0]; safety = 50;  moq = 200;  lead = 12; sup = $supIds[2]; ind = '{"rated_voltage_v":1000,"rated_current_a":250,"poles":2,"standard":"IEC 60947-2","trip_type":"热磁式"}' },
    @{ id = $matIds[20]; code = 'RAW-ELEC-005';     name = '高压母排与绝缘件总成';      type = 'RAW';      spec = 'T2 紫铜 30×8，镀锡，含绝缘支撑'; unit = '套'; cat = $catIds[0]; safety = 100; moq = 1000; lead = 8;  sup = $supIds[2]; ind = '{"material":"T2 紫铜","dimensions_mm":"30×8","plating":"镀锡","insulation":"环氧绝缘支撑","voltage_v":1000}' },
    @{ id = $matIds[21]; code = 'RAW-STR-002';    name = 'PACK 箱体密封胶条 EPDM';    type = 'RAW';      spec = 'EPDM 阻燃，20×5mm，UL94 V-0'; unit = '米'; cat = $catIds[0]; safety = 500; moq = 5000; lead = 6;  sup = $supIds[3]; ind = '{"material":"EPDM","width_mm":20,"thickness_mm":5,"flame_retardant":"UL94 V-0","temperature_range_c":"-40~120"}' },
    @{ id = $matIds[22]; code = 'RAW-PKG-001';   name = '铭牌与安全标识套件';        type = 'RAW';      spec = 'PET 耐候，UL94 V-0，含铭牌/警示/追溯码'; unit = '套'; cat = $catIds[4]; safety = 200; moq = 5000; lead = 5;  sup = $supIds[3]; ind = '{"material":"PET","flame_retardant":"UL94 V-0","weathering":"户外 5 年","items":"铭牌+警示标签+追溯二维码"}' },
    @{ id = $matIds[23]; code = 'AUX-CHEM-002';  name = '导热硅脂（3.0W/m·K）';      type = 'AUX';      spec = '导热系数 3.0W/m·K，1kg/罐'; unit = '罐'; cat = $catIds[3]; safety = 50;  moq = 200;  lead = 5;  sup = $supIds[3]; ind = '{"thermal_conductivity_wmk":3.0,"weight_kg":1,"temperature_range_c":"-50~200","viscosity":"膏状"}' },
    @{ id = $matIds[24]; code = 'SEMI-HARNESS-001'; name = '高压线束总成 125A';         type = 'SEMI';     spec = '125A / 1500V，耐温 125℃'; unit = '套'; cat = $catIds[1]; safety = 50;  moq = 200;  lead = 4;  sup = $null;      ind = '{"current_a":125,"voltage_v":1500,"temperature_c":125,"connector":"高压连接器 2 芯","standard":"LV 215"}' },
    @{ id = $matIds[25]; code = 'FG-ESS-005';      name = '工商业储能一体柜 ESS-150（液冷）'; type = 'FINISHED'; spec = '磷酸铁锂 2P52S，150kWh / 1000V，液冷'; unit = '台'; cat = $catIds[2]; safety = 3;   moq = 5;    lead = 18; sup = $null;      ind = '{"configuration":"2P52S","energy_kwh":150,"cooling":"液冷","voltage_v":1000,"protection":"IP55","standard":"GB/T 36276"}' }
)
$matByCode = @{}
foreach ($m in $materials) { $matByCode[$m.code] = $m }

# 单价与币种（material 表无价格列，落在 ext_attrs，供订单金额与页脚展示）
$priceByCode = @{
    'RAW-CELL-001' = 420; 'RAW-SMT-001' = 860; 'RAW-SMT-002' = 1250; 'RAW-STR-001' = 6800;
    'RAW-ELEC-001' = 540; 'RAW-ELEC-002' = 380; 'RAW-ELEC-003' = 260; 'SEMI-SMT-001' = 9200;
    'SEMI-MOD-001' = 24800; 'FG-ESS-001' = 168000; 'FG-ESS-002' = 218000; 'AUX-CHEM-001' = 180;
    'RAW-BMS-001' = 4800; 'FG-ESS-003' = 98000; 'RAW-THM-001' = 320; 'RAW-THM-002' = 350;
    'RAW-BMS-002' = 4800; 'RAW-BMS-003' = 5200; 'SEMI-PDU-001' = 8600; 'RAW-ELEC-004' = 260;
    'RAW-ELEC-005' = 540; 'RAW-STR-002' = 45; 'RAW-PKG-001' = 8; 'AUX-CHEM-002' = 120;
    'SEMI-HARNESS-001' = 380; 'FG-ESS-005' = 288000
}

# 每行带：用量、损耗率、工序关联（op）、替代料（subs，含优先级与比例）。
# 层级：成品（FG）→ 半成品（SEMI）→ 原材料（RAW），FG-ESS-001/002/005 均为 3 层。
$bomDefs = @(
    @{ id = $bomIds[0]; parent = 'FG-ESS-001'; version = 'V1.0'; status = 'ACTIVE'; eff = (AnchorDay (-308)); items = @(
        @{ child = 'SEMI-MOD-001'; qty = 1; scrap = 0.5; level = 1; seq = 10; pos = 'A01'; op = 'OP-MOD-01' },
        @{ child = 'SEMI-SMT-001';   qty = 1; scrap = 0.5; level = 1; seq = 20; pos = 'A02'; op = 'OP-SMT-01' },
        @{ child = 'RAW-STR-001';    qty = 1; scrap = 0.2; level = 1; seq = 30; pos = 'A03'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-001';    qty = 4; scrap = 0.5; level = 1; seq = 40; pos = 'A04'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-002';    qty = 2; scrap = 0.0; level = 1; seq = 50; pos = 'A05'; op = 'OP-PACK-01' },
        @{ child = 'AUX-CHEM-001'; qty = 1; scrap = 0.0; level = 1; seq = 60; pos = 'A06'; op = 'OP-PKG-01' } ) },
    @{ id = $bomIds[1]; parent = 'SEMI-MOD-001'; version = 'V1.0'; status = 'ACTIVE'; eff = (AnchorDay (-308)); items = @(
        @{ child = 'RAW-CELL-001'; qty = 16; scrap = 1.0; level = 1; seq = 10; pos = 'M01'; op = 'OP-CELL-01' },
        @{ child = 'RAW-ELEC-003'; qty = 2;  scrap = 0.5; level = 1; seq = 20; pos = 'M02'; op = 'OP-MOD-01' } ) },
    @{ id = $bomIds[2]; parent = 'SEMI-SMT-001'; version = 'V1.0'; status = 'ACTIVE'; eff = (AnchorDay (-308)); items = @(
        @{ child = 'RAW-SMT-001';  qty = 1; scrap = 1.0; level = 1; seq = 10; pos = 'S01'; op = 'OP-SMT-01' },
        @{ child = 'RAW-SMT-002'; qty = 8; scrap = 2.0; level = 1; seq = 20; pos = 'S02'; op = 'OP-SMT-01' },
        @{ child = 'RAW-ELEC-001'; qty = 6; scrap = 0.5; level = 1; seq = 30; pos = 'S03'; op = 'OP-SMT-01' } ) },
    @{ id = $bomIds[3]; parent = 'FG-ESS-002'; version = 'V1.0'; status = 'ACTIVE'; eff = (AnchorDay (-308)); items = @(
        @{ child = 'SEMI-MOD-001'; qty = 2; scrap = 0.5; level = 1; seq = 10; pos = 'B01'; op = 'OP-MOD-01' },
        @{ child = 'SEMI-SMT-001';   qty = 1; scrap = 0.5; level = 1; seq = 20; pos = 'B02'; op = 'OP-SMT-01' },
        @{ child = 'RAW-STR-001';    qty = 1; scrap = 0.2; level = 1; seq = 30; pos = 'B03'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-001';    qty = 8; scrap = 0.5; level = 1; seq = 40; pos = 'B04'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-002';    qty = 2; scrap = 0.0; level = 1; seq = 50; pos = 'B05'; op = 'OP-PACK-01' },
        @{ child = 'AUX-CHEM-001'; qty = 2; scrap = 0.0; level = 1; seq = 60; pos = 'B06'; op = 'OP-PKG-01' } ) },
    @{ id = $bomIds[4]; parent = 'FG-ESS-001'; version = 'V1.1'; status = 'DRAFT'; eff = (AnchorDay 26); items = @(
        @{ child = 'SEMI-MOD-001'; qty = 2; scrap = 0.5; level = 1; seq = 10; pos = 'A01'; op = 'OP-MOD-01' },
        @{ child = 'SEMI-SMT-001';   qty = 1; scrap = 0.5; level = 1; seq = 20; pos = 'A02'; op = 'OP-SMT-01' },
        @{ child = 'RAW-STR-001';    qty = 1; scrap = 0.2; level = 1; seq = 30; pos = 'A03'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-001';    qty = 6; scrap = 0.5; level = 1; seq = 40; pos = 'A04'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-002';    qty = 2; scrap = 0.0; level = 1; seq = 50; pos = 'A05'; op = 'OP-PACK-01' },
        @{ child = 'AUX-CHEM-001'; qty = 1; scrap = 0.0; level = 1; seq = 60; pos = 'A06'; op = 'OP-PKG-01' } ) },
    # 缺料演示专用 BOM：只挂 RAW-BMS-001 一个子件，与六单的 BOM 完全隔离，库存为 0。
    @{ id = $bomIds[5]; parent = 'FG-ESS-003'; version = 'V1.0'; status = 'ACTIVE'; eff = (AnchorDay (-308)); items = @(
        @{ child = 'RAW-BMS-001'; qty = 1; scrap = 0.0; level = 1; seq = 10; pos = 'C01'; op = 'OP-PKG-01' } ) },
    # 150kWh 一体柜：三层结构（FG → SEMI-PDU/SEMI-MOD → RAW）；含两组替代料。
    @{ id = $bomIds[6]; parent = 'FG-ESS-005'; version = 'V1.0'; status = 'ACTIVE'; eff = (AnchorDay (-308)); items = @(
        @{ child = 'SEMI-PDU-001';     qty = 1; scrap = 0.5; level = 1; seq = 10; pos = 'D01'; op = 'OP-PACK-01' },
        @{ child = 'SEMI-HARNESS-001'; qty = 1; scrap = 0.5; level = 1; seq = 20; pos = 'D02'; op = 'OP-PACK-01' },
        @{ child = 'SEMI-MOD-001';  qty = 2; scrap = 0.5; level = 1; seq = 30; pos = 'D03'; op = 'OP-MOD-01' },
        @{ child = 'SEMI-SMT-001';    qty = 1; scrap = 0.5; level = 1; seq = 40; pos = 'D04'; op = 'OP-SMT-01' },
        @{ child = 'RAW-THM-001';      qty = 2; scrap = 1.0; level = 1; seq = 50; pos = 'D05'; op = 'OP-PACK-01'; subs = @(@{ code = 'RAW-THM-002'; priority = 1; ratio = '1:1' }) },
        @{ child = 'RAW-BMS-002';      qty = 1; scrap = 0.0; level = 1; seq = 60; pos = 'D06'; op = 'OP-PACK-01'; subs = @(@{ code = 'RAW-BMS-003'; priority = 1; ratio = '1:1' }) },
        @{ child = 'RAW-STR-002';     qty = 4; scrap = 5.0; level = 1; seq = 70; pos = 'D07'; op = 'OP-PACK-01' },
        @{ child = 'RAW-PKG-001';    qty = 1; scrap = 0.0; level = 1; seq = 80; pos = 'D08'; op = 'OP-PKG-01' },
        @{ child = 'AUX-CHEM-002';   qty = 1; scrap = 0.0; level = 1; seq = 90; pos = 'D09'; op = 'OP-PKG-01' } ) },
    # PDU 半成品：第三层（SEMI → RAW），支撑“BOM 逐层展开”。
    @{ id = $bomIds[7]; parent = 'SEMI-PDU-001'; version = 'V1.0'; status = 'ACTIVE'; eff = (AnchorDay (-308)); items = @(
        @{ child = 'RAW-ELEC-004'; qty = 2; scrap = 0.5; level = 1; seq = 10; pos = 'E01'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-005';     qty = 1; scrap = 0.5; level = 1; seq = 20; pos = 'E02'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-001';    qty = 2; scrap = 0.5; level = 1; seq = 30; pos = 'E03'; op = 'OP-PACK-01' },
        @{ child = 'RAW-ELEC-003';    qty = 2; scrap = 0.5; level = 1; seq = 40; pos = 'E04'; op = 'OP-PACK-01' } ) }
)

$shiftsFac  = '[{"startTime":"08:00","endTime":"20:00","daysOfWeek":[1,2,3,4,5,6]}]'
$shiftsTwo  = '[{"startTime":"08:00","endTime":"20:00","daysOfWeek":[1,2,3,4,5,6]},{"startTime":"20:00","endTime":"08:00","daysOfWeek":[1,2,3,4,5]}]'
$holidaysFac = J @(
    [ordered]@{ date = (AnchorDay 11); name = '设备年度检修'; kind = 'COMPANY' },
    [ordered]@{ date = (AnchorDay 49); name = '年终盘点'; kind = 'COMPANY' }
)

$groups = @(
    @{ id = $grpIds[0]; code = 'RG-SMT';  name = 'SMT 贴片车间'; desc = '高速贴片、回流焊与 AOI 光学检测'; cal = $calIds[1] },
    @{ id = $grpIds[1]; code = 'RG-ASSY'; name = '模组装配车间'; desc = '电芯分选配组与模组激光焊接装配'; cal = $calIds[2] },
    @{ id = $grpIds[2]; code = 'RG-PACK'; name = 'PACK 总装车间'; desc = 'PACK 总装、气密测试与包装入库'; cal = $calIds[2] },
    @{ id = $grpIds[3]; code = 'RG-TEST'; name = '测试与老化中心'; desc = '老化房与 EOL 终检'; cal = $calIds[2] },
    @{ id = $grpIds[4]; code = 'RG-HR';   name = '装调技师组';   desc = '人工装配、调试与返修支持'; cal = $calIds[0] }
)

$resources = @(
    @{ id = $resIds[0];  code = 'RES-SMT-01';     name = '高速贴片线 SMT-01'; type = 'PRIMARY'; grp = $grpIds[0]; mode = 'UNIT_PER_HOUR'; cap = 120; batch = $null; setup = 1800; cal = $calIds[1]; ind = '{"maker":"FUJI","model":"NXT III","feeder_slots":180,"workshop":"SMT 贴片车间","process_function":"高速贴片（CHIP/QFP/QFN）","capacity_text":"120 点/小时，单班 12 小时"}' },
    @{ id = $resIds[1];  code = 'RES-SMT-02';     name = '高速贴片线 SMT-02'; type = 'PRIMARY'; grp = $grpIds[0]; mode = 'UNIT_PER_HOUR'; cap = 120; batch = $null; setup = 2400; cal = $calIds[1]; ind = '{"maker":"ASM","model":"SIPLACE SX","feeder_slots":160,"workshop":"SMT 贴片车间","process_function":"高速贴片（CHIP/QFP/QFN）","capacity_text":"120 点/小时，单班 12 小时"}' },
    @{ id = $resIds[2];  code = 'RES-AOI-01';     name = '回流焊 AOI 检测线 AOI-01'; type = 'PRIMARY'; grp = $grpIds[0]; mode = 'UNIT_PER_HOUR'; cap = 200; batch = $null; setup = 900;  cal = $calIds[1]; ind = '{"maker":"KOH YOUNG","workshop":"SMT 贴片车间","process_function":"回流焊 + AOI 光学检测","capacity_text":"200 块/小时"}' },
    @{ id = $resIds[3];  code = 'RES-CELL-01';    name = '电芯分选配组线 CELL-01'; type = 'PRIMARY'; grp = $grpIds[1]; mode = 'UNIT_PER_HOUR'; cap = 150; batch = $null; setup = 600;  cal = $calIds[2]; ind = '{"maker":"PRISMTECH","workshop":"模组装配车间","process_function":"电芯分容分选与配组","capacity_text":"150 只/小时"}' },
    @{ id = $resIds[4];  code = 'RES-MODULE-01';  name = '模组装配线 MOD-01'; type = 'PRIMARY'; grp = $grpIds[1]; mode = 'UNIT_PER_HOUR'; cap = 6;   batch = $null; setup = 1800; cal = $calIds[2]; ind = '{"workshop":"模组装配车间","process_function":"电芯堆叠、激光焊接与模组下线","capacity_text":"6 组/小时"}' },
    @{ id = $resIds[5];  code = 'RES-MODULE-02';  name = '模组装配线 MOD-02'; type = 'PRIMARY'; grp = $grpIds[1]; mode = 'UNIT_PER_HOUR'; cap = 6;   batch = $null; setup = 1800; cal = $calIds[2]; ind = '{"workshop":"模组装配车间","process_function":"电芯堆叠、激光焊接与模组下线","capacity_text":"6 组/小时"}' },
    @{ id = $resIds[6];  code = 'RES-PACK-01';    name = 'PACK 总装线 PACK-01'; type = 'PRIMARY'; grp = $grpIds[2]; mode = 'UNIT_PER_HOUR'; cap = 4;   batch = $null; setup = 2400; cal = $calIds[2]; ind = '{"workshop":"PACK 总装车间","process_function":"模组入柜、铜排装配与气密测试","capacity_text":"4 台/小时"}' },
    @{ id = $resIds[7];  code = 'RES-PACK-02';    name = 'PACK 总装线 PACK-02'; type = 'PRIMARY'; grp = $grpIds[2]; mode = 'UNIT_PER_HOUR'; cap = 4;   batch = $null; setup = 2400; cal = $calIds[2]; ind = '{"workshop":"PACK 总装车间","process_function":"模组入柜、铜排装配与气密测试","capacity_text":"4 台/小时"}' },
    @{ id = $resIds[8];  code = 'RES-AGING-01';   name = '老化房 AGING-01（20 工位）'; type = 'PRIMARY'; grp = $grpIds[3]; mode = 'BATCH_SECONDS'; cap = 7200; batch = 20; setup = 3600; cal = $calIds[2]; ind = '{"workshop":"测试与老化中心","process_function":"整柜充放电老化","capacity_text":"20 工位/批，单批 2 小时","temperature_range":"25~55℃"}' },
    @{ id = $resIds[9];  code = 'RES-EOL-01';     name = 'EOL 终检线 EOL-01'; type = 'PRIMARY'; grp = $grpIds[3]; mode = 'UNIT_PER_HOUR'; cap = 20;  batch = $null; setup = 1200; cal = $calIds[2]; ind = '{"workshop":"测试与老化中心","process_function":"EOL 绝缘、耐压与功能终检","capacity_text":"20 台/小时"}' },
    @{ id = $resIds[10]; code = 'RES-PACKING-01'; name = '包装入库线 PKG-01'; type = 'PRIMARY'; grp = $grpIds[2]; mode = 'UNIT_PER_HOUR'; cap = 30;  batch = $null; setup = 900;  cal = $calIds[2]; ind = '{"workshop":"PACK 总装车间","process_function":"铭牌粘贴、包装与入库","capacity_text":"30 台/小时"}' },
    @{ id = $resIds[11]; code = 'RES-TECH-01';    name = '装调技师组 TECH-01（6 人）'; type = 'HUMAN';   grp = $grpIds[4]; mode = 'UNIT_PER_HOUR'; cap = 4;   batch = $null; setup = 600;  cal = $calIds[0]; ind = '{"headcount":6,"workshop":"全厂","process_function":"人工装配、调试与返修","capacity_text":"6 人，按 4 台/小时折算"}' }
)

$operations = @(
    @{ id = $opIds[0]; code = 'OP-SMT-01';  name = 'SMT 贴片';     group = $grpIds[0]; res = $resIds[0]; std = 1800;  setup = 1800; out = 'SEMI-SMT-001' },
    @{ id = $opIds[1]; code = 'OP-SMT-02';  name = 'AOI 光学检测'; group = $grpIds[0]; res = $resIds[2]; std = 900;   setup = 900;  out = 'SEMI-SMT-001' },
    @{ id = $opIds[2]; code = 'OP-CELL-01'; name = '电芯分选配组'; group = $grpIds[1]; res = $resIds[3]; std = 1800;  setup = 600;  out = 'SEMI-MOD-001' },
    @{ id = $opIds[3]; code = 'OP-MOD-01';  name = '模组装配';     group = $grpIds[1]; res = $resIds[4]; std = 3600;  setup = 1800; out = 'SEMI-MOD-001' },
    @{ id = $opIds[4]; code = 'OP-PACK-01'; name = 'PACK 总装';    group = $grpIds[2]; res = $resIds[6]; std = 7200;  setup = 2400; out = 'FG-ESS-001' },
    @{ id = $opIds[5]; code = 'OP-AGE-01';  name = '老化测试';     group = $grpIds[3]; res = $resIds[8]; std = 10800; setup = 3600; out = 'FG-ESS-001' },
    @{ id = $opIds[6]; code = 'OP-EOL-01';  name = 'EOL 终检';     group = $grpIds[3]; res = $resIds[9]; std = 3600;  setup = 1200; out = 'FG-ESS-001' },
    @{ id = $opIds[7]; code = 'OP-PKG-01';  name = '包装入库';     group = $grpIds[2]; res = $resIds[10]; std = 1800; setup = 900;  out = 'FG-ESS-001' }
)

$routingDefs = @(
    @{ id = $routingIds[0]; product = 'FG-ESS-001';      version = 'V1.0'; ops = @(0,1,2,3,4,5,6,7); edges = @('0-1','1-3','2-3','3-4','4-5','5-6','6-7') },
    @{ id = $routingIds[1]; product = 'FG-ESS-002';      version = 'V1.0'; ops = @(0,1,2,3,4,5,6,7); edges = @('0-1','1-3','2-3','3-4','4-5','5-6','6-7') },
    @{ id = $routingIds[2]; product = 'SEMI-MOD-001'; version = 'V1.0'; ops = @(2,3); edges = @('0-1') }
)

# 订单：客户/联系人/收货地/下单日/交期/单价/金额/多行明细都从 AnchorDate 与 priceByCode 派生。
# 1001、1009 为多行订单（ext_attrs.lines），订单头 product/qty 保留 MRP 口径。
$orders = @(
    @{ id = $orderIds[0]; no = 'DM-SO-1001'; product = 'FG-ESS-001'; qty = 36; due = (AnchorDay 20); prio = 'NORMAL'; cust = 'CUS-003'; status = 'DRAFT';       freeze = 'UNFROZEN';           routing = $routingIds[0]; remark = '华南系统集成客户试用批次，等待销售确认'; od = -14; po = 'PO-SC-26-1001'; contact = '陈志强 020-38381111'; shipTo = '广东省深圳市宝安区福海街道'; cur = 'CNY'; lines = @(@{ code = 'FG-ESS-001'; qty = 30 }, @{ code = 'FG-ESS-005'; qty = 6 }) },
    @{ id = $orderIds[1]; no = 'DM-SO-1002'; product = 'FG-ESS-001'; qty = 24; due = (AnchorDay 24); prio = 'URGENT'; cust = 'CUS-001'; status = 'CONFIRMED';   freeze = 'UNFROZEN';           routing = $routingIds[0]; remark = '华北储能客户首批交付，齐套可排'; od = -9; po = 'PO-NC-26-1002'; contact = '张伟 010-88991234'; shipTo = '河北省张家口市桥东区工业园 1 号'; cur = 'CNY' },
    @{ id = $orderIds[2]; no = 'DM-SO-1003'; product = 'FG-ESS-002'; qty = 18; due = (AnchorDay 27); prio = 'HIGH';   cust = 'CUS-002'; status = 'CONFIRMED';   freeze = 'FROZEN';             routing = $routingIds[1]; remark = '华东渠道客户已冻结交期，不可移动'; od = -10; po = 'PO-EC-26-1003'; contact = '李娜 021-55667788'; shipTo = '江苏省苏州市工业园区星湖街 88 号'; cur = 'CNY'; frz = @{ freezeReason = '客户锁定交期，工艺验证完成' } },
    @{ id = $orderIds[3]; no = 'DM-SO-1004'; product = 'FG-ESS-002'; qty = 32; due = (AnchorDay 30); prio = 'HIGH';   cust = 'CUS-003'; status = 'CONFIRMED';   freeze = 'LOCKED_RESOURCE';     routing = $routingIds[1]; remark = '华南客户指定模组线与总装线'; od = -11; po = 'PO-SC-26-1004'; contact = '陈志强 020-38381111'; shipTo = '广东省深圳市宝安区福海街道'; cur = 'CNY'; frz = @{ freezeLock = @{ resourceCodes = @('RES-MODULE-01','RES-PACK-01') } } },
    @{ id = $orderIds[4]; no = 'DM-SO-1005'; product = 'FG-ESS-001'; qty = 48; due = (AnchorDay 35); prio = 'NORMAL'; cust = 'CUS-001'; status = 'RELEASED';    freeze = 'LOCKED_TIME_WINDOW'; routing = $routingIds[0]; remark = '华北客户锁定演示次日 08:00-11:00 窗口'; od = -12; po = 'PO-NC-26-1005'; contact = '张伟 010-88991234'; shipTo = '河北省张家口市桥东区工业园 1 号'; cur = 'CNY'; frz = @{ freezeLock = @{ from = (AnchorTime 1 '08:00:00'); to = (AnchorTime 1 '11:00:00') } } },
    @{ id = $orderIds[5]; no = 'DM-SO-1006'; product = 'FG-ESS-001'; qty = 20; due = (AnchorDay 40); prio = 'URGENT'; cust = 'CUS-004'; status = 'IN_PROGRESS'; freeze = 'UNFROZEN';           routing = $routingIds[0]; remark = '西南分布式客户在制订单，SMT 已完成'; od = -13; po = 'PO-WC-26-1006'; contact = '赵敏 028-86665555'; shipTo = '四川省成都市青白江区物流园'; cur = 'CNY' },
    @{ id = $orderIds[6]; no = 'DM-SO-1007'; product = 'FG-ESS-002'; qty = 60; due = (AnchorDay 45); prio = 'NORMAL'; cust = 'CUS-005'; status = 'SCHEDULED';   freeze = 'UNFROZEN';           routing = $routingIds[1]; remark = '海外出口客户订单，已排产待下发'; od = -15; po = 'PO-EU-26-1007'; contact = 'Anna Schmidt +49-30-1234567'; shipTo = 'Hamburg Port, Germany'; cur = 'USD'; px = 30000 },
    @{ id = $orderIds[7]; no = 'DM-SO-1008'; product = 'FG-ESS-001'; qty = 90; due = (AnchorDay 49); prio = 'HIGH';   cust = 'CUS-001'; status = 'CONFIRMED';   freeze = 'UNFROZEN';           routing = $routingIds[0]; remark = '华北客户大批量订单，齐套可排'; od = -16; po = 'PO-NC-26-1008'; contact = '张伟 010-88991234'; shipTo = '河北省张家口市桥东区工业园 1 号'; cur = 'CNY' },
    # 部分齐套演示单：150kWh 新型号首单，两层缺料（FAN-001 可替代、BMS-002 在途未到），其余齐套。
    @{ id = $orderIds[8]; no = 'DM-SO-1009'; product = 'FG-ESS-005'; qty = 30; due = (AnchorDay 53); prio = 'LOW';    cust = 'CUS-002'; status = 'DRAFT';       freeze = 'UNFROZEN';           routing = $null;          remark = '华东渠道 150kWh 一体柜首单，部分齐套待评审'; od = -5; po = 'PO-EC-26-1009'; contact = '李娜 021-55667788'; shipTo = '江苏省苏州市工业园区星湖街 88 号'; cur = 'CNY'; lines = @(@{ code = 'FG-ESS-005'; qty = 24 }, @{ code = 'FG-ESS-002'; qty = 6 }) },
    # 缺料演示专用单：DRAFT、无工艺路线、不进入六单可排集合；单张 MRP 必为 SHORTAGE（缺 RAW-BMS-001）。
    @{ id = $orderIds[9]; no = 'DM-SO-1010'; product = 'FG-ESS-003'; qty = 40; due = (AnchorDay 43); prio = 'HIGH';   cust = 'CUS-002'; status = 'DRAFT';       freeze = 'UNFROZEN';           routing = $null;          remark = '华东渠道户用储能样机，BMS 主控缺料，待评审'; od = -6; po = 'PO-EC-26-1010'; contact = '李娜 021-55667788'; shipTo = '江苏省苏州市工业园区星湖街 88 号'; cur = 'CNY' }
)
$orderByNo = @{}
foreach ($o in $orders) { $orderByNo[$o.no] = $o }

$strategies = @(
    @{ id = $stratIds[0]; name = '交期优先·正向排产（演示）'; dir = 'FORWARD'; weights = @(25,20,15,12,10,8,5,5) },
    @{ id = $stratIds[1]; name = '均衡产线·正向排产（演示）'; dir = 'FORWARD'; weights = @(15,12,10,15,12,20,8,8) }
)
$softCodes = @('MIN_TARDINESS','EDD','PRIORITY_RESPECT','MIN_SETUP_TIME','SAME_PRODUCT_CONTINUOUS','BALANCE_LOAD','SPT','MIN_MAKESPAN')
$softNames = @('最小化总拖期','优先早交期订单','优先高优先级订单','最小化换型时间','同产品连续生产','产线负载均衡','短作业优先','最小化总完工时间')

$inventory = @(
    @{ id = $invIds[0]; code = 'RAW-CELL-001';    qty = 8000;  safety = 200 },
    @{ id = $invIds[1]; code = 'RAW-SMT-001';     qty = 2000;  safety = 50 },
    @{ id = $invIds[2]; code = 'RAW-SMT-002';    qty = 20000; safety = 500 },
    @{ id = $invIds[3]; code = 'RAW-STR-001';    qty = 1000;  safety = 20 },
    @{ id = $invIds[4]; code = 'RAW-ELEC-001';    qty = 5000;  safety = 100 },
    @{ id = $invIds[5]; code = 'RAW-ELEC-002';    qty = 400;   safety = 20 },
    @{ id = $invIds[6]; code = 'RAW-ELEC-003';    qty = 3000;  safety = 100 },
    @{ id = $invIds[7]; code = 'SEMI-SMT-001';   qty = 340;   safety = 30 },
    @{ id = $invIds[8]; code = 'SEMI-MOD-001'; qty = 450;   safety = 30 },
    @{ id = $invIds[9]; code = 'AUX-CHEM-001'; qty = 500;   safety = 20 },
    # 缺料演示：专用 BMS 主控库存为 0，且无在途，单张 1010 必缺 40 套。
    @{ id = $invIds[10]; code = 'RAW-BMS-001';    qty = 0;     safety = 0 },
    # 150kWh 族：FAN-001 零库存（替代料 FAN-002 有货）；BMS-002 零库存 + 在途未到（替代料 BMS-003 有货）。
    @{ id = $invIds[11]; code = 'RAW-THM-001';     qty = 0;     safety = 10 },
    @{ id = $invIds[12]; code = 'RAW-THM-002';     qty = 120;   safety = 20 },
    @{ id = $invIds[13]; code = 'RAW-BMS-002';     qty = 0;     safety = 0 },
    @{ id = $invIds[14]; code = 'RAW-BMS-003';     qty = 40;    safety = 10 },
    @{ id = $invIds[15]; code = 'SEMI-PDU-001';    qty = 80;    safety = 10 },
    @{ id = $invIds[16]; code = 'RAW-ELEC-004'; qty = 500;   safety = 50 },
    @{ id = $invIds[17]; code = 'RAW-ELEC-005';     qty = 600;   safety = 100 },
    @{ id = $invIds[18]; code = 'RAW-STR-002';    qty = 5000;  safety = 500 },
    @{ id = $invIds[19]; code = 'RAW-PKG-001';   qty = 5000;  safety = 200 },
    @{ id = $invIds[20]; code = 'AUX-CHEM-002';  qty = 300;   safety = 50 },
    @{ id = $invIds[21]; code = 'SEMI-HARNESS-001'; qty = 80;   safety = 20 }
)

# ----------------------------------------------------------------------------- SQL 组装
$script:sb = [System.Text.StringBuilder]::new()
function AddS([string]$line) { [void]$script:sb.AppendLine($line) }

AddS "SET client_encoding = 'UTF8';"
AddS "BEGIN;"

function Add-Cleanup {
    AddS "DELETE FROM notification_outbox WHERE tenant_id = '$tenant' AND (plan_id IN ($planIdsList) OR dispatch_id = '$dispatchId');"
    AddS "DELETE FROM task_adjustment WHERE tenant_id = '$tenant' AND (sandbox_id IN ('$whatIfId','$archivedId') OR task_id IN (SELECT t.id FROM task t JOIN orders o ON o.id = t.order_id WHERE t.tenant_id = '$tenant' AND o.order_no LIKE 'DM-SO-%'));"
    AddS "DELETE FROM plan_version WHERE tenant_id = '$tenant' AND id IN ($planIdsList);"
    AddS "DELETE FROM constraint_justification WHERE solve_task_id IN (SELECT id FROM solve_task WHERE tenant_id = '$tenant' AND idempotency_key LIKE 'demo-seed-%');"
    AddS "DELETE FROM solve_task WHERE tenant_id = '$tenant' AND idempotency_key LIKE 'demo-seed-%';"
    AddS "DELETE FROM task WHERE tenant_id = '$tenant' AND (sandbox_id IN ('$whatIfId','$archivedId') OR order_id IN (SELECT id FROM orders WHERE tenant_id = '$tenant' ORDER BY order_no) AND order_id IN (SELECT id FROM orders WHERE tenant_id = '$tenant' AND order_no LIKE 'DM-SO-%'));"
    AddS "DELETE FROM sandbox WHERE tenant_id = '$tenant' AND id IN ('$whatIfId','$archivedId');"
    AddS "DELETE FROM strategy_rule WHERE tenant_id = '$tenant' AND strategy_id IN ($stratIdsList);"
    AddS "DELETE FROM strategy WHERE tenant_id = '$tenant' AND id IN ($stratIdsList);"
    AddS "DELETE FROM orders WHERE tenant_id = '$tenant' AND (id IN ($orderIdsList) OR order_no LIKE 'DM-SO-%');"
    AddS "DELETE FROM inventory WHERE tenant_id = '$tenant' AND material_id IN ($matIdsList);"
    AddS "DELETE FROM inventory_transit WHERE tenant_id = '$tenant' AND material_id IN ($matIdsList);"
    if ($hasSetupMatrix) {
        AddS "DELETE FROM setup_matrix_entry WHERE tenant_id = '$tenant' AND id IN ($setupIdsList);"
    }
    AddS "DELETE FROM bom_item WHERE tenant_id = '$tenant' AND bom_id IN ($bomIdsList);"
    AddS "DELETE FROM bom WHERE tenant_id = '$tenant' AND id IN ($bomIdsList);"
    AddS "DELETE FROM routing_edge WHERE tenant_id = '$tenant' AND routing_id IN ($routingIdsList);"
    AddS "DELETE FROM routing_node WHERE tenant_id = '$tenant' AND routing_id IN ($routingIdsList);"
    AddS "DELETE FROM routing WHERE tenant_id = '$tenant' AND id IN ($routingIdsList);"
    AddS "DELETE FROM operation_template WHERE tenant_id = '$tenant' AND id IN ($opIdsList);"
    AddS "UPDATE resource_group SET calendar_id = NULL WHERE tenant_id = '$tenant' AND id IN ($grpIdsList);"
    AddS "DELETE FROM resource WHERE tenant_id = '$tenant' AND id IN ($resIdsList);"
    AddS "DELETE FROM calendar WHERE tenant_id = '$tenant' AND id IN ($calIdsList);"
    AddS "DELETE FROM resource_group WHERE tenant_id = '$tenant' AND id IN ($grpIdsList);"
    AddS "DELETE FROM material WHERE tenant_id = '$tenant' AND id IN ($matIdsList);"
    AddS "DELETE FROM material_category WHERE tenant_id = '$tenant' AND id IN ($catIdsList);"
    # 客户 CUS-001..008 为 seed-m3 与 demo 共用主数据，-Reset 不删除（不碰其它车道）
    AddS "DELETE FROM supplier WHERE tenant_id = '$tenant' AND id IN ($supIdsList);"
    AddS "DELETE FROM import_job WHERE tenant_id = '$tenant' AND id IN ($importIdsList);"
    AddS "DELETE FROM user_role WHERE user_id IN ('$demoUserId1','$demoUserId2');"
    AddS "DELETE FROM users WHERE tenant_id = '$tenant' AND id IN ('$demoUserId1','$demoUserId2');"
    AddS "DELETE FROM audit_log WHERE tenant_id = '$tenant' AND trace_id LIKE 'demo-seed-%';"
}

function Add-MasterData {
    # 客户是与 seed-m3 共用的主数据：值未变时不动 updated_at/version，保证同一 AnchorDate 重复灌注逐行一致。
    $custChanged = "(customer.name, customer.priority, customer.lead_time_days, customer.payment_terms, customer.blacklisted, customer.ext_attrs) IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.priority, EXCLUDED.lead_time_days, EXCLUDED.payment_terms, EXCLUDED.blacklisted, EXCLUDED.ext_attrs)"
    foreach ($c in $customers) {
        AddS ("INSERT INTO customer (tenant_id, code, name, priority, lead_time_days, payment_terms, blacklisted, ext_attrs, created_at, updated_at) VALUES " +
            "('$tenant', $(Q $c.code), $(Q $c.name), '$($c.priority)', $($c.lead), $(Q $c.payment), $($c.black), $(Q $c.ext)::jsonb, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')') " +
            "ON CONFLICT (tenant_id, code) WHERE deleted = false DO UPDATE SET name = EXCLUDED.name, priority = EXCLUDED.priority, " +
            "lead_time_days = EXCLUDED.lead_time_days, payment_terms = EXCLUDED.payment_terms, blacklisted = EXCLUDED.blacklisted, " +
            "ext_attrs = EXCLUDED.ext_attrs, updated_at = CASE WHEN $custChanged THEN now() ELSE customer.updated_at END, " +
            "version = CASE WHEN $custChanged THEN customer.version + 1 ELSE customer.version END;")
    }
    foreach ($s in $suppliers) {
        AddS ("INSERT INTO supplier (id, tenant_id, code, name, lead_time_days, moq, quality_grade, ext_attrs, created_at, updated_at) VALUES " +
            "('$($s.id)', '$tenant', $(Q $s.code), $(Q $s.name), $($s.lead), $($s.moq), '$($s.grade)', $(Q $s.ext)::jsonb, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    }
    foreach ($c in $categories) {
        AddS ("INSERT INTO material_category (id, tenant_id, parent_id, code, name, sort_no, created_at, updated_at) VALUES " +
            "('$($c.id)', '$tenant', NULL, $(Q $c.code), $(Q $c.name), 0, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    }
    foreach ($m in $materials) {
        $sup = if ($m.sup) { Q $m.sup } else { 'NULL' }
        $price = if ($priceByCode.ContainsKey($m.code)) { $priceByCode[$m.code] } else { 0 }
        $extJson = J ([ordered]@{ unitPrice = $price; currency = 'CNY'; priceDate = (AnchorDay (-30)); priceUom = $m.unit })
        AddS ("INSERT INTO material (id, tenant_id, code, name, type, spec, unit, category_id, safety_stock, moq, lead_time_days, default_supplier_id, ext_attrs, industry_attrs, created_at, updated_at) VALUES " +
            "('$($m.id)', '$tenant', $(Q $m.code), $(Q $m.name), '$($m.type)', $(Q $m.spec), '$($m.unit)', '$($m.cat)', $($m.safety), $($m.moq), $($m.lead), $sup, $(Q $extJson)::jsonb, $(Q $m.ind)::jsonb, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    }
    $itemIdx = 0
    foreach ($b in $bomDefs) {
        $snap = @()
        foreach ($it in $b.items) {
            $child = $matByCode[$it.child]
            $subs = @()
            if ($it.ContainsKey('subs') -and $it.subs) {
                foreach ($sd in $it.subs) {
                    $subs += [ordered]@{ materialId = $matByCode[$sd.code].id; materialCode = $sd.code; priority = $sd.priority; ratio = $sd.ratio }
                }
            }
            $snap += [ordered]@{ materialId = $child.id; materialCode = $it.child; materialName = $child.name; qtyPerUnit = $it.qty; scrapRate = [double]$it.scrap; position = $it.pos; operationCode = $it.op; substitutes = $subs; level = $it.level; seqNo = $it.seq }
        }
        $snapJson = J $snap
        AddS ("INSERT INTO bom (id, tenant_id, parent_material_id, bom_version, effective_date, status, items, created_at, updated_at) VALUES " +
            "('$($b.id)', '$tenant', '$($matByCode[$b.parent].id)', '$($b.version)', '$($b.eff)', '$($b.status)', $(Q $snapJson)::jsonb, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        foreach ($it in $b.items) {
            $itemIdx++
            $itemId = $bomItemIds[$itemIdx - 1]
            $itemSubs = @()
            if ($it.ContainsKey('subs') -and $it.subs) {
                foreach ($sd in $it.subs) {
                    $itemSubs += [ordered]@{ materialId = $matByCode[$sd.code].id; materialCode = $sd.code; priority = $sd.priority; ratio = $sd.ratio }
                }
            }
            $subsJson = if ($itemSubs.Count -eq 0) { '[]' } else { J $itemSubs }
            AddS ("INSERT INTO bom_item (id, tenant_id, bom_id, child_material_id, qty_per_unit, scrap_rate, position, substitutes, is_phantom, level, seq_no, created_at, updated_at) VALUES " +
                "('$itemId', '$tenant', '$($b.id)', '$($matByCode[$it.child].id)', $($it.qty), $($it.scrap), $(Q $it.pos), $(Q $subsJson)::jsonb, false, $($it.level), $($it.seq), '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        }
    }
    foreach ($g in $groups) {
        AddS ("INSERT INTO resource_group (id, tenant_id, code, name, description, created_at, updated_at) VALUES ('$($g.id)', '$tenant', $(Q $g.code), $(Q $g.name), $(Q $g.desc), '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    }
    AddS ("INSERT INTO calendar (id, tenant_id, code, name, type, effective_from, shifts, holidays, calendar_version, created_at, updated_at) VALUES ('$($calIds[0])', '$tenant', 'CAL-PLANT-1SHIFT', '全厂单班制日历（08:00-20:00，含年度检修）', 'FACTORY', '$(AnchorDay (-308))', $(Q $shiftsFac)::jsonb, $(Q $holidaysFac)::jsonb, 1, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    AddS ("INSERT INTO calendar (id, tenant_id, code, name, type, resource_group_id, effective_from, shifts, holidays, calendar_version, created_at, updated_at) VALUES ('$($calIds[1])', '$tenant', 'CAL-SMT-2SHIFT', 'SMT 贴片车间两班制日历', 'SHIFT', '$($grpIds[0])', '$(AnchorDay (-308))', $(Q $shiftsTwo)::jsonb, '[]'::jsonb, 1, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    AddS ("INSERT INTO calendar (id, tenant_id, code, name, type, resource_group_id, effective_from, shifts, holidays, calendar_version, created_at, updated_at) VALUES ('$($calIds[2])', '$tenant', 'CAL-PACK-2SHIFT', '包装车间两班制日历', 'SHIFT', '$($grpIds[2])', '$(AnchorDay (-308))', $(Q $shiftsTwo)::jsonb, '[]'::jsonb, 1, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    AddS "UPDATE resource_group SET calendar_id = '$($calIds[1])' WHERE id = '$($grpIds[0])';"
    AddS "UPDATE resource_group SET calendar_id = '$($calIds[2])' WHERE id IN ('$($grpIds[1])','$($grpIds[2])','$($grpIds[3])');"
    AddS "UPDATE resource_group SET calendar_id = '$($calIds[0])' WHERE id = '$($grpIds[4])';"
    foreach ($r in $resources) {
        $batch = if ($null -ne $r.batch) { "$($r.batch)" } else { 'NULL' }
        AddS ("INSERT INTO resource (id, tenant_id, code, name, type, group_id, capacity_mode, capacity_value, batch_capacity, efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs, created_at, updated_at) VALUES " +
            "('$($r.id)', '$tenant', $(Q $r.code), $(Q $r.name), '$($r.type)', '$($r.grp)', '$($r.mode)', $($r.cap), $batch, 1.00, '$($r.cal)', $($r.setup), 600, '{}'::jsonb, $(Q $r.ind)::jsonb, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    }
    foreach ($o in $operations) {
        AddS ("INSERT INTO operation_template (id, tenant_id, code, name, primary_resource_id, primary_resource_group_id, secondary_resource_ids, input_material_ids, output_material_id, standard_seconds, setup_seconds, teardown_seconds, template_version, created_at, updated_at) VALUES " +
            "('$($o.id)', '$tenant', $(Q $o.code), $(Q $o.name), '$($o.res)', '$($o.group)', '[]'::jsonb, '[]'::jsonb, '$($matByCode[$o.out].id)', $($o.std), $($o.setup), 300, 1, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    }
    # 工艺路线：先主表（含图快照），再节点/边（均有 FK -> routing）
    $nodeIdx = 0; $edgeIdx = 0
    foreach ($rt in $routingDefs) {
        $localNodes = @()
        for ($k = 0; $k -lt $rt.ops.Count; $k++) {
            $nodeIdx++
            $op = $operations[$rt.ops[$k]]
            $y = if ($k % 2 -eq 0) { 120 } else { 240 }
            $localNodes += @{ nid = $nodeIds[$nodeIdx - 1]; key = "N$($k+1)"; op = $op; x = (140 * ($k+1)); y = $y; seq = ($k+1) }
        }
        $nodeSnap = @(); $edgeSnap = @(); $edgeRows = @()
        foreach ($n in $localNodes) {
            $nodeSnap += [ordered]@{ id = $n.nid; nodeKey = $n.key; operationCode = $n.op.code; operationName = $n.op.name; standardSeconds = $n.op.std; setupSeconds = $n.op.setup; positionX = $n.x; positionY = $n.y; seqNo = $n.seq; operationTemplateId = $n.op.id; isOptional = $false }
        }
        foreach ($e in $rt.edges) {
            $edgeIdx++
            $edgeId = $edgeIds[$edgeIdx - 1]
            $pair = $e -split '-'
            $from = $localNodes[[int]$pair[0]].nid; $to = $localNodes[[int]$pair[1]].nid
            $edgeRows += @{ id = $edgeId; from = $from; to = $to }
            $edgeSnap += [ordered]@{ id = $edgeId; fromNodeId = $from; toNodeId = $to; dependType = 'FS'; lagMinutes = 0 }
        }
        AddS ("INSERT INTO routing (id, tenant_id, product_id, routing_version, effective_date, status, nodes, edges, created_at, updated_at) VALUES " +
            "('$($rt.id)', '$tenant', '$($matByCode[$rt.product].id)', '$($rt.version)', '$(AnchorDay (-308))', 'ACTIVE', $(Q (J $nodeSnap))::jsonb, $(Q (J $edgeSnap))::jsonb, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        foreach ($n in $localNodes) {
            AddS ("INSERT INTO routing_node (id, tenant_id, routing_id, node_key, operation_template_id, operation_code, operation_name, standard_seconds, setup_seconds, teardown_seconds, is_optional, overrides, position_x, position_y, seq_no, created_at, updated_at) VALUES " +
                "('$($n.nid)', '$tenant', '$($rt.id)', '$($n.key)', '$($n.op.id)', $(Q $n.op.code), $(Q $n.op.name), $($n.op.std), $($n.op.setup), 300, false, '{`"primaryResourceMode`":`"FLOAT`"}'::jsonb, $($n.x), $($n.y), $($n.seq), '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        }
        foreach ($er in $edgeRows) {
            AddS ("INSERT INTO routing_edge (id, tenant_id, routing_id, from_node_id, to_node_id, depend_type, lag_minutes, created_at, updated_at) VALUES ('$($er.id)', '$tenant', '$($rt.id)', '$($er.from)', '$($er.to)', 'FS', 0, '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        }
    }
    foreach ($i in $inventory) {
        AddS ("INSERT INTO inventory (id, tenant_id, material_id, warehouse_code, on_hand_qty, safety_stock, snapshot_at, created_at, updated_at) VALUES " +
            "('$($i.id)', '$tenant', '$($matByCode[$i.code].id)', 'WH-MAIN', $($i.qty), $($i.safety), '$(AnchorTime (-6) '18:00:00')', '$(AnchorTime (-6) '18:00:00')', '$(AnchorTime (-6) '18:00:00')');")
    }
    # 在途：近期到期计入，远期越过最早需求日被 MRP 裁剪
    $fuseId = $matByCode['RAW-ELEC-002'].id
    $cellId = $matByCode['RAW-CELL-001'].id
    $bms2Id = $matByCode['RAW-BMS-002'].id
    AddS ("INSERT INTO inventory_transit (id, tenant_id, material_id, qty, expected_date, source_type, status, created_at, updated_at) VALUES " +
        "('$($transitIds[0])', '$tenant', '$fuseId', 50, '$(AnchorDay 20)', 'PURCHASE', 'CONFIRMED', '$(AnchorTime (-6) '18:00:00')', '$(AnchorTime (-6) '18:00:00')')," +
        "('$($transitIds[1])', '$tenant', '$fuseId', 200, '$(AnchorDay 76)', 'PURCHASE', 'CONFIRMED', '$(AnchorTime (-6) '18:00:00')', '$(AnchorTime (-6) '18:00:00')')," +
        "('$($transitIds[2])', '$tenant', '$cellId', 2000, '$(AnchorDay 7)', 'PURCHASE', 'CONFIRMED', '$(AnchorTime (-6) '18:00:00')', '$(AnchorTime (-6) '18:00:00')')," +
        "('$($transitIds[3])', '$tenant', '$cellId', 4000, '$(AnchorDay 115)', 'PURCHASE', 'PLANNED', '$(AnchorTime (-6) '18:00:00')', '$(AnchorTime (-6) '18:00:00')')," +
        # BMS-002 在途 30 套，预计 +60 天到货，晚于 1009 交期（+53 天）→ 本次不计入可用量，缺料明细可给出该在途数。
        "('$($transitIds[4])', '$tenant', '$bms2Id', 30, '$(AnchorDay 60)', 'PURCHASE', 'CONFIRMED', '$(AnchorTime (-6) '18:00:00')', '$(AnchorTime (-6) '18:00:00')');")
    if ($hasSetupMatrix) {
        $setup = @(
            @{ id = $setupIds[0]; type = 'MATERIAL_CODE';   from = 'FG-ESS-001';    to = 'FG-ESS-002';    scope = 'TENANT';         rid = $null;    gid = $null;       sec = 5400; same = 3600; cross = 7200; remark = '不同 PACK 型号切换' },
            @{ id = $setupIds[1]; type = 'MATERIAL_CODE';   from = 'FG-ESS-002';    to = 'FG-ESS-001';    scope = 'TENANT';         rid = $null;    gid = $null;       sec = 5400; same = 3600; cross = 7200; remark = '反向切换' },
            @{ id = $setupIds[2]; type = 'MATERIAL_CODE';   from = 'SEMI-SMT-001'; to = 'SEMI-MOD-001'; scope = 'TENANT';      rid = $null;    gid = $null;       sec = 1800; same = 900;  cross = 2400; remark = 'SMT 到模组跨工序' },
            @{ id = $setupIds[3]; type = 'MATERIAL_CODE';   from = 'SEMI-SMT-001'; to = 'FG-ESS-001';    scope = 'RESOURCE_GROUP'; rid = $null;    gid = $grpIds[0];  sec = 900;  same = 600;  cross = 1200; remark = 'SMT 线组内切换' },
            @{ id = $setupIds[4]; type = 'MATERIAL_CODE';   from = 'FG-ESS-001';    to = 'FG-ESS-002';    scope = 'RESOURCE';       rid = $resIds[6]; gid = $null;     sec = 3900; same = 3000; cross = 4800; remark = 'PACK-01 专用换型' },
            @{ id = $setupIds[5]; type = 'OPERATION_CODE';  from = 'OP-SMT-01';     to = 'OP-SMT-02';     scope = 'TENANT';         rid = $null;    gid = $null;       sec = 1800; same = 900;  cross = 2400; remark = '贴片转 AOI' }
        )
        foreach ($s in $setup) {
            AddS ("INSERT INTO setup_matrix_entry (id, tenant_id, key_type, from_key, to_key, scope_type, resource_id, resource_group_id, setup_seconds, same_series_setup_seconds, cross_series_setup_seconds, remark, created_at, updated_at) VALUES " +
                "('$($s.id)', '$tenant', '$($s.type)', $(Q $s.from), $(Q $s.to), '$($s.scope)', $(if ($s.rid) { "'$($s.rid)'" } else { 'NULL' }), $(if ($s.gid) { "'$($s.gid)'" } else { 'NULL' }), $($s.sec), $($s.same), $($s.cross), $(Q $s.remark), '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        }
    }
}

function Add-OrdersAndStrategy {
    foreach ($o in $orders) {
        # 多行订单：明细来自 $o.lines；单行订单按订单头 product/qty 生成一行。
        $lineDefs = if ($o.ContainsKey('lines') -and $o.lines) { $o.lines } else { @(@{ code = $o.product; qty = $o.qty }) }
        $lineSnap = @()
        $amount = 0
        foreach ($ln in $lineDefs) {
            $unitPrice = if ($o.ContainsKey('px') -and $o.px) { $o.px } else { $priceByCode[$ln.code] }
            $lineAmount = $unitPrice * $ln.qty
            $amount += $lineAmount
            $lineSnap += [ordered]@{ lineNo = $lineSnap.Count + 1; productCode = $ln.code; productName = $matByCode[$ln.code].name; unit = $matByCode[$ln.code].unit; qty = $ln.qty; unitPrice = $unitPrice; currency = $o.cur; amount = $lineAmount; dueDate = $o.due }
        }
        $headPrice = if ($o.ContainsKey('px') -and $o.px) { $o.px } else { $priceByCode[$o.product] }
        $extObj = [ordered]@{
            orderDate  = (AnchorDay $o.od)
            customerPo = $o.po
            contact    = $o.contact
            shipTo     = $o.shipTo
            currency   = $o.cur
            unitPrice  = $headPrice
            amount     = $amount
            lines      = $lineSnap
        }
        if ($o.ContainsKey('frz') -and $o.frz) {
            foreach ($k in $o.frz.Keys) { $extObj[$k] = $o.frz[$k] }
        }
        $extJson = J $extObj
        AddS ("INSERT INTO orders (id, tenant_id, order_no, source, product_id, qty, due_date, priority, customer_id, routing_id, status, freeze_status, remark, ext_attrs, created_by, created_at, updated_at) VALUES " +
            "('$($o.id)', '$tenant', '$($o.no)', 'ERP_SYNC', '$($matByCode[$o.product].id)', $($o.qty), '$($o.due)', '$($o.prio)', (SELECT id FROM customer WHERE tenant_id = '$tenant' AND code = $(Q $o.cust) AND deleted = false), $(if ($o.routing) { "'$($o.routing)'" } else { 'NULL' }), '$($o.status)', '$($o.freeze)', $(Q $o.remark), $(Q $extJson)::jsonb, '$AdminUserId', '$(AnchorTime (-8) '10:00:00')', '$(AnchorTime (-8) '10:00:00')');")
    }
    $ruleIdx = 0
    foreach ($s in $strategies) {
        $opt = [ordered]@{ softWeights = [ordered]@{} }
        for ($i = 0; $i -lt $softCodes.Count; $i++) { $opt.softWeights[$softCodes[$i]] = $s.weights[$i] }
        $optJson = J $opt
        AddS ("INSERT INTO strategy (id, tenant_id, name, direction, strategy_version, initial_rules, optimization_rules, effective_from, apply_condition, created_by, created_at, updated_at) VALUES " +
            "('$($s.id)', '$tenant', $(Q $s.name), '$($s.dir)', 1, '{`"forwardSchedulingRule`":`"EARLIEST_DUE_DATE_FIRST`"}'::jsonb, $(Q $optJson)::jsonb, '$(AnchorDay (-308))', '{}'::jsonb, '$AdminUserId', '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        for ($i = 0; $i -lt $softCodes.Count; $i++) {
            $ruleIdx++
            AddS ("INSERT INTO strategy_rule (id, tenant_id, strategy_id, rule_code, name, rule_phase, rule_type, weight, params, enabled, seq_no, created_by, created_at, updated_at) VALUES ('$($ruleIds[$ruleIdx-1])', '$tenant', '$($s.id)', '$($softCodes[$i])', $(Q $softNames[$i]), 'OPTIMIZATION', 'SOFT', $($s.weights[$i]), '{}'::jsonb, true, $($i+1), '$AdminUserId', '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
        }
    }
}

function Add-SandboxTasks {
    AddS "INSERT INTO sandbox (id, tenant_id, name, type, parent_sandbox_id, snapshot, created_by, created_at, updated_at) VALUES ('$whatIfId', '$tenant', 'WhatIf：1002 加急插单并调整 SMT 资源', 'WHAT_IF', '$BaselineSandboxId', '{`"note`":`"基于 BASELINE 的插单与资源调整仿真`"}'::jsonb, '$AdminUserId', '$(AnchorTime (-1) '10:30:00')', '$(AnchorTime (-1) '10:30:00')');"
    AddS "INSERT INTO sandbox (id, tenant_id, name, type, parent_sandbox_id, snapshot, created_by, created_at, updated_at) VALUES ('$archivedId', '$tenant', '历史场景：1002-1006 已下发（已归档）', 'ARCHIVED', '$BaselineSandboxId', '{`"note`":`"上一次 apply 的结果，归档保留用于追溯`"}'::jsonb, '$AdminUserId', '$(AnchorTime (-3) '15:00:00')', '$(AnchorTime (-3) '15:00:00')');"

    $chain = @(
        @{ op = $opIds[0]; res = $resIds[0];  ri = 0;  dur = 1800;  setup = 1800 },
        @{ op = $opIds[1]; res = $resIds[2];  ri = 2;  dur = 900;   setup = 900 },
        @{ op = $opIds[2]; res = $resIds[3];  ri = 3;  dur = 1800;  setup = 600 },
        @{ op = $opIds[3]; res = $resIds[4];  ri = 4;  dur = 3600;  setup = 1800 },
        @{ op = $opIds[4]; res = $resIds[6];  ri = 6;  dur = 7200;  setup = 2400 },
        @{ op = $opIds[5]; res = $resIds[8];  ri = 8;  dur = 10800; setup = 3600 },
        @{ op = $opIds[6]; res = $resIds[9];  ri = 9;  dur = 3600;  setup = 1200 },
        @{ op = $opIds[7]; res = $resIds[10]; ri = 10; dur = 1800;  setup = 900 }
    )

    $plans = @(
        @{ no = 'DM-SO-1002'; anchor = (AnchorTime 6 '08:00:00'); shift = 0;   ov = @{ } },
        @{ no = 'DM-SO-1003'; anchor = (AnchorTime 4 '08:00:00'); shift = 0;   ov = @{ } },
        @{ no = 'DM-SO-1004'; anchor = (AnchorTime 7 '08:00:00'); shift = 0;   ov = @{ } },
        @{ no = 'DM-SO-1005'; anchor = (AnchorTime 1 '08:00:00'); shift = 0;   ov = @{ } },
        @{ no = 'DM-SO-1006'; anchor = (AnchorTime 0 '08:00:00'); shift = 0;   ov = @{ } },
        @{ no = 'DM-SO-1007'; anchor = (AnchorTime 18 '08:00:00'); shift = 0;   ov = @{ } }
    )
    $whatIfPlans = @(
        @{ no = 'DM-SO-1002'; anchor = (AnchorTime 6 '08:00:00'); shift = 120; ov = @{ 1 = $resIds[1] } },
        @{ no = 'DM-SO-1003'; anchor = (AnchorTime 4 '08:00:00'); shift = 360; ov = @{ } },
        @{ no = 'DM-SO-1004'; anchor = (AnchorTime 7 '08:00:00'); shift = 240; ov = @{ } },
        @{ no = 'DM-SO-1005'; anchor = (AnchorTime 1 '08:00:00'); shift = -60; ov = @{ 5 = $resIds[7] } },
        @{ no = 'DM-SO-1006'; anchor = (AnchorTime 0 '08:00:00'); shift = 0;   ov = @{ } },
        @{ no = 'DM-SO-1007'; anchor = (AnchorTime 18 '08:00:00'); shift = 480; ov = @{ } }
    )
    $archivedPlans = @($plans[0], $plans[1], $plans[2], $plans[3], $plans[4])

    # 后端以 OperationRunDuration 按“工序主资源 + 订单数量”重算加工秒数，任务里预排的时长必须与之一致，
    # 否则 FROZEN（start 被 pin 住）的工序链在重算后会压到后一道工序（OPERATION_DEPENDENCY 硬违反）。
    function Get-RunSeconds([int]$resIndex, $operation, [int]$qty) {
        $r = $resources[$resIndex]
        switch ($r.mode) {
            'UNIT_PER_HOUR'    { return [long][Math]::Ceiling(($qty * 3600.0) / [double]$r.cap) }
            'SECONDS_PER_UNIT' { return [long][Math]::Ceiling($qty * [double]$operation.std) }
            'BATCH_SECONDS'    { return [long]([Math]::Ceiling($qty / [double]$r.batch) * [double]$r.cap) }
            default            { throw "未知产能模式: $($r.mode)" }
        }
    }
    # 冻结订单的 pin 间距按“最坏换型”预留：setup_matrix_entry 跨型号换型上限为 7200 秒。
    $setupCeilingSeconds = 7200

    function Emit-Tasks([string]$sbx, $defs, [bool]$isWhatIf) {
        foreach ($p in $defs) {
            $o = $orderByNo[$p.no]
            $cursor = [DateTimeOffset]::Parse($p.anchor).AddMinutes($p.shift)
            for ($i = 0; $i -lt $chain.Count; $i++) {
                $c = $chain[$i]
                if ($p.ov.ContainsKey($i + 1)) { $res = $p.ov[$i + 1] } else { $res = $c.res }
                $runSeconds = Get-RunSeconds $c.ri $operations[$i] $o.qty
                $ss = $cursor
                $se = $cursor.AddSeconds($c.setup)
                $end = $se.AddSeconds($runSeconds)
                $status = 'PLANNED'; $lockType = 'NONE'; $lockedFrom = 'NULL'; $lockedTo = 'NULL'; $lockedRes = "'[]'::jsonb"
                if ($o.freeze -eq 'FROZEN') { $status = 'FROZEN'; $lockType = 'FROZEN' }
                if ($o.no -eq 'DM-SO-1004' -and $i -eq 3) { $lockType = 'RESOURCE'; $lockedRes = "'[""$($resIds[4])""]'::jsonb" }
                if ($o.no -eq 'DM-SO-1005' -and $i -eq 0) {
                    $lockType = 'TIME_WINDOW'
                    $lockedFrom = Q ($ss.ToString('yyyy-MM-ddTHH:mm:sszzz'))
                    $lockedTo = Q ($end.AddHours(2).ToString('yyyy-MM-ddTHH:mm:sszzz'))
                }
                if ($o.no -eq 'DM-SO-1006') {
                    if ($i -eq 0) { $status = 'COMPLETED' } elseif ($i -eq 1) { $status = 'IN_PROGRESS' }
                }
                $setupStart = Q ($ss.ToString('yyyy-MM-ddTHH:mm:sszzz'))
                $setupEnd = Q ($se.ToString('yyyy-MM-ddTHH:mm:sszzz'))
                if ($isWhatIf -and $o.no -eq 'DM-SO-1002' -and $i -eq 0) { $setupStart = 'NULL'; $setupEnd = 'NULL' }
                AddS ("INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, operation_template_id, resource_id, secondary_resource_ids, start_time, end_time, setup_start_time, setup_end_time, qty, status, sequence_in_resource, lock_type, locked_resource_ids, locked_from, locked_to, created_by, created_at, updated_at) VALUES " +
                    "('$tenant', '$($o.id)', '$sbx', $($i+1), '$($c.op)', '$res', '[]'::jsonb, '$($ss.ToString('yyyy-MM-ddTHH:mm:sszzz'))', '$($end.ToString('yyyy-MM-ddTHH:mm:sszzz'))', $setupStart, $setupEnd, $($o.qty), '$status', $($i+1), '$lockType', $lockedRes, $lockedFrom, $lockedTo, '$AdminUserId', '$(AnchorTime (-3) '12:00:00')', '$(AnchorTime (-3) '12:00:00')');")
                $advanceSeconds = if ($o.freeze -eq 'FROZEN') {
                    [Math]::Max($c.setup, $setupCeilingSeconds) + $runSeconds
                } else {
                    $c.setup + $runSeconds
                }
                $cursor = $ss.AddSeconds($advanceSeconds).AddMinutes(10)
            }
        }
    }

    Emit-Tasks $BaselineSandboxId $plans $false
    Emit-Tasks $whatIfId $whatIfPlans $true
    Emit-Tasks $archivedId $archivedPlans $false
}

function Add-Solve {
    $solveDefs = @(
        @{ key = 'demo-seed-solve-001'; sandbox = $BaselineSandboxId; status = 'SOLVED';    hard = '0'; soft = '-210'; unassigned = 0; taskCount = 32; util = 0.72; strategy = $stratIds[0]; orders = @('DM-SO-1002','DM-SO-1005','DM-SO-1006','DM-SO-1007'); started = (AnchorTime (-2) '09:00:00'); finished = (AnchorTime (-2) '09:42:00'); err = 'NULL' },
        @{ key = 'demo-seed-solve-002'; sandbox = $BaselineSandboxId; status = 'SOLVED';    hard = '0'; soft = '-165'; unassigned = 0; taskCount = 48; util = 0.81; strategy = $stratIds[1]; orders = @('DM-SO-1002','DM-SO-1003','DM-SO-1004','DM-SO-1005','DM-SO-1006','DM-SO-1007'); started = (AnchorTime (-1) '09:00:00'); finished = (AnchorTime (-1) '09:55:00'); err = 'NULL' },
        @{ key = 'demo-seed-solve-003'; sandbox = $whatIfId;          status = 'SOLVED';    hard = '0'; soft = '-132'; unassigned = 0; taskCount = 48; util = 0.86; strategy = $stratIds[0]; orders = @('DM-SO-1002','DM-SO-1003','DM-SO-1004','DM-SO-1005','DM-SO-1006','DM-SO-1007'); started = (AnchorTime 0 '09:00:00'); finished = (AnchorTime 0 '10:05:00'); err = 'NULL' },
        @{ key = 'demo-seed-solve-004'; sandbox = $BaselineSandboxId; status = 'FAILED';    hard = 'NULL'; soft = 'NULL'; unassigned = $null; taskCount = 0; util = 0; strategy = $stratIds[0]; orders = @('DM-SO-1008'); started = (AnchorTime (-3) '14:00:00'); finished = (AnchorTime (-3) '14:03:00'); err = "'引擎内存不足：订单 1008 批量过大，求解终止'" },
        @{ key = 'demo-seed-solve-005'; sandbox = $BaselineSandboxId; status = 'CANCELLED'; hard = 'NULL'; soft = 'NULL'; unassigned = $null; taskCount = 0; util = 0; strategy = $stratIds[1]; orders = @('DM-SO-1009'); started = (AnchorTime (-3) '16:00:00'); finished = (AnchorTime (-3) '16:00:30'); err = 'NULL' }
    )
    foreach ($g in $solveDefs) {
        if ($g.status -eq 'SOLVED') {
            $elapsed = [int](([DateTimeOffset]::Parse($g.finished) - [DateTimeOffset]::Parse($g.started)).TotalMilliseconds)
            $metrics = J ([ordered]@{ phase = 'DONE'; progress = 1.0; unassignedTaskCount = $g.unassigned; taskCount = $g.taskCount; assignedTaskCount = $g.taskCount; elapsedMs = $elapsed; resourceUtilization = $g.util; setupCount = [int]($g.taskCount / 8 * 2); engineFallback = $false })
        } else { $metrics = '{}' }
        $config = J ([ordered]@{ sandboxId = $g.sandbox; orderNos = $g.orders; strategyId = $g.strategy })
        AddS ("INSERT INTO solve_task (tenant_id, sandbox_id, strategy_id, engine, status, config, score_hard, score_soft, started_at, finished_at, metrics, error_message, idempotency_key, created_by, created_at, updated_at) VALUES " +
            "('$tenant', '$($g.sandbox)', '$($g.strategy)', 'TIMEFOLD', '$($g.status)', $(Q $config)::jsonb, $($g.hard), $($g.soft), '$($g.started)', '$($g.finished)', $(Q $metrics)::jsonb, $($g.err), '$($g.key)', '$AdminUserId', '$(AnchorTime (-3) '12:00:00')', '$(AnchorTime (-3) '12:00:00')');")
    }
    # 归因事实必须带真实 task.id（bigint），格式与求解结果写入完全一致：
    # [{"type":"TASK","id":<task.id>}]。这里在灌库事务内用子查询解析，
    # 保证 /gantt/order 返回的 taskId 与 /agent/explain 反查的 id 是同一批任务。
    function TaskFacts([string]$sandbox, [string[]]$orderNos, [int[]]$ops) {
        $orderList = ($orderNos | ForEach-Object { "'$_'" }) -join ','
        $opList = ($ops -join ',')
        return ("(SELECT jsonb_agg(jsonb_build_object('type', 'TASK', 'id', t.id) ORDER BY t.order_id, t.operation_index) " +
            "FROM task t WHERE t.tenant_id = '$tenant' AND t.sandbox_id = '$sandbox' AND t.deleted = false " +
            "AND t.order_id IN (SELECT id FROM orders WHERE tenant_id = '$tenant' AND order_no IN ($orderList) AND deleted = false) " +
            "AND t.operation_index IN ($opList))")
    }
    # 扣分合计 = solve-001 的 score_soft(-210)：120+45+30+15；权重取自 solve-001 所用的交期优先策略。
    $just = @(
        @{ id = $justIds[0]; code = 'MIN_TARDINESS';           weight = 25; deduction = 120; facts = (TaskFacts $BaselineSandboxId @('DM-SO-1005','DM-SO-1006') @(1,2)); zh = ('1005 批量 48 台，SMT 锁定窗口 ' + (AnchorMd 1) + ' 08:00-11:00，与 1006 在制订单争用线体，相关 SMT 任务合计拖期；软分扣减 120（策略权重 25）。') },
        @{ id = $justIds[1]; code = 'EDD';                     weight = 20; deduction = 45;  facts = (TaskFacts $BaselineSandboxId @('DM-SO-1002') @(1,2)); zh = ('1002 采用最早交期优先，被放入 ' + (AnchorMd 1) + ' 的首个 SMT 窗口；软分扣减 45（策略权重 20）。') },
        @{ id = $justIds[2]; code = 'SAME_PRODUCT_CONTINUOUS'; weight = 10; deduction = 30;  facts = (TaskFacts $BaselineSandboxId @('DM-SO-1002','DM-SO-1005','DM-SO-1006') @(5)); zh = '同型号 FG-ESS-001 在 PACK-01 连续排产，减少换型；软分扣减 30（策略权重 10）。' },
        @{ id = $justIds[3]; code = 'MIN_SETUP_TIME';          weight = 12; deduction = 15;  facts = (TaskFacts $BaselineSandboxId @('DM-SO-1005','DM-SO-1007') @(5)); zh = 'PACK-01 从 FG-ESS-001 切换到 FG-ESS-002 预留 65 分钟换型（换型矩阵 3900 秒）；软分扣减 15（策略权重 12）。' }
    )
    foreach ($j in $just) {
        AddS ("INSERT INTO constraint_justification (id, tenant_id, solve_task_id, constraint_id, constraint_name, score_impact, implicated_facts, explanation_zh, weight, deduction, created_at, updated_at) SELECT " +
            "'$($j.id)', '$tenant', st.id, '$($j.code)', '$($j.code)', 'SOFT', $($j.facts)::jsonb, $(Q $j.zh), $($j.weight), $($j.deduction), '$(AnchorTime (-2) '09:43:00')', '$(AnchorTime (-2) '09:43:00')' FROM solve_task st WHERE st.tenant_id = '$tenant' AND st.idempotency_key = 'demo-seed-solve-001';")
    }
    AddS ("INSERT INTO task_adjustment (tenant_id, task_id, sandbox_id, adjust_type, before_value, after_value, reason, operator_id, created_at, created_by) SELECT '$tenant', t.id, t.sandbox_id, 'MOVE', '{`"startTime`":`"$(AnchorTime 1 '08:00:00')`"}'::jsonb, '{`"startTime`":`"$(AnchorTime 1 '10:00:00')`"}'::jsonb, '客户要求 1002 提前上线', '$AdminUserId', '$(AnchorTime (-1) '11:00:00')', '$AdminUserId' FROM task t JOIN orders o ON o.id = t.order_id WHERE t.tenant_id = '$tenant' AND t.sandbox_id = '$BaselineSandboxId' AND o.order_no = 'DM-SO-1002' AND t.operation_index = 1;")
    AddS ("INSERT INTO task_adjustment (tenant_id, task_id, sandbox_id, adjust_type, before_value, after_value, reason, operator_id, created_at, created_by) SELECT '$tenant', t.id, t.sandbox_id, 'FREEZE', '{`"status`":`"PLANNED`"}'::jsonb, '{`"status`":`"FROZEN`"}'::jsonb, '1003 交期已与客户锁定', '$AdminUserId', '$(AnchorTime (-1) '11:10:00')', '$AdminUserId' FROM task t JOIN orders o ON o.id = t.order_id WHERE t.tenant_id = '$tenant' AND t.sandbox_id = '$BaselineSandboxId' AND o.order_no = 'DM-SO-1003' AND t.operation_index = 1;")
    AddS ("INSERT INTO task_adjustment (tenant_id, task_id, sandbox_id, adjust_type, before_value, after_value, reason, operator_id, created_at, created_by) SELECT '$tenant', t.id, t.sandbox_id, 'LOCK', '{`"lockType`":`"NONE`"}'::jsonb, '{`"lockType`":`"RESOURCE`",`"resourceCodes`":[`"RES-MODULE-01`"]}'::jsonb, '1004 指定模组线', '$AdminUserId', '$(AnchorTime (-1) '11:20:00')', '$AdminUserId' FROM task t JOIN orders o ON o.id = t.order_id WHERE t.tenant_id = '$tenant' AND t.sandbox_id = '$BaselineSandboxId' AND o.order_no = 'DM-SO-1004' AND t.operation_index = 4;")
}

function Add-Plans {
    $planDefs = @(
        @{ id = $planIds[0]; sandbox = $BaselineSandboxId; no = 'PLAN-20261103-001'; ver = '1'; orders = "'DM-SO-1002','DM-SO-1005','DM-SO-1006','DM-SO-1007'"; count = 32; at = (AnchorTime (-2) '10:00:00'); reason = '交期优先策略首版：1002 加急插单'; rollback = 'NULL' },
        @{ id = $planIds[1]; sandbox = $BaselineSandboxId; no = 'PLAN-20261104-002'; ver = '2'; orders = "'DM-SO-1002','DM-SO-1003','DM-SO-1004','DM-SO-1005','DM-SO-1006','DM-SO-1007'"; count = 48; at = (AnchorTime (-1) '10:00:00'); reason = '均衡策略：纳入 1003/1004 冻结与锁定订单'; rollback = "'$($planIds[0])'" },
        @{ id = $planIds[2]; sandbox = $whatIfId;          no = 'PLAN-20261105-003'; ver = '3'; orders = "'DM-SO-1002','DM-SO-1003','DM-SO-1004','DM-SO-1005','DM-SO-1006','DM-SO-1007'"; count = 48; at = (AnchorTime 0 '10:30:00'); reason = '交期优先 v3：插单 WhatIf 应用后发布'; rollback = "'$($planIds[1])'" },
        @{ id = $planIds[3]; sandbox = $archivedId;        no = 'PLAN-20261102-004'; ver = '4'; orders = "'DM-SO-1002','DM-SO-1003','DM-SO-1004','DM-SO-1005','DM-SO-1006'"; count = 40; at = (AnchorTime (-3) '15:30:00'); reason = '历史场景 apply 后的快照（已归档）'; rollback = 'NULL' }
    )
    foreach ($p in $planDefs) {
        $snapExpr = "jsonb_build_object('tasks', COALESCE((SELECT jsonb_agg(jsonb_build_object('taskId', t.id::text, 'orderNo', o.order_no, 'operationIndex', t.operation_index, 'status', t.status, 'startTime', t.start_time, 'endTime', t.end_time, 'resourceId', t.resource_id::text, 'qty', t.qty) ORDER BY t.start_time) FROM task t JOIN orders o ON o.id = t.order_id WHERE t.tenant_id = '$tenant' AND t.sandbox_id = '$($p.sandbox)' AND o.order_no IN ($($p.orders))), '[]'::jsonb), 'publishedFrom', 'BASELINE', 'strategyId', '$($stratIds[0])')"
        AddS ("INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status, snapshot, task_count, published_at, published_by, rollback_of, reason, approver, created_by, created_at, updated_at) VALUES " +
            "('$($p.id)', '$tenant', '$($p.sandbox)', '$($p.no)', '$($p.ver)', 'PUBLISHED', $snapExpr, $($p.count), '$($p.at)', '$AdminUserId', $($p.rollback), $(Q $p.reason), 'admin', '$AdminUserId', '$(AnchorTime (-3) '12:00:00')', '$(AnchorTime (-3) '12:00:00')');")
    }
    AddS ("INSERT INTO notification_outbox (tenant_id, dispatch_id, plan_id, channel, address, message, status, attempts, next_attempt_at, last_error, created_at, updated_at) VALUES " +
        "('$tenant', '$dispatchId', '$($planIds[2])', 'FEISHU', 'planner-group', '新版计划 PLAN-20261105-003 已发布，请车间确认', 'SENT', 1, '$(AnchorTime 0 '10:31:00')', NULL, '$(AnchorTime 0 '10:30:00')', '$(AnchorTime 0 '10:31:00')')," +
        "('$tenant', '$dispatchId', '$($planIds[2])', 'EMAIL', 'planning@demo.com', '新版计划 PLAN-20261105-003 已发布（邮件副本）', 'QUEUED', 0, '$(AnchorTime 0 '11:00:00')', NULL, '$(AnchorTime 0 '10:30:00')', '$(AnchorTime 0 '10:30:00')');")
}

function Add-AuditExtras {
    $audits = @(
        @{ action = 'AUTH_LOGIN_FAILED';  rtype = 'USER';    rid = 'finance01';    before = 'NULL';                        after = '{"reason":"bad-credentials","username":"finance01"}'; ip = '10.20.1.15'; trace = 'demo-seed-audit-01'; at = (AnchorTime (-3) '08:12:00'); uid = 'NULL' },
        @{ action = 'AUTH_LOGIN';          rtype = 'USER';    rid = $AdminUserId;  before = 'NULL';                        after = '{"result":"success"}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-02'; at = (AnchorTime (-3) '08:30:00'); uid = "'$AdminUserId'" },
        @{ action = 'ORDER_STATUS_CHANGED'; rtype = 'ORDER';  rid = 'DM-SO-1005';  before = '{"status":"CONFIRMED"}';       after = '{"status":"RELEASED"}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-03'; at = (AnchorTime (-3) '09:05:00'); uid = "'$AdminUserId'" },
        @{ action = 'ORDER_FROZEN';        rtype = 'ORDER';   rid = 'DM-SO-1003';  before = '{"freezeStatus":"UNFROZEN"}';  after = '{"freezeStatus":"FROZEN"}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-04'; at = (AnchorTime (-3) '09:20:00'); uid = "'$AdminUserId'" },
        @{ action = 'SANDBOX_CREATED';     rtype = 'SANDBOX'; rid = $whatIfId;     before = 'NULL';                        after = '{"name":"WhatIf：1002 加急插单并调整 SMT 资源","type":"WHAT_IF"}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-05'; at = (AnchorTime (-3) '10:00:00'); uid = "'$AdminUserId'" },
        @{ action = 'SANDBOX_APPLIED';     rtype = 'SANDBOX'; rid = $archivedId;   before = '{"type":"WHAT_IF"}';           after = '{"type":"ARCHIVED","promoted":true}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-06'; at = (AnchorTime (-3) '15:30:00'); uid = "'$AdminUserId'" },
        @{ action = 'PLAN_PUBLISHED';      rtype = 'PLAN';    rid = $planIds[0];   before = 'NULL';                        after = '{"planNo":"PLAN-20261103-001","taskCount":32}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-07'; at = (AnchorTime (-2) '10:00:00'); uid = "'$AdminUserId'" },
        @{ action = 'PLAN_PUBLISHED';      rtype = 'PLAN';    rid = $planIds[1];   before = 'NULL';                        after = '{"planNo":"PLAN-20261104-002","taskCount":48}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-08'; at = (AnchorTime (-1) '10:00:00'); uid = "'$AdminUserId'" },
        @{ action = 'TASK_ADJUSTED';       rtype = 'TASK';    rid = 'DM-SO-1002#1'; before = ('{"startTime":"' + (AnchorTime 1 '08:00:00') + '"}'); after = ('{"startTime":"' + (AnchorTime 1 '10:00:00') + '"}'); ip = '10.20.1.10'; trace = 'demo-seed-audit-09'; at = (AnchorTime (-1) '11:00:00'); uid = "'$AdminUserId'" },
        @{ action = 'TASK_ADJUSTED';       rtype = 'TASK';    rid = 'DM-SO-1004#4'; before = '{"lockType":"NONE"}';          after = '{"lockType":"RESOURCE"}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-10'; at = (AnchorTime (-1) '11:20:00'); uid = "'$AdminUserId'" },
        @{ action = 'PLAN_PUBLISHED';      rtype = 'PLAN';    rid = $planIds[2];   before = 'NULL';                        after = '{"planNo":"PLAN-20261105-003","taskCount":48}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-11'; at = (AnchorTime 0 '10:30:00'); uid = "'$AdminUserId'" },
        @{ action = 'PLAN_DISPATCHED';     rtype = 'PLAN';    rid = $planIds[2];   before = 'NULL';                        after = '{"channels":["FEISHU","EMAIL"],"status":"QUEUED"}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-12'; at = (AnchorTime 0 '10:31:00'); uid = "'$AdminUserId'" },
        @{ action = 'IMPORT_EXECUTED';     rtype = 'MATERIAL'; rid = $importIds[0]; before = 'NULL';                        after = '{"entity":"materials","inserted":10,"updated":2}'; ip = '10.20.1.17'; trace = 'demo-seed-audit-13'; at = (AnchorTime (-4) '14:00:00'); uid = "'$AdminUserId'" },
        @{ action = 'USER_UPDATED';        rtype = 'USER';    rid = $demoUserId1;  before = '{"status":"DISABLED"}';       after = '{"status":"ACTIVE"}'; ip = '10.20.1.10'; trace = 'demo-seed-audit-14'; at = (AnchorTime (-4) '09:30:00'); uid = "'$AdminUserId'" }
    )
    foreach ($a in $audits) {
        $bv = if ($a.before -eq 'NULL') { 'NULL' } else { Q $a.before }
        $av = if ($a.after -eq 'NULL') { 'NULL' } else { Q $a.after }
        AddS ("INSERT INTO audit_log (tenant_id, user_id, action, resource_type, resource_id, before_value, after_value, ip, user_agent, trace_id, created_at) VALUES " +
            "('$tenant', $($a.uid), '$($a.action)', '$($a.rtype)', $(Q $a.rid), $bv::jsonb, $av::jsonb, '$($a.ip)', 'TraeDemoAgent/1.0', '$($a.trace)', '$($a.at)');")
    }
    AddS ("INSERT INTO import_job (id, tenant_id, entity, status, content_hash, file_name, file_bytes, total_rows, processed_rows, inserted, updated, skipped, invalid_rows, errors, committed, created_by, created_at, started_at, finished_at) VALUES " +
        "('$($importIds[0])', '$tenant', 'materials', 'SUCCEEDED', 'demo-seed-import-001', 'material-master-demo.xlsx', 18240, 12, 12, 10, 2, 0, 0, '[]'::jsonb, true, '$AdminUserId', '$(AnchorTime (-4) '13:55:00')', '$(AnchorTime (-4) '13:55:30')', '$(AnchorTime (-4) '14:00:00')')," +
        "('$($importIds[1])', '$tenant', 'orders', 'FAILED', 'demo-seed-import-002', 'orders-bad-demo.xlsx', 6400, 8, 8, 0, 0, 0, 8, '[{`"row`":3,`"field`":`"due_date`",`"message`":`"交期格式非法`"},{`"row`":5,`"field`":`"qty`",`"message`":`"数量必须大于 0`"}]'::jsonb, false, '$AdminUserId', '$(AnchorTime (-3) '09:40:00')', '$(AnchorTime (-3) '09:40:20')', '$(AnchorTime (-3) '09:41:00')');")
    AddS ("INSERT INTO users (id, tenant_id, username, display_name, password_hash, email, phone, status, ext_attrs, created_by, created_at, updated_at) VALUES " +
        "('$demoUserId1', '$tenant', 'planner.demo', '演示计划员', NULL, 'planner@demo.com', '13800000001', 'ACTIVE', '{`"demo`":true}'::jsonb, '$AdminUserId', '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')')," +
        "('$demoUserId2', '$tenant', 'engineer.demo', '演示工艺工程师', NULL, 'engineer@demo.com', '13800000002', 'ACTIVE', '{`"demo`":true}'::jsonb, '$AdminUserId', '$(AnchorTime (-35) '09:00:00')', '$(AnchorTime (-35) '09:00:00')');")
    AddS "INSERT INTO user_role (user_id, role_id) VALUES ('$demoUserId1', '00000000-0000-0000-0000-000000000102'), ('$demoUserId2', '00000000-0000-0000-0000-000000000103') ON CONFLICT DO NOTHING;"
}

# ----------------------------------------------------------------------------- 场景装配
if ($Scene -eq 'audit') {
    AddS "DELETE FROM audit_log WHERE tenant_id = '$tenant' AND trace_id LIKE 'demo-seed-%';"
    AddS "DELETE FROM import_job WHERE tenant_id = '$tenant' AND id IN ($importIdsList);"
    AddS "DELETE FROM user_role WHERE user_id IN ('$demoUserId1','$demoUserId2');"
    AddS "DELETE FROM users WHERE tenant_id = '$tenant' AND id IN ('$demoUserId1','$demoUserId2');"
    if (-not $Reset) { Add-AuditExtras }
} else {
    Add-Cleanup
    if (-not $Reset) {
        switch ($Scene) {
            'master'  { Add-MasterData }
            'orders'  { Add-MasterData; Add-OrdersAndStrategy }
            'sandbox' { Add-MasterData; Add-OrdersAndStrategy; Add-SandboxTasks }
            'solve'   { Add-MasterData; Add-OrdersAndStrategy; Add-SandboxTasks; Add-Solve }
            'plans'   { Add-MasterData; Add-OrdersAndStrategy; Add-SandboxTasks; Add-Solve; Add-Plans }
            default   { Add-MasterData; Add-OrdersAndStrategy; Add-SandboxTasks; Add-Solve; Add-Plans; Add-AuditExtras }
        }
    }
}
AddS "COMMIT;"

# ----------------------------------------------------------------------------- 执行
$tmp = Join-Path $env:TEMP ("aps-seed-demo-" + [guid]::NewGuid().ToString('N') + ".sql")
[System.IO.File]::WriteAllText($tmp, $script:sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
try {
    & $PsqlPath @psqlArgs -d $Database -f $tmp
    if ($LASTEXITCODE -ne 0) { throw "psql 执行失败，退出码 $LASTEXITCODE" }
} finally {
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
}

if ($Reset) {
    Write-Host "已清理演示数据（库：$Database，场景：$Scene）。"
    exit 0
}
if (-not $hasSetupMatrix) { Write-Host "SKIP setup_matrix_entry（V14 表不存在，待该车道建表后补）" }
if (-not $hasAtp) { Write-Host "SKIP ATP 演示数据（ATP 表不存在，待该车道建表后补）" }

# ----------------------------------------------------------------------------- 行数核对
$setupCheck = if ($hasSetupMatrix) {
    "UNION ALL SELECT 'setup_matrix=' || count(*) FROM setup_matrix_entry WHERE tenant_id='$tenant' AND id IN ($setupIdsList)"
} else {
    "UNION ALL SELECT 'setup_matrix=SKIP_TABLE_ABSENT'"
}
$checkSql = @"
SELECT 'customers=' || count(*) FROM customer WHERE tenant_id='$tenant' AND code LIKE 'CUS-00%'
UNION ALL SELECT 'orders_with_customer=' || count(*) FROM orders WHERE tenant_id='$tenant' AND order_no LIKE 'DM-SO-%' AND customer_id IS NOT NULL
UNION ALL SELECT 'materials=' || count(*) FROM material WHERE tenant_id='$tenant' AND id IN ($matIdsList)
UNION ALL SELECT 'boms=' || count(*) FROM bom WHERE tenant_id='$tenant' AND id IN ($bomIdsList)
UNION ALL SELECT 'routings=' || count(*) FROM routing WHERE tenant_id='$tenant' AND id IN ($routingIdsList)
UNION ALL SELECT 'orders=' || count(*) FROM orders WHERE tenant_id='$tenant' AND order_no LIKE 'DM-SO-%'
UNION ALL SELECT 'order_states=' || string_agg(DISTINCT status, ',' ORDER BY status) FROM orders WHERE tenant_id='$tenant' AND order_no LIKE 'DM-SO-%'
UNION ALL SELECT 'freeze_states=' || string_agg(DISTINCT freeze_status, ',' ORDER BY freeze_status) FROM orders WHERE tenant_id='$tenant' AND order_no LIKE 'DM-SO-%'
UNION ALL SELECT 'strategy_rules=' || count(*) FROM strategy_rule WHERE tenant_id='$tenant' AND strategy_id IN ($stratIdsList)
UNION ALL SELECT 'tasks=' || count(*) FROM task WHERE tenant_id='$tenant' AND sandbox_id IN ('$BaselineSandboxId','$whatIfId','$archivedId')
UNION ALL SELECT 'solve_tasks=' || count(*) FROM solve_task WHERE tenant_id='$tenant' AND idempotency_key LIKE 'demo-seed-%'
UNION ALL SELECT 'plans=' || count(*) FROM plan_version WHERE tenant_id='$tenant' AND id IN ($planIdsList)
UNION ALL SELECT 'audit=' || count(*) FROM audit_log WHERE tenant_id='$tenant' AND trace_id LIKE 'demo-seed-%'
UNION ALL SELECT 'import_jobs=' || count(*) FROM import_job WHERE tenant_id='$tenant' AND id IN ($importIdsList)
$setupCheck;
"@
$checkTmp = Join-Path $env:TEMP ("aps-seed-check-" + [guid]::NewGuid().ToString('N') + ".sql")
[System.IO.File]::WriteAllText($checkTmp, "SET client_encoding = 'UTF8';`n" + $checkSql, (New-Object System.Text.UTF8Encoding($false)))
try { & $PsqlPath @psqlArgs -d $Database -f $checkTmp } finally { Remove-Item $checkTmp -Force -ErrorAction SilentlyContinue }
