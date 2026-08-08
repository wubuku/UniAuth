package org.dddml.email.service;

import lombok.RequiredArgsConstructor;
import org.dddml.email.repository.EmailQueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EmailQueueClaimService {

    private final EmailQueueRepository emailQueueRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPending(Long queueId, LocalDateTime now) {
        return emailQueueRepository.claimPending(queueId, now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimRecoverable(Long queueId, LocalDateTime now, LocalDateTime stuckTime) {
        return emailQueueRepository.claimRecoverable(queueId, now, stuckTime) == 1;
    }
}
