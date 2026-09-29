package ru.chmod777.trubofiesta.model;

/** Замечание к входным данным. В выходной GeoJSON не попадает: там только типы техприложения. */
public class Diagnostic {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";
    public static final String INFO = "info";

    public String level;
    public String code;
    public String message;
    public String objectId;

    public Diagnostic() {
    }

    public Diagnostic(String level, String code, String message, String objectId) {
        this.level = level;
        this.code = code;
        this.message = message;
        this.objectId = objectId;
    }

    public static Diagnostic error(String code, String message, String objectId) {
        return new Diagnostic(ERROR, code, message, objectId);
    }

    public static Diagnostic warning(String code, String message, String objectId) {
        return new Diagnostic(WARNING, code, message, objectId);
    }

    public static Diagnostic info(String code, String message, String objectId) {
        return new Diagnostic(INFO, code, message, objectId);
    }
}
