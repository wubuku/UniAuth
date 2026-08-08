package org.dddml.email.repository;

import org.dddml.email.entity.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    List<EmailLog> findByStatus(String status);

    Page<EmailLog> findByStatus(String status, Pageable pageable);

    List<EmailLog> findByQueueId(Long queueId);

    @Query("SELECT e.sendMethod, COUNT(e) FROM EmailLog e " +
           "WHERE e.sentTime >= :since GROUP BY e.sendMethod")
    List<Object[]> countBySendMethodSince(LocalDateTime since);

    List<EmailLog> findByRecipient(String recipient);

    long countByStatus(String status);
}
