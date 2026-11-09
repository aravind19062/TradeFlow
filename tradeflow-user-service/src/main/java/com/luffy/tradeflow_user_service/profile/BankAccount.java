package com.luffy.tradeflow_user_service.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/** A user's bank account. The full number exists only as ciphertext; see V3__user_bank_accounts.sql. */
@Entity
@Table(name = "user_bank_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(exclude = "accountNumberCiphertext")
public class BankAccount {

    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "account_holder_name", nullable = false)
    private String accountHolderName;
    @Column(name = "bank_name", nullable = false)
    private String bankName;
    @Column(name = "account_number_ciphertext", nullable = false)
    private String accountNumberCiphertext;
    @Column(name = "account_last4", nullable = false)
    private String accountLast4;
    @Column(nullable = false)
    private String ifsc;
    @Column(name = "account_type", nullable = false)
    private String accountType;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    BankAccount(Long userId, Instant now) {
        this.userId = userId;
        this.createdAt = now;
    }

    void update(String holder, String bank, String ciphertext, String last4, String ifsc, String type, Instant now) {
        this.accountHolderName = holder;
        this.bankName = bank;
        this.accountNumberCiphertext = ciphertext;
        this.accountLast4 = last4;
        this.ifsc = ifsc;
        this.accountType = type;
        this.updatedAt = now;
    }
}
