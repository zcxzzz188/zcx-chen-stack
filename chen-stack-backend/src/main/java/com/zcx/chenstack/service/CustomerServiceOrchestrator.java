package com.zcx.chenstack.service;

import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import com.zcx.chenstack.rag.ProductRagAnswerService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class CustomerServiceOrchestrator {
    private final ProductRagAnswerService answers;
    private final EditableArticleService articles;
    private final AiUsageService usage;

    public CustomerServiceOrchestrator(ProductRagAnswerService answers, EditableArticleService articles, AiUsageService usage) {
        this.answers = answers;
        this.articles = articles;
        this.usage = usage;
    }

    public Flux<CustomerServiceEvent> chat(String message, String chatId, Integer authenticatedUserId) {
        return Flux.create(sink -> {
            CustomerServiceTools tools = new CustomerServiceTools(authenticatedUserId, articles, sink::next);
            var subscription = Flux.defer(() -> answers.answer(message, chatId, authenticatedUserId,
                            () -> usage.tryReserveUsage(authenticatedUserId), tools))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(part -> sink.next(CustomerServiceEvent.text(part)), error -> {
                        CustomerServiceError failure = CustomerServiceError.classify(error);
                        sink.next(CustomerServiceEvent.failure(failure.name(), failure.description(), null));
                        sink.next(CustomerServiceEvent.state("DONE", null, null, null));
                        sink.complete();
                    }, () -> {
                        sink.next(CustomerServiceEvent.state("DONE", null, null, null));
                        sink.complete();
                    });
            sink.onDispose(subscription::dispose);
        });
    }

}
