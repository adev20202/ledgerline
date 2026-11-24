package io.ledgerline.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import io.ledgerline.common.AssignedIdEntity;
import io.ledgerline.common.Direction;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "journal_entries")
public class JournalEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String reference;

    @Column(nullable = false)
    private String description;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryStatus status;

    @Column(name = "reversal_of")
    private UUID reversalOf;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
    }

    JournalEntry(UUID id, UUID tenantId, String reference, String description, LocalDate effectiveDate,
            String currency, UUID reversalOf, Instant postedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.reference = reference;
        this.description = description;
        this.effectiveDate = effectiveDate;
        this.currency = currency;
        this.status = EntryStatus.POSTED;
        this.reversalOf = reversalOf;
        this.postedAt = postedAt;
    }

    void addLine(UUID accountId, Direction direction, long amountMinor, String memo) {
        lines.add(new JournalLine(UUID.randomUUID(), this, lines.size() + 1, accountId, direction, amountMinor, memo));
    }

    void markReversed(UUID reversalEntryId) {
        this.status = EntryStatus.REVERSED;
        this.reversedBy = reversalEntryId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getReference() {
        return reference;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getCurrency() {
        return currency;
    }

    public EntryStatus getStatus() {
        return status;
    }

    public UUID getReversalOf() {
        return reversalOf;
    }

    public UUID getReversedBy() {
        return reversedBy;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public List<JournalLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
