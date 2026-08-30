package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxPublisher {

    static final String EXCHANGE = "pinkward.events";

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbit;
    private final Clock clock = Clock.systemUTC();

    OutboxPublisher(OutboxEventRepository outbox, RabbitTemplate rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${pinkward.outbox.tick:250ms}")
    @Transactional
    void publishBatch() {
        for (OutboxEvent event : outbox.lockUnpublishedBatch()) {
            rabbit.convertAndSend(EXCHANGE, event.routingKey(), event.payload(), message -> {
                message.getMessageProperties().setContentType("application/json");
                message.getMessageProperties().setMessageId(event.id().toString());
                message.getMessageProperties().setDeliveryMode(
                        org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                return message;
            });
            event.markPublished(clock.instant());
        }
    }
}
