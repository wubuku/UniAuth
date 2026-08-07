package org.dddml.email.repository;

import org.dddml.email.entity.EmailQueue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EmailQueueRepository extends JpaRepository<EmailQueue, Long> {

    @Query("SELECT e FROM EmailQueue e WHERE " +
           "(e.status = 'PENDING' AND (e.nextRetryTime IS NULL OR e.nextRetryTime <= :now)) " +
           "OR (e.status = 'PROCESSING' AND e.updatedTime < :stuckTime) " +
           "ORDER BY e.priority ASC, e.createdTime ASC")
    Page<EmailQueue> findFailedOrStuckEmails(
        @Param("now") LocalDateTime now,
        @Param("stuckTime") LocalDateTime stuckTime,
        Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE EmailQueue e SET e.status = 'PROCESSING', e.updatedTime = :now " +
           "WHERE e.id = :id AND e.status = 'PENDING'")
    int updateStatusToProcessing(@Param("id") Long id, @Param("now") LocalDateTime now);

    long countByStatus(String status);

    List<EmailQueue> findByStatus(String status);

    List<EmailQueue> findByStatusOrderByPriorityDesc(String status);
}
