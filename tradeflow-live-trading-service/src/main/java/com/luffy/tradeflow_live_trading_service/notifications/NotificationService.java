package com.luffy.tradeflow_live_trading_service.notifications;

import com.luffy.tradeflow.common.web.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** In-app notices about live access (revocation, halts). Shown on the LIVE dashboard. */
@Service
public class NotificationService {

    public record View(long id, String kind, String message, Instant createdAt, Instant readAt) {
        static View of(LiveNotification n) {
            return new View(n.getId(), n.getKind(), n.getMessage(), n.getCreatedAt(), n.getReadAt());
        }
    }

    private final LiveNotificationRepository repository;
    private final Clock clock;

    public NotificationService(LiveNotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void notify(long userId, String kind, String message) {
        repository.save(new LiveNotification(userId, kind, message, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<View> recent(long userId) {
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 50)).stream().map(View::of).toList();
    }

    @Transactional
    public View markRead(long userId, long id) {
        LiveNotification n = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Notification " + id));
        n.markRead(clock.instant());
        return View.of(n);
    }
}
