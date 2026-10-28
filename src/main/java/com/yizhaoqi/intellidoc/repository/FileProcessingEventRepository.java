package com.yizhaoqi.intellidoc.repository;

import com.yizhaoqi.intellidoc.model.FileProcessingEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileProcessingEventRepository extends JpaRepository<FileProcessingEvent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FileProcessingEvent> findByEventKey(String eventKey);

    @Query("select e.payload from FileProcessingEvent e where e.id = :id")
    Optional<String> findPayloadById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from FileProcessingEvent e where e.id = :id")
    Optional<FileProcessingEvent> findByIdForUpdate(@Param("id") String id);

    /**
     * A Kafka acknowledgement can survive while the consumer offset or the broker log does not.
     * Re-delivering an old SENT receipt is safe because consumers use the stable task id as an
     * idempotency key. Do not select recent SENT receipts: their consumer may still be working.
     */
    @Query("select e.id from FileProcessingEvent e where e.processingStatus = 'PENDING' and "
            + "((e.deliveryStatus = 'PENDING' and e.nextAttemptAt <= :now) "
            + "or (e.deliveryStatus = 'SENT' and e.sentAt is not null and e.sentAt <= :sentBefore)) "
            + "order by e.nextAttemptAt, e.id")
    List<String> findDispatchableIds(@Param("now") LocalDateTime now,
                                     @Param("sentBefore") LocalDateTime sentBefore,
                                     Pageable pageable);

    Page<FileProcessingEvent> findByProcessingStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
