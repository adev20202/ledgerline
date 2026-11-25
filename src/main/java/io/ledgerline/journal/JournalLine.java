package io.ledgerline.journal;

import java.util.UUID;

import io.ledgerline.common.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id")
    private JournalEntry entry;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    private String memo;

    protected JournalLine() {
    }

    JournalLine(UUID id, JournalEntry entry, int lineNo, UUID accountId, String currency, Direction direction,
            long amountMinor, String memo) {
        this.id = id;
        this.entry = entry;
        this.lineNo = lineNo;
        this.accountId = accountId;
        this.currency = currency;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.memo = memo;
    }

    public UUID getId() {
        return id;
    }

    public JournalEntry getEntry() {
        return entry;
    }

    public int getLineNo() {
        return lineNo;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getMemo() {
        return memo;
    }
}
