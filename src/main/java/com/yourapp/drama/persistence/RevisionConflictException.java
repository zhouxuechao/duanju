package com.yourapp.drama.persistence;

public class RevisionConflictException extends RuntimeException {
    public RevisionConflictException(ResourceKind kind, String id) { super("Resource was changed; reload before editing: " + kind.path() + "/" + id); }
}
