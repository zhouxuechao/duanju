package com.yourapp.drama.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Durable relational documents. Revisions are compare-and-swap tokens, not generation versions. */
public interface DocumentStore {
    ObjectNode get(ResourceKind kind, String id);
    Optional<ObjectNode> find(ResourceKind kind, String id);
    List<ObjectNode> list(ResourceKind kind, String projectId, String parentId);
    ObjectNode create(ResourceKind kind, ObjectNode document);
    ObjectNode update(ResourceKind kind, String id, long expectedRevision, ObjectNode document);
    ObjectNode getForUpdate(ResourceKind kind, String id);
    <T> T transaction(Supplier<T> work);
}
