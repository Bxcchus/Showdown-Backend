package lol.pinkward.showdown.match;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RabbitConfiguration {

    static final String EVENTS_EXCHANGE = "pinkward.events";
    static final String DEAD_LETTER_EXCHANGE = "pinkward.events.dlx";
    static final String MATCH_FOUND_QUEUE = "pinkward.match.match-found.v1";
    static final String MATCH_FOUND_DLQ = "pinkward.match.match-found.v1.dlq";
    static final String MATCH_FOUND_KEY = "pinkward.matchmaking.match-found.v1";

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue matchFoundQueue() {
        return new Queue(MATCH_FOUND_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", MATCH_FOUND_KEY));
    }

    @Bean
    Binding matchFoundBinding(Queue matchFoundQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(matchFoundQueue).to(eventsExchange).with(MATCH_FOUND_KEY);
    }

    @Bean
    Queue matchFoundDeadLetterQueue() {
        return new Queue(MATCH_FOUND_DLQ, true);
    }

    @Bean
    Binding matchFoundDeadLetterBinding(
            Queue matchFoundDeadLetterQueue,
            TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(matchFoundDeadLetterQueue)
                .to(deadLetterExchange)
                .with(MATCH_FOUND_KEY);
    }
}
