package io.github.codaaaaaa.mecc.core.audit;

/** Audited actions. Stored by name: add new values freely, never rename. */
public enum AuditAction {
    DEVICE_PAIR,
    DEVICE_RENAME,
    DEVICE_REVOKE,
    NETWORK_CLAIM,
    NETWORK_RENAME,
    NETWORK_DELETE,
    NETWORK_SHARE,
    NETWORK_MEMBER_ROLE_CHANGE,
    NETWORK_MEMBER_REMOVE,
    CRAFT_SUBMIT,
    CRAFT_CANCEL,
    PATTERN_ENCODE,
    PATTERN_DEPLOY,
    PROVIDER_SETTING_CHANGE,
    RESTOCK_RULE_CHANGE,
    RESTOCK_CRAFT,
    AUTOMATION_STOPPED,
    REQUESTER_REQUEST_CLEARED,
    DATABASE_BACKUP
}
