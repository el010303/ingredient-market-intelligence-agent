package com.marketintel.marketintelligenceagent;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "market.events";
    public static final String DLX = "market.events.dlx";       // dead-letter exchange

    public static final String SUMMARIZE_QUEUE = "events.summarize";
    public static final String EMBED_QUEUE = "events.embed";
    public static final String DEAD_LETTER_QUEUE = "events.dlq";

    public static final String RK_SUMMARIZE = "summarize";
    public static final String RK_EMBED = "embed";
    public static final String RK_DEAD = "dead";

    @Bean
    public DirectExchange eventsExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Queue summarizeQueue() {
        return QueueBuilder.durable(SUMMARIZE_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", RK_DEAD)
                .build();
    }

    @Bean
    public Queue embedQueue() {
        return QueueBuilder.durable(EMBED_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", RK_DEAD)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding summarizeBinding() {
        return BindingBuilder.bind(summarizeQueue()).to(eventsExchange()).with(RK_SUMMARIZE);
    }

    @Bean
    public Binding embedBinding() {
        return BindingBuilder.bind(embedQueue()).to(eventsExchange()).with(RK_EMBED);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(RK_DEAD);
    }
}