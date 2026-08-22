package com.richard.fyoung.customeradmin.billing.entity;

/** 金额预算告警类型；参与数据库唯一键，命名不可随意修改。 */
public enum CostAlertType {

    /** 已达到配置的预警百分比，但尚未超出金额上限。 */
    BUDGET_WARNING,
    /** 已达到或超出金额上限。 */
    BUDGET_EXCEEDED,
    /** 按当前自然月日均消耗预测将在月末超限。 */
    FORECAST_EXCEEDED
}
