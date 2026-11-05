package com.hengce.aps.admin.bootstrap;

import com.hengce.aps.service.rbac.AdminPasswordBootstrap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * admin 首次登录口令的启动钩子（组合根装配）。
 *
 * <p>门控与写入逻辑统一在 {@link AdminPasswordBootstrap}：仅 dev profile + 环境变量
 * {@code APS_ADMIN_INIT_PASSWORD} 存在 + {@code password_hash IS NULL} 才写入一次。</p>
 */
@Component
public class AdminPasswordBootstrapRunner implements ApplicationRunner {

    private final AdminPasswordBootstrap adminPasswordBootstrap;

    /**
     * 构造钩子。
     *
     * @param adminPasswordBootstrap 初始化组件
     */
    public AdminPasswordBootstrapRunner(AdminPasswordBootstrap adminPasswordBootstrap) {
        this.adminPasswordBootstrap = adminPasswordBootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        adminPasswordBootstrap.bootstrap();
    }
}
