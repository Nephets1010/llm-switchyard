/*
 * Copyright 2026 The llm-switchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.switchyard.core.audit;

/** 审计事件类型（契约 EVT-001）。三仓共用同一份枚举值。 */
public enum AuditEventType {

    /** 模型调用。仓库①的主要事件类型。 */
    MODEL_CALL,

    /** 工具调用。 */
    TOOL_CALL,

    /** 检索。 */
    RETRIEVAL,

    /** 人工审批。 */
    APPROVAL,

    /** 流程步骤。 */
    FLOW_STEP
}
