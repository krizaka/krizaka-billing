package com.krizaka.billing.service.infrastructure.config;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for the billing service (AGENTS.md §6): its own {@code orazaka.events.billing}
 * queue bound to the terminal job outcomes on the shared {@code orazaka.events} topic exchange,
 * dead-lettering through {@code orazaka.dlx}.
 *
 * <p>Billing is a <em>second</em> consumer of those events, not a replacement — a topic exchange
 * fans out, so the SSE relay keeps receiving them unchanged. Exchange declarations are idempotent
 * duplicates of the platform topology (same type/durability) so the service can start first; the
 * queue arguments match {@code EventsTopologyConfig}'s event queues, or RabbitMQ rejects a
 * re-declare with {@code PRECONDITION_FAILED}.
 *
 * <p>No {@code x-max-length}: the work queues cap and reject-publish on overflow, which is right
 * for requests you can refuse, and wrong for settlements — dropping one loses money already spent.
 */
@Configuration
public class AmqpConfiguration {

  @Bean
  public TopicExchange eventsExchange() {
    return new TopicExchange(AmqpConstants.EVENTS_EXCHANGE, true, false);
  }

  @Bean
  public DirectExchange deadLetterExchange() {
    return new DirectExchange(AmqpConstants.DLX_EXCHANGE, true, false);
  }

  @Bean
  public Queue settlementQueue() {
    return new Queue(
        AmqpConstants.SETTLEMENT_QUEUE,
        true,
        false,
        false,
        Map.of(
            "x-dead-letter-exchange", AmqpConstants.DLX_EXCHANGE,
            "x-dead-letter-routing-key", AmqpConstants.SETTLEMENT_QUEUE));
  }

  @Bean
  public Binding settlementDoneBinding(Queue settlementQueue, TopicExchange eventsExchange) {
    return BindingBuilder.bind(settlementQueue).to(eventsExchange).with(AmqpConstants.DONE_BINDING);
  }

  @Bean
  public Binding settlementErrorBinding(Queue settlementQueue, TopicExchange eventsExchange) {
    return BindingBuilder.bind(settlementQueue)
        .to(eventsExchange)
        .with(AmqpConstants.ERROR_BINDING);
  }

  @Bean
  public Queue settlementDlq() {
    return new Queue(AmqpConstants.SETTLEMENT_DLQ, true, false, false);
  }

  @Bean
  public Binding settlementDlqBinding(Queue settlementDlq, DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(settlementDlq)
        .to(deadLetterExchange)
        .with(AmqpConstants.SETTLEMENT_QUEUE);
  }

  @Bean
  public Queue unmeteredTurnQueue() {
    return new Queue(
        AmqpConstants.UNMETERED_QUEUE,
        true,
        false,
        false,
        Map.of(
            "x-dead-letter-exchange", AmqpConstants.DLX_EXCHANGE,
            "x-dead-letter-routing-key", AmqpConstants.UNMETERED_QUEUE));
  }

  @Bean
  public Binding unmeteredTurnBinding(Queue unmeteredTurnQueue, TopicExchange eventsExchange) {
    return BindingBuilder.bind(unmeteredTurnQueue)
        .to(eventsExchange)
        .with(AmqpConstants.UNMETERED_BINDING);
  }

  @Bean
  public Queue unmeteredTurnDlq() {
    return new Queue(AmqpConstants.UNMETERED_DLQ, true, false, false);
  }

  @Bean
  public Binding unmeteredTurnDlqBinding(
      Queue unmeteredTurnDlq, DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(unmeteredTurnDlq)
        .to(deadLetterExchange)
        .with(AmqpConstants.UNMETERED_QUEUE);
  }

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter();
  }
}
