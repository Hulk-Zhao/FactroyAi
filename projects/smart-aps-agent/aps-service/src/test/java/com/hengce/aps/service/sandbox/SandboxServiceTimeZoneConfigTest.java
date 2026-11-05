package com.hengce.aps.service.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository;
import com.hengce.aps.service.plan.PlanVersionService;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * C32 业务时区配置绑定单测：{@code aps.business.time-zone} 默认 Asia/Shanghai，可被覆盖。
 */
class SandboxServiceTimeZoneConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SandboxService.class)
            .withBean(SandboxRepository.class, () -> mock(SandboxRepository.class))
            .withBean(PlanVersionRepository.class, () -> mock(PlanVersionRepository.class))
            .withBean(PlanVersionService.class, () -> mock(PlanVersionService.class))
            .withBean(AuditLogRepository.class, () -> mock(AuditLogRepository.class));

    @Test
    @DisplayName("未配置时默认 Asia/Shanghai")
    void shouldDefaultToShanghai() {
        runner.run(context -> assertThat(context.getBean(SandboxService.class).businessTimeZone())
                .isEqualTo(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    @DisplayName("配置为 UTC 时按 UTC 计算截止点")
    void shouldFollowConfiguredZone() {
        runner.withPropertyValues("aps.business.time-zone=UTC")
                .run(context -> assertThat(context.getBean(SandboxService.class).businessTimeZone())
                        .isEqualTo(ZoneId.of("UTC")));
    }
}
