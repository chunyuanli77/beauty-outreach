package local.outreach.semantic;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
final class SemanticWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SemanticWorker.class);

    private final SemanticService semantic;
    private final SemanticProvider provider;
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(3, runnable -> {
        var thread = new Thread(runnable, "semantic-worker");
        thread.setDaemon(true);
        return thread;
    });

    SemanticWorker(SemanticService semantic, SemanticProvider provider) {
        this.semantic = semantic;
        this.provider = provider;
    }

    @Order(100)
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!provider.enabled()) return;
        for (int slot = 0; slot < 2; slot++) {
            workers.scheduleWithFixedDelay(this::advance, 0, 250, TimeUnit.MILLISECONDS);
        }
        workers.scheduleWithFixedDelay(this::refresh, 12, 12, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    private void advance() {
        try {
            if (!semantic.status().configured()) return;
            var work = semantic.claim();
            if (work == null) return;
            SemanticProvider.Result result = null;
            String failure = null;
            try {
                result = provider.analyze(work.profile(), work.model(), work.promptVersion());
            } catch (SemanticProvider.Failure failed) {
                failure = failed.getMessage();
            } catch (RuntimeException unexpected) {
                failure = "PROVIDER_UNAVAILABLE";
            }
            semantic.finish(work, result, failure);
        } catch (RuntimeException transaction) {
            LOG.warn("Semantic worker transaction failed ({})", transaction.getClass().getSimpleName());
        }
    }

    private void refresh() {
        try {
            semantic.refreshProvider();
        } catch (RuntimeException unavailable) {
            LOG.warn("Semantic metadata refresh failed ({})", unavailable.getClass().getSimpleName());
        }
    }
}
