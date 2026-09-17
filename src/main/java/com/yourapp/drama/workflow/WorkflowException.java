package com.yourapp.drama.workflow;

public class WorkflowException extends RuntimeException {
    private final String code;
    public WorkflowException(String code, String message) { super(message); this.code=code; }
    public String code() { return code; }
}
