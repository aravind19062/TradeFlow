package com.luffy.tradeflow_learning_service.readiness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** A signed risk acknowledgement: exact text version and hash, typed phrase, when and from where. */
@Entity
@Immutable
@Table(name = "risk_acknowledgements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskAcknowledgement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "policy_version", nullable = false)
    private int policyVersion;
    @Column(name = "text_version", nullable = false)
    private int textVersion;
    @Column(name = "text_sha256", nullable = false)
    private String textSha256;
    @Column(name = "typed_phrase", nullable = false)
    private String typedPhrase;
    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;
    @Column(name = "client_ip")
    private String clientIp;
    @Column(name = "user_agent")
    private String userAgent;

    RiskAcknowledgement(long userId, int policyVersion, int textVersion, String textSha256, String typedPhrase,
                        Instant acceptedAt, String clientIp, String userAgent) {
        this.userId = userId;
        this.policyVersion = policyVersion;
        this.textVersion = textVersion;
        this.textSha256 = textSha256;
        this.typedPhrase = typedPhrase;
        this.acceptedAt = acceptedAt;
        this.clientIp = clientIp;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255));
    }
}
