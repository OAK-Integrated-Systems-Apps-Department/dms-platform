package com.oak.dms.workflow.enums;

/**
 * workflow_stages.completion_rule (V44) - counted per participant ENTRY, not per expanded user. Required approvals: all = n, any = 1, majority = floor(n/2)+1.
 */
public enum CompletionRule {
    all, any, majority
}
