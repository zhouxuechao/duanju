package com.yourapp.drama.persistence;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(ResourceKind kind, String id) { super(kind.path() + " not found: " + id); }
}
