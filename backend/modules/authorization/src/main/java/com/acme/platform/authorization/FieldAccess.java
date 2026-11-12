package com.acme.platform.authorization;

/** 单个字段在查看/编辑/导出/AI 四类用途下的授权结果。 */
public record FieldAccess(boolean view, boolean edit, boolean export, boolean ai) {

    public static FieldAccess none() {
        return new FieldAccess(false, false, false, false);
    }

    public static FieldAccess full() {
        return new FieldAccess(true, true, true, true);
    }

    public FieldAccess denyAll() {
        return none();
    }
}
