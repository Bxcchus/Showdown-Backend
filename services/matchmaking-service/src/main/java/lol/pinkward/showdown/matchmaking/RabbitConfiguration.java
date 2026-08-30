package lol.pinkward.showdown.matchmaking;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RabbitConfiguration {

    static final String DEAD_LETTER_EXCHANGE = "pinkward.events.dlx";
    static final String MATCH_CANCELLED_QUEUE = "pinkward.matchmaking.match-cancelled.v1";
    static final String MATCH_CANCELLED_DLQ = "pinkward.matchmaking.match-cancelled.v1.dlq";
    static final String MATCH_CANCELLED_KEY = "pinkward.match.cancelled.v1";
    static final String MATCH_CONFIRMED_QUEUE = "pinkward.matchmaking.match-confirmed.v1";
    static final String MATCH_CONFIRMED_DLQ = "pinkward.matchmaking.match-confirmed.v1.dlq";
    static final String MATCH_CONFIRMED_KEY = "pinkward.match.confirmed.v1";
    static final String PLAYER_RATING_UPDATED_QUEUE = "pinkward.matchmaking.rating-updated.v2";
    static final String PLAYER_RATING_UPDATED_DLQ = "pinkward.matchmaking.rating-updated.v2.dlq";
    static final String PLAYER_RATING_UPDATED_KEY = "pinkward.match.rating-updated.v2";
    static final String DUEL_RATING_UPDATED_QUEUE = "pinkward.matchmaking.duel-rating-updated.v1";
    static final String DUEL_RATING_UPDATED_DLQ = "pinkward.matchmaking.duel-rating-updated.v1.dlq";
    static final String DUEL_RATING_UPDATED_KEY = "pinkward.match.duel-rating-updated.v1";

    @Bean
    TopicExchange pinkwardEventsExchange() {
        return new TopicExchange(OutboxPublisher.EXCHANGE, true, false);
    }

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue matchCancelledQueue() {
        return new Queue(MATCH_CANCELLED_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", MATCH_CANCELLED_KEY));
    }

    @Bean
    Binding matchCancelledBinding(Queue matchCancelledQueue, TopicExchange pinkwardEventsExchange) {
        return BindingBuilder.bind(matchCancelledQueue)
                .to(pinkwardEventsExchange)
                .with(MATCH_CANCELLED_KEY);
    }

    @Bean
    Queue matchCancelledDeadLetterQueue() {
        return new Queue(MATCH_CANCELLED_DLQ, true);
    }

    @Bean
    Binding matchCancelledDeadLetterBinding(
            Queue matchCancelledDeadLetterQueue,
            TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(matchCancelledDeadLetterQueue)
                .to(deadLetterExchange)
                .with(MATCH_CANCELLED_KEY);
    }

    @Bean
    Queue matchConfirmedQueue() {
        return new Queue(MATCH_CONFIRMED_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", MATCH_CONFIRMED_KEY));
    }

    @Bean
    Binding matchConfirmedBinding(Queue matchConfirmedQueue, TopicExchange pinkwardEventsExchange) {
        return BindingBuilder.bind(matchConfirmedQueue)
                .to(pinkwardEventsExchange)
                .with(MATCH_CONFIRMED_KEY);
    }

    @Bean
    Queue matchConfirmedDeadLetterQueue() {
        return new Queue(MATCH_CONFIRMED_DLQ, true);
    }

    @Bean
    Binding matchConfirmedDeadLetterBinding(
            Queue matchConfirmedDeadLetterQueue,
            TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(matchConfirmedDeadLetterQueue)
                .to(deadLetterExchange)
                .with(MATCH_CONFIRMED_KEY);
    }

    @Bean
    Queue playerRatingUpdatedQueue() {
        return new Queue(PLAYER_RATING_UPDATED_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", PLAYER_RATING_UPDATED_KEY));
    }

    @Bean
    Binding playerRatingUpdatedBinding(Queue playerRatingUpdatedQueue, TopicExchange pinkwardEventsExchange) {
        return BindingBuilder.bind(playerRatingUpdatedQueue)
                .to(pinkwardEventsExchange)
                .with(PLAYER_RATING_UPDATED_KEY);
    }

    @Bean
    Queue playerRatingUpdatedDeadLetterQueue() {
        return new Queue(PLAYER_RATING_UPDATED_DLQ, true);
    }

    @Bean
    Binding playerRatingUpdatedDeadLetterBinding(
            Queue playerRatingUpdatedDeadLetterQueue,
            TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(playerRatingUpdatedDeadLetterQueue)
                .to(deadLetterExchange)
                .with(PLAYER_RATING_UPDATED_KEY);
    }

    @Bean
    Queue duelRatingUpdatedQueue() {
        return new Queue(DUEL_RATING_UPDATED_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", DUEL_RATING_UPDATED_KEY));
    }

    @Bean
    Binding duelRatingUpdatedBinding(Queue duelRatingUpdatedQueue, TopicExchange pinkwardEventsExchange) {
        return BindingBuilder.bind(duelRatingUpdatedQueue)
                .to(pinkwardEventsExchange)
                .with(DUEL_RATING_UPDATED_KEY);
    }

    @Bean
    Queue duelRatingUpdatedDeadLetterQueue() {
        return new Queue(DUEL_RATING_UPDATED_DLQ, true);
    }

    @Bean
    Binding duelRatingUpdatedDeadLetterBinding(
            Queue duelRatingUpdatedDeadLetterQueue,
            TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(duelRatingUpdatedDeadLetterQueue)
                .to(deadLetterExchange)
                .with(DUEL_RATING_UPDATED_KEY);
    }
}
