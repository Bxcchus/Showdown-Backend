package lol.pinkward.showdown.match;

import java.time.Clock;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MatchOutboxPublisher {

    private final MatchOutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final Clock clock = Clock.systemUTC();

    MatchOutboxPublisher(MatchOutboxRepository outbox, RabbitTemplate rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${pinkward.outbox.tick:250ms}")
    @Transactional
    void publishBatch() {
        for (MatchOutboxEvent event : outbox.lockUnpublishedBatch()) {
            rabbit.convertAndSend(RabbitConfiguration.EVENTS_EXCHANGE, event.routingKey(), event.payload(), message -> {
                message.getMessageProperties().setContentType("application/json");
                message.getMessageProperties().setMessageId(event.id().toString());
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            event.markPublished(clock.instant());
        }
    }
}
