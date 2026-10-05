package com.oak.dms.workflow.enums;

/**
 * workflow_stages.rejection_rule (V44). halt_immediately: the first rejection rejects the stage. wait_for_all: the stage concludes only once every entry has responded (approval can still short-circuit).
 */
public enum RejectionRule {
    halt_immediately, wait_for_all
}
