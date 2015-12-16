/*
 * Copyright (c) 2011-2016 Pivotal Software Inc., Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.spring.context.config

import groovy.transform.EqualsAndHashCode
import org.reactivestreams.Processor
import org.springframework.aop.framework.ProxyFactoryBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.DefaultConversionService
import reactor.Processors
import reactor.bus.Event
import reactor.bus.EventBus
import reactor.spring.context.annotation.Consumer
import reactor.spring.context.annotation.ReplyTo
import reactor.spring.context.annotation.Selector
import reactor.spring.context.annotation.SelectorType
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
class ConsumerBeanAutoConfigurationSpec extends Specification {
	def "Annotated Consumer is wired to a Reactor"() {

		given:
			"an ApplicationContext with an annotated bean handler"
			def appCtx = new AnnotationConfigApplicationContext(AnnotatedHandlerConfig)
			def handlerBean = appCtx.getBean(HandlerBean)
			def reactor = appCtx.getBean(EventBus)

		when:
			"an Event is emitted onto the Reactor in context"
			reactor.notify('/a/b', Event.wrap("Hello World!"))

		then:
			"the method has been invoked"
			handlerBean.latch.await(1, TimeUnit.SECONDS)

	}

	def "Annotated Consumer with custom type is wired and the selector method is invoked after converting the value"() {

		given:
			"an ApplicationContext with an annotated bean handler"
			def appCtx = new AnnotationConfigApplicationContext(AnnotatedHandlerConfig)
			def handlerBean = appCtx.getBean(HandlerBean)
			def reactor = appCtx.getBean(EventBus)

		when:
			"an Event is emitted onto the Reactor in context"
			reactor.send('customEvent.sink', Event.wrap("Hello").setReplyTo('customEventReply.sink'))

		then:
			"the method has been invoked"
			handlerBean.latch.await(1, TimeUnit.SECONDS)

	}

	def "Annotated Consumer with Method that throws RuntimeException should use event's errorConsumer"() {
		given:
			"an ApplicationContext with an annotated bean handler"
			def appCtx = new AnnotationConfigApplicationContext(AnnotatedHandlerConfig)
			def reactor = appCtx.getBean(EventBus)
			final errorConsumedLatch = new CountDownLatch(1)

		when:
			"Event has an errorConsumer and event handler throws an error"
			Event<String> ev = new Event(null, "Hello", { t ->
				errorConsumedLatch.countDown()
			})
			reactor.notify('throws.exception', ev)

		then:
			"errorConsumer method has been invoked"
			errorConsumedLatch.await(30, TimeUnit.SECONDS)

	}

	def "Proxy to Annotated Consumer is wired"() {

		given:
		"an ApplicationContext with an annotated bean handler"
		ApplicationContext ctx = new AnnotationConfigApplicationContext(AnnotatedHandlerConfig)
		ConsumerBeanAutoConfiguration configuration = new ConsumerBeanAutoConfiguration(
				applicationContext: ctx
		)

		when:
		"reads beans for context"
		configuration.onApplicationEvent(new ContextRefreshedEvent(ctx))

		then:
		"proxied bean has been wired"
		configuration.wiredBeans["handlerBeanAop"] == true
	}

}

@EqualsAndHashCode
class CustomEvent {
	String data
}


@Consumer
class HandlerBean {
	@Autowired
	EventBus eventBus
	def latch = new CountDownLatch(1)

	@Selector(value = '/{a}/{b}', type = SelectorType.URI)
	void handleTest(Event<String> ev) {
		//println "a=${ev.headers['a']}, b=${ev.headers['b']}"
		latch.countDown()
	}

	@Selector(value = 'customEvent.sink')
	@ReplyTo(value = 'customEventReply.sink')
	CustomEvent handleCustomEvent(CustomEvent customEvent) {
		return new CustomEvent(data: customEvent.data + " from custom event.")
	}

	@Selector('customEventReply.sink')
	void handleReplyToCustomEvent(Event<String> ev) {
		println "Received response: ${ev.data}"
		latch.countDown()
	}

	@Selector(value = 'throws.exception')
	@ReplyTo
	String handleString(Event<String> ev) {
		throw new CustomRuntimeException('This is an exception');
	}
}

interface HandlerProxiedBean {}

@Consumer
class HandlerProxiedBeanImpl implements HandlerProxiedBean {
	def latch = new CountDownLatch(1)

	@Selector(value = 'proxiedBean.sink', eventBus = "@eventBus")
	void handleTest(Event<String> ev) {
		println "a=${ev.headers['a']}, b=${ev.headers['b']}"
		latch.countDown()
	}
}


class EventToCustomEventConverter implements Converter<Event, CustomEvent> {

	@Override
	CustomEvent convert(Event source) {
		return new CustomEvent(data: source.getData().toString())
	}

}

class CustomEventToEventConverter implements Converter<CustomEvent, Event> {

	@Override
	Event convert(CustomEvent source) {
		return Event.wrap(source.data)
	}

}

@Configuration
class AnnotatedHandlerConfig {

	@Bean
	Processor<Event<?>, Event<?>> env() {
		Processors.topic()
	}

	@Bean
	EventBus eventBus(Processor<Event<?>, Event<?>> env) {
		return EventBus.create(env)
	}

	@Bean
	ConsumerBeanAutoConfiguration consumerBeanAutoConfiguration() {
		return new ConsumerBeanAutoConfiguration()
	}

	@Bean
	HandlerBean handlerBean() {
		return new HandlerBean()
	}

	@Bean
	ConversionService reactorConversionService() {
		DefaultConversionService defaultConversionService = new DefaultConversionService()
		defaultConversionService.addConverter(Event, CustomEvent, new EventToCustomEventConverter())
		defaultConversionService.addConverter(CustomEvent, Event, new CustomEventToEventConverter())
		return defaultConversionService;
	}

	@Bean
	Object handlerBeanAop() {
		return new ProxyFactoryBean(
				proxyInterfaces: [HandlerProxiedBean],
				target: new HandlerProxiedBeanImpl(),
				interceptorNames: []
		)
	}

}
