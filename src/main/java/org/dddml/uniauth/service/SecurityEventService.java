package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.entity.SecurityEvent;
import org.dddml.uniauth.repository.SecurityEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SecurityEventService {

    private final SecurityEventRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            String eventType,
            String subjectId,
            Outcome outcome,
            String reasonCode) {
        repository.save(SecurityEvent.builder()
                .id(UUID.randomUUID().toString())
                .eventType(eventType)
                .subjectId(subjectId)
                .requestId(UUID.randomUUID().toString())
                .outcome(outcome.name())
                .reasonCode(reasonCode)
                .build());
    }

    public enum Outcome {
        SUCCESS,
        FAILURE,
        DENIED
    }
}
