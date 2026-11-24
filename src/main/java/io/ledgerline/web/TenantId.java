package io.ledgerline.web;

import java.util.UUID;

/** The tenant of the current request, resolved from its API key. */
public record TenantId(UUID value) {
}
