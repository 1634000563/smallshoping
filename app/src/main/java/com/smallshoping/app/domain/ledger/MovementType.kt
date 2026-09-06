package com.smallshoping.app.domain.ledger

/**
 * 流水变动类型，声明预期符号（正=余额增加）。
 *
 * 追加流水时校验 delta 符号与类型一致，防止「SALE_OUT 记成正数」这类账务错误。
 */
enum class MovementType(val expectedSign: Int) {
    // 库存
    SALE_OUT(-1),
    PURCHASE_IN(1),
    LOSS_OUT(-1),
    ADJUST_IN(1),
    ADJUST_OUT(-1),
    REFUND_IN(1),
    // 会员资金
    MEMBER_RECHARGE(1),
    MEMBER_CONSUME(-1),
    MEMBER_REFUND(1),
    // 客户应收
    CUSTOMER_CREDIT(1),
    CUSTOMER_PAYMENT(-1);
}
