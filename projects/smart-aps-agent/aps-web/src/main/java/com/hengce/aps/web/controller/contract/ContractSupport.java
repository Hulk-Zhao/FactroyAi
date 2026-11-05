package com.hengce.aps.web.controller.contract;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;

/**
 * 占位契约端点辅助方法：统一抛出“尚未实现”，由全局异常处理转换为 501 统一返回体。
 */
public final class ContractSupport {

    private ContractSupport() {
    }

    /**
     * 构造未实现异常。
     *
     * @return 未实现业务异常
     */
    public static BizException notImplemented() {
        return new BizException(ErrorCode.NOT_IMPLEMENTED);
    }
}
