package com.yourapp.drama.model;

/** Never blindly repeat a billable submission when uncertain is true. */
public class ProviderException extends RuntimeException {
    private final String code;
    private final String requestId;
    private final int statusCode;
    private final boolean retryable;
    private final boolean uncertain;
    private String rawOutput;
    private String finishReason;
    private long promptTokens = -1;
    private long completionTokens = -1;
    private long totalTokens = -1;

    public ProviderException(String code, String message, String requestId, int statusCode,
                             boolean retryable, boolean uncertain) {
        super(message);
        this.code = code;
        this.requestId = requestId;
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.uncertain = uncertain;
    }
    public String code() { return code; }
    public String requestId() { return requestId; }
    public int statusCode() { return statusCode; }
    public boolean retryable() { return retryable; }
    public boolean uncertain() { return uncertain; }
    public String rawOutput(){return rawOutput;}
    public ProviderException withRawOutput(String value){rawOutput=value;return this;}
    public String finishReason(){return finishReason;}
    public long promptTokens(){return promptTokens;}
    public long completionTokens(){return completionTokens;}
    public long totalTokens(){return totalTokens;}
    public ProviderException withProviderDiagnostics(String reason,long prompt,long completion,long total){finishReason=reason;promptTokens=prompt;completionTokens=completion;totalTokens=total;return this;}
    public static ProviderException invalid(String code, String message) {
        return new ProviderException(code, message, null, 0, false, false);
    }
}
